package com.fongmi.android.tv;

import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.api.config.RemoteConfig;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;

public class Updater implements Download.Callback, UpdateListener {

    private Download download;
    private UpdateDialog dialog;
    private int code;

    public static Updater create() {
        return new Updater();
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getJson() {
        return RemoteConfig.URL;
    }

    private String getAbi() {
        return android.os.Process.is64Bit() ? "arm64_v8a" : "armeabi_v7a";
    }

    private String getApk(JSONObject object) {
        String url = "";
        for (String abi : Build.SUPPORTED_ABIS) {
            url = object.optString(BuildConfig.FLAVOR + "-" + abi.replace('-', '_'));
            if (!TextUtils.isEmpty(url)) break;
        }
        if (TextUtils.isEmpty(url)) url = object.optString(BuildConfig.FLAVOR);
        if (TextUtils.isEmpty(url)) url = object.optString("uri");
        return TextUtils.isEmpty(url) ? Github.getApk(BuildConfig.FLAVOR + "-" + getAbi()) : url;
    }

    public Updater force() {
        Notify.show(R.string.update_check);
        return this;
    }

    public void start(FragmentActivity activity) {
        start(activity, false);
    }

    public void start(FragmentActivity activity, boolean force) {
        Task.execute(() -> doInBackground(activity, force));
    }

    private void doInBackground(FragmentActivity activity, boolean force) {
        try {
            JSONObject object = new JSONObject(OkHttp.string(getJson()));
            String name = object.optString("name");
            String desc = object.optString("desc");
            int code = object.optInt("code");
            if (code <= BuildConfig.VERSION_CODE) return;
            if (!force && code == Setting.getUpdateSkip()) return;
            App.post(() -> show(activity, code, name, desc, getApk(object)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void show(FragmentActivity activity, int code, String version, String desc, String apk) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismiss();
        this.code = code;
        download = Download.create(apk, getFile());
        dialog = UpdateDialog.create().title(ResUtil.getString(R.string.update_version, version)).desc(desc).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        view.setEnabled(false);
        download.start(this);
    }

    @Override
    public void onCancel(View view) {
        Setting.putUpdateSkip(code);
        download.cancel();
        dismiss();
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void progress(int progress) {
        if (dialog != null) dialog.setProgress(progress);
    }

    @Override
    public void error(String msg) {
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        FileUtil.openFile(file);
        dismiss();
    }
}
