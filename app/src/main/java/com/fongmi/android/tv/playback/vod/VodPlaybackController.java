package com.fongmi.android.tv.playback.vod;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.playback.PlaybackResult;

import java.util.Collections;
import java.util.List;

public class VodPlaybackController {

    private static final String PUSH_PREFIX = "push://";
    private static final String SEARCH_PREFIX = "msearch:";

    private final VodPlaybackHost host;
    private final VodDataSource dataSource;
    private final VodPlaybackState state;
    private final VodHistoryPolicy historyPolicy;
    private final VodFallbackPolicy fallbackPolicy;
    private final VodPreloader preloader;
    private History lastHistory;

    public VodPlaybackController(VodPlaybackHost host, VodDataSource dataSource, VodPlaybackState state) {
        this.host = host;
        this.dataSource = dataSource;
        this.state = state;
        this.historyPolicy = new VodHistoryPolicy();
        this.fallbackPolicy = new VodFallbackPolicy(this, state, host, dataSource);
        this.preloader = new VodPreloader(host, dataSource, state);
    }

    public void reset() {
        preloader.clear();
        state.reset();
    }

    public void checkId() {
        String id = resolveVodId();
        if (id.isEmpty() || id.startsWith(SEARCH_PREFIX)) detailEmpty(false);
        else if (!state.isDetailRequested(host.getVodKey(), id)) requestDetail();
    }

    /**
     * 离线播放本地文件：不联网，用 Download 记录里的信息还原详情页（剧名/线路/集数），
     * 然后直接播放本地文件。返回 true 表示已走离线路径。
     */
    private boolean startLocalPlayback() {
        String path = host.getLocalFile();
        if (path == null || path.isEmpty() || !new java.io.File(path).exists()) return false;
        try {
            // 用 host 提供的剧名/线路/集数构造一个合成 Vod + Flag，渲染详情页。
            String name = host.getVodName();
            if (name == null || name.isEmpty()) name = "离线播放";
            Vod vod = new Vod();
            vod.setName(name);
            vod.setId(host.getVodId());
            Flag flag = Flag.create(host.getVodMark());

            // 查出同一剧、同一站源、同一线路下所有已下载完成的集，全部列进选集。
            int cid = host.getCid();
            String flagName = flag.getFlag();
            String targetUrl = host.getEpisodeUrl();
            Episode target = null;
            java.util.List<com.fongmi.android.tv.bean.Download> downloads = com.fongmi.android.tv.bean.Download.find(cid, host.getVodKey(), flagName);
            // 先收集已下载集，再按集数自然排序（第1集→第10集），避免依赖数据库存储顺序导致乱序。
            java.util.List<Episode> downloaded = new java.util.ArrayList<>();
            for (com.fongmi.android.tv.bean.Download d : downloads) {
                if (!d.isDone()) continue;
                Episode ep = Episode.create(d.getEpisodeName(), d.getEpisodeUrl());
                downloaded.add(ep);
                if (target == null && d.getEpisodeUrl().equals(targetUrl)) target = ep;
            }
            downloaded.sort((a, b) -> {
                int na = a.getNumber();
                int nb = b.getNumber();
                if (na != nb) return Integer.compare(na, nb);
                return a.getName().compareToIgnoreCase(b.getName());
            });
            flag.getEpisodes().addAll(downloaded);
            // 兜底：若没匹配到目标集，用 host 提供的集名构造一个。
            if (target == null) {
                String episodeName = host.getEpisodeName();
                if (episodeName == null || episodeName.isEmpty()) episodeName = "第 1 集";
                String episodeUrl = targetUrl == null || targetUrl.isEmpty() ? host.getVodId() : targetUrl;
                target = Episode.create(episodeName, episodeUrl);
                flag.getEpisodes().add(target);
            }
            vod.getFlags().add(flag);

            // 渲染详情页（剧名 + 线路 + 全部已下载集数），复用正常详情页布局。
            History history = historyPolicy.findOrCreate(host.getHistoryKey(), host.getVodMark(), vod);
            state.setFlags(vod.getFlags());
            state.setHistory(history);
            lastHistory = history;
            host.renderDetail(vod, history);
            host.renderFlags(vod.getFlags());
            host.renderHistory(history);
            host.renderEpisodes(flag.getEpisodes());
            host.renderEpisodeSelection(target);

            // 直接播放目标集的本地文件。
            Result result = new Result();
            result.setUrl("file://" + path);
            result.setFlag(flag.getFlag());
            result.setParse(0);
            applyPlaybackResult(result, VodPlayRequest.create(host.getVodKey(), flag, target));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveVodId() {
        String id = host.getVodId();
        if (!id.startsWith(PUSH_PREFIX)) return id;
        host.usePushId(id.substring(PUSH_PREFIX.length()));
        return host.getVodId();
    }

    public void requestDetail() {
        String key = host.getVodKey();
        String id = host.getVodId();
        state.setDetailRequest(key, id);
        dataSource.detailContent(key, id);
    }

    public void onDetailResult(VodDetailResult detail) {
        if (detail == null || !detail.matches(host.getVodKey(), host.getVodId())) return;
        Result result = detail.result();
        if (result.getList().isEmpty()) detailEmpty(result.hasMsg());
        else detailLoaded(result.getVod());
        host.showDetailMessage(result.getMsg());
    }

    public void updateVod(Vod item) {
        if (preloader.isRequestPending()) return;
        History history = state.getHistory();
        replaceVodId(history, item.getId());
        mergeFlags(item.getFlags());
        updateHistory(history, item);
        host.renderVodUpdate(item);
        publishActivePlaybackMetadata();
    }

    private void updateHistory(History history, Vod item) {
        String pic = item.getPic();
        String name = item.getName();
        boolean hasPic = !pic.isEmpty();
        boolean hasName = !name.isEmpty();
        if (hasPic) history.setVodPic(pic);
        if (hasName) history.setVodName(name);
        if (hasName || hasPic) historyPolicy.saveCurrent(history);
    }

    private void publishActivePlaybackMetadata() {
        Episode episode = findEpisode(state.getActiveRequest());
        if (episode != null) publishPlaybackMetadata(episode);
    }

    private void replaceVodId(History history, String id) {
        if (id.isEmpty() || id.equals(host.getVodId())) return;
        String oldKey = host.getHistoryKey();
        host.setVodId(id);
        String newKey = host.getHistoryKey();
        history.replace(newKey);
        Keep.replace(oldKey, newKey);
    }

    public void onPlaybackResult(PlaybackResult<VodPlayRequest> playback) {
        if (playback == null || cannotApply(playback)) return;
        applyPlaybackResult(playback.result(), playback.request());
    }

    private void applyPlaybackResult(Result result, VodPlayRequest request) {
        Episode episode = findEpisode(request);
        if (episode == null) return;
        applyPlaybackState(result, request);
        renderPlaybackResult(result);
        updatePlaybackPosition(result);
        host.loadDanmaku(result, state.getHistory(), episode);
        startPlayback(result, startPositionMs(), episode);
        preloader.update(result);
    }

    private void applyPlaybackState(Result result, VodPlayRequest request) {
        state.setQuality(result);
        state.setPlayingRequest(request);
        state.setUseParse(result.isUseParse());
        result.getUrl().set(state.getQualityPosition());
    }

    private void renderPlaybackResult(Result result) {
        host.renderUseParse(state.isUseParse());
        host.renderQuality(result, result.getUrl().isMulti());
        if (result.hasDesc()) host.renderDescription(result.getDesc());
        if (result.hasArtwork()) host.renderArtwork(result.getArtwork());
    }

    private void updatePlaybackPosition(Result result) {
        if (result.hasPosition()) state.getHistory().setPosition(result.getPosition());
    }

    private void startPlayback(Result result, long startPositionMs, Episode episode) {
        MediaMetadata metadata = publishPlaybackMetadata(episode);
        host.startPlayback(result, state.isUseParse(), startPositionMs, metadata);
    }

    public void onSearchResult(Result result) {
        fallbackPolicy.onSearchResult(result);
    }

    public void selectFlag(Flag item) {
        selectFlag(item, false);
    }

    /**
     * 当前线路，供下载面板按线路取集数。
     */
    public Flag getCurrentFlag() {
        return state.hasFlags() ? state.getFlag() : null;
    }

    public History getCurrentHistory() {
        return state.getHistory();
    }

    private void selectFlag(Flag item, boolean force) {
        if (!state.hasFlags()) return;
        Flag selected = resolveFlag(item);
        if (!force && selected.isSelected()) return;
        preloader.clear();
        for (Flag flag : state.getFlags()) flag.setSelected(selected);
        host.renderFlagSelection(selected);
        host.renderEpisodes(selected.getEpisodes());
        host.renderQualityVisible(false);
        seamless(selected);
    }

    public void selectEpisode(Episode item) {
        if (!state.hasFlags()) return;
        saveCurrentHistory();
        Flag selected = state.getFlag();
        for (Flag flag : state.getFlags()) flag.toggle(flag == selected, item);
        historyPolicy.updateEpisode(state.getHistory(), state.getFlag(), item);
        host.renderEpisodeSelection(item);
        if (host.isFullscreenForPlayback()) host.showEpisodeReady(item);
        playEpisode(item);
    }

    private void playEpisode(Episode item) {
        // 优先匹配本地离线文件：命中则直接用本地文件播放，保持详情页布局（剧名/线路/集数）不变，做到无感。
        Result local = resolveLocalResult(item);
        if (local != null) {
            applyPlaybackResult(local, VodPlayRequest.create(host.getVodKey(), state.getFlag(), item));
            return;
        }
        Result result = preloader.consume(item);
        host.stopPlaybackForRefresh();
        if (result == null) requestSelectedEpisode();
        else applyPlaybackResult(result, VodPlayRequest.create(host.getVodKey(), state.getFlag(), item));
    }

    /**
     * 按 站源/线路/集数 匹配本地已下载文件，命中则构造一个指向本地文件的 Result。
     */
    private Result resolveLocalResult(Episode item) {
        try {
            if (item == null) return null;
            History history = state.getHistory();
            if (history == null) return null;
            Flag flag = state.getFlag();
            if (flag == null) return null;
            com.fongmi.android.tv.bean.Download download = com.fongmi.android.tv.bean.Download.find(history.getCid(), host.getVodKey(), flag.getFlag(), item.getUrl());
            if (download == null || !download.isDone()) return null;
            String path = download.getDataSource();
            if (path == null || path.isEmpty() || !new java.io.File(path).exists()) return null;
            Result result = new Result();
            result.setUrl("file://" + path);
            result.setFlag(flag.getFlag());
            result.setParse(0);
            result.setHeader(download.headerMap());
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public void selectQuality(Result result) {
        if (!state.hasEpisode()) return;
        state.setQuality(result);
        state.setQualityPosition(result.getUrl().getPosition());
        preloader.clear();
        startPlayback(result, host.getPlayerPosition(), state.getEpisode());
        preloader.preloadNext();
    }

    public void selectParse(Parse item) {
        VodConfig.get().setParse(item);
        refresh();
    }

    private void mergeFlags(List<Flag> items) {
        if (items.isEmpty()) return;
        if (state.hasFlags()) {
            Flag activated = state.getFlag();
            for (Flag item : items) mergeFlag(activated, item);
            host.renderFlags(state.getFlags());
        } else {
            state.setFlags(items);
            host.renderFlags(state.getFlags());
        }
    }

    public void selectSource(Vod item) {
        switchSource(item, false);
    }

    void fallbackSource(Vod item) {
        switchSource(item, true);
    }

    private void switchSource(Vod item, boolean autoFallback) {
        state.setAutoFallback(autoFallback);
        saveCurrentHistory();
        preloader.clear();
        state.clearPlayRequest();
        host.prepareSource(item);
        requestDetail();
    }

    public void search(String keyword) {
        fallbackPolicy.search(keyword, false);
    }

    public void manualSwitchSource() {
        fallbackPolicy.manualSwitchSource();
    }

    public void playbackError(String msg) {
        preloader.clear();
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    public void playbackEnded() {
        nextEpisode(true);
    }

    public void replay() {
        if (state.getHistory() != null) state.getHistory().setPosition(C.TIME_UNSET);
        if (host.isPlayerEmpty()) refresh();
        else host.replay(startPositionMs());
    }

    public void refresh() {
        saveCurrentHistory();
        preloader.clear();
        host.stopPlaybackForRefresh();
        restorePlaybackSelection(state.getPlayingRequest());
        requestSelectedEpisode();
    }

    public void onPlaybackServiceReady() {
        MediaMetadata metadata = state.getPlaybackMetadata();
        if (metadata != null) host.renderPlaybackMetadata(metadata);
        VodPlayRequest request = state.getPlayingRequest();
        if (request == null || host.hasPlaybackSession()) return;
        restorePlaybackSelection(request);
        requestSelectedEpisode();
    }

    private void requestSelectedEpisode() {
        if (state.hasEpisode()) requestPlayer(state.getFlag(), state.getEpisode());
    }

    public void nextEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? -1 : 1, notify, reversed);
    }

    public void prevEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? 1 : -1, notify, reversed);
    }

    private void moveEpisode(int offset, boolean notify, boolean reversed) {
        if (!state.hasEpisode()) return;
        Episode item = state.getRelativeEpisode(offset);
        if (!item.isSelected()) selectEpisode(item);
        else if (notify && offset > 0) host.showNoNext(reversed);
        else if (notify) host.showNoPrev(reversed);
    }

    public void reverseEpisode(boolean scroll) {
        if (!state.hasFlags()) return;
        for (Flag flag : state.getFlags()) reverseEpisodes(flag);
        host.renderReverseEpisodes(state.getFlag().getEpisodes(), scroll);
    }

    private void reverseEpisodes(Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        int position = flag.getPosition();
        Collections.reverse(episodes);
        if (position >= 0 && position < episodes.size()) flag.setPosition(episodes.size() - position - 1);
    }

    private void saveCurrentHistory() {
        if (state.getPlayingRequest() == null || !host.canTrackPlaybackProgress()) historyPolicy.save(currentHistory());
        else saveHistory(false, System.currentTimeMillis(), host.getPlayerPosition(), host.getPlayerDuration());
    }

    public void saveHistory(boolean exit, long time, long position, long duration) {
        History history = exit ? historyForExit() : currentHistory();
        if (host.isLivePlayback()) historyPolicy.saveVisit(history, exit, time);
        else historyPolicy.saveProgress(history, exit, time, position, duration);
    }

    public void onTimeChanged(long time, long position, long duration) {
        History history = currentHistory();
        historyPolicy.updateProgress(history, time, position, duration);
        if (history != null && history.getEnding() > 0 && history.getEnding() + position >= duration) nextEpisode(false);
    }

    public long startPositionMs() {
        return historyPolicy.startPositionMs(state.getHistory());
    }

    private History currentHistory() {
        History history = state.getHistory();
        if (history != null) lastHistory = history;
        return history;
    }

    private History historyForExit() {
        History history = currentHistory();
        return history == null ? lastHistory : history;
    }

    public void onPreloadResult(PlaybackResult<VodPlayRequest> preload) {
        preloader.onResult(preload);
    }

    public void setOpening(long opening) {
        if (state.getHistory() != null) state.getHistory().setOpening(opening);
    }

    public void setEnding(long ending) {
        if (state.getHistory() != null) state.getHistory().setEnding(ending);
    }

    public void setScale(int scale) {
        if (state.getHistory() != null) state.getHistory().setScale(scale);
    }

    public void setRevSort(boolean revSort) {
        if (state.getHistory() != null) state.getHistory().setRevSort(revSort);
    }

    public void setRevPlay(boolean revPlay) {
        if (state.getHistory() != null) state.getHistory().setRevPlay(revPlay);
    }

    private void detailEmpty(boolean shouldFinish) {
        if (shouldFinish || host.isFromCollect()) {
            host.finishVod();
            return;
        }
        // 联网查不到详情时，回退用本地缓存的下载记录还原详情页并播放本地文件。
        if (startLocalPlayback()) return;
        String name = host.getVodName();
        if (name.isEmpty()) {
            host.renderEmptyDetail();
        } else {
            host.renderFallbackName(name);
            host.onDetailFallbackScheduled();
            fallbackPolicy.emptyDetail();
        }
    }

    private void detailLoaded(Vod item) {
        VodPlayRequest activeRequest = state.getActiveRequest();
        History history = applyDetail(item);
        if (state.hasFlags()) restoreDetail(activeRequest, history);
        else fallbackPolicy.emptyFlag();
    }

    private History applyDetail(Vod item) {
        item.checkPic(host.getVodPic());
        item.checkName(host.getVodName());
        List<Flag> flags = item.getFlags();
        state.setFlags(flags);
        History history = historyPolicy.findOrCreate(host.getHistoryKey(), host.getVodMark(), item);
        state.setHistory(history);
        lastHistory = history;
        host.renderDetail(item, history);
        host.renderFlags(flags);
        host.renderHistory(history);
        host.onDetailFallbackCancelled();
        return history;
    }

    private void restoreDetail(VodPlayRequest activeRequest, History history) {
        if (restorePlaybackSelection(activeRequest)) publishPlaybackMetadata(state.getEpisode());
        else selectFlag(history.getFlag(), true);
        if (history.isRevSort()) reverseEpisode(true);
        if (state.getPlayingRequest() != null && !host.hasPlaybackSession()) requestSelectedEpisode();
    }

    private boolean restorePlaybackSelection(VodPlayRequest request) {
        Flag flag = state.findFlag(host.getVodKey(), request);
        Episode episode = state.findEpisode(host.getVodKey(), flag, request);
        if (flag == null || episode == null) return false;
        for (Flag item : state.getFlags()) item.setSelected(flag);
        for (Flag item : state.getFlags()) item.toggle(item == flag, episode);
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        host.renderFlagSelection(flag);
        host.renderEpisodes(flag.getEpisodes());
        host.renderEpisodeSelection(episode);
        host.renderQualityVisible(state.getQuality().getUrl().isMulti());
        return true;
    }

    private void requestPlayer(Flag flag, Episode episode) {
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), flag, episode);
        state.setPendingRequest(request);
        publishPlaybackMetadata(episode);
        dataSource.playerContent(request);
        host.onPlaybackRequested();
    }

    private Episode findEpisode(VodPlayRequest request) {
        return state.findEpisode(host.getVodKey(), request);
    }

    private MediaMetadata publishPlaybackMetadata(Episode episode) {
        MediaMetadata metadata = VodPlaybackMedia.metadata(state.getHistory(), episode);
        state.setPlaybackMetadata(metadata);
        host.renderPlaybackMetadata(metadata);
        return metadata;
    }

    private void seamless(Flag flag) {
        History history = state.getHistory();
        Episode episode = history == null ? null : flag.find(history.getVodRemarks(), host.getVodMark().isEmpty());
        host.renderQualityVisible(episode != null && episode.isSelected() && state.getQuality().getUrl().isMulti());
        if (episode == null || episode.isSelected()) return;
        history.setVodRemarks(episode.getName());
        selectEpisode(episode);
    }

    private void mergeFlag(Flag activated, Flag item) {
        Flag target = findFlag(item);
        if (target == null) {
            state.getFlags().add(item);
        } else {
            target.mergeEpisodes(item.getEpisodes(), state.getHistory() != null && state.getHistory().isRevSort());
            if (target.equals(activated)) host.renderEpisodes(target.getEpisodes());
        }
    }

    private Flag resolveFlag(Flag item) {
        Flag flag = findFlag(item);
        if (flag != null) return flag;
        return state.getFlags().get(0);
    }

    private Flag findFlag(Flag item) {
        if (item != null) for (Flag flag : state.getFlags()) if (flag.equals(item)) return flag;
        return null;
    }

    private boolean cannotApply(PlaybackResult<VodPlayRequest> playback) {
        VodPlayRequest pending = state.getPendingRequest();
        VodPlayRequest request = playback.request();
        if (host.isHostFinishing() || pending == null || request == null) return true;
        return !pending.matches(request) || findEpisode(request) == null || !request.accepts(playback.result());
    }
}
