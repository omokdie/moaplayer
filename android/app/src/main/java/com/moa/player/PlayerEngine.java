package com.moa.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
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

    // external subtitles
    public final List<SubtitleParser.Track> subTracks = new ArrayList<>();
    public int subSelected = -1;
    public long subDelay;
    public boolean embeddedSubsOff;

    // pending playlist handed over from the library screens
    private List<MediaRef> pending;
    private int pendingIndex;
    private boolean pendingFromStart;

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
        boolean resume = !"never".equals(Prefs.str(app, Prefs.RESUME, "ask"));
        long start = resume && h != null && !h.finished() && h.position > 5000 ? h.position : 0;
        subDelay = h != null ? h.subDelay : 0;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        try {
            player.setMediaItem(mediaItemFor(r), start);
            player.setPlaybackParameters(new PlaybackParameters(speed));
            player.prepare();
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
        DefaultRenderersFactory rf = new DefaultRenderersFactory(app)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector(swMode ? SOFTWARE_FIRST : MediaCodecSelector.DEFAULT);
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
        player = new ExoPlayer.Builder(app, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(app, ef))
                .setTrackSelector(ts)
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
        for (Player.Listener l : listeners) player.addListener(l);
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
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (player.getPlaybackState() == Player.STATE_ENDED && dur > 0) pos = dur;
        if (dur == C.TIME_UNSET) dur = 0;
        HistoryDb.get(app).save(r, pos, dur);
    }

    public void release() {
        savePosition();
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
