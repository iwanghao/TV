package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.ui.activity.HomeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 承载下载队列的前台服务，保证退出播放页/切后台后下载继续。
 */
public class DownloadService extends Service implements DownloadManager.Listener {

    private static final String CHANNEL_ID = "download";
    private static final int NOTIFICATION_ID = 9528;

    private NotificationManagerCompat manager;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, DownloadService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        manager = NotificationManagerCompat.from(this);
        createChannel();
        DownloadManager.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        DownloadManager.get().restore();
        if (!DownloadManager.get().isActive()) stopSelf();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        List<Long> ids = DownloadManager.get().getActiveIds();
        List<Download> active = new ArrayList<>();
        active.addAll(Download.find(Download.RUNNING));
        active.addAll(Download.find(Download.QUEUED));
        long bytes = 0;
        long size = 0;
        String text = getString(R.string.download_notify_preparing);
        for (Download item : active) {
            bytes += item.getBytes();
            size += Math.max(item.getTotal(), item.getBytes());
            if (item.isRunning()) text = item.getVodName().concat(" · ").concat(item.getEpisodeName());
        }
        if (active.size() > 1) text = text.concat("  (").concat(String.valueOf(active.size())).concat(")");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_vod)
                .setContentTitle(getString(R.string.download_notify_title))
                .setContentText(text)
                .setOngoing(!ids.isEmpty())
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(contentIntent());
        if (size > 0) builder.setProgress(100, (int) Math.min(100, bytes * 100 / size), false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        if (manager == null) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDownloadChanged(String vodKey, String flag) {
        if (!DownloadManager.get().isActive()) {
            stopSelf();
            return;
        }
        updateNotification();
    }

    @Override
    public void onDestroy() {
        DownloadManager.get().removeListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
