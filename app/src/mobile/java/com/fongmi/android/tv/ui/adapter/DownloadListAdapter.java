package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.databinding.AdapterDownloadEpisodeBinding;
import com.fongmi.android.tv.databinding.AdapterDownloadGroupBinding;
import com.fongmi.android.tv.utils.FileUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 下载列表：剧名 → 站源 → 线路 → 集数 四级层级，逐层展开/收起。
 * 用扁平化 entry + 缩进表达层级，展开状态用 HashSet 记录节点 key。
 */
public class DownloadListAdapter extends RecyclerView.Adapter<DownloadListAdapter.Holder> {

    public interface OnClickListener {

        void onNodeToggle(String key);

        void onNodeDelete(String key);

        void onEpisodeClick(Download item);
    }

    public static final int TYPE_GROUP = 0;   // 剧名/站源/线路 节点（可展开）
    public static final int TYPE_EPISODE = 1; // 集数（叶子）

    public static class Entry {

        final int type;
        final int depth;          // 0=剧名 1=站源 2=线路 3=集数
        final String key;         // 唯一节点 key（仅 group 用于展开状态）
        final String title;       // 显示标题
        final String subtitle;    // 右侧辅助信息（集数/大小等）
        final Download download;  // 仅 episode 使用

        Entry(int type, int depth, String key, String title, String subtitle, Download download) {
            this.type = type;
            this.depth = depth;
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.download = download;
        }

        public static Entry group(int depth, String key, String title, String subtitle) {
            return new Entry(TYPE_GROUP, depth, key, title, subtitle, null);
        }

        public static Entry episode(int depth, Download download, String subtitle) {
            return new Entry(TYPE_EPISODE, depth, null, download.getEpisodeName(), subtitle, download);
        }
    }

    private final OnClickListener listener;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    public DownloadListAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    public void toggle(String key) {
        if (expanded.contains(key)) expanded.remove(key);
        else expanded.add(key);
        notifyDataSetChanged();
    }

    public boolean isExpanded(String key) {
        return expanded.contains(key);
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) return new GroupHolder(AdapterDownloadGroupBinding.inflate(inflater, parent, false));
        return new EpisodeHolder(AdapterDownloadEpisodeBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof GroupHolder) ((GroupHolder) holder).bind(entry, this, listener);
        else ((EpisodeHolder) holder).bind(entry, this, listener);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    abstract static class Holder extends RecyclerView.ViewHolder {

        Holder(@NonNull View itemView) {
            super(itemView);
        }
    }

    static class GroupHolder extends Holder {

        private final AdapterDownloadGroupBinding binding;

        GroupHolder(@NonNull AdapterDownloadGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Entry entry, DownloadListAdapter adapter, OnClickListener listener) {
            binding.name.setText(entry.title);
            binding.count.setText(entry.subtitle);
            boolean expanded = adapter.isExpanded(entry.key);
            binding.arrow.setRotation(expanded ? 90f : 0f);

            // 按层级设置视觉样式：剧名突出，站源/线路次级。
            int depth = entry.depth;
            int leftMargin = depth * dp(20);   // 缩进
            int topMargin = depth == 0 ? dp(10) : dp(2);
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) binding.getRoot().getLayoutParams();
            if (lp == null) lp = new android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(leftMargin, topMargin, 0, dp(2));
            binding.getRoot().setLayoutParams(lp);

            if (depth == 0) {
                binding.icon.setImageResource(R.drawable.ic_nav_vod);
                binding.icon.setVisibility(android.view.View.VISIBLE);
                binding.name.setTextSize(16);
                binding.name.setTypeface(null, android.graphics.Typeface.BOLD);
            } else if (depth == 1) {
                binding.icon.setImageResource(R.drawable.ic_action_site);
                binding.icon.setVisibility(android.view.View.VISIBLE);
                binding.name.setTextSize(15);
                binding.name.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else {
                binding.icon.setImageResource(R.drawable.ic_action_download);
                binding.icon.setVisibility(android.view.View.VISIBLE);
                binding.name.setTextSize(14);
                binding.name.setTypeface(null, android.graphics.Typeface.NORMAL);
            }

            binding.getRoot().setOnClickListener(v -> listener.onNodeToggle(entry.key));
            binding.getRoot().setOnLongClickListener(v -> {
                listener.onNodeDelete(entry.key);
                return true;
            });
        }
    }

    static class EpisodeHolder extends Holder {

        private final AdapterDownloadEpisodeBinding binding;

        EpisodeHolder(@NonNull AdapterDownloadEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Entry entry, DownloadListAdapter adapter, OnClickListener listener) {
            binding.name.setText(entry.title);
            binding.size.setText(entry.subtitle);

            int depth = entry.depth;
            int leftMargin = depth * dp(20);
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) binding.getRoot().getLayoutParams();
            if (lp == null) lp = new android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(leftMargin + dp(16), 0, 0, 0);
            binding.getRoot().setLayoutParams(lp);

            binding.getRoot().setOnClickListener(v -> listener.onEpisodeClick(entry.download));
        }
    }

    private static int dp(int value) {
        return (int) (value * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }
}
