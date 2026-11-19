package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteConfig {

    private static final String TAG = RemoteConfig.class.getSimpleName();

    public static final String URL = "https://3xui.haoiyu.cn/d/onedrive/TvBox/update.json";

    /** 首屏（缓存优先）加载已发起，此时才允许开始异步探测远端更新。 */
    private static volatile boolean booted;

    /**
     * 本次启动「开始加载之前」本地是否已有可用缓存。
     * <p>
     * 必须在调用 load() 之前取值：load 成功后会立即把内容写入 Config.json，
     * 若等到回调里再判断，读到的永远是刚写入的新值，判定失去意义。
     */
    private static volatile boolean hadCache;

    public static void init(Callback callback) {
        // 先记录"启动前"的缓存状态，供 checkUpdate 决定是否延迟。
        hadCache = hasCache();
        Task.execute(() -> {
            boolean state = isEmpty() && assign();
            App.post(() -> {
                booted = true;
                load(callback, state);
            });
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

    /** 有缓存时延迟校验的时长：先让首页用缓存渲染出来，再在后台比对。 */
    private static final long CHECK_DELAY = 60 * 1000L;

    /**
     * 固定的延迟任务引用。
     * 不能写成 {@code App.post(RemoteConfig::doCheck, ...)}：每次求值都会产生新的 Runnable 实例，
     * 导致 App.post 内部的 removeCallbacks 无法识别为同一个任务，重复调用时会叠加多次校验。
     */
    private static final Runnable CHECK_TASK = RemoteConfig::doCheck;

    /**
     * 按本地缓存情况决定校验时机。
     * <p>
     * 没有缓存：立即校验，因为此时界面本身无可展示内容，不存在"抢首页"的问题。
     * 有缓存：延迟 {@link #CHECK_DELAY} 再校验，让首页优先用缓存加载出来，
     * 避免启动瞬间同时发出首页请求与校验请求抢占带宽。
     */
    public static void checkUpdate() {
        App.post(CHECK_TASK, hadCache ? CHECK_DELAY : 0);
    }

    /**
     * 本地是否已有可用于首屏的点播缓存。
     * <p>
     * 只判定点播：它是首屏主体内容，直播是否有缓存不影响首页能否快速渲染。
     */
    private static boolean hasCache() {
        return !TextUtils.isEmpty(Config.vod().getJson());
    }

    /**
     * 异步校验远端点播/直播源内容是否有更新。
     * <p>
     * 流程：请求 update.json 取到 vodUri / liveUri → 分别下载源内容 →
     * 与本地缓存内容比对语义指纹（站点 key + api 集合）：一致则不做任何处理，
     * 不一致才静默重建。
     * <p>
     * 任何一步失败都静默退出，保证断网时仍可用上一次缓存。
     */
    private static void doCheck() {
        if (!booted) return;
        Task.execute(() -> {
            try {
                String body = OkHttp.string(URL);
                if (TextUtils.isEmpty(body)) return;
                JSONObject object = new JSONObject(body);
                String vod = object.optString("vodUri");
                String live = object.optString("liveUri");
                if (TextUtils.isEmpty(vod) && TextUtils.isEmpty(live)) return;
                boolean vodChanged = !TextUtils.isEmpty(vod) && isChanged(vod, Config.vod());
                boolean liveChanged = !TextUtils.isEmpty(live) && isChanged(live, Config.live());
                if (vodChanged) {
                    Config.find(vod, BaseConfig.VOD).update();
                    // 静默 + 强制回源重建：不广播事件（界面不刷新），
                    // 也不删除旧缓存，拉取成功后才覆盖，失败时旧内容仍可用。
                    VodConfig vodConfig = VodConfig.get().init();
                    vodConfig.setSilent();
                    vodConfig.setForceReload();
                    vodConfig.load(new Callback());
                }
                if (liveChanged) {
                    Config.find(live, BaseConfig.LIVE).update();
                    LiveConfig liveConfig = LiveConfig.get().init();
                    liveConfig.setSilent();
                    liveConfig.setForceReload();
                    liveConfig.load();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 判断远端内容相对本地缓存是否发生变化。
     * <p>
     * 采用语义比对而非逐字节哈希：订阅接口常在 ext 等字段里带 uid、时间戳一类动态值，
     * 每次请求字节都不同，但站点集合其实没变。这里只比对真正影响用户的
     * 站点 key + api 集合，动态字段与顺序变化都不算更新。
     * <p>
     * 比对的基准是上一次成功加载并写入 Config.json 的内容：
     * 本地没有缓存时视为需要更新；下载失败则视为未变化，避免把可用缓存冲掉。
     */
    private static boolean isChanged(String url, Config local) {
        // 地址变了必然要回源，此时内容一定按新地址重新拉取。
        if (!url.equals(local.getUrl())) return true;
        String cached = local.getJson();
        if (TextUtils.isEmpty(cached)) return true;
        String remote = fetch(url);
        if (TextUtils.isEmpty(remote)) return false;
        String a = fingerprint(cached);
        String b = fingerprint(remote);
        // 任一侧无法解析成站点集合时，退回逐字节比对，至少不会漏掉更新。
        if (a == null || b == null) return !md5(remote).equals(md5(cached));
        return !a.equals(b);
    }

    /**
     * 提取内容的语义指纹：把 sites 里每个站点的 key 与 api 排序后拼成字符串。
     * 顺序、ext 内的动态字段、格式空白都不会影响结果。
     *
     * @return 指纹；内容不是站点配置结构时返回 null
     */
    private static String fingerprint(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            JsonElement element = object.get("sites");
            if (element == null || !element.isJsonArray()) return null;
            List<String> items = new ArrayList<>();
            for (JsonElement site : element.getAsJsonArray()) {
                if (!site.isJsonObject()) continue;
                JsonObject item = site.getAsJsonObject();
                items.add(optString(item, "key") + "\u0001" + optString(item, "api") + "\u0001" + optString(item, "name"));
            }
            Collections.sort(items);
            return String.join("\n", items);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 下载源内容，失败返回空串。 */
    private static String fetch(String url) {
        try {
            return Decoder.getJson(UrlUtil.convert(url), TAG);
        } catch (Throwable e) {
            e.printStackTrace();
            return "";
        }
    }

    /** 安全读取 JSON 字符串字段，缺失或 null 时返回空串。 */
    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
