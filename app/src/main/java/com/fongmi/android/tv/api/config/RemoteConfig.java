package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

public class RemoteConfig {

    public static final String URL = "https://3xui.haoiyu.cn/d/onedrive/TvBox/update.json";

    public static void init(Callback callback) {
        Task.execute(() -> {
            boolean state = isEmpty() && assign();
            App.post(() -> load(callback, state));
        });
    }

    private static boolean isEmpty() {
        return Config.vod().isEmpty();
    }

    private static boolean assign() {
        try {
            JSONObject object = new JSONObject(OkHttp.string(URL));
            String vod = object.optString("vodUri");
            String live = object.optString("liveUri");
            String wall = object.optString("wallUri");
            if (!TextUtils.isEmpty(vod)) Config.find(vod, BaseConfig.VOD).update();
            if (!TextUtils.isEmpty(live)) Config.find(live, BaseConfig.LIVE).update();
            if (!TextUtils.isEmpty(wall)) Config.find(wall, BaseConfig.WALL).update();
            return !TextUtils.isEmpty(wall);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void load(Callback callback, boolean fresh) {
        VodConfig.get().init().load(callback);
        LiveConfig.get().init().load();
        if (fresh) WallConfig.get().init().load();
        else WallConfig.get().init();
    }
}
