package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterDownloadGridBinding;
import com.fongmi.android.tv.ui.widget.ProgressTextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 下载面板的集数网格，用方块背景表达 5 种下载状态。
 * 状态来源：优先读面板推送的快照（{@link #updateStates}），首次绑定时回退到逐条查库。
 */
public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.Holder> {

    public interface OnClickListener {

        void onItemClick(Episode item, Download download);
    }

    private final Function<Episode, Download> provider;
    private final List<Episode> items;
    private final OnClickListener listener;
    private final Map<String, Download> states = new HashMap<>();

    public DownloadAdapter(List<Episode> items, Function<Episode, Download> provider, OnClickListener listener) {
        this.items = items;
        this.provider = provider;
        this.listener = listener;
    }

    /**
     * 面板把整部剧的最新下载状态一次性推进来（一次 DB 查询的结果快照），然后全量重绑。
     * 避免每个方块各自查库，在进度高频刷新时打爆主线程。
     */
    public void updateStates(Map<String, Download> snapshot) {
        states.clear();
        if (snapshot != null) states.putAll(snapshot);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void notifyAllStates() {
        notifyItemRangeChanged(0, getItemCount());
    }

    private Download lookup(Episode episode) {
        Download cached = states.get(episode.getUrl());
        if (cached != null) return cached;
        return provider.apply(episode);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(AdapterDownloadGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.initView(items.get(position), lookup(items.get(position)));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {

        private final AdapterDownloadGridBinding binding;
        private final OnClickListener listener;

        Holder(@NonNull AdapterDownloadGridBinding binding, OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
        }

        void initView(Episode item, Download download) {
            // 背景层 ProgressTextView 只负责画状态色块，文字交给上层 episode/state 两个 TextView。
            binding.text.setText("");
            binding.episode.setText(item.getName());
            bindState(download);
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item, download);
            });
        }

        void bindState(Download download) {
            ProgressTextView view = binding.text;
            if (download == null || download.isIdle()) {
                view.setState(ProgressTextView.MODE_NONE, 0);
                binding.state.setText(R.string.download_state_none);
            } else if (download.isDone()) {
                view.setState(ProgressTextView.MODE_DONE, 1);
                binding.state.setText(R.string.download_state_done);
            } else if (download.isFailed()) {
                view.setState(ProgressTextView.MODE_FAILED, 1);
                binding.state.setText(R.string.download_state_failed);
            } else if (download.isQueued()) {
                view.setState(ProgressTextView.MODE_QUEUED, 1);
                binding.state.setText(R.string.download_state_queued);
            } else if (download.getTotal() <= 0) {
                view.setState(ProgressTextView.MODE_RUNNING, 0, true);
                binding.state.setText(R.string.download_state_running);
            } else {
                view.setState(ProgressTextView.MODE_RUNNING, download.getProgress());
                int percent = (int) (download.getProgress() * 100);
                binding.state.setText(binding.state.getContext().getString(R.string.download_state_running_percent, percent));
            }
        }
    }
}
