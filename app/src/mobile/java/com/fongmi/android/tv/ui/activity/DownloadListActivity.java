package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.ActivityDownloadListBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadService;
import com.fongmi.android.tv.ui.adapter.DownloadListAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DownloadListActivity extends BaseActivity implements DownloadListAdapter.OnClickListener, DownloadManager.Listener {

    private ActivityDownloadListBinding mBinding;
    private DownloadListAdapter mAdapter;
    // 展开状态：key 形如 vod@剧名 / vod@剧名>site@站源 / ...>flag@线路
    private final Set<String> expanded = new HashSet<>();
    // 缓存当前所有下载记录（各状态）
    private List<Download> allItems = new ArrayList<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable speedTask = this::refreshSpeed;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadListActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadListBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        refresh();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new DownloadListAdapter(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadManager.get().addListener(this);
        refresh();
        handler.removeCallbacks(speedTask);
        handler.postDelayed(speedTask, 1000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(speedTask);
        DownloadManager.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onDownloadChanged(String vodKey, String flag) {
        // 下载状态变化（进度/完成/失败）时刷新列表。
        handler.removeCallbacks(speedTask);
        refresh();
        handler.postDelayed(speedTask, 1000);
    }

    /**
     * 定时刷新网速显示（下载中时）。
     */
    private void refreshSpeed() {
        if (isFinishing() || isDestroyed()) return;
        refresh();
        if (DownloadManager.get().isActive()) handler.postDelayed(speedTask, 1000);
    }

    private void refresh() {
        allItems = Download.findAll();
        rebuild();
    }

    /**
     * 根据下载状态生成集数行的副标题（状态 / 进度 / 网速）。
     */
    private String episodeSubtitle(Download item) {
        if (item.isDone()) return FileUtil.byteCountToDisplaySize(item.getTotal());
        if (item.isFailed()) return getString(R.string.download_state_failed);
        if (item.isQueued()) return getString(R.string.download_state_queued);
        if (item.isPaused()) {
            // 暂停中：显示「暂停中」，有进度则附带百分比。
            if (item.getTotal() > 0) {
                int percent = (int) (item.getProgress() * 100);
                return getString(R.string.download_state_paused) + " " + percent + "%";
            }
            return getString(R.string.download_state_paused);
        }
        // 下载中：进度 + 网速
        long speed = DownloadManager.get().getSpeed(item.getId());
        if (item.getTotal() > 0) {
            int percent = (int) (item.getProgress() * 100);
            String speedText = speed > 0 ? " · " + FileUtil.byteCountToDisplaySize(speed) + "/s" : "";
            return percent + "%" + speedText;
        }
        String speedText = speed > 0 ? " · " + FileUtil.byteCountToDisplaySize(speed) + "/s" : "";
        return getString(R.string.download_state_running) + speedText;
    }

    /**
     * 按 剧名 → 站源 → 线路 → 集数 组织，结合展开状态生成扁平 entry 列表。
     */
    private void rebuild() {
        // vodName -> { siteName -> { flag -> [episodes] } }，保持插入顺序
        Map<String, Map<String, Map<String, List<Download>>>> tree = new LinkedHashMap<>();
        for (Download item : allItems) {
            String vod = item.getVodName();
            if (vod == null || vod.isEmpty()) vod = item.getVodKey();
            Map<String, Map<String, List<Download>>> sites = tree.get(vod);
            if (sites == null) {
                sites = new LinkedHashMap<>();
                tree.put(vod, sites);
            }
            String site = item.getSiteName();
            if (site == null || site.isEmpty()) site = item.getVodKey();
            Map<String, List<Download>> flags = sites.get(site);
            if (flags == null) {
                flags = new LinkedHashMap<>();
                sites.put(site, flags);
            }
            String flag = item.getFlag();
            if (flag == null || flag.isEmpty()) flag = "默认";
            List<Download> eps = flags.get(flag);
            if (eps == null) {
                eps = new ArrayList<>();
                flags.put(flag, eps);
            }
            eps.add(item);
        }

        // 每个线路下的集数按集数自然排序（第1集→第10集），避免依赖数据库存储顺序导致乱序。
        for (Map<String, Map<String, List<Download>>> sites : tree.values()) {
            for (Map<String, List<Download>> flags : sites.values()) {
                for (List<Download> eps : flags.values()) {
                    eps.sort((a, b) -> {
                        int na = Util.getNumber(a.getEpisodeName());
                        int nb = Util.getNumber(b.getEpisodeName());
                        if (na != nb) return Integer.compare(na, nb);
                        return a.getEpisodeName().compareToIgnoreCase(b.getEpisodeName());
                    });
                }
            }
        }

        List<DownloadListAdapter.Entry> entries = new ArrayList<>();
        for (String vod : tree.keySet()) {
            String vodKey = "vod@" + vod;
            Map<String, Map<String, List<Download>>> sites = tree.get(vod);
            int vodCount = countEpisodesInSites(sites);
            entries.add(DownloadListAdapter.Entry.group(0, vodKey, vod, getString(R.string.download_episodes_count, vodCount)));
            if (!expanded.contains(vodKey)) continue;
            for (String site : sites.keySet()) {
                String siteKey = vodKey + ">site@" + site;
                Map<String, List<Download>> flags = sites.get(site);
                int siteCount = countEpisodesInFlags(flags);
                entries.add(DownloadListAdapter.Entry.group(1, siteKey, site, getString(R.string.download_episodes_count, siteCount)));
                if (!expanded.contains(siteKey)) continue;
                for (String flag : flags.keySet()) {
                    String flagKey = siteKey + ">flag@" + flag;
                    List<Download> eps = flags.get(flag);
                    entries.add(DownloadListAdapter.Entry.group(2, flagKey, flag, getString(R.string.download_episodes_count, eps.size())));
                    if (!expanded.contains(flagKey)) continue;
                    for (Download item : eps) entries.add(DownloadListAdapter.Entry.episode(3, item, episodeSubtitle(item)));
                }
            }
        }

        mAdapter.submit(entries);
        mBinding.progressLayout.showContent(true, entries.size());
    }

    private int countEpisodesInSites(Map<String, Map<String, List<Download>>> sites) {
        int n = 0;
        for (Map<String, List<Download>> flags : sites.values()) for (List<Download> eps : flags.values()) n += eps.size();
        return n;
    }

    private int countEpisodesInFlags(Map<String, List<Download>> flags) {
        int n = 0;
        for (List<Download> eps : flags.values()) n += eps.size();
        return n;
    }

    @Override
    public void onNodeToggle(String key) {
        if (expanded.contains(key)) expanded.remove(key);
        else expanded.add(key);
        rebuild();
    }

    @Override
    public void onNodeDelete(String key) {
        List<Download> targets = matchNode(key);
        if (targets.isEmpty()) return;
        String title = nodeTitle(key);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_delete_title)
                .setMessage(getString(R.string.download_delete_message, title))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    for (Download item : targets) DownloadManager.get().delete(item);
                    refresh();
                })
                .show();
    }

    /**
     * 解析节点 key，返回该节点下所有已下载记录。
     */
    private List<Download> matchNode(String key) {
        List<Download> result = new ArrayList<>();
        if (key == null) return result;
        String[] parts = key.split(">");
        // parts[0]=vod@剧名, parts[1]=site@站源, parts[2]=flag@线路
        String vod = parts.length > 0 ? partValue(parts[0]) : null;
        String site = parts.length > 1 ? partValue(parts[1]) : null;
        String flag = parts.length > 2 ? partValue(parts[2]) : null;
        for (Download item : allItems) {
            String iv = item.getVodName();
            if (iv == null || iv.isEmpty()) iv = item.getVodKey();
            if (vod != null && !vod.equals(iv)) continue;
            String is = item.getSiteName();
            if (is == null || is.isEmpty()) is = item.getVodKey();
            if (site != null && !site.equals(is)) continue;
            String ifl = item.getFlag();
            if (ifl == null || ifl.isEmpty()) ifl = "默认";
            if (flag != null && !flag.equals(ifl)) continue;
            result.add(item);
        }
        return result;
    }

    private String partValue(String part) {
        int i = part.indexOf('@');
        return i >= 0 ? part.substring(i + 1) : part;
    }

    private String nodeTitle(String key) {
        if (key == null) return "";
        String[] parts = key.split(">");
        return parts.length > 0 ? partValue(parts[parts.length - 1]) : key;
    }

    @Override
    public void onEpisodeClick(Download item) {
        if (item.isDone()) {
            showDoneDialog(item);
        } else if (item.isPaused()) {
            showPausedDialog(item);
        } else if (item.isRunning() || item.isQueued()) {
            showRunningDialog(item);
        } else if (item.isFailed()) {
            showFailedDialog(item);
        }
    }

    private void showDoneDialog(Download item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getEpisodeName())
                .setItems(new String[]{ResUtil.getString(R.string.download_play), ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    if (which == 0) onPlay(item);
                    else confirmDelete(item);
                })
                .show();
    }

    private void showRunningDialog(Download item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getEpisodeName())
                .setItems(new String[]{ResUtil.getString(R.string.download_pause), ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    if (which == 0) {
                        // 暂停：停止当前任务，保留进度，释放并发位给等待任务。
                        DownloadManager.get().pause(item);
                        refresh();
                    } else {
                        confirmDelete(item);
                    }
                })
                .show();
    }

    private void showPausedDialog(Download item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getEpisodeName())
                .setItems(new String[]{ResUtil.getString(R.string.download_resume), ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    if (which == 0) {
                        // 继续：重新入队继续下载。
                        DownloadManager.get().resume(item);
                        DownloadService.start(this);
                        refresh();
                    } else {
                        confirmDelete(item);
                    }
                })
                .show();
    }

    private void showFailedDialog(Download item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getEpisodeName())
                .setItems(new String[]{ResUtil.getString(R.string.download_retry), ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    if (which == 0) retry(item);
                    else confirmDelete(item);
                })
                .show();
    }

    private void retry(Download item) {
        // 失败重试：重新入队。
        item.setStatus(Download.QUEUED);
        item.setBytes(0);
        item.setTotal(0);
        item.save();
        DownloadManager.get().enqueue(item.getCid(), item.getVodKey(), item.getVodId(), item.getVodName(), item.getSiteName(), item.getFlag(), Episode.create(item.getEpisodeName(), item.getEpisodeUrl()));
        DownloadService.start(this);
        refresh();
    }

    private void onPlay(Download item) {
        String path = item.getDataSource();
        if (TextUtils.isEmpty(path) || !new java.io.File(path).exists()) {
            Notify.show(R.string.download_offline_missing);
            return;
        }
        // 离线播放：先联网查详情页，查不到再回退本地缓存；传 cid 供离线兜底按 cid 查同剧同源同线路的所有已下载集。
        VideoActivity.start(this, item.getVodKey(), item.getVodId(), item.getVodName(), null, null, item.getFlag(), item.getEpisodeUrl(), item.getEpisodeName(), path, item.getCid());
    }

    private void confirmDelete(Download item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_delete_title)
                .setMessage(getString(R.string.download_delete_message, item.getEpisodeName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    DownloadManager.get().delete(item);
                    refresh();
                })
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackInvoked();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        super.onBackInvoked();
    }
}
