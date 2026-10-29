package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogDownloadGridBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadService;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.fragment.DownloadFragment;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayoutMediator;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载面板：正序小方块展示当前线路的全部集数及其下载状态。
 */
public class DownloadGridDialog extends BaseBottomSheetDialog implements DownloadManager.Listener {

    private final List<String> titles = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;
    private DialogDownloadGridBinding binding;
    private FragmentStateAdapter pagerAdapter;
    private List<Episode> episodes;
    private String vodKey;
    private String vodId;
    private String vodName;
    private String flag;
    private int cid;
    private int spanCount = 5;
    private int itemCount;

    public static DownloadGridDialog create() {
        return new DownloadGridDialog();
    }

    public DownloadGridDialog vod(int cid, String vodKey, String vodId, String vodName, String flag) {
        this.cid = cid;
        this.vodKey = vodKey;
        this.vodId = vodId;
        this.vodName = vodName;
        this.flag = flag;
        return this;
    }

    public DownloadGridDialog episodes(List<Episode> episodes) {
        this.episodes = episodes;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DownloadGridDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDownloadGridBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 先注册结果监听，再创建分页，避免子 Fragment 早于监听注册就发出点击结果。
        getChildFragmentManager().setFragmentResultListener(DownloadFragment.RESULT, this, (key, bundle) -> onEpisodeClick(bundle.getString("episodeUrl")));
        if (episodes == null || episodes.isEmpty()) {
            binding.pager.setVisibility(View.GONE);
            binding.tabs.setVisibility(View.GONE);
            binding.empty.setVisibility(View.VISIBLE);
            binding.summary.setVisibility(View.GONE);
            return;
        }
        setSpanCount();
        setTitles();
        setPager();
        updateSummary(collectStates());
    }

    @Override
    protected void initEvent() {
        DownloadManager.get().addListener(this);
    }

    @Override
    public void onDownloadChanged(String vodKey, String flag) {
        if (!TextUtils.equals(this.vodKey, vodKey) || !TextUtils.equals(this.flag, flag)) return;
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, 150);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTask);
        DownloadManager.get().removeListener(this);
        super.onDestroyView();
    }

    private void setSpanCount() {
        int avg = (int) Math.ceil(episodes.stream().mapToInt(e -> e.getName().length()).average().orElse(0));
        if (avg >= 12) spanCount = 1;
        else if (avg >= 8) spanCount = 2;
        else if (avg >= 4) spanCount = 3;
        else if (avg >= 2) spanCount = 4;
        itemCount = spanCount * (ResUtil.isLand(requireActivity()) ? 5 : 10);
    }

    private void setTitles() {
        for (int i = 0; i < episodes.size(); i += itemCount) titles.add((i + 1) + " - " + Math.min(i + itemCount, episodes.size()));
    }

    private void setPager() {
        binding.pager.setAdapter(pagerAdapter = new PageAdapter(this));
        new TabLayoutMediator(binding.tabs, binding.pager, (tab, position) -> tab.setText(titles.get(position))).attach();
    }

    /**
     * 整剧状态快照：一次 DB 查询的结果，按 episodeUrl 索引，
     * 供摘要与所有分页的方块共用，避免逐方块查库。
     */
    private Map<String, Download> collectStates() {
        Map<String, Download> states = new LinkedHashMap<>();
        if (vodKey == null) return states;
        // 必须按 cid 隔离：不同剧的 vodKey 可能相同，否则会把别的剧的下载记录算进来。
        for (Download item : Download.find(cid, vodKey, flag)) states.put(item.getEpisodeUrl(), item);
        return states;
    }

    private void updateSummary(Map<String, Download> states) {
        long done = states.values().stream().filter(Download::isDone).count();
        long running = states.values().stream().filter(i -> i.isRunning() || i.isQueued()).count();
        StringBuilder sb = new StringBuilder();
        sb.append(done).append("/").append(episodes.size());
        if (running > 0) sb.append("  ↓").append(running);
        binding.summary.setText(sb.toString());
    }

    private Download findEpisode(String episodeUrl) {
        for (Episode episode : episodes) if (episode.getUrl().equals(episodeUrl)) return Download.find(cid, vodKey, flag, episodeUrl);
        return null;
    }

    private Episode find(String episodeUrl) {
        for (Episode episode : episodes) if (episode.getUrl().equals(episodeUrl)) return episode;
        return null;
    }

    private void onEpisodeClick(String episodeUrl) {
        Episode episode = find(episodeUrl);
        if (episode == null) return;
        Download download = findEpisode(episodeUrl);
        if (download != null && download.isDone()) {
            showDoneDialog(episode, download);
        } else if (download != null && (download.isRunning() || download.isQueued())) {
            showCancelDialog(episode, download);
        } else {
            startDownload(episode);
        }
    }

    private void showDoneDialog(Episode episode, Download download) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(episode.getName())
                .setItems(new String[]{ResUtil.getString(R.string.download_play), ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    if (which == 0) onPlay(episode);
                    else confirmDelete(episode, download);
                })
                .show();
    }

    private void showCancelDialog(Episode episode, Download download) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(episode.getName())
                .setItems(new String[]{ResUtil.getString(R.string.menu_delete)}, (dialog, which) -> {
                    // 删除：真正删除记录 + 本地文件（而非仅取消），避免离线列表残留。
                    DownloadManager.get().delete(download);
                    refresh();
                })
                .show();
    }

    private void confirmDelete(Episode episode, Download download) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.download_delete_title)
                .setMessage(getString(R.string.download_delete_message, episode.getName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    DownloadManager.get().delete(download);
                    refresh();
                })
                .show();
    }

    private void startDownload(Episode episode) {
        // 下载写到 APP 专属外部目录（getExternalFilesDir），无需存储权限。
        DownloadManager.get().enqueue(cid, vodKey, vodId, vodName, siteName(), flag, episode);
        DownloadService.start(requireContext());
        refresh();
    }

    private String siteName() {
        try {
            return VodConfig.get().getSite(vodKey).getName();
        } catch (Exception e) {
            return "";
        }
    }

    private void onPlay(Episode episode) {
        if (requireActivity() instanceof EpisodeAdapter.OnClickListener listener) listener.onItemClick(episode);
        dismiss();
    }

    private void refresh() {
        if (getContext() == null || binding == null) return;
        // 方块状态由各 DownloadFragment 自行订阅 DownloadManager 刷新（ViewPager2 的
        // fragment 不在 getChildFragmentManager().getFragments() 里，父级遍历推送不可靠）。
        // 这里只刷新摘要栏。
        if (!episodes.isEmpty()) updateSummary(collectStates());
    }

    class PageAdapter extends FragmentStateAdapter {

        PageAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return DownloadFragment.newInstance(spanCount, cid, vodKey, flag, episodes.subList(position * itemCount, Math.min(position * itemCount + itemCount, episodes.size())));
        }

        @Override
        public int getItemCount() {
            return titles.size();
        }
    }
}
