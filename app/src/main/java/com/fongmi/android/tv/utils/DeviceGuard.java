package com.fongmi.android.tv.utils;

import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 设备授权：启动时向服务端静默上报本机型号信息，由服务端决定是否放行。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>服务端是唯一权威，客户端只执行结论。本地名单/缓存全部可被 root 设备篡改，不可作为放行依据。</li>
 *   <li>上报失败（断网、服务端异常）一律放行，只记录一个"未能校验"的标记。
 *       真正的封禁依赖服务端在线判定，避免用户家里断网就打不开 APP。</li>
 *   <li>上报本身不阻塞启动：网络请求在后台线程跑，UI 不受影响。</li>
 * </ul>
 */
public final class DeviceGuard {

    private static final String TAG = "DeviceGuard";

    /**
     * 设备上报接口。返回 {@code {"allow":true}} 放行，{@code {"allow":false}} 拦截。
     * 服务端也可以回 {@code {"allow":true,"msg":"..."}}，msg 会作为拦截理由展示。
     * <p>
     * 实际取值来自 buildConfigField，可通过 {@code -PdeviceApi=...} 覆盖，见 app/build.gradle。
     */
    public static final String API = BuildConfig.DEVICE_API;

    /** 约定的共享密钥，服务端校验 X-Sign，防止别人伪造请求刷名单。 */
    private static final String SECRET = "haoiyu-tv-4f3c9a7e2b6d1c85";

    /** 同一次进程运行内只上报一次。 */
    private static volatile boolean reported;

    /** socket 连接超时，与 OkHttp 的 connectTimeout 保持一致。 */
    private static final int SOCKET_TIMEOUT = 5000;

    private DeviceGuard() {
    }

    /**
     * 静默上报设备信息，并在需要时拦截或提示。
     *
     * @param callback 结论回调，{@code null} 表示调用方不关心结果（后台补报场景）
     */
    public static void report(Callback callback) {
        if (reported) return;
        reported = true;
        Task.execute(() -> {
            try {
                JSONObject result = send();
                if (result == null) return;
                // 服务端明确回 false 才拦截；字段缺失（老版本服务端）视为放行。
                boolean allow = result.optBoolean("allow", true);
                String msg = result.optString("msg", "");
                Notice notice = Notice.parse(result.optJSONObject("notice"));
                Prefers.put("device_allow", allow);
                Prefers.put("device_report_time", System.currentTimeMillis());
                App.post(() -> {
                    if (!allow) {
                        // 拦截优先：有 notice 用 notice 的内容，没有就退回 msg
                        if (callback != null) callback.error(TextUtils.isEmpty(msg) ? fallback(notice) : msg);
                        else Notify.show(TextUtils.isEmpty(msg) ? fallback(notice) : msg);
                        return;
                    }
                    if (callback != null) callback.success();
                    // 放行但带提醒（更新提示、试用剩余天数）：交给调用方决定怎么展示
                    if (notice != null && listener != null) listener.onNotice(notice);
                });
            } catch (Throwable e) {
                Log.e(TAG, "report failed", e);
            }
        });
    }

    private static String fallback(Notice notice) {
        return notice == null ? "本设备已被禁止使用" : notice.desc;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    private static volatile Listener listener;

    public interface Listener {

        /** 服务端下发了提醒（放行状态下的版本更新提示、试用剩余天数等）。 */
        void onNotice(Notice notice);
    }

    /** 服务端下发的提示内容，对应响应里的 {@code notice} 字段。 */
    public static class Notice {

        public final String title;
        public final String desc;
        public final String confirm;
        public final boolean cancelable;

        private Notice(String title, String desc, String confirm, boolean cancelable) {
            this.title = title;
            this.desc = desc;
            this.confirm = confirm;
            this.cancelable = cancelable;
        }

        /** 字段缺失或内容为空时返回 null，避免弹一个空白框。 */
        static Notice parse(JSONObject object) {
            if (object == null) return null;
            String desc = object.optString("desc", "");
            if (TextUtils.isEmpty(desc)) return null;
            String title = object.optString("title", "提示");
            String confirm = object.optString("confirm", "知道了");
            return new Notice(title, desc, confirm, object.optBoolean("cancelable", true));
        }
    }

    /** 同步发起上报，返回服务端 JSON；任何异常返回 null。 */
    private static JSONObject send() {
        try {
            long ts = System.currentTimeMillis() / 1000;

            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .dns(DeviceGuard::lookupIpv4)
                    // 字面 IP 的 URL 不会经过 dns，必须靠 socketFactory 强制 IPv4 栈，
                    // 否则双栈系统仍会用 ::ffff: 映射地址去连。
                    .socketFactory(new Ipv4SocketFactory())
                    .retryOnConnectionFailure(true)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build();
            RequestBody body = RequestBody.create(payload(ts).toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(API)
                    .addHeader("X-Sign", sign(ts))
                    .addHeader("User-Agent", "haoiyu-tv")
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                if (TextUtils.isEmpty(text)) return null;
                return new JSONObject(text);
            }
        } catch (Throwable e) {
            Log.e(TAG, "send failed: " + e, e);
            return null;
        }
    }

    /**
     * 只用 IPv4 解析。双栈环境下（WiFi 下发 IPv6 默认路由）系统会把 IPv4 目标也塞进
     * IPv6 映射地址走 v6 栈，若该路径不通就表现为 SYN_SENT 卡死到超时。
     */
    private static java.util.List<java.net.InetAddress> lookupIpv4(String hostname) throws java.net.UnknownHostException {
        java.util.List<java.net.InetAddress> result = new java.util.ArrayList<>();
        for (java.net.InetAddress address : java.net.InetAddress.getAllByName(hostname)) {
            if (address instanceof java.net.Inet4Address) result.add(address);
        }
        if (result.isEmpty()) result.addAll(java.util.Arrays.asList(java.net.InetAddress.getAllByName(hostname)));
        return result;
    }

    /**
     * 强制走 IPv4 的 SocketFactory。
     * <p>
     * 双栈设备上 Java 的 Socket 默认会走"未指定地址族"的 socket，Android 在
     * 存在 IPv6 默认路由时可能把 IPv4 目标映射成 ::ffff:x.x.x.x 交给 v6 栈，
     * 一旦该 v6 路径不通，连接就会一直卡在 SYN_SENT 直到超时。
     * 这里用 {@link java.net.Inet4Address} 显式构造 IPv4 socket 规避。
     */
    private static final class Ipv4SocketFactory extends javax.net.SocketFactory {

        @Override
        public java.net.Socket createSocket() throws java.io.IOException {
            return new java.net.Socket();
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return connect(host, port);
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return connect(host, port);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return connect(host.getHostAddress(), port);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return connect(host.getHostAddress(), port);
        }

        /** 用 IPv4 字面地址 + Inet4Address 绑定，避免落到 IPv6 映射栈。 */
        private java.net.Socket connect(String host, int port) throws java.io.IOException {
            java.net.InetAddress target = resolveIpv4(host);
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(target, port), SOCKET_TIMEOUT);
            return socket;
        }

        private java.net.InetAddress resolveIpv4(String host) throws java.io.IOException {
            java.util.List<java.net.InetAddress> all = lookupIpv4(host);
            if (all.isEmpty()) throw new java.net.UnknownHostException(host);
            return all.get(0);
        }
    }

    /** 上报体：设备维度信息 + 应用版本，服务端需要用哪几个字段由后台决定。 */
    private static JSONObject payload(long ts) {
        JSONObject object = new JSONObject();
        try {
            object.put("android_id", androidId());
            object.put("android_version", Build.VERSION.RELEASE);
            object.put("sdk", Build.VERSION.SDK_INT);
            object.put("brand", Build.BRAND);
            object.put("manufacturer", Build.MANUFACTURER);
            object.put("model", Build.MODEL);
            // 局域网 IP。公网 IP 由服务端从请求头 X-Forwarded-For / RemoteAddr 取，
            // 客户端自报的公网 IP 不可信。
            object.put("lan_ip", com.github.catvod.utils.Util.getIp());
            object.put("package", BuildConfig.APPLICATION_ID);
            object.put("version", BuildConfig.VERSION_NAME);
            object.put("version_code", BuildConfig.VERSION_CODE);
            object.put("ts", ts);
        } catch (Throwable e) {
            Log.e(TAG, "payload failed", e);
        }
        return object;
    }

    /**
     * 设备唯一标识。ANDROID_ID 在 Android 8 以后按签名+用户隔离，
     * 同一个 APK 在同一台设备上重装也保持一致，足够做"设备维度"的名单。
     * <p>
     * 部分定制电视盒子会返回 null 或全 0，此时退化用"型号+品牌"的哈希，
     * 精度差但至少能把同类盒子一起管控。
     */
    private static String androidId() {
        try {
            String id = Settings.Secure.getString(App.get().getContentResolver(), Settings.Secure.ANDROID_ID);
            if (!TextUtils.isEmpty(id) && !"9774d56d682e549c".equals(id) && !"0000000000000000".equals(id)) return id;
        } catch (Throwable ignored) {
        }
        return "fallback-" + Integer.toHexString((Build.BRAND + Build.MODEL + Build.DEVICE).hashCode());
    }

    /** {@code sha256(时间戳 + ":" + 密钥)}，服务端做同样计算并校验，防止伪造上报刷名单。 */
    private static String sign(long ts) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((ts + ":" + SECRET).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
