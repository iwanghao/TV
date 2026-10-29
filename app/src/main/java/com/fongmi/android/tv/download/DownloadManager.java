package com.fongmi.android.tv.download;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线缓存任务调度：排队执行（同时最多 {@link #MAX_CONCURRENT} 个），
 * 负责解析剧集播放地址、写入播放器缓存、落库并通知界面刷新。
 */
@UnstableApi
public class DownloadManager {

    public interface Listener {

        void onDownloadChanged(String vodKey, String flag);
    }

    private static final int MAX_CONCURRENT = 3;
    /**
     * 下载过程中的状态广播节拍（毫秒）：落库与 UI 通知共用，
     * 既保证方块填充动画流畅，又避免打爆主线程和通知栏。
     */
    private static final long NOTIFY_INTERVAL_MS = 400;

    private static volatile DownloadManager instance;

    private final Map<Long, Runner> running = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Download> queue = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT + 1);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = Collections.synchronizedList(new ArrayList<>());
    // 每个任务的瞬时下载速度（bytes/s），供 UI 显示网速。
    private final Map<Long, Long> speeds = new ConcurrentHashMap<>();

    public static DownloadManager get() {
        if (instance == null) synchronized (DownloadManager.class) {
            if (instance == null) instance = new DownloadManager();
        }
        return instance;
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isActive() {
        return !running.isEmpty() || !queue.isEmpty();
    }

    public List<Long> getActiveIds() {
        List<Long> ids = new ArrayList<>();
        synchronized (running) {
            ids.addAll(running.keySet());
        }
        synchronized (queue) {
            for (Download item : queue) ids.add(item.getId());
        }
        return ids;
    }

    /**
     * 某个任务的瞬时下载速度（bytes/s），未在下载或已结束返回 0。
     */
    public long getSpeed(long id) {
        Long speed = speeds.get(id);
        return speed == null ? 0 : speed;
    }

    /**
     * 加入队列。已下载/已在队列中的会被忽略。
     */
    public void enqueue(int cid, String vodKey, String vodId, String vodName, String siteName, String flag, Episode episode) {
        if (TextUtils.isEmpty(vodKey) || TextUtils.isEmpty(flag) || episode == null || TextUtils.isEmpty(episode.getUrl())) return;
        Download found = Download.find(cid, vodKey, flag, episode.getUrl());
        if (found != null && (found.isDone() || found.isQueued() || isRunning(found.getId()))) return;
        final Download item;
        if (found == null) {
            item = new Download();
            item.setCid(cid);
            item.setVodKey(vodKey);
            item.setVodId(vodId);
            item.setVodName(vodName);
            item.setSiteName(siteName);
            item.setFlag(flag);
            item.setEpisodeName(episode.getName());
            item.setEpisodeUrl(episode.getUrl());
            item.setCreateTime(System.currentTimeMillis());
        } else {
            item = found;
            if (TextUtils.isEmpty(item.getSiteName()) && !TextUtils.isEmpty(siteName)) item.setSiteName(siteName);
            if (TextUtils.isEmpty(item.getVodId()) && !TextUtils.isEmpty(vodId)) item.setVodId(vodId);
        }
        item.setStatus(Download.QUEUED);
        item.setBytes(0);
        item.setTotal(0);
        item.save();
        addToQueue(item);
        notifyChanged(item.getVodKey(), item.getFlag());
        schedule();
    }

    private void addToQueue(Download item) {
        synchronized (queue) {
            for (Download queued : queue) if (queued.getId() == item.getId()) return;
            queue.add(item);
        }
    }

    /**
     * 进程被杀后恢复未完成任务（上次失败的保留失败态，等用户点重试）。
     */
    public void restore() {
        List<Download> pending = new ArrayList<>(Download.find(Download.QUEUED));
        pending.addAll(Download.find(Download.RUNNING));
        for (Download item : pending) addToQueue(item);
        if (!pending.isEmpty()) schedule();
    }

    public void cancel(Download item) {
        removeFromQueue(item.getId());
        Runner runner = running.get(item.getId());
        if (runner != null) runner.cancel();
        running.remove(item.getId());
        speeds.remove(item.getId());
        item.setStatus(Download.NONE);
        item.setBytes(0);
        item.setTotal(0);
        item.save();
        notifyChanged(item.getVodKey(), item.getFlag());
    }

    /**
     * 暂停下载：停止当前 Runner，保留已下载进度，状态置为 PAUSED，释放并发位给等待任务。
     */
    public void pause(Download item) {
        if (item == null) return;
        Runner runner = running.get(item.getId());
        if (runner != null) runner.cancel();
        running.remove(item.getId());
        removeFromQueue(item.getId());
        speeds.remove(item.getId());
        item.setStatus(Download.PAUSED);
        item.save();
        notifyChanged(item.getVodKey(), item.getFlag());
        mainHandler.post(this::schedule);
    }

    /**
     * 继续下载：从 PAUSED 恢复为 QUEUED 重新入队（重新下载，进度从头开始）。
     */
    public void resume(Download item) {
        if (item == null || !item.isPaused()) return;
        item.setStatus(Download.QUEUED);
        item.setBytes(0);
        item.setTotal(0);
        item.save();
        addToQueue(item);
        notifyChanged(item.getVodKey(), item.getFlag());
        schedule();
    }

    /**
     * 删除已下载记录，并删除对应的本地离线文件。
     */
    public void delete(Download item) {
        if (item == null) return;
        removeFromQueue(item.getId());
        Runner runner = running.get(item.getId());
        if (runner != null) runner.cancel();
        running.remove(item.getId());
        speeds.remove(item.getId());
        deleteLocalFile(item);
        Download.delete(item);
        notifyChanged(item.getVodKey(), item.getFlag());
    }

    /**
     * 删除该记录对应的本地离线文件（含 HLS 分片目录），并向上清理空目录。
     */
    private void deleteLocalFile(Download item) {
        try {
            String path = item.getDataSource();
            File dir;
            if (TextUtils.isEmpty(path)) {
                // dataSource 为空（下载中/等待中被删除，尚未记录完成路径）：按 剧名/站源/线路/集数 推导目录，
                // 删除该集可能残留的 mp4 文件或 m3u8 目录。
                File flagDir = Path.download(safe(item.getVodName()), safe(item.getSiteName()), safe(item.getFlag()));
                String ep = safe(item.getEpisodeName());
                File mp4 = new File(flagDir, ep + ".mp4");
                if (mp4.exists()) mp4.delete();
                File epDir = new File(flagDir, ep);
                if (epDir.exists()) Path.clear(epDir);
                dir = flagDir;
            } else {
                File file = new File(path);
                if (file.isDirectory()) {
                    // 目录（异常情况）：整目录清除。
                    Path.clear(file);
                    dir = file;
                } else if (file.getName().equalsIgnoreCase("index.m3u8")) {
                    // HLS：dataSource 指向 index.m3u8，分片 .ts 在父目录里，删整个父目录。
                    File parent = file.getParentFile();
                    if (parent != null) Path.clear(parent);
                    dir = parent;
                } else {
                    // mp4 单文件：删文件本身。
                    if (file.exists()) file.delete();
                    dir = file.getParentFile();
                }
            }
            // 向上清理空目录（集数/线路/站源/剧名），直到 download 根目录。
            File root = Path.download();
            while (dir != null && !dir.equals(root) && dir.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                if (dir.isDirectory() && dir.list() != null && dir.list().length == 0) dir.delete();
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 缓存被 LRU 淘汰或播放失败时，把「已下载」回退为未下载。
     */
    public void invalidate(int cid, String vodKey, String flag, String episodeUrl) {
        Download item = Download.find(cid, vodKey, flag, episodeUrl);
        if (item == null || !item.isDone()) return;
        deleteLocalFile(item);
        Download.delete(item);
        notifyChanged(vodKey, flag);
    }

    public boolean isDownloaded(int cid, String vodKey, String flag, String episodeUrl) {
        Download item = Download.find(cid, vodKey, flag, episodeUrl);
        return item != null && item.isDone();
    }

    private boolean isRunning(long id) {
        return running.containsKey(id);
    }

    private void removeFromQueue(long id) {
        synchronized (queue) {
            queue.removeIf(i -> i.getId() == id);
        }
    }

    private void schedule() {
        synchronized (running) {
            while (running.size() < MAX_CONCURRENT) {
                Download item;
                synchronized (queue) {
                    if (queue.isEmpty()) return;
                    item = queue.remove(0);
                }
                if (!item.isQueued()) continue;
                if (running.containsKey(item.getId())) continue;
                Runner runner = new Runner(item);
                running.put(item.getId(), runner);
                runner.start();
            }
        }
    }

    void onRunnerFinished(Download item) {
        running.remove(item.getId());
        speeds.remove(item.getId());
        mainHandler.post(this::schedule);
    }

    void notifyChanged(String vodKey, String flag) {
        mainHandler.post(() -> {
            List<Listener> snapshot;
            synchronized (listeners) {
                snapshot = new ArrayList<>(listeners);
            }
            for (Listener listener : snapshot) listener.onDownloadChanged(vodKey, flag);
        });
    }

    private void postChanged(Download item) {
        App.post(() -> notifyChanged(item.getVodKey(), item.getFlag()));
    }

    /**
     * 单个任务执行体。
     */
    private final class Runner {

        private final Download item;
        private final FileDownloader downloader;
        private volatile boolean canceled;

        Runner(Download item) {
            this.item = item;
            this.downloader = new FileDownloader(new FileDownloader.Callback() {
                private long lastSync;
                private long lastBytes;
                private long lastSpeedTime;

                @Override
                public void onTotal(long total) {
                    if (total <= 0) return;
                    item.setTotal(total);
                    // 总长度确定后立刻落库并广播，让方块从「不确定态」切换为「按进度填充」。
                    item.persist();
                    DownloadManager.this.notifyChanged(item.getVodKey(), item.getFlag());
                }

                @Override
                public void onProgress(long bytes, long total) {
                    item.setBytes(bytes);
                    if (total > 0) item.setTotal(total);
                    // 计算瞬时速度（bytes/s）。
                    long now = System.currentTimeMillis();
                    if (lastSpeedTime > 0 && now > lastSpeedTime) {
                        long deltaBytes = bytes - lastBytes;
                        long deltaMs = now - lastSpeedTime;
                        if (deltaBytes >= 0 && deltaMs >= 200) {
                            speeds.put(item.getId(), deltaBytes * 1000 / deltaMs);
                        }
                    }
                    lastBytes = bytes;
                    lastSpeedTime = now;
                    // 落库 + 广播合并为同一节拍（约 400ms 一次），
                    // UI 有足够刷新率看到绿色填充推进，又不会打爆主线程。
                    if (now - lastSync < NOTIFY_INTERVAL_MS) return;
                    lastSync = now;
                    item.persist();
                    DownloadManager.this.notifyChanged(item.getVodKey(), item.getFlag());
                }

                @Override
                public void onFile(File file) {
                    // 记录本地文件路径，离线播放直接读这个文件。
                    item.setDataSource(file.getAbsolutePath());
                    item.persist();
                }
            });
        }

        void start() {
            item.setStatus(Download.RUNNING);
            item.setBytes(0);
            item.persist();
            postChanged(item);
            DownloadManager.this.notifyChanged(item.getVodKey(), item.getFlag());
            pool.execute(this::run);
        }

        void cancel() {
            canceled = true;
            downloader.cancel();
        }

        private void run() {
            String errorMessage = null;
            try {
                Media resolve = resolve();
                Log.d("DL", "resolve ok url=" + resolve.url() + " header=" + (resolve.header() == null ? "null" : resolve.header().size()));
                item.header(resolve.header());
                item.persist();
                postChanged(item);
                File dir = Path.download(safe(item.getVodName()), safe(item.getSiteName()), safe(item.getFlag()));
                Log.d("DL", "download dir=" + dir.getAbsolutePath() + " episode=" + item.getEpisodeName());
                downloader.download(resolve.url(), resolve.header(), dir, item.getEpisodeName());
                if (canceled) return;
            } catch (Throwable e) {
                if (canceled) return;
                errorMessage = TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
                Log.e("DL", "download failed: " + errorMessage, e);
                e.printStackTrace();
            } finally {
                if (canceled) {
                    // 被取消/删除时也要清理 running 中的残留，避免 isActive 一直为 true。
                    running.remove(item.getId());
                    speeds.remove(item.getId());
                    return;
                }
                finish(errorMessage);
            }
        }

        private void finish(@Nullable String error) {
            if (error == null) {
                item.setStatus(Download.DONE);
                long size = item.getTotal() > 0 ? item.getTotal() : item.getBytes();
                item.setBytes(size);
                item.setTotal(size);
                item.persist();
            } else {
                item.setStatus(Download.FAILED);
                item.persist();
            }
            notifyChanged(item.getVodKey(), item.getFlag());
            onRunnerFinished(item);
        }

        private Media resolve() throws Exception {
            String key = item.getVodKey();
            Result result = SiteApi.playerContent(key, item.getFlag(), item.getEpisodeUrl());
            if (result == null) throw new IllegalStateException("empty result");
            if (result.needParse() || result.isUseParse()) throw new IllegalStateException("need parse");
            if (result.getDrm() != null) throw new IllegalStateException("drm unsupported");
            String url = result.getRealUrl();
            if (TextUtils.isEmpty(url)) throw new IllegalStateException("empty url");
            if (!FileDownloader.isSupported(url)) throw new IllegalStateException("unsupported source");
            return new Media(url, new LinkedHashMap<>(result.getHeader()));
        }
    }

    private static String safe(String name) {
        if (TextUtils.isEmpty(name)) return "_";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private record Media(String url, Map<String, String> header) {
    }
}
