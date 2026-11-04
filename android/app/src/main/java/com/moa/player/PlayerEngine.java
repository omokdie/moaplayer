package com.moa.player;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Process wide playback session. Owns the ExoPlayer instance so that playback can continue
 * in the background (service) and survive activity re-creation.
 */
public final class PlayerEngine {
    private static PlayerEngine inst;

    public static synchronized PlayerEngine get(Context c) {
        if (inst == null) inst = new PlayerEngine(c.getApplicationContext());
        return inst;
    }

    public static final int REPEAT_OFF = 0, REPEAT_ONE = 1, REPEAT_ALL = 2;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean sw;
    private final List<Player.Listener> listeners = new ArrayList<>();

    public final List<MediaRef> playlist = new ArrayList<>();
    public int index = -1;

    // per-session state
    public float speed = 1f;
    public int repeatMode = REPEAT_OFF;
    public long abA = -1, abB = -1;
    public boolean backgroundActive;
    public boolean backgroundOverride; // toggled from the player menu for this session
    public String videoDecoder = "", audioDecoder = "";

    // sleep timer (kept here so it also works while the player screen is gone)
    public long sleepAt = -1;
    public boolean sleepAtEnd;
    /** When the sleep timer last stopped playback (elapsedRealtime): a covered player must not resume. */
    public long sleepStoppedAt;

    // external subtitles
    public final List<SubtitleParser.Track> subTracks = new ArrayList<>();
    public int subSelected = -1;
    public long subDelay;
    public boolean embeddedSubsOff;

    // pending playlist handed over from the library screens
    private List<MediaRef> pending;
    private int pendingIndex;
    private boolean pendingFromStart;

    // ---- fast start
    /** Built ahead of time on the library screens, so a tap does not pay for creating the player. */
    private ExoPlayer spare;
    private boolean spareSw;
    private static boolean codecsWarm;
    /** File opened at tap time, while the player screen is still being created. */
    private String aheadKey;
    private boolean aheadSw, aheadFromStart;
    /** A resume point that is moved back to a close key frame once the file is open. */
    private String snapKey;
    private long snapMs = -1;
    /** The original resume point: opening and closing again without watching must not creep backwards. */
    private String keepKey;
    private long keepMs = -1;
    /** How far a resume point may move back to start on a key frame (with "빠른 탐색"). */
    static final long SNAP_BEFORE_MS = 2000;

    private PlayerEngine(Context c) {
        app = c;
    }

    public ExoPlayer player() {
        return player;
    }

    public boolean isSw() {
        return sw;
    }

    public MediaRef current() {
        return index >= 0 && index < playlist.size() ? playlist.get(index) : null;
    }

    public boolean hasNext() {
        return index + 1 < playlist.size();
    }

    public boolean hasPrev() {
        return index > 0;
    }

    public void setPending(List<MediaRef> list, int idx, boolean fromStart) {
        pending = new ArrayList<>(list);
        pendingIndex = idx;
        pendingFromStart = fromStart;
    }

    /** Takes the pending playlist. Returns the "play from start" flag, or null if none was pending. */
    public Boolean takePending() {
        if (pending == null) return null;
        playlist.clear();
        playlist.addAll(pending);
        index = Util.clamp(pendingIndex, 0, Math.max(0, playlist.size() - 1));
        pending = null;
        return pendingFromStart;
    }

    public void setPlaylist(List<MediaRef> list, int idx) {
        playlist.clear();
        playlist.addAll(list);
        index = Util.clamp(idx, 0, Math.max(0, playlist.size() - 1));
    }

    public void addListener(Player.Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        if (player != null) player.addListener(l);
    }

    public void removeListener(Player.Listener l) {
        listeners.remove(l);
        if (player != null) player.removeListener(l);
    }

    /** Returns a player using the requested decoder mode, re-creating it if needed. */
    public ExoPlayer ensure(boolean swMode) {
        if (player != null && sw == swMode) return player;
        create(swMode);
        return player;
    }

    // ------------------------------------------------------------------ fast start

    /**
     * Prepares the next playback while the user is still browsing: fills the codec list cache in
     * the background (slow the first time in a process) and builds a spare player when idle.
     */
    public void warmUp() {
        warmCodecs();
        if (spare != null) return;
        Looper.myQueue().addIdleHandler(() -> {
            if (spare == null) {
                boolean swMode = "sw".equals(Prefs.str(app, Prefs.DECODER, "hw"));
                spare = build(swMode);
                spareSw = swMode;
            }
            return false;
        });
    }

    private static synchronized void warmCodecs() {
        if (codecsWarm) return;
        codecsWarm = true;
        // background priority, not the lowest: the playback thread waits on the same (synchronized)
        // cache, so a starved warm-up thread would hold up a tap right after start
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            String[] types = {MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, MimeTypes.VIDEO_VP9,
                    MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_OPUS};
            for (String m : types) MediaCodecUtil.warmDecoderInfoCache(m, false, false);
        }, "codec-warmup");
        t.start();
    }

    /**
     * Opens the tapped file right away (paused), before the player screen exists. The screen then
     * finds it already decoding and only has to attach its surface and press play.
     */
    public void prepareAhead(MediaRef r, boolean fromStart) {
        aheadKey = null;
        if (r == null || r.stream || unsupported(r.uri, r.mime)) return;
        if (player != null && current() != null) return; // a session is still running (background, PiP)
        boolean swMode = "sw".equals(Prefs.str(app, Prefs.DECODER, "hw"));
        ExoPlayer p = ensure(swMode);
        try {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .clearOverrides()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .build());
            p.setPlaybackParameters(new PlaybackParameters(speed));
            p.setRepeatMode(Player.REPEAT_MODE_OFF);
            p.setWakeMode(C.WAKE_MODE_LOCAL);
            p.setPlayWhenReady(false);
            open(r, resumePosition(r, fromStart));
        } catch (RuntimeException e) {
            return;
        }
        aheadKey = r.key();
        aheadSw = swMode;
        aheadFromStart = fromStart;
        // the screen did not come (launch cancelled): do not keep a decoder busy for nothing
        handler.removeCallbacks(aheadTimeout);
        handler.postDelayed(aheadTimeout, 8000);
    }

    private final Runnable aheadTimeout = () -> {
        if (aheadKey != null && !PlayerActivity.isAlive() && current() == null) {
            aheadKey = null;
            release();
        }
    };

    /** True (once) if {@link #prepareAhead} already opened this item with the same settings. */
    public boolean takeAhead(MediaRef r, boolean swMode, boolean fromStart) {
        boolean ok = aheadKey != null && player != null && r != null && aheadKey.equals(r.key())
                && aheadSw == swMode && aheadFromStart == fromStart && sw == swMode
                && player.getPlayerError() == null && player.getPlaybackState() != Player.STATE_IDLE
                && isCurrentItem(r);
        aheadKey = null;
        handler.removeCallbacks(aheadTimeout);
        return ok;
    }

    /** Where playback of an item starts: its resume point unless asked to start over or finished. */
    public long resumePosition(MediaRef r, boolean fromStart) {
        if (r == null || r.stream || fromStart) return 0;
        if ("never".equals(Prefs.str(app, Prefs.RESUME, "ask"))) return 0;
        HistoryDb.Entry h = HistoryDb.get(app).get(r.key());
        return h != null && !h.finished() && h.position > 5000 ? h.position : 0;
    }

    /**
     * Loads an item at a start position. With "빠른 탐색", a resume point is moved back once the file
     * is open: to a key frame if one lies within {@link #SNAP_BEFORE_MS} before it (then the first
     * frame shows at once), otherwise to exactly SNAP_BEFORE_MS earlier (Media3's tolerance rule),
     * which still shortens what has to be decoded from the previous key frame. An exact start
     * decodes everything from that key frame first: up to a second in long-GOP files.
     */
    public void open(MediaRef r, long startMs) {
        player.setMediaItem(mediaItemFor(r), startMs);
        boolean snap = startMs > 0 && !r.stream && Prefs.bool(app, Prefs.FAST_SEEK, true);
        snapKey = snap ? r.key() : null;
        snapMs = snap ? startMs : -1;
        keepKey = snap ? r.key() : null;
        keepMs = snap ? startMs : -1;
        player.prepare();
    }

    private final Player.Listener snapper = new Player.Listener() {
        @Override public void onTracksChanged(Tracks tracks) {
            maybeSnap();
        }

        @Override public void onPlaybackStateChanged(int state) {
            maybeSnap();
        }
    };

    private void maybeSnap() {
        if (snapMs < 0 || player == null) return;
        MediaItem mi = player.getCurrentMediaItem();
        if (mi == null || snapKey == null || !snapKey.equals(mi.mediaId)) {
            snapMs = -1;
            return;
        }
        if (player.getCurrentTracks().isEmpty() && player.getPlaybackState() != Player.STATE_READY) return; // not open yet
        long target = snapMs;
        snapMs = -1;
        if (!player.isCurrentMediaItemSeekable()) return;
        if (Math.abs(player.getCurrentPosition() - target) > 1000) return; // already moved by the user
        SeekParameters before = player.getSeekParameters(); // e.g. a seek bar drag already going on
        player.setSeekParameters(new SeekParameters(SNAP_BEFORE_MS * 1000, 0));
        player.seekTo(target);
        player.setSeekParameters(before);
    }

    /** Whether the player currently holds this item (and not the next one being loaded). */
    public boolean isCurrentItem(MediaRef r) {
        if (player == null || r == null) return false;
        MediaItem mi = player.getCurrentMediaItem();
        return mi != null && r.key().equals(mi.mediaId);
    }

    public void recreate(boolean swMode) {
        create(swMode);
    }

    private static final MediaCodecSelector SOFTWARE_FIRST = new MediaCodecSelector() {
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                    boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!MimeTypes.isVideo(mimeType)) return infos;
            List<MediaCodecInfo> softFirst = new ArrayList<>();
            List<MediaCodecInfo> hard = new ArrayList<>();
            for (MediaCodecInfo i : infos) {
                if (i.softwareOnly) softFirst.add(i); else hard.add(i);
            }
            softFirst.addAll(hard);
            return softFirst;
        }
    };

    /** Media item for a reference; adaptive stream MIME types from other apps are kept. */
    public static MediaItem mediaItemFor(MediaRef r) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(r.uri).setMediaId(r.key());
        if (r.mime != null) {
            String m = r.mime.toLowerCase(Locale.ROOT);
            if (m.contains("mpegurl")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
            else if (m.contains("dash+xml")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        return b.build();
    }

    /** True for stream types this build cannot open (only Smooth Streaming is left out). */
    public static boolean unsupported(Uri uri, String mime) {
        int t = androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(uri, mime);
        return t == C.CONTENT_TYPE_SS;
    }

    public void setSleep(long at, boolean atEnd) {
        sleepAt = at;
        sleepAtEnd = atEnd;
        handler.removeCallbacks(sleepTick);
        if (at > 0) handler.postDelayed(sleepTick, 1000);
    }

    private final Runnable sleepTick = new Runnable() {
        @Override public void run() {
            if (sleepAt <= 0) return;
            if (System.currentTimeMillis() >= sleepAt) {
                sleepAt = -1;
                sleepStoppedAt = android.os.SystemClock.elapsedRealtime();
                if (player != null) {
                    player.pause();
                    savePosition();
                }
                Util.toast(app, "수면 타이머: 재생을 멈췄습니다");
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** Handles the end of a file when no player screen is alive (background play). */
    private final Player.Listener headless = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED && !PlayerActivity.isAlive()) handler.post(PlayerEngine.this::onEndedHeadless);
        }
    };

    private void onEndedHeadless() {
        if (player == null || player.getPlaybackState() != Player.STATE_ENDED || PlayerActivity.isAlive()) return;
        savePosition();
        if (repeatMode == REPEAT_ONE) {
            player.seekTo(0);
            player.play();
            return;
        }
        if (sleepAtEnd) {
            sleepAtEnd = false;
            stopAll();
            return;
        }
        boolean auto = Prefs.bool(app, Prefs.AUTOPLAY, true) || repeatMode == REPEAT_ALL;
        if (auto && hasNext()) playHeadless(index + 1);
        else if (repeatMode == REPEAT_ALL && !playlist.isEmpty()) playHeadless(0);
        else stopAll();
    }

    /** Switches to another playlist item without a player screen (notification / headless). */
    public void playHeadless(int i) {
        if (player == null || i < 0 || i >= playlist.size()) return;
        savePosition();
        index = i;
        abA = abB = -1;
        clearSubs();
        MediaRef r = current();
        HistoryDb.Entry h = r.stream ? null : HistoryDb.get(app).get(r.key());
        long start = resumePosition(r, false);
        subDelay = h != null ? h.subDelay : 0;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        try {
            player.setPlaybackParameters(new PlaybackParameters(speed));
            open(r, start);
            player.play();
        } catch (RuntimeException e) {
            stopAll();
        }
    }

    /** Stops playback completely and leaves background mode. */
    public void stopAll() {
        savePosition();
        if (player != null) player.pause();
        release();
        PlaybackService.stop(app);
    }

    private void create(boolean swMode) {
        if (player != null) {
            for (Player.Listener l : listeners) player.removeListener(l);
            player.release();
            player = null;
        }
        sw = swMode;
        videoDecoder = "";
        audioDecoder = "";
        aheadKey = null;
        if (spare != null && spareSw == swMode) {
            player = spare;
        } else {
            if (spare != null) spare.release();
            player = build(swMode);
        }
        spare = null;
        for (Player.Listener l : listeners) player.addListener(l);
    }

    private ExoPlayer build(boolean swMode) {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(app)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector(swMode ? SOFTWARE_FIRST : MediaCodecSelector.DEFAULT);
        if (Build.VERSION.SDK_INT >= 34) {
            // frames before a seek target are only decoded, not output: exact seeks finish sooner
            rf.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(true);
        }
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        DefaultTrackSelector ts = new DefaultTrackSelector(app);
        String lang = Locale.getDefault().getLanguage();
        ts.setParameters(ts.buildUponParameters()
                .setPreferredTextLanguage(lang)
                .setPreferredAudioLanguage(lang)
                .setSelectUndeterminedTextLanguage(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                // streams (HLS/DASH): allow the original resolution, not only the screen size
                .clearViewportSizeConstraints());
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        // Files on the device start once a quarter second is buffered (the reader keeps far ahead
        // anyway). After running dry (only possible with slow sources, e.g. a content:// URI that a
        // file manager serves from a network share) it waits for the usual second again.
        // Network streams keep Media3's default margins.
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForLocalPlayback(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                        250, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS)
                .build();
        ExoPlayer player = new ExoPlayer.Builder(app, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(app, ef))
                .setTrackSelector(ts)
                .setLoadControl(lc)
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                videoDecoder = decoderName;
            }

            @Override
            public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                audioDecoder = decoderName;
            }
        });
        player.addListener(headless);
        player.addListener(snapper);
        return player;
    }

    public void clearSubs() {
        subTracks.clear();
        subSelected = -1;
        subDelay = 0;
        embeddedSubsOff = false;
    }

    public SubtitleParser.Track currentSub() {
        return subSelected >= 0 && subSelected < subTracks.size() ? subTracks.get(subSelected) : null;
    }

    /** Saves the resume position of the current item. */
    public void savePosition() {
        MediaRef r = current();
        if (r == null || player == null) return;
        // the player may already hold the next file (being switched): never store its position here
        if (!isCurrentItem(r)) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (player.getPlaybackState() == Player.STATE_ENDED && dur > 0) pos = dur;
        if (keepMs >= 0 && r.key().equals(keepKey)) {
            if (pos >= keepMs || keepMs - pos > SNAP_BEFORE_MS + 500) keepMs = -1; // watched on, or moved by hand
            else pos = keepMs; // still before the original point: keep it
        }
        if (dur == C.TIME_UNSET) dur = 0;
        HistoryDb.get(app).save(r, pos, dur);
    }

    public void release() {
        savePosition();
        aheadKey = null;
        snapMs = keepMs = -1;
        snapKey = keepKey = null;
        handler.removeCallbacks(aheadTimeout);
        if (player != null) {
            for (Player.Listener l : listeners) player.removeListener(l);
            player.release();
            player = null;
        }
        playlist.clear();
        index = -1;
        backgroundActive = false;
        abA = abB = -1;
        handler.removeCallbacks(sleepTick);
        sleepAt = -1;
        sleepAtEnd = false;
        clearSubs();
    }
}
