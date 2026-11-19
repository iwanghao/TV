package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogNoticeBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 服务端下发的通用提示框：版本停用通知、试用剩余天数、更新提醒等都走这一个。
 * <p>
 * TV 端没有 AlertDialog 默认按钮，确定/取消都在布局里自绘。
 * {@link #cancelable(boolean)} 为 false 时隐藏取消按钮（旧版本停用这类强制提示）。
 */
public class NoticeDialog extends BaseAlertDialog {

    private DialogNoticeBinding binding;
    private Runnable onConfirm;
    private Runnable onCancel;
    private String title;
    private String desc;
    private String confirmText;
    private boolean cancelable;

    public static NoticeDialog create() {
        return new NoticeDialog();
    }

    public NoticeDialog title(String title) {
        this.title = title;
        return this;
    }

    public NoticeDialog desc(String desc) {
        this.desc = desc;
        return this;
    }

    public NoticeDialog confirmText(String confirmText) {
        this.confirmText = confirmText;
        return this;
    }

    /** true 时显示"稍后"按钮，用户可跳过；false（默认）只能点确定。 */
    public NoticeDialog cancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public NoticeDialog onConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm;
        return this;
    }

    public NoticeDialog onCancel(Runnable onCancel) {
        this.onCancel = onCancel;
        return this;
    }

    public NoticeDialog show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return this;
        show(activity.getSupportFragmentManager(), null);
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNoticeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setCancelable(false);
    }

    @Override
    protected void initView() {
        binding.desc.setText(desc);
        binding.confirm.setText(confirmText);
        binding.cancel.setVisibility(cancelable ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    private void onConfirm(View view) {
        dismissAllowingStateLoss();
        if (onConfirm != null) onConfirm.run();
    }

    private void onCancel(View view) {
        dismissAllowingStateLoss();
        if (onCancel != null) onCancel.run();
    }
}
