package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线缓存任务。以 (cid, vodKey, flag, episodeUrl) 唯一确定一集，
 * dataSource 保存可下载的媒体地址（mp4 或 m3u8），用于在无播放页时也能续下。
 */
@Entity(indices = {@Index(value = {"cid", "vodKey", "flag", "episodeUrl"}, unique = true)})
public class Download {

    public static final int NONE = 0;
    public static final int QUEUED = 1;
    public static final int RUNNING = 2;
    public static final int DONE = 3;
    public static final int FAILED = 4;
    public static final int PAUSED = 5;

    @PrimaryKey(autoGenerate = true)
    private long id;
    private int cid;
    private String vodKey;
    private String vodId;
    private String vodName;
    private String siteName;
    private String flag;
    private String episodeName;
    private String episodeUrl;
    private String dataSource;
    private String header;
    private int status;
    private long bytes;
    private long total;
    private long createTime;

    public Download() {
    }

    public static Download find(int cid, String vodKey, String flag, String episodeUrl) {
        return AppDatabase.get().getDownloadDao().find(cid, vodKey, flag, episodeUrl);
    }

    public static List<Download> find(String vodKey, String flag) {
        return AppDatabase.get().getDownloadDao().find(vodKey, flag);
    }

    public static List<Download> find(int cid, String vodKey, String flag) {
        return AppDatabase.get().getDownloadDao().find(cid, vodKey, flag);
    }

    public static List<Download> find(String vodKey) {
        return AppDatabase.get().getDownloadDao().find(vodKey);
    }

    public static List<Download> find(int status) {
        return AppDatabase.get().getDownloadDao().find(status);
    }

    public static List<Download> findAll() {
        return AppDatabase.get().getDownloadDao().findAll();
    }

    public static int countDone(String vodKey, String flag) {
        return AppDatabase.get().getDownloadDao().countDone(vodKey, flag);
    }

    public static void delete(Download item) {
        if (item != null) AppDatabase.get().getDownloadDao().delete(item.getId());
    }

    public static void delete(String vodKey) {
        AppDatabase.get().getDownloadDao().delete(vodKey);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public String getVodKey() {
        return vodKey == null ? "" : vodKey;
    }

    public void setVodKey(String vodKey) {
        this.vodKey = vodKey;
    }

    public String getVodId() {
        return vodId == null ? "" : vodId;
    }

    public void setVodId(String vodId) {
        this.vodId = vodId;
    }

    public String getVodName() {
        return vodName == null ? "" : vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getSiteName() {
        return siteName == null ? "" : siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getFlag() {
        return flag == null ? "" : flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getEpisodeName() {
        return episodeName == null ? "" : episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public String getEpisodeUrl() {
        return episodeUrl == null ? "" : episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public String getDataSource() {
        return dataSource == null ? "" : dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getHeader() {
        return header == null ? "" : header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public Map<String, String> headerMap() {
        if (TextUtils.isEmpty(header)) return new HashMap<>();
        try {
            Type type = TypeToken.getParameterized(Map.class, String.class, String.class).getType();
            Map<String, String> map = App.gson().fromJson(header, type);
            return map == null ? new HashMap<>() : map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public Download header(Map<String, String> map) {
        this.header = map == null || map.isEmpty() ? "" : App.gson().toJson(map);
        return this;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getBytes() {
        return bytes;
    }

    public void setBytes(long bytes) {
        this.bytes = bytes;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public boolean isDone() {
        return status == DONE;
    }

    public boolean isRunning() {
        return status == RUNNING;
    }

    public boolean isQueued() {
        return status == QUEUED;
    }

    public boolean isFailed() {
        return status == FAILED;
    }

    public boolean isPaused() {
        return status == PAUSED;
    }

    public boolean isIdle() {
        return status == NONE;
    }

    public float getProgress() {
        if (isDone()) return 1f;
        if (total <= 0) return 0f;
        return Math.min(1f, bytes * 1f / total);
    }

    public Download save() {
        if (id == 0) {
            Long rowId = AppDatabase.get().getDownloadDao().insert(this);
            if (rowId != null && rowId > 0) id = rowId;
            else {
                Download exist = AppDatabase.get().getDownloadDao().find(cid, vodKey, flag, episodeUrl);
                if (exist != null) id = exist.getId();
            }
            AppDatabase.get().getDownloadDao().update(this);
        } else {
            AppDatabase.get().getDownloadDao().update(this);
        }
        return this;
    }

    /**
     * 只写状态/进度，避免频繁全字段更新。
     */
    public Download persist() {
        if (id == 0) return save();
        AppDatabase.get().getDownloadDao().updateState(id, status, bytes, total, dataSource);
        return this;
    }

    public Download status(int status) {
        this.status = status;
        if (status == DONE) {
            this.bytes = Math.max(bytes, total);
        }
        AppDatabase.get().getDownloadDao().updateStatus(id, status);
        return this;
    }

    public Download progress(long bytes, long total) {
        this.bytes = bytes;
        this.total = total;
        AppDatabase.get().getDownloadDao().updateProgress(id, bytes, total);
        return this;
    }

    public static long sizeOf(String vodKey, String flag) {
        long size = 0;
        for (Download item : find(vodKey, flag)) if (item.isDone()) size += item.getTotal();
        return size;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Download it)) return false;
        return id == it.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @NonNull
    @Override
    public String toString() {
        return getVodName() + " " + getFlag() + " " + getEpisodeName();
    }
}
