package com.fongmi.android.tv.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogNoticeBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 服务端下发的通用提示框：版本停用通知、试用剩余天数、更新提醒等都走这一个。
 * <p>
 * 只有"确定"按钮是必给的；"取消"用于可跳过的提醒（{@link #cancelable(boolean)} 为 true 时显示）。
 * 强制类的提示（旧版本停用）不显示取消，用户只能确认后退出。
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
        MaterialAlertDialogBuilder builder = builder().setTitle(title).setView(getBinding().getRoot()).setCancelable(false);
        builder.setPositiveButton(confirmText, null);
        if (cancelable) builder.setNegativeButton(R.string.dialog_negative, null);
        return builder;
    }

    @Override
    protected void initView() {
        binding.desc.setText(desc);
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            dismissAllowingStateLoss();
            if (onConfirm != null) onConfirm.run();
        });
        if (cancelable) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            dismissAllowingStateLoss();
            if (onCancel != null) onCancel.run();
        });
    }
}
