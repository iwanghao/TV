package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.Download;

import java.util.List;

@Dao
public abstract class DownloadDao extends BaseDao<Download> {

    @Query("SELECT * FROM Download")
    public abstract List<Download> findAll();

    @Query("SELECT * FROM Download WHERE id = :id")
    public abstract Download find(long id);

    @Query("SELECT * FROM Download WHERE cid = :cid AND vodKey = :vodKey AND flag = :flag AND episodeUrl = :episodeUrl LIMIT 1")
    public abstract Download find(int cid, String vodKey, String flag, String episodeUrl);

    @Query("SELECT * FROM Download WHERE vodKey = :vodKey AND flag = :flag")
    public abstract List<Download> find(String vodKey, String flag);

    @Query("SELECT * FROM Download WHERE cid = :cid AND vodKey = :vodKey AND flag = :flag")
    public abstract List<Download> find(int cid, String vodKey, String flag);

    @Query("SELECT * FROM Download WHERE vodKey = :vodKey")
    public abstract List<Download> find(String vodKey);

    @Query("SELECT * FROM Download WHERE status = :status")
    public abstract List<Download> find(int status);

    @Query("SELECT COUNT(*) FROM Download WHERE vodKey = :vodKey AND flag = :flag AND status = 3")
    public abstract int countDone(String vodKey, String flag);

    @Query("UPDATE Download SET status = :status WHERE id = :id")
    public abstract void updateStatus(long id, int status);

    @Query("UPDATE Download SET status = :status, bytes = :bytes, total = :total, dataSource = :dataSource WHERE id = :id")
    public abstract void updateState(long id, int status, long bytes, long total, String dataSource);

    @Query("UPDATE Download SET bytes = :bytes, total = :total WHERE id = :id")
    public abstract void updateProgress(long id, long bytes, long total);

    @Query("UPDATE Download SET status = 1 WHERE status = 2")
    public abstract void resetRunning();

    @Query("DELETE FROM Download WHERE id = :id")
    public abstract void delete(long id);

    @Query("DELETE FROM Download WHERE vodKey = :vodKey")
    public abstract void delete(String vodKey);

    @Query("DELETE FROM Download WHERE cid = :cid")
    public abstract void delete(int cid);

    @Query("DELETE FROM Download")
    public abstract void delete();
}
