package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.FragmentEpisodeBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.ui.adapter.DownloadAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载面板的单个分页。自己订阅下载状态变化并刷新自己的方块，
 * 避免依赖父 Dialog 遍历 ViewPager2 的 fragment（层级不可靠）。
 */
public class DownloadFragment extends BaseFragment implements DownloadAdapter.OnClickListener, DownloadManager.Listener {

    public static final String RESULT = "download-result";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    private FragmentEpisodeBinding mBinding;
    private DownloadAdapter adapter;

    private int getSpanCount() {
        return getArguments().getInt("spanCount");
    }

    private int getCid() {
        return getArguments().getInt("cid");
    }

    private String getVodKey() {
        return getArguments().getString("vodKey");
    }

    private String getFlag() {
        return getArguments().getString("flag");
    }

    private ArrayList<Episode> getItems() {
        return getArguments().getParcelableArrayList("items");
    }

    public static DownloadFragment newInstance(int spanCount, int cid, String vodKey, String flag, List<Episode> items) {
        Bundle args = new Bundle();
        args.putInt("spanCount", spanCount);
        args.putInt("cid", cid);
        args.putString("vodKey", vodKey);
        args.putString("flag", flag);
        args.putParcelableArrayList("items", new ArrayList<>(items));
        DownloadFragment fragment = new DownloadFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentEpisodeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(getContext(), getSpanCount()));
        mBinding.recycler.setAdapter(adapter = new DownloadAdapter(getItems(), this::find, this));
    }

    @Override
    protected void initEvent() {
    }

    @Override
    public void onResume() {
        super.onResume();
        // 可见时才订阅，进入面板立即同步一次最新状态，避免依赖懒加载时机。
        DownloadManager.get().addListener(this);
        refresh();
    }

    @Override
    public void onPause() {
        DownloadManager.get().removeListener(this);
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTask);
        DownloadManager.get().removeListener(this);
        super.onDestroyView();
    }

    private Download find(Episode episode) {
        return Download.find(getCid(), getVodKey(), getFlag(), episode.getUrl());
    }

    public DownloadAdapter getAdapter() {
        return adapter;
    }

    @Override
    public void onDownloadChanged(String vodKey, String flag) {
        if (!TextUtils.equals(getVodKey(), vodKey) || !TextUtils.equals(getFlag(), flag)) return;
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, 150);
    }

    /**
     * 查本页集数的最新状态并全量重绑。
     */
    private void refresh() {
        if (!isAdded() || adapter == null) return;
        Map<String, Download> states = new LinkedHashMap<>();
        for (Episode episode : getItems()) {
            Download item = Download.find(getCid(), getVodKey(), getFlag(), episode.getUrl());
            if (item != null) states.put(episode.getUrl(), item);
        }
        adapter.updateStates(states);
    }

    @Override
    public void onItemClick(Episode item, Download download) {
        Bundle result = new Bundle();
        result.putString("episodeUrl", item.getUrl());
        getParentFragmentManager().setFragmentResult(RESULT, result);
    }
}
