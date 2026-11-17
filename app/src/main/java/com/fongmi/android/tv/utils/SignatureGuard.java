package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * 安装包签名校验：只允许由指定证书（haoiyu1.keystore）签名的包运行。
 * <p>
 * 比对对象为签名证书的 SHA-256 摘要（即 apksigner 输出的 SHA-256 指纹），
 * 该值来自证书本身、与打包时的签名算法无关，重签名后必然变化。
 * 用 {@code keytool -list -v -keystore haoiyu1.keystore} 可复查。
 */
public final class SignatureGuard {

    private static final String TAG = "SignatureGuard";

    /** haoiyu1.keystore 中签名证书的 SHA-256 指纹（小写十六进制）。 */
    private static final String EXPECTED_SHA256 = "d2f569ec0d8facb3d2996161a1699adf8df92d063c945be27388de48795265f0";

    private SignatureGuard() {
    }

    /**
     * 校验当前安装包的签名。
     *
     * @return true 表示签名与基准证书一致，可以继续运行；false 表示需立即终止
     */
    public static boolean verify(Context context) {
        byte[][] digests = collectDigests(context);
        if (digests.length == 0) {
            Log.e(TAG, "signature not found");
            return false;
        }
        for (byte[] digest : digests) {
            if (EXPECTED_SHA256.equalsIgnoreCase(toHex(digest))) return true;
        }
        Log.e(TAG, "signature mismatch, actual=" + toHex(digests[0]));
        return false;
    }

    /**
     * 签名不一致时终止进程。直接 kill 而非抛异常，
     * 一是不会弹出 CustomActivityOnCrash 的崩溃跟踪页，二是绕过各线程的 uncaught handler。
     */
    public static void kill() {
        try {
            // 先清空任务栈，避免"最近任务"里残留一个可点击恢复的界面
            android.os.Process.killProcess(android.os.Process.myPid());
        } finally {
            System.exit(10);
        }
    }

    /**
     * 取当前包的签名摘要。新签名方案（v2/v3）会带来多个签名者，
     * 因此逐个比对：只要其中有基准证书即视为合法。
     */
    private static byte[][] collectDigests(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return new byte[0][];
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return new byte[0][];
            byte[][] digests = new byte[signatures.length][];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < signatures.length; i++) digests[i] = md.digest(signatures[i].toByteArray());
            return digests;
        } catch (Exception e) {
            Log.e(TAG, "read signature failed", e);
            return new byte[0][];
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
