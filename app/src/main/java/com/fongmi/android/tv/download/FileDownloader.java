package com.fongmi.android.tv.download;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.common.net.HttpHeaders;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 把媒体下载到独立文件夹（不再写播放器 SimpleCache）。
 * 目录结构：TV/download/{剧名}/{站源}/{线路}/{集数}.mp4 或 .../{集数}/index.m3u8（HLS 分片放同目录）。
 * mp4 走流式直下；m3u8 解析清单、下载全部分片并重写为本地清单。
 */
final class FileDownloader {

    interface Callback {

        /** 预估总字节数，作为进度分母。未知时传 0。 */
        void onTotal(long total);

        void onProgress(long bytes, long total);

        /** 下载完成后的本地文件路径（用于离线播放）。 */
        void onFile(File file);
    }

    private static final long NOTIFY_INTERVAL_MS = 200;

    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicLong lastNotify = new AtomicLong();
    private final Callback callback;

    private volatile Call call;
    private volatile long bytes;
    private volatile long total;

    FileDownloader(Callback callback) {
        this.callback = callback;
    }

    /**
     * 阻塞执行直到结束，必须在工作线程调用。
     *
     * @param dir     目标目录（已按 剧名/站源/线路 组织好）
     * @param episode 集数名（用作文件名主体）
     */
    void download(String url, Map<String, String> headers, File dir, String episode) throws Exception {
        if (isHls(url)) downloadHls(url, headers, dir, episode);
        else downloadProgressive(url, headers, dir, episode);
        if (canceled.get()) throw new InterruptedException("canceled");
    }

    private void downloadProgressive(String url, Map<String, String> headers, File dir, String episode) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        String ext = ".mp4";
        String name = safeName(episode) + ext;
        File target = new File(dir, name);
        total = probeLength(url, headers);
        callback.onTotal(total);
        request(url, headers, target);
    }

    private void downloadHls(String url, Map<String, String> headers, File dir, String episode) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        File epDir = new File(dir, safeName(episode));
        if (!epDir.exists()) epDir.mkdirs();
        // 拉取主清单
        String master = fetchText(url, headers);
        if (TextUtils.isEmpty(master)) throw new IllegalStateException("empty m3u8");
        String base = baseUrl(url);
        // 处理：若为 media playlist 直接下分片；若为 master 则取第一条 media playlist
        String mediaUrl = url;
        String media = master;
        String best = pickMediaUrl(master, base);
        if (best != null) {
            mediaUrl = best;
            media = fetchText(best, headers);
        }
        List<String> segments = parseSegments(media);
        if (segments.isEmpty()) throw new IllegalStateException("no segments");
        total = segments.size();
        bytes = 0;
        callback.onTotal(total);

        // 预生成分片文件名与完整 URL 列表（索引即文件名序号，保证顺序）。
        String[] segUrls = new String[segments.size()];
        String[] segNames = new String[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            segUrls[i] = resolve(base, mediaUrl, segments.get(i));
            segNames[i] = String.format(Locale.getDefault(), "seg_%05d.ts", i + 1);
        }

        // 8 线程并发下载分片。
        int threadCount = Math.min(THREAD_COUNT, segments.size());
        AtomicLong downloaded = new AtomicLong();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.atomic.AtomicInteger nextIndex = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                while (!canceled.get()) {
                    int idx = nextIndex.getAndIncrement();
                    if (idx >= segments.size()) break;
                    try {
                        downloadRaw(segUrls[idx], headers, new File(epDir, segNames[idx]));
                    } catch (IOException ignored) {
                    }
                    bytes = downloaded.incrementAndGet();
                    notifyProgress(false);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        executor.shutdown();
        if (canceled.get()) throw new InterruptedException("canceled");

        // 写本地清单（按顺序），统计实际字节。
        long realBytes = 0;
        StringBuilder localPlaylist = new StringBuilder();
        localPlaylist.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n");
        for (String name : segNames) {
            File segFile = new File(epDir, name);
            realBytes += segFile.length();
            localPlaylist.append("#EXTINF:10.0,\n").append(name).append('\n');
        }
        localPlaylist.append("#EXT-X-ENDLIST\n");
        writeText(new File(epDir, "index.m3u8"), localPlaylist.toString());
        total = Math.max(realBytes, 1);
        bytes = realBytes;
        notifyProgress(true);
        callback.onFile(new File(epDir, "index.m3u8"));
    }

    void cancel() {
        canceled.set(true);
        Call target = call;
        if (target != null) target.cancel();
    }

    // ---- 网络 ----

    /**
     * 确保请求头含 User-Agent（很多源无 UA 会 403）。与播放器一致：优先 Setting.getUa()，否则默认 UA。
     */
    private static Map<String, String> ensureHeaders(Map<String, String> headers) {
        Map<String, String> map = headers == null ? new HashMap<>() : new HashMap<>(headers);
        boolean hasUa = false;
        for (String key : map.keySet()) if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) hasUa = true;
        if (!hasUa) map.put(HttpHeaders.USER_AGENT, Setting.getUa().isEmpty() ? MediaItemFactory.getDefaultUserAgent() : Setting.getUa());
        return map;
    }

    private static final int THREAD_COUNT = 8;

    private void request(String url, Map<String, String> headers, File target) throws IOException {
        headers = ensureHeaders(headers);
        // 探测总长度并确认是否支持 Range 分段。
        if (total <= 0) total = probeLength(url, headers);
        callback.onTotal(total);
        if (total > 0 && supportsRange(url, headers)) {
            downloadMultiThread(url, headers, target);
        } else {
            downloadSingle(url, headers, target);
        }
        if (total <= 0) total = bytes;
        bytes = total;
        notifyProgress(true);
        callback.onFile(target);
    }

    private boolean supportsRange(String url, Map<String, String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=0-0");
            for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
            try (Response resp = OkHttp.client().newCall(rb.build()).execute()) {
                if (resp.code() == 206) return true;
                String ar = resp.header("Accept-Ranges");
                return ar != null && ar.toLowerCase().contains("bytes");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 8 线程分段下载：每个线程用 Range 请求一段，写到目标文件对应偏移。
     */
    private void downloadMultiThread(String url, Map<String, String> headers, File target) throws IOException {
        Path.create(target);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(target, "rw")) {
            raf.setLength(total);
        }
        long chunk = total / THREAD_COUNT;
        List<Thread> threads = new java.util.ArrayList<>();
        AtomicLong done = new AtomicLong();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final long start = i * chunk;
            final long end = (i == THREAD_COUNT - 1) ? (total - 1) : (start + chunk - 1);
            Thread t = new Thread(() -> {
                try {
                    downloadRange(url, headers, target, start, end, done);
                } catch (IOException ignored) {
                }
            }, "dl-seg-" + i);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (canceled.get()) throw new IOException("canceled");
        bytes = total;
        notifyProgress(true);
    }

    private void downloadRange(String url, Map<String, String> headers, File target, long start, long end, AtomicLong done) throws IOException {
        Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=" + start + "-" + end);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        Call c = OkHttp.client().newCall(rb.build());
        try (Response resp = c.execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            if (resp.body() == null) throw new IOException("empty body");
            try (InputStream in = resp.body().byteStream(); java.io.RandomAccessFile raf = new java.io.RandomAccessFile(target, "rw")) {
                raf.seek(start);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (canceled.get()) throw new IOException("canceled");
                    raf.write(buf, 0, n);
                    done.addAndGet(n);
                    bytes += n;
                    notifyProgress(false);
                }
            }
        }
    }

    private void downloadSingle(String url, Map<String, String> headers, File target) throws IOException {
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        call = OkHttp.client().newCall(rb.build());
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            if (resp.body() == null) throw new IOException("empty body");
            if (total <= 0) total = resp.body().contentLength();
            callback.onTotal(total);
            InputStream in = resp.body().byteStream();
            try (OutputStream out = new FileOutputStream(Path.create(target))) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (canceled.get()) throw new IOException("canceled");
                    out.write(buf, 0, n);
                    bytes += n;
                    notifyProgress(false);
                }
            }
        }
    }

    private void downloadRaw(String url, Map<String, String> headers, File target) throws IOException {
        headers = ensureHeaders(headers);
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        Call c = OkHttp.client().newCall(rb.build());
        try (Response resp = c.execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            if (resp.body() == null) throw new IOException("empty body");
            InputStream in = resp.body().byteStream();
            try (OutputStream out = new FileOutputStream(Path.create(target))) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (canceled.get()) throw new IOException("canceled");
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private String fetchText(String url, Map<String, String> headers) throws IOException {
        headers = ensureHeaders(headers);
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        try (Response resp = OkHttp.client().newCall(rb.build()).execute()) {
            if (resp.body() == null) return "";
            return resp.body().string();
        }
    }

    private long probeLength(String url, Map<String, String> headers) {
        try {
            headers = ensureHeaders(headers);
            Request.Builder rb = new Request.Builder().url(url).get().header("Range", "bytes=0-0");
            for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
            try (Response resp = OkHttp.client().newCall(rb.build()).execute()) {
                String contentRange = resp.header("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    return Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
                }
                if (resp.body() != null) return resp.body().contentLength();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ---- m3u8 解析 ----

    private String pickMediaUrl(String master, String base) {
        // 优先挑分辨率最高的一条 media playlist
        String bestUrl = null;
        int bestRes = -1;
        for (String line : master.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                int res = 0;
                String resTag = trimmed.substring(trimmed.indexOf("RESOLUTION=") + 11);
                if (resTag.length() > 0) {
                    int x = resTag.indexOf('x');
                    if (x > 0) {
                        try {
                            res = Integer.parseInt(resTag.substring(0, x).trim());
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (res >= bestRes) bestRes = res;
            } else if (bestRes >= 0 && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                bestUrl = resolve(base, "", trimmed);
                break;
            }
        }
        return bestUrl;
    }

    private List<String> parseSegments(String playlist) {
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String line : playlist.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            segs.add(t);
        }
        return segs;
    }

    private static String baseUrl(String url) {
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(0, i + 1) : url;
    }

    private static String resolve(String base, String mediaUrl, String seg) {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg;
        String b = mediaUrl != null && !mediaUrl.isEmpty() ? baseUrl(mediaUrl) : base;
        if (seg.startsWith("/")) {
            Uri u = Uri.parse(mediaUrl != null && !mediaUrl.isEmpty() ? mediaUrl : base);
            return u.getScheme() + "://" + u.getHost() + seg;
        }
        return b + seg;
    }

    private static boolean isHls(String url) {
        return !TextUtils.isEmpty(url) && url.toLowerCase().contains(".m3u8");
    }

    static boolean isSupported(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase();
        return !lower.startsWith("rtmp://") && !lower.startsWith("rtsp://") && !lower.startsWith("rtp://") && !lower.startsWith("udp://") && !lower.startsWith("content://");
    }

    private static String safeName(String name) {
        if (TextUtils.isEmpty(name)) return "episode";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(Path.create(file))) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void notifyProgress(boolean force) {
        long now = System.currentTimeMillis();
        long last = lastNotify.get();
        if (!force && now - last < NOTIFY_INTERVAL_MS) return;
        if (!lastNotify.compareAndSet(last, now)) return;
        callback.onProgress(bytes, total);
    }
}
