package com.moa.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Rational;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full screen video player with MX Player style controls and gestures. */
public class PlayerActivity extends Activity implements GestureView.Callback {
    public static final String EXTRA_PENDING = "pending";
    public static final String EXTRA_ATTACH = "attach";
    private static final String ACTION_PIP = "com.moa.player.PIP_CONTROL";
    private static final int REQ_SUB_FILE = 51;
    private static final int BRIGHT_MAX = 15;
    private static final int BOOST_MAX = 10;

    private static PlayerActivity live;
    /** Random per-process token so other apps cannot drive the PiP controls. */
    private static final String PIP_TOKEN = Long.toHexString(new java.security.SecureRandom().nextLong());

    static boolean isAlive() {
        return live != null && !live.isFinishing() && !live.isDestroyed();
    }

    // ------------------------------------------------------------------ entry points

    public static void play(Context c, List<VideoItem> list, int index, boolean fromStart) {
        if (list == null || list.isEmpty() || index < 0) return;
        PlayerEngine.get(c).setPending(MediaRef.list(list), index, fromStart);
        c.startActivity(new Intent(c, PlayerActivity.class).putExtra(EXTRA_PENDING, true));
    }

    public static void playUri(Context c, Uri uri) {
        c.startActivity(new Intent(Intent.ACTION_VIEW, uri, c, PlayerActivity.class));
    }

    /** Next / previous from the notification or PiP controls. */
    static void requestSkip(Context c, int dir) {
        if (isAlive()) {
            live.skip(dir);
            return;
        }
        PlayerEngine e = PlayerEngine.get(c);
        e.playHeadless(e.index + dir);
    }

    static void finishIfOpen() {
        if (live != null) {
            live.closing = true;
            live.finish();
        }
    }

    // ------------------------------------------------------------------ fields

    private PlayerEngine engine;
    private AudioManager audio;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View controls, topBar, bottomBar, hud, loading;
    private VideoFrameLayout videoFrame;
    private SurfaceView surface;
    private SubtitleView subView;
    private GestureView gestures;
    private ImageButton btnPlay, btnPrev, btnNext, btnLock, btnUnlock, btnAspect, btnRotate, btnBack, btnSub, btnAudio, btnMore, btnPip;
    private TextView tvTitle, tvPos, tvDur, chipDecoder, chipAb, btnSpeed, speedBadge, hudText, hudSub, seekLeft, seekRight;
    private ImageView hudIcon;
    private ProgressBar hudBar;
    private SeekBar seekBar;

    private boolean controlsVisible = true;
    private boolean locked, userSeeking, showRemaining, closing, inPipSession, triedSwFallback, bgRequested;
    private boolean resumeAfterPicker;
    private boolean stopped, resumed;
    private String orientPref;
    private long seekStartPos, seekTarget, lastLiveSeek;
    private boolean seekWasPlaying, seekActive;
    private int volStart, brightStart;
    private int brightLevel = -1;
    private int boost;
    private LoudnessEnhancer loudness;
    private int loudnessSession;
    private float zoomStart, panX, panY;
    private boolean longPressActive;
    private int chainSeconds, chainDir;
    private long chainBase;
    private boolean fastSeek = true;
    private long lastSave;
    private String loadedSubsFor;
    private String warnedTracksFor;
    private final List<File> otherSubFiles = new ArrayList<>();
    private int orientMode;
    private Dialog resumeDialog;
    private BroadcastReceiver pipReceiver;

    // cached preferences
    private boolean gSeek, gBright, gVolume, gZoom, gLong, volBoost;
    private int dtMode, seekSec;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (live != null && live != this) {
            live.closing = true;
            live.finish();
        }
        live = this;
        setContentView(R.layout.activity_player);
        engine = PlayerEngine.get(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        bindViews();
        loadPrefs();
        applySubtitleStyle();
        initBrightness();
        orientPref = Prefs.str(this, Prefs.ORIENTATION, "video");
        orientMode = orientIndex(orientPref);
        engine.addListener(listener);
        registerPipReceiver();
        hideSystemUi();

        Intent it = getIntent();
        boolean recreated = b != null; // e.g. after a configuration change we could not handle
        if ((recreated || it.getBooleanExtra(EXTRA_ATTACH, false)) && engine.player() != null && engine.current() != null) {
            attach();
        } else {
            openFromIntent(it);
        }
        if (Prefs.bool(this, Prefs.BG_PLAY, false)) requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 61);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_ATTACH, false) && engine.player() != null && engine.current() != null) {
            attach();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_PENDING, false) || intent.getData() != null) {
            engine.savePosition();
            openFromIntent(intent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        ui.removeCallbacks(progressTick);
        ui.post(progressTick);
        ExoPlayer p = engine.player();
        if (p != null) {
            p.setVideoSurfaceView(surface);
            setVideoTrackEnabled(true);
        }
        startSubTick();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        loadPrefs();
        applySubtitleStyle();
        String op = Prefs.str(this, Prefs.ORIENTATION, "video");
        if (!op.equals(orientPref)) {
            orientPref = op;
            orientMode = orientIndex(op);
            ExoPlayer pp = engine.player();
            VideoSize vs = pp != null ? pp.getVideoSize() : VideoSize.UNKNOWN;
            applyOrientation(Math.round(vs.width * vs.pixelWidthHeightRatio), vs.height);
        }
        hideSystemUi();
        bgRequested = false;
        if (PlaybackService.isRunning() && !isInPip()) {
            PlaybackService.stop(this);
            engine.backgroundActive = false;
        }
        if (inPipSession && !isInPip()) inPipSession = false;
        updatePlayButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (isChangingConfigurations()) return;
        if (!isFinishing() && !closing && !isInPip() && shouldBackground()) {
            bgRequested = true;
            PlaybackService.start(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        ui.removeCallbacks(subTick);
        if (isChangingConfigurations()) return;
        ExoPlayer p = engine.player();
        if (inPipSession && !isInPip()) {
            // the picture-in-picture window was closed
            inPipSession = false;
            if (p != null) p.pause();
            engine.savePosition();
            closing = true;
            finishAndRemoveTask();
            return;
        }
        if (p == null) return;
        if (isInPip()) {
            // screen turned off while in picture-in-picture
            if (!shouldBackground()) {
                p.pause();
            } else if (!bgRequested) {
                bgRequested = true;
                PlaybackService.start(this);
            }
            return;
        }
        if (bgRequested || engine.backgroundActive) {
            setVideoTrackEnabled(false);
            engine.savePosition();
            return;
        }
        p.pause();
        engine.savePosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (live == this) live = null;
        ui.removeCallbacksAndMessages(null);
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { }
        }
        engine.removeListener(listener);
        releaseLoudness();
        if (resumeDialog != null) resumeDialog.dismiss();
        boolean keep = isChangingConfigurations() || (!closing && (engine.backgroundActive || bgRequested));
        if (keep) {
            ExoPlayer p = engine.player();
            if (p != null) p.clearVideoSurfaceView(surface);
        } else if (live == null) {
            engine.release();
            PlaybackService.stop(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (locked) {
            showUnlockButton();
            Util.toast(this, "화면이 잠겨 있습니다. 자물쇠를 눌러 잠금을 해제하세요");
            return;
        }
        closing = true;
        engine.savePosition();
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        ExoPlayer p = engine.player();
        if (Prefs.bool(this, Prefs.AUTO_PIP, false) && p != null && p.isPlaying() && pipSupported() && !closing) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip) {
            inPipSession = true;
            hideControlsNow();
            hud.setVisibility(View.GONE);
            seekLeft.setVisibility(View.GONE);
            seekRight.setVisibility(View.GONE);
            btnUnlock.setVisibility(View.GONE);
            subView.setCompact(true);
        } else {
            subView.setCompact(false);
            if (stopped && inPipSession && !isChangingConfigurations()) {
                // the picture-in-picture window was dismissed
                inPipSession = false;
                ExoPlayer p = engine.player();
                if (p != null) p.pause();
                engine.savePosition();
                closing = true;
                finishAndRemoveTask();
            }
        }
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        View root = findViewById(R.id.root);
        videoFrame = findViewById(R.id.video_frame);
        surface = findViewById(R.id.surface);
        subView = findViewById(R.id.subtitles);
        gestures = findViewById(R.id.gestures);
        controls = findViewById(R.id.controls);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        hud = findViewById(R.id.hud);
        hudIcon = findViewById(R.id.hud_icon);
        hudText = findViewById(R.id.hud_text);
        hudSub = findViewById(R.id.hud_sub);
        hudBar = findViewById(R.id.hud_bar);
        loading = findViewById(R.id.loading);
        speedBadge = findViewById(R.id.speed_badge);
        seekLeft = findViewById(R.id.seek_left);
        seekRight = findViewById(R.id.seek_right);
        btnBack = findViewById(R.id.btn_back);
        tvTitle = findViewById(R.id.title);
        chipAb = findViewById(R.id.chip_ab);
        chipDecoder = findViewById(R.id.chip_decoder);
        btnPip = findViewById(R.id.btn_pip);
        btnAudio = findViewById(R.id.btn_audio);
        btnSub = findViewById(R.id.btn_sub);
        btnMore = findViewById(R.id.btn_more);
        tvPos = findViewById(R.id.pos);
        tvDur = findViewById(R.id.dur);
        seekBar = findViewById(R.id.seek);
        btnLock = findViewById(R.id.btn_lock);
        btnSpeed = findViewById(R.id.btn_speed);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnAspect = findViewById(R.id.btn_aspect);
        btnRotate = findViewById(R.id.btn_rotate);
        btnUnlock = findViewById(R.id.btn_unlock);

        gestures.setCallback(this);
        videoFrame.setOnVideoRectListener((l, t, r, bt) -> subView.setVideoRect(l, t, r, bt));

        btnBack.setOnClickListener(v -> {
            closing = true;
            engine.savePosition();
            finish();
        });
        btnPlay.setOnClickListener(v -> { togglePlay(); scheduleHide(); });
        btnPrev.setOnClickListener(v -> skip(-1));
        btnNext.setOnClickListener(v -> skip(1));
        btnLock.setOnClickListener(v -> lock());
        btnUnlock.setOnClickListener(v -> unlock());
        btnAspect.setOnClickListener(v -> cycleAspect());
        btnRotate.setOnClickListener(v -> cycleOrientation());
        btnSub.setOnClickListener(v -> showSubtitleDialog());
        btnAudio.setOnClickListener(v -> showAudioDialog());
        btnMore.setOnClickListener(v -> showMoreMenu());
        btnPip.setOnClickListener(v -> enterPip());
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        chipDecoder.setOnClickListener(v -> switchDecoder(!engine.isSw(), true));
        chipAb.setOnClickListener(v -> {
            engine.abA = engine.abB = -1;
            updateAbChip();
            Util.toast(this, "A-B 반복을 해제했습니다");
        });
        tvDur.setOnClickListener(v -> {
            showRemaining = !showRemaining;
            updateProgressNow();
        });
        if (!pipSupported()) btnPip.setVisibility(View.GONE);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                ExoPlayer p = engine.player();
                if (p == null) return;
                long dur = p.getDuration();
                if (dur <= 0) return;
                seekTarget = (long) progress * dur / 10000L;
                tvPos.setText(Util.time(seekTarget));
                showHud(0, Util.time(seekTarget), "/ " + Util.time(dur), -1, 0);
                liveSeek(p, seekTarget);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                ui.removeCallbacks(hideControlsRunnable);
                ExoPlayer p = engine.player();
                if (p != null) {
                    p.setSeekParameters(SeekParameters.CLOSEST_SYNC);
                    seekTarget = p.getCurrentPosition();
                }
            }

            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                ExoPlayer p = engine.player();
                if (p != null) {
                    p.setSeekParameters(SeekParameters.EXACT);
                    p.seekTo(seekTarget);
                }
                hideHudSoon();
                scheduleHide();
            }
        });

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l = 0, t = 0, r = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = insets.getDisplayCutout();
                if (dc != null) {
                    l = dc.getSafeInsetLeft();
                    t = dc.getSafeInsetTop();
                    r = dc.getSafeInsetRight();
                }
            }
            int p4 = Util.dp(this, 4), p6 = Util.dp(this, 6);
            topBar.setPadding(p4 + l, p6 + t, p4 + r, topBar.getPaddingBottom());
            bottomBar.setPadding(l, bottomBar.getPaddingTop(), r, bottomBar.getPaddingBottom());
            return insets;
        });
    }

    private void loadPrefs() {
        gSeek = Prefs.bool(this, Prefs.G_SEEK, true);
        gBright = Prefs.bool(this, Prefs.G_BRIGHT, true);
        gVolume = Prefs.bool(this, Prefs.G_VOLUME, true);
        gZoom = Prefs.bool(this, Prefs.G_ZOOM, true);
        gLong = Prefs.bool(this, Prefs.LONG_SPEED, true);
        volBoost = Prefs.bool(this, Prefs.VOL_BOOST, true);
        String dt = Prefs.str(this, Prefs.DOUBLE_TAP, "seek");
        dtMode = "play_pause".equals(dt) ? 1 : ("off".equals(dt) ? 2 : 0);
        seekSec = Math.max(1, Prefs.intOf(this, Prefs.SEEK_SEC, 10));
        fastSeek = Prefs.bool(this, Prefs.FAST_SEEK, true);
    }

    private void applySubtitleStyle() {
        float scale = Prefs.intOf(this, Prefs.SUB_SIZE, 100) / 100f;
        int color;
        try {
            color = android.graphics.Color.parseColor(Prefs.str(this, Prefs.SUB_COLOR, "#FFFFFFFF"));
        } catch (Exception e) {
            color = 0xFFFFFFFF;
        }
        subView.setStyle(scale, color, Prefs.bool(this, Prefs.SUB_OUTLINE, true),
                Prefs.bool(this, Prefs.SUB_BG, false), Prefs.intOf(this, Prefs.SUB_BOTTOM, 6) / 100f);
    }

    private void registerPipReceiver() {
        pipReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!PIP_TOKEN.equals(i.getStringExtra("token"))) return;
                int cmd = i.getIntExtra("cmd", 0);
                if (cmd == 1) togglePlay();
                else if (cmd == 2) skip(1);
                else if (cmd == 3) skip(-1);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_PIP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pipReceiver, f);
    }

    // ------------------------------------------------------------------ opening media

    private void openFromIntent(Intent it) {
        Boolean fromStart = it.getBooleanExtra(EXTRA_PENDING, false) ? engine.takePending() : null;
        if (fromStart == null) {
            Uri data = it.getData();
            if (data == null) {
                if (engine.player() != null && engine.current() != null) {
                    attach();
                } else {
                    closing = true;
                    finish();
                }
                return;
            }
            MediaRef ref = MediaRef.fromUri(this, data, it.getType());
            List<MediaRef> list = new ArrayList<>();
            list.add(ref);
            engine.setPlaylist(list, 0);
            fromStart = false;
            if (ref.path != null) loadSiblingsAsync(ref);
        }
        if (PlaybackService.isRunning()) PlaybackService.stop(this);
        startCurrent(fromStart);
    }

    private void loadSiblingsAsync(final MediaRef ref) {
        new Thread(() -> {
            final List<VideoItem> sib = MediaRepo.siblings(getApplicationContext(), ref.path);
            ui.post(() -> {
                MediaRef cur = engine.current();
                if (cur == null || !ref.key().equals(cur.key()) || engine.playlist.size() != 1) return;
                for (int i = 0; i < sib.size(); i++) {
                    if (ref.path.equals(sib.get(i).path)) {
                        List<MediaRef> refs = MediaRef.list(sib);
                        refs.set(i, cur);
                        engine.setPlaylist(refs, i);
                        updateNavButtons();
                        return;
                    }
                }
            });
        }, "siblings").start();
    }

    private void startCurrent(boolean fromStart) {
        MediaRef r = engine.current();
        if (r == null) {
            closing = true;
            finish();
            return;
        }
        if (resumeDialog != null) {
            resumeDialog.dismiss();
            resumeDialog = null;
        }
        boolean sw = "sw".equals(Prefs.str(this, Prefs.DECODER, "hw"));
        triedSwFallback = sw;
        ExoPlayer p = engine.ensure(sw);
        p.setVideoSurfaceView(surface);
        releaseLoudness();
        if (boost > 0) setBoost(boost);

        engine.abA = engine.abB = -1;
        updateAbChip();
        resetZoom();
        videoFrame.setVideoSize(r.widthHint, r.heightHint, 1f);
        engine.clearSubs();
        subView.clear();
        otherSubFiles.clear();
        loadedSubsFor = null;
        warnedTracksFor = null;

        HistoryDb.Entry h = r.stream ? null : HistoryDb.get(this).get(r.key());
        engine.subDelay = h != null ? h.subDelay : 0;
        long resume = 0;
        if (!fromStart && h != null && !h.finished() && h.position > 5000) resume = h.position;
        String mode = Prefs.str(this, Prefs.RESUME, "ask");
        if ("never".equals(mode)) resume = 0;
        // A prompt nobody can see (background, PiP) would stall playback: resume directly then.
        boolean visible = resumed && !isInPip();
        boolean ask = resume > 0 && "ask".equals(mode) && visible;

        if (PlayerEngine.unsupported(r.uri, r.mime)) {
            showFatal("이 스트림 형식(Smooth Streaming)은 지원하지 않습니다.");
            return;
        }
        TrackSelectionParameters tsp = p.getTrackSelectionParameters().buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, stopped && !isInPip())
                .build();
        p.setTrackSelectionParameters(tsp);
        try {
            p.setMediaItem(PlayerEngine.mediaItemFor(r), ask ? 0 : resume);
            p.setPlaybackParameters(new PlaybackParameters(engine.speed));
            p.setRepeatMode(Player.REPEAT_MODE_OFF);
            p.setWakeMode(r.stream ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL);
            p.prepare();
        } catch (RuntimeException e) {
            showFatal("이 주소나 파일을 열 수 없습니다.\n\n(" + e.getClass().getSimpleName() + ")");
            return;
        }
        p.setPlayWhenReady(!ask);
        if (ask) showResumeDialog(resume);
        else if (resume > 0) Util.toast(this, Util.time(resume) + "부터 이어서 재생합니다");

        tvTitle.setText(r.title);
        updateDecoderChip();
        updateNavButtons();
        updateSpeedChip();
        applyOrientation(r.widthHint, r.heightHint);
        showControls();
        loadSubtitlesAsync(r);
    }

    private void showFatal(String msg) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("재생할 수 없음")
                .setMessage(msg)
                .setNegativeButton("닫기", (d, w) -> { closing = true; finish(); })
                .setCancelable(false);
        if (engine.hasNext()) b.setPositiveButton("다음 파일", (d, w) -> skip(1));
        showImmersive(b.create());
    }

    private void attach() {
        ExoPlayer p = engine.player();
        MediaRef r = engine.current();
        if (p == null || r == null) {
            closing = true;
            finish();
            return;
        }
        if (PlaybackService.isRunning()) PlaybackService.stop(this);
        engine.backgroundActive = false;
        p.setVideoSurfaceView(surface);
        setVideoTrackEnabled(true);
        VideoSize vs = p.getVideoSize();
        if (vs.width > 0) videoFrame.setVideoSize(vs.width, vs.height, vs.pixelWidthHeightRatio);
        else videoFrame.setVideoSize(r.widthHint, r.heightHint, 1f);
        tvTitle.setText(r.title);
        updateDecoderChip();
        updateNavButtons();
        updateSpeedChip();
        updateAbChip();
        updatePlayButton();
        applyOrientation(vs.width > 0 ? vs.width : r.widthHint, vs.height > 0 ? vs.height : r.heightHint);
        showControls();
        if (!r.key().equals(loadedSubsFor) && engine.subTracks.isEmpty()) loadSubtitlesAsync(r);
        startSubTick();
    }

    private void showResumeDialog(final long pos) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("이어서 재생")
                .setMessage("마지막으로 본 위치(" + Util.time(pos) + ")부터 이어서 재생할까요?")
                .setPositiveButton("이어서 재생", (x, w) -> {
                    ExoPlayer p = engine.player();
                    if (p != null) { p.seekTo(pos); p.play(); }
                })
                .setNegativeButton("처음부터", (x, w) -> {
                    ExoPlayer p = engine.player();
                    if (p != null) { p.seekTo(0); p.play(); }
                })
                .setOnCancelListener(x -> {
                    ExoPlayer p = engine.player();
                    if (p != null) { p.seekTo(pos); p.play(); }
                })
                .create();
        resumeDialog = d;
        showImmersive(d);
    }

    // ------------------------------------------------------------------ player events

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            // Re-creating the player from inside its own callback is unsafe, so defer.
            if (state == Player.STATE_ENDED) ui.post(PlayerActivity.this::onEnded);
            updatePlayButton();
            updateProgressNow();
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            updatePlayButton();
            if (playing) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                scheduleHide();
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ui.removeCallbacks(hideControlsRunnable);
            }
            updatePipParams();
        }

        @Override
        public void onVideoSizeChanged(VideoSize vs) {
            if (vs.width <= 0 || vs.height <= 0) return;
            videoFrame.setVideoSize(vs.width, vs.height, vs.pixelWidthHeightRatio);
            if (orientMode == 0) applyOrientation(Math.round(vs.width * vs.pixelWidthHeightRatio), vs.height);
            updatePipParams();
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            checkTracks(tracks);
        }

        @Override
        public void onCues(CueGroup cueGroup) {
            if (engine.currentSub() == null) subView.setCues(cueGroup.cues);
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            ui.post(() -> handleError(e));
        }

        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            if (boost > 0) {
                releaseLoudness();
                setBoost(boost);
            }
        }
    };

    private void onEnded() {
        ExoPlayer p = engine.player();
        if (p == null || p.getPlaybackState() != Player.STATE_ENDED || isDestroyed()) return;
        engine.savePosition();
        if (engine.repeatMode == PlayerEngine.REPEAT_ONE) {
            p.seekTo(0);
            p.play();
            return;
        }
        if (engine.sleepAtEnd) {
            engine.sleepAtEnd = false;
            Util.toast(this, "수면 타이머: 재생을 마쳤습니다");
            closing = true;
            finish();
            return;
        }
        boolean auto = Prefs.bool(this, Prefs.AUTOPLAY, true) || engine.repeatMode == PlayerEngine.REPEAT_ALL;
        if (auto && engine.hasNext()) {
            engine.index++;
            startCurrent(false);
            return;
        }
        if (engine.repeatMode == PlayerEngine.REPEAT_ALL && !engine.playlist.isEmpty()) {
            engine.index = 0;
            startCurrent(false);
            return;
        }
        closing = true;
        finish();
    }

    private void handleError(PlaybackException e) {
        if (isDestroyed() || isFinishing()) return;
        int code = e.errorCode;
        boolean decoder = code >= 4000 && code < 5000;
        if (decoder && !engine.isSw() && !triedSwFallback) {
            triedSwFallback = true;
            Util.toast(this, "HW 디코더로 재생할 수 없어 SW 디코더로 전환합니다");
            switchDecoder(true, false);
            return;
        }
        String msg;
        if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) msg = "파일을 찾을 수 없습니다.";
        else if (code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) msg = "파일에 접근할 권한이 없습니다.";
        else if (code >= 2000 && code < 3000) msg = "파일을 읽거나 네트워크에 연결할 수 없습니다.";
        else if (code >= 3000 && code < 4000) msg = "지원하지 않거나 손상된 파일 형식입니다.";
        else if (decoder) msg = "이 기기에서 해당 코덱을 재생할 수 없습니다.";
        else msg = "재생할 수 없습니다.";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("재생 오류")
                .setMessage(msg + "\n\n(" + e.getErrorCodeName() + ")")
                .setNegativeButton("닫기", (d, w) -> { closing = true; finish(); })
                .setCancelable(false);
        if (!decoder || engine.isSw()) {
            b.setNeutralButton(engine.isSw() ? "HW로 재시도" : "SW로 재시도", (d, w) -> switchDecoder(!engine.isSw(), true));
        }
        if (engine.hasNext()) b.setPositiveButton("다음 파일", (d, w) -> skip(1));
        showImmersive(b.create());
    }

    private void checkTracks(Tracks tracks) {
        MediaRef r = engine.current();
        if (r == null || r.key().equals(warnedTracksFor)) return;
        boolean hasAudio = false, audioOk = false, hasVideo = false, videoOk = false;
        String audioMime = null;
        for (Tracks.Group g : tracks.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                    if (g.isTrackSupported(i)) audioOk = true;
                    else if (audioMime == null) audioMime = g.getTrackFormat(i).sampleMimeType;
                } else if (g.getType() == C.TRACK_TYPE_VIDEO) {
                    hasVideo = true;
                    if (g.isTrackSupported(i)) videoOk = true;
                }
            }
        }
        if (tracks.getGroups().isEmpty()) return;
        warnedTracksFor = r.key();
        if (hasAudio && !audioOk) {
            Util.toast(this, "이 기기는 " + codecName(audioMime) + " 오디오를 지원하지 않습니다. 소리 없이 재생됩니다.");
        } else if (hasVideo && !videoOk && !engine.isSw()) {
            Util.toast(this, "영상 코덱을 지원하지 않을 수 있습니다. 문제가 있으면 SW 디코더로 바꿔 보세요.");
        }
    }

    // ------------------------------------------------------------------ periodic work

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            ExoPlayer p = engine.player();
            if (p != null) {
                long pos = p.getCurrentPosition();
                if (!userSeeking && controlsVisible) updateProgressUi(p);
                if (engine.abA >= 0 && engine.abB > engine.abA && pos >= engine.abB) p.seekTo(engine.abA);
                long now = SystemClock.elapsedRealtime();
                if (p.isPlaying() && now - lastSave > 5000) {
                    lastSave = now;
                    engine.savePosition();
                }
            }
            ui.postDelayed(this, controlsVisible ? 200 : 500);
        }
    };

    private final Runnable subTick = new Runnable() {
        @Override public void run() {
            SubtitleParser.Track t = engine.currentSub();
            ExoPlayer p = engine.player();
            if (t == null || p == null) return;
            long pos = p.getCurrentPosition() - engine.subDelay;
            subView.setText(t.textAt(pos), t.lastTop);
            ui.postDelayed(this, 40);
        }
    };

    private void startSubTick() {
        ui.removeCallbacks(subTick);
        if (engine.currentSub() != null) ui.post(subTick);
    }

    private void updateProgressNow() {
        ExoPlayer p = engine.player();
        if (p != null) updateProgressUi(p);
    }

    private void updateProgressUi(ExoPlayer p) {
        long pos = p.getCurrentPosition();
        long dur = p.getDuration();
        if (dur > 0 && dur != C.TIME_UNSET) {
            seekBar.setEnabled(true);
            seekBar.setProgress((int) Util.clamp(pos * 10000L / dur, 0, 10000));
            seekBar.setSecondaryProgress((int) Util.clamp(p.getBufferedPosition() * 10000L / dur, 0, 10000));
            tvDur.setText(showRemaining ? "-" + Util.time(dur - pos) : Util.time(dur));
        } else {
            seekBar.setEnabled(false);
            seekBar.setProgress(0);
            tvDur.setText("--:--");
        }
        tvPos.setText(Util.time(pos));
    }

    // ------------------------------------------------------------------ controls

    private final Runnable hideControlsRunnable = this::hideControls;
    private final Runnable hideHudRunnable = () -> hud.setVisibility(View.GONE);
    private final Runnable hideUnlockRunnable = () -> btnUnlock.setVisibility(View.GONE);
    private final Runnable hideSeekLeft = () -> { seekLeft.setVisibility(View.GONE); chainSeconds = 0; };
    private final Runnable hideSeekRight = () -> { seekRight.setVisibility(View.GONE); chainSeconds = 0; };

    private void showControls() {
        if (locked || isInPip()) return;
        controls.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(controlsVisible ? controls.getAlpha() : 0f);
        controls.animate().alpha(1f).setDuration(150).start();
        controlsVisible = true;
        updateProgressNow();
        scheduleHide();
    }

    private void hideControls() {
        if (userSeeking) return;
        controlsVisible = false;
        controls.animate().cancel();
        controls.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!controlsVisible) controls.setVisibility(View.GONE);
        }).start();
        hideSystemUi();
    }

    private void hideControlsNow() {
        controlsVisible = false;
        controls.animate().cancel();
        controls.setVisibility(View.GONE);
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideControlsRunnable);
        ExoPlayer p = engine.player();
        if (p != null && p.getPlayWhenReady()) ui.postDelayed(hideControlsRunnable, 3500);
    }

    private void updatePlayButton() {
        ExoPlayer p = engine.player();
        boolean playing = p != null && p.getPlayWhenReady() && p.getPlaybackState() != Player.STATE_ENDED;
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateNavButtons() {
        btnPrev.setAlpha(engine.hasPrev() ? 1f : 0.35f);
        btnNext.setAlpha(engine.hasNext() ? 1f : 0.35f);
    }

    private void updateDecoderChip() {
        chipDecoder.setText(engine.isSw() ? "SW" : "HW");
    }

    private void updateSpeedChip() {
        btnSpeed.setText(Util.speed(engine.speed));
        btnSpeed.setBackgroundResource(Math.abs(engine.speed - 1f) > 0.001f ? R.drawable.bg_chip_on : R.drawable.bg_chip);
    }

    private void updateAbChip() {
        if (engine.abA < 0) {
            chipAb.setVisibility(View.GONE);
        } else {
            chipAb.setVisibility(View.VISIBLE);
            chipAb.setText(engine.abB < 0 ? "A-" : "A-B");
        }
    }

    private void togglePlay() {
        ExoPlayer p = engine.player();
        if (p == null) return;
        if (p.getPlaybackState() == Player.STATE_ENDED) {
            p.seekTo(0);
            p.play();
        } else if (p.getPlayWhenReady()) {
            p.pause();
            engine.savePosition();
        } else {
            if (p.getPlaybackState() == Player.STATE_IDLE) p.prepare();
            p.play();
        }
    }

    void skip(int dir) {
        int ni = engine.index + dir;
        if (ni < 0 || ni >= engine.playlist.size()) {
            Util.toast(this, dir > 0 ? "마지막 동영상입니다" : "첫 번째 동영상입니다");
            return;
        }
        engine.savePosition();
        engine.index = ni;
        startCurrent(false);
    }

    private void seekBy(long deltaMs) {
        ExoPlayer p = engine.player();
        if (p == null) return;
        skipTo(p.getCurrentPosition() + deltaMs, deltaMs);
    }

    /**
     * A skip by a fixed step (double tap, keys). With "빠른 탐색" the player may land on a key frame
     * within half a step (at most 5 s) of the target: that jump is immediate, while an exact position
     * has to decode everything from the previous key frame first (slow for long key-frame intervals,
     * e.g. downloaded MKV files). The window never reaches back past the starting point.
     */
    private void skipTo(long target, long step) {
        ExoPlayer p = engine.player();
        if (p == null) return;
        long dur = p.getDuration();
        if (dur > 0 && dur != C.TIME_UNSET) target = Math.min(target, dur - 500);
        target = Math.max(0, target);
        if (fastSeek) {
            long tolUs = Math.min(5000L, Math.abs(step) / 2) * 1000L;
            p.setSeekParameters(new SeekParameters(tolUs, tolUs));
            p.seekTo(target);
            p.setSeekParameters(SeekParameters.EXACT);   // later seeks (A-B, resume, seek bar) stay exact
        } else {
            p.setSeekParameters(SeekParameters.EXACT);
            p.seekTo(target);
        }
    }

    private void liveSeek(ExoPlayer p, long target) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLiveSeek > 120) {
            lastLiveSeek = now;
            p.seekTo(target);
        }
    }

    private void lock() {
        locked = true;
        hideControlsNow();
        showUnlockButton();
        Util.toast(this, "화면을 잠갔습니다");
    }

    private void unlock() {
        locked = false;
        btnUnlock.setVisibility(View.GONE);
        showControls();
    }

    private void showUnlockButton() {
        btnUnlock.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideUnlockRunnable);
        ui.postDelayed(hideUnlockRunnable, 2500);
    }

    private void resetZoom() {
        zoomStart = 1f;
        panX = panY = 0;
        videoFrame.setZoomPan(1f, 0, 0);
    }

    private void cycleAspect() {
        int m = (videoFrame.getMode() + 1) % VideoFrameLayout.MODE_NAMES.length;
        videoFrame.setMode(m);
        resetZoom();
        showHud(R.drawable.ic_aspect, VideoFrameLayout.MODE_NAMES[m], null, -1, 0);
        hideHudSoon();
        scheduleHide();
    }

    private static int orientIndex(String s) {
        if ("landscape".equals(s)) return 1;
        if ("portrait".equals(s)) return 2;
        if ("sensor".equals(s)) return 3;
        return 0;
    }

    private void applyOrientation(int w, int h) {
        int req;
        switch (orientMode) {
            case 1: req = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; break;
            case 2: req = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; break;
            case 3: req = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; break;
            default:
                if (w <= 0 || h <= 0) return;
                req = w >= h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        }
        if (isInPip()) return;
        if (getRequestedOrientation() != req) setRequestedOrientation(req);
    }

    private void cycleOrientation() {
        int req = getRequestedOrientation();
        String label;
        if (req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || req == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            orientMode = 2;
            label = "세로 화면";
        } else if (req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || req == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            orientMode = 3;
            label = "자동 회전";
        } else {
            orientMode = 1;
            label = "가로 화면";
        }
        applyOrientation(0, 0);
        showHud(R.drawable.ic_rotate, label, null, -1, 0);
        hideHudSoon();
        scheduleHide();
    }

    // ------------------------------------------------------------------ HUD

    private void showHud(int icon, String text, String sub, int progress, int secondary) {
        if (isInPip()) return;
        ui.removeCallbacks(hideHudRunnable);
        hud.setVisibility(View.VISIBLE);
        if (icon != 0) {
            hudIcon.setVisibility(View.VISIBLE);
            hudIcon.setImageResource(icon);
        } else {
            hudIcon.setVisibility(View.GONE);
        }
        hudText.setText(text);
        if (sub != null) {
            hudSub.setVisibility(View.VISIBLE);
            hudSub.setText(sub);
        } else {
            hudSub.setVisibility(View.GONE);
        }
        if (progress >= 0) {
            hudBar.setVisibility(View.VISIBLE);
            hudBar.setProgress(progress);
            hudBar.setSecondaryProgress(secondary);
        } else {
            hudBar.setVisibility(View.GONE);
        }
    }

    private void hideHudSoon() {
        ui.removeCallbacks(hideHudRunnable);
        ui.postDelayed(hideHudRunnable, 700);
    }

    // ------------------------------------------------------------------ gestures (GestureView.Callback)

    @Override public boolean isLocked() { return locked || isInPip(); }

    @Override public boolean gestureEnabled(int type) {
        switch (type) {
            case GestureView.G_SEEK: return gSeek;
            case GestureView.G_BRIGHT: return gBright;
            case GestureView.G_VOLUME: return gVolume;
            case GestureView.G_ZOOM: return gZoom;
            case GestureView.G_LONG: return gLong;
            default: return false;
        }
    }

    @Override public int doubleTapMode() { return dtMode; }

    @Override public void onSingleTap() {
        if (isInPip()) return;
        if (locked) {
            showUnlockButton();
            return;
        }
        if (controlsVisible) hideControls(); else showControls();
    }

    @Override public void onDoubleTap(int zone) {
        if (locked) return;
        ExoPlayer p = engine.player();
        if (p == null) return;
        if (zone == 0) {
            togglePlay();
            showHud(p.getPlayWhenReady() ? R.drawable.ic_play : R.drawable.ic_pause, p.getPlayWhenReady() ? "재생" : "일시정지", null, -1, 0);
            hideHudSoon();
            return;
        }
        if (chainDir != zone) chainSeconds = 0;
        if (chainSeconds == 0) chainBase = p.getCurrentPosition();
        chainDir = zone;
        chainSeconds += seekSec;
        // every tap of a chain counts from where the chain started, so landing on a key frame never drifts
        skipTo(chainBase + zone * chainSeconds * 1000L, zone * seekSec * 1000L);
        int w = (int) (findViewById(R.id.root).getWidth() * 0.36f);
        TextView tv = zone < 0 ? seekLeft : seekRight;
        TextView other = zone < 0 ? seekRight : seekLeft;
        other.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            tv.setLayoutParams(lp);
        }
        tv.setText(chainSeconds + "초");
        tv.setAlpha(1f);
        tv.setVisibility(View.VISIBLE);
        Runnable hide = zone < 0 ? hideSeekLeft : hideSeekRight;
        ui.removeCallbacks(hideSeekLeft);
        ui.removeCallbacks(hideSeekRight);
        ui.postDelayed(hide, 750);
        if (controlsVisible) updateProgressNow();
    }

    @Override public void onLongPressStart() {
        ExoPlayer p = engine.player();
        if (p == null || !p.isPlaying()) return;
        longPressActive = true;
        float s = Math.min(4f, Math.max(2f, engine.speed * 2f));
        p.setPlaybackParameters(new PlaybackParameters(s));
        speedBadge.setText(Util.speed(s) + " 빨리 감기 ▶▶");
        speedBadge.setVisibility(View.VISIBLE);
    }

    @Override public void onLongPressEnd() {
        if (!longPressActive) return;
        longPressActive = false;
        ExoPlayer p = engine.player();
        if (p != null) p.setPlaybackParameters(new PlaybackParameters(engine.speed));
        speedBadge.setVisibility(View.GONE);
    }

    @Override public void onSeekStart() {
        ExoPlayer p = engine.player();
        if (p == null || p.getDuration() <= 0 || !p.isCurrentMediaItemSeekable()) {
            seekActive = false;
            return;
        }
        seekActive = true;
        seekStartPos = p.getCurrentPosition();
        seekTarget = seekStartPos;
        seekWasPlaying = p.getPlayWhenReady();
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        p.pause();
    }

    @Override public void onSeekMove(float fx) {
        ExoPlayer p = engine.player();
        if (!seekActive || p == null) return;
        long dur = p.getDuration();
        long range = Math.min(90000L, Math.max(dur, 10000L));
        seekTarget = Util.clamp(seekStartPos + (long) (fx * range), 0, Math.max(0, dur - 300));
        long delta = seekTarget - seekStartPos;
        showHud(delta >= 0 ? R.drawable.ic_fast_forward : R.drawable.ic_fast_rewind,
                Util.signedTime(delta), Util.time(seekTarget) + " / " + Util.time(dur),
                (int) (seekTarget * 100 / Math.max(1, dur)), 0);
        if (controlsVisible) {
            tvPos.setText(Util.time(seekTarget));
            seekBar.setProgress((int) (seekTarget * 10000L / Math.max(1, dur)));
        }
        liveSeek(p, seekTarget);
    }

    @Override public void onSeekEnd() {
        ExoPlayer p = engine.player();
        if (!seekActive || p == null) return;
        seekActive = false;
        p.setSeekParameters(SeekParameters.EXACT);
        p.seekTo(seekTarget);
        if (seekWasPlaying) p.play();
        hideHudSoon();
    }

    @Override public void onBrightnessStart() {
        brightStart = currentBrightLevel();
    }

    @Override public void onBrightnessMove(float fy) {
        applyBrightness(brightStart + Math.round(fy * BRIGHT_MAX * 1.2f), true);
    }

    @Override public void onVolumeStart() {
        volStart = currentVolumeLevel();
    }

    @Override public void onVolumeMove(float fy) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        setVolumeLevel(volStart + Math.round(fy * max * 1.2f));
    }

    @Override public void onVerticalEnd() {
        hideHudSoon();
    }

    @Override public void onScaleStart() {
        zoomStart = videoFrame.getZoom();
        panX = videoFrame.getPanX();
        panY = videoFrame.getPanY();
    }

    @Override public void onScale(float factor, float dx, float dy) {
        float z = Util.clamp(videoFrame.getZoom() * factor, 0.5f, 3f);
        panX += dx;
        panY += dy;
        videoFrame.setZoomPan(z, panX, panY);
        panX = videoFrame.getPanX();
        panY = videoFrame.getPanY();
        showHud(R.drawable.ic_zoom, "확대 " + Math.round(z * 100) + "%", null, -1, 0);
    }

    @Override public void onScaleEnd() {
        float z = videoFrame.getZoom();
        if (Math.abs(z - 1f) < 0.06f) {
            videoFrame.setZoomPan(1f, 0, 0);
            panX = panY = 0;
            showHud(R.drawable.ic_zoom, "확대 100%", null, -1, 0);
        }
        hideHudSoon();
    }

    // ------------------------------------------------------------------ volume & brightness

    private int currentVolumeLevel() {
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) + boost;
    }

    private void setVolumeLevel(int level) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cap = max + (volBoost ? BOOST_MAX : 0);
        level = Util.clamp(level, 0, cap);
        try {
            if (level <= max) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
                if (boost != 0) setBoost(0);
            } else {
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                setBoost(level - max);
            }
        } catch (SecurityException ignored) {
            // Do-not-disturb can block volume changes
        }
        int shown = Math.min(level, max);
        showHud(level == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume_up,
                "볼륨 " + level, boost > 0 ? "부스트 +" + (boost * 10) + "%" : null,
                shown * 100 / Math.max(1, max), boost > 0 ? 100 : 0);
    }

    private void setBoost(int b) {
        boost = b;
        ExoPlayer p = engine.player();
        if (p == null) return;
        try {
            if (b > 0) {
                int sid = p.getAudioSessionId();
                if (sid == C.AUDIO_SESSION_ID_UNSET || sid == 0) return;
                if (loudness == null || loudnessSession != sid) {
                    releaseLoudness();
                    loudness = new LoudnessEnhancer(sid);
                    loudnessSession = sid;
                }
                loudness.setTargetGain(b * 100);
                loudness.setEnabled(true);
            } else if (loudness != null) {
                loudness.setEnabled(false);
            }
        } catch (Throwable t) {
            boost = 0;
            releaseLoudness();
        }
    }

    private void releaseLoudness() {
        if (loudness != null) {
            try { loudness.release(); } catch (Throwable ignored) { }
            loudness = null;
            loudnessSession = 0;
        }
    }

    private void initBrightness() {
        int saved = Prefs.rawInt(this, Prefs.BRIGHT_LEVEL, -1);
        if (Prefs.bool(this, Prefs.REMEMBER_BRIGHT, false) && saved >= 0) applyBrightness(saved, false);
    }

    private int currentBrightLevel() {
        if (brightLevel >= 0) return brightLevel;
        try {
            int sys = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Util.clamp(Math.round(sys / 255f * BRIGHT_MAX), 0, BRIGHT_MAX);
        } catch (Exception e) {
            return BRIGHT_MAX / 2;
        }
    }

    private void applyBrightness(int level, boolean hud) {
        brightLevel = Util.clamp(level, 0, BRIGHT_MAX);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.01f, brightLevel / (float) BRIGHT_MAX);
        getWindow().setAttributes(lp);
        if (Prefs.bool(this, Prefs.REMEMBER_BRIGHT, false)) Prefs.putInt(this, Prefs.BRIGHT_LEVEL, brightLevel);
        if (hud) showHud(R.drawable.ic_brightness, "밝기 " + brightLevel, null, brightLevel * 100 / BRIGHT_MAX, 0);
    }

    // ------------------------------------------------------------------ decoder / tracks

    private void switchDecoder(boolean toSw, boolean announce) {
        MediaRef r = engine.current();
        if (r == null) return;
        ExoPlayer old = engine.player();
        long pos = old != null ? Math.max(0, old.getCurrentPosition()) : 0;
        boolean play = old == null || old.getPlayWhenReady() || old.getPlaybackState() == Player.STATE_IDLE;
        releaseLoudness();
        engine.recreate(toSw);
        ExoPlayer p = engine.player();
        p.setVideoSurfaceView(surface);
        if (engine.currentSub() != null) {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        try {
            p.setMediaItem(PlayerEngine.mediaItemFor(r), pos);
            p.setPlaybackParameters(new PlaybackParameters(engine.speed));
            p.prepare();
        } catch (RuntimeException e) {
            showFatal("이 주소나 파일을 열 수 없습니다.");
            return;
        }
        p.setPlayWhenReady(play);
        if (boost > 0) setBoost(boost);
        updateDecoderChip();
        if (announce) {
            showHud(0, toSw ? "SW 디코더" : "HW 디코더", toSw ? "소프트웨어 디코딩" : "하드웨어 가속", -1, 0);
            hideHudSoon();
        }
    }

    private void setVideoTrackEnabled(boolean enabled) {
        ExoPlayer p = engine.player();
        if (p == null) return;
        TrackSelectionParameters cur = p.getTrackSelectionParameters();
        boolean disabled = cur.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO);
        if (disabled == !enabled) return;
        p.setTrackSelectionParameters(cur.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build());
    }

    static String codecName(String mime) {
        if (mime == null) return "알 수 없는";
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("mp4a") || m.contains("aac")) return "AAC";
        if (m.contains("eac3")) return "E-AC3";
        if (m.contains("ac3")) return "AC3";
        if (m.contains("ac4")) return "AC4";
        if (m.contains("dts")) return "DTS";
        if (m.contains("true-hd") || m.contains("truehd")) return "TrueHD";
        if (m.equals("audio/mpeg")) return "MP3";
        if (m.contains("mpeg-l2") || m.contains("mpeg-l1")) return "MP2";
        if (m.contains("opus")) return "Opus";
        if (m.contains("vorbis")) return "Vorbis";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("raw") || m.contains("wav") || m.contains("pcm")) return "PCM";
        if (m.contains("amr")) return "AMR";
        if (m.contains("subrip")) return "SRT";
        if (m.contains("ssa") || m.contains("ass")) return "ASS";
        if (m.contains("vtt")) return "VTT";
        if (m.contains("pgs")) return "PGS";
        if (m.contains("vobsub")) return "VobSub";
        if (m.contains("dvbsubs")) return "DVB";
        if (m.contains("tx3g")) return "TX3G";
        if (m.contains("ttml")) return "TTML";
        if (m.contains("cea")) return "CC";
        if (m.contains("avc")) return "H.264";
        if (m.contains("hevc")) return "H.265";
        if (m.contains("av01")) return "AV1";
        if (m.contains("vp9")) return "VP9";
        if (m.contains("vp8")) return "VP8";
        if (m.contains("mp4v")) return "MPEG-4";
        if (m.contains("mpeg2")) return "MPEG-2";
        int slash = m.indexOf('/');
        return (slash >= 0 ? m.substring(slash + 1) : m).toUpperCase(Locale.ROOT);
    }

    private static String trackLabel(Format f, int n) {
        StringBuilder sb = new StringBuilder();
        String lang = Util.langName(f.language);
        if (f.label != null && !f.label.isEmpty()) sb.append(f.label);
        if (lang != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(lang);
        }
        if (sb.length() == 0) sb.append("트랙 ").append(n);
        sb.append(" · ").append(codecName(f.sampleMimeType));
        if (f.channelCount > 0) {
            int c = f.channelCount;
            sb.append(' ').append(c == 1 ? "모노" : c == 2 ? "스테레오" : c == 6 ? "5.1ch" : c == 8 ? "7.1ch" : c + "ch");
        }
        return sb.toString();
    }

    private void showAudioDialog() {
        ExoPlayer p = engine.player();
        if (p == null) return;
        final List<String> labels = new ArrayList<>();
        final List<Tracks.Group> groups = new ArrayList<>();
        final List<Integer> indexes = new ArrayList<>();
        int checked = -1, n = 0;
        for (Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                n++;
                String l = trackLabel(g.getTrackFormat(i), n);
                if (!g.isTrackSupported(i)) l += "  (지원 안 됨)";
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(l);
                groups.add(g);
                indexes.add(i);
            }
        }
        if (labels.isEmpty()) {
            Util.toast(this, "오디오 트랙이 없습니다");
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("오디오 트랙")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dlg, which) -> {
                    dlg.dismiss();
                    selectTrack(groups.get(which), indexes.get(which), C.TRACK_TYPE_AUDIO);
                })
                .setNegativeButton("닫기", null)
                .create();
        showImmersive(d);
    }

    private void selectTrack(Tracks.Group g, int i, int type) {
        ExoPlayer p = engine.player();
        if (p == null) return;
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
                .build());
    }

    // ------------------------------------------------------------------ subtitles

    private void loadSubtitlesAsync(final MediaRef r) {
        if (r.path == null) return;
        final String key = r.key();
        final String enc = Prefs.str(this, Prefs.SUB_ENC, "auto");
        new Thread(() -> {
            final List<File> others = new ArrayList<>();
            final List<File> found = SubtitleParser.findSidecars(r.path, others);
            final List<SubtitleParser.Track> tracks = new ArrayList<>();
            for (File f : found) {
                try {
                    tracks.addAll(SubtitleParser.parse(SubtitleParser.readFile(f), f.getName(), enc));
                } catch (Throwable ignored) {
                }
                if (tracks.size() >= 8) break;
            }
            ui.post(() -> {
                MediaRef cur = engine.current();
                if (cur == null || !key.equals(cur.key()) || isDestroyed()) return;
                loadedSubsFor = key;
                otherSubFiles.clear();
                otherSubFiles.addAll(others);
                if (!tracks.isEmpty() && !engine.subTracks.isEmpty()) {
                    // the user already picked a subtitle while we were loading: just offer these too
                    engine.subTracks.addAll(tracks);
                } else if (!tracks.isEmpty()) {
                    engine.subTracks.clear();
                    engine.subTracks.addAll(tracks);
                    selectExternal(0);
                    SubtitleParser.Track t = tracks.get(0);
                    Util.toast(this, "자막: " + t.file + (tracks.size() > 1 ? " (" + t.name + ")" : ""));
                }
            });
        }, "subs").start();
    }

    private void selectExternal(int i) {
        engine.subSelected = i;
        ExoPlayer p = engine.player();
        if (p != null) {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, i >= 0 || engine.embeddedSubsOff)
                    .build());
        }
        subView.clear();
        startSubTick();
    }

    private void subtitlesOff() {
        engine.subSelected = -1;
        engine.embeddedSubsOff = true;
        ExoPlayer p = engine.player();
        if (p != null) {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        }
        subView.clear();
        ui.removeCallbacks(subTick);
    }

    private void selectEmbedded(Tracks.Group g, int i) {
        engine.subSelected = -1;
        engine.embeddedSubsOff = false;
        ui.removeCallbacks(subTick);
        subView.clear();
        selectTrack(g, i, C.TRACK_TYPE_TEXT);
    }

    private void showSubtitleDialog() {
        ExoPlayer p = engine.player();
        if (p == null) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        int checked = -1;
        boolean embeddedActive = false;

        labels.add("자막 끄기");
        actions.add(this::subtitlesOff);

        int n = 0;
        for (final Tracks.Group g : p.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                n++;
                final int idx = i;
                String l = "내장 · " + trackLabel(g.getTrackFormat(i), n);
                if (!g.isTrackSupported(i)) l += " (지원 안 됨)";
                if (g.isTrackSelected(i) && engine.currentSub() == null) {
                    checked = labels.size();
                    embeddedActive = true;
                }
                labels.add(l);
                actions.add(() -> selectEmbedded(g, idx));
            }
        }
        for (int i = 0; i < engine.subTracks.size(); i++) {
            final int idx = i;
            SubtitleParser.Track t = engine.subTracks.get(i);
            if (engine.subSelected == i) checked = labels.size();
            labels.add("외부 · " + t.file + (t.name != null && !t.name.isEmpty() && !t.name.equals(t.file) ? "  [" + t.name + "]" : ""));
            actions.add(() -> selectExternal(idx));
        }
        for (final File f : otherSubFiles) {
            labels.add("폴더 · " + f.getName());
            actions.add(() -> loadSubtitleFile(f));
        }
        if (checked < 0 && !embeddedActive && engine.currentSub() == null) checked = 0;
        labels.add("자막 파일 열기…");
        actions.add(this::pickSubtitleFile);
        labels.add("싱크 조절  (현재 " + delayLabel(engine.subDelay) + ")");
        actions.add(this::showSyncDialog);
        labels.add("자막 스타일 (크기·색·위치)");
        actions.add(this::showSubStyleDialog);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("자막")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dlg, which) -> {
                    dlg.dismiss();
                    actions.get(which).run();
                })
                .setNegativeButton("닫기", null)
                .create();
        showImmersive(d);
    }

    private void loadSubtitleFile(final File f) {
        final String enc = Prefs.str(this, Prefs.SUB_ENC, "auto");
        final String key = engine.current() != null ? engine.current().key() : "";
        new Thread(() -> {
            List<SubtitleParser.Track> tracks;
            try {
                tracks = SubtitleParser.parse(SubtitleParser.readFile(f), f.getName(), enc);
            } catch (Throwable t) {
                tracks = new ArrayList<>();
            }
            final List<SubtitleParser.Track> res = tracks;
            ui.post(() -> addExternalTracks(res, f.getName(), key));
        }, "subs").start();
    }

    private void addExternalTracks(List<SubtitleParser.Track> tracks, String name, String forKey) {
        MediaRef cur = engine.current();
        if (isDestroyed() || cur == null || !cur.key().equals(forKey)) return;
        if (tracks.isEmpty()) {
            Util.toast(this, "자막을 읽을 수 없습니다: " + name);
            return;
        }
        int first = engine.subTracks.size();
        engine.subTracks.addAll(tracks);
        selectExternal(first);
        Util.toast(this, "자막: " + name);
    }

    private void pickSubtitleFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        ExoPlayer p = engine.player();
        resumeAfterPicker = p != null && p.isPlaying();
        try {
            startActivityForResult(i, REQ_SUB_FILE);
        } catch (Exception e) {
            Util.toast(this, "파일 선택기를 열 수 없습니다");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SUB_FILE) return;
        if (resumeAfterPicker) {
            ExoPlayer p = engine.player();
            if (p != null) p.play();
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String enc = Prefs.str(this, Prefs.SUB_ENC, "auto");
        final String key = engine.current() != null ? engine.current().key() : "";
        new Thread(() -> {
            String name = "subtitle.srt";
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            List<SubtitleParser.Track> tracks = new ArrayList<>();
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    try {
                        tracks = SubtitleParser.parse(SubtitleParser.readAll(in), name, enc);
                    } finally {
                        in.close();
                    }
                }
            } catch (Throwable ignored) {
            }
            final String fn = name;
            final List<SubtitleParser.Track> res = tracks;
            ui.post(() -> addExternalTracks(res, fn, key));
        }, "subs").start();
    }

    private static String delayLabel(long d) {
        return String.format(Locale.US, "%+.1f초", d / 1000f);
    }

    private void showSyncDialog() {
        if (engine.currentSub() == null) {
            Util.toast(this, "싱크 조절은 외부 자막(SMI·SRT·ASS 등)에서만 가능합니다");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(this, 16);
        box.setPadding(pad, pad, pad, 0);
        final TextView value = new TextView(this);
        value.setTextSize(22);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(0xFFFFFFFF);
        final TextView hint = new TextView(this);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(0xFFAAAAAA);
        box.addView(value);
        box.addView(hint);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, pad, 0, 0);
        final long[] steps = {-1000, -100, 100, 1000};
        final String[] names = {"-1초", "-0.1초", "+0.1초", "+1초"};
        final Runnable refresh = () -> {
            value.setText(delayLabel(engine.subDelay));
            hint.setText(engine.subDelay > 0 ? "자막이 늦게 나옵니다" : engine.subDelay < 0 ? "자막이 빨리 나옵니다" : "원래 타이밍");
        };
        for (int i = 0; i < steps.length; i++) {
            final long st = steps[i];
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                engine.subDelay += st;
                HistoryDb.get(this).saveSubDelay(engine.current(), engine.subDelay);
                refresh.run();
            });
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(row);
        refresh.run();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("자막 싱크")
                .setView(box)
                .setNeutralButton("초기화", null)
                .setPositiveButton("닫기", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            engine.subDelay = 0;
            HistoryDb.get(this).saveSubDelay(engine.current(), 0);
            refresh.run();
        }));
        showImmersive(d);
    }

    private void showSubStyleDialog() {
        final String[] sizes = getResources().getStringArray(R.array.subsize_values);
        final String[] sizeNames = getResources().getStringArray(R.array.subsize_entries);
        final String[] bottoms = getResources().getStringArray(R.array.subbottom_values);
        final String[] bottomNames = getResources().getStringArray(R.array.subbottom_entries);
        final String[] colors = getResources().getStringArray(R.array.subcolor_values);
        final String[] colorNames = getResources().getStringArray(R.array.subcolor_entries);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(this, 20);
        box.setPadding(pad, Util.dp(this, 8), pad, 0);

        final TextView sizeLabel = new TextView(this);
        box.addView(sizeLabel);
        SeekBar size = new SeekBar(this);
        size.setMax(sizes.length - 1);
        size.setProgress(Math.max(0, indexOf(sizes, Prefs.str(this, Prefs.SUB_SIZE, "100"))));
        sizeLabel.setText("글자 크기: " + sizeNames[size.getProgress()]);
        size.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                Prefs.put(PlayerActivity.this, Prefs.SUB_SIZE, sizes[v]);
                sizeLabel.setText("글자 크기: " + sizeNames[v]);
                applySubtitleStyle();
            }
        });
        box.addView(size);

        final TextView posLabel = new TextView(this);
        posLabel.setPadding(0, Util.dp(this, 10), 0, 0);
        box.addView(posLabel);
        SeekBar pos = new SeekBar(this);
        pos.setMax(bottoms.length - 1);
        pos.setProgress(Math.max(0, indexOf(bottoms, Prefs.str(this, Prefs.SUB_BOTTOM, "6"))));
        posLabel.setText("위치: " + bottomNames[pos.getProgress()]);
        pos.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                Prefs.put(PlayerActivity.this, Prefs.SUB_BOTTOM, bottoms[v]);
                posLabel.setText("위치: " + bottomNames[v]);
                applySubtitleStyle();
            }
        });
        box.addView(pos);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("글자 색");
        colorLabel.setPadding(0, Util.dp(this, 10), 0, 0);
        box.addView(colorLabel);
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String cur = Prefs.str(this, Prefs.SUB_COLOR, "#FFFFFFFF");
        for (int i = 0; i < colors.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(colorNames[i]);
            rb.setTextColor(android.graphics.Color.parseColor(colors[i]));
            rg.addView(rb);
            if (colors[i].equalsIgnoreCase(cur)) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            int i = id - 2000;
            if (i >= 0 && i < colors.length) {
                Prefs.put(this, Prefs.SUB_COLOR, colors[i]);
                applySubtitleStyle();
            }
        });
        box.addView(rg);

        CheckBox outline = new CheckBox(this);
        outline.setText("테두리");
        outline.setChecked(Prefs.bool(this, Prefs.SUB_OUTLINE, true));
        outline.setOnCheckedChangeListener((b, c) -> { Prefs.put(this, Prefs.SUB_OUTLINE, c); applySubtitleStyle(); });
        box.addView(outline);
        CheckBox bg = new CheckBox(this);
        bg.setText("반투명 배경");
        bg.setChecked(Prefs.bool(this, Prefs.SUB_BG, false));
        bg.setOnCheckedChangeListener((b, c) -> { Prefs.put(this, Prefs.SUB_BG, c); applySubtitleStyle(); });
        box.addView(bg);

        AlertDialog d = new AlertDialog.Builder(this).setTitle("자막 스타일").setView(box)
                .setPositiveButton("닫기", null).create();
        showImmersive(d);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equalsIgnoreCase(v)) return i;
        return -1;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    // ------------------------------------------------------------------ more menu

    private void showMoreMenu() {
        PopupMenu pm = new PopupMenu(this, btnMore);
        Menu m = pm.getMenu();
        String[] repeatNames = {"끄기", "한 개 반복", "전체 반복"};
        boolean bgOn = Prefs.bool(this, Prefs.BG_PLAY, false) || engine.backgroundOverride;
        m.add(0, 1, 0, "재생 속도  (" + Util.speed(engine.speed) + ")");
        m.add(0, 2, 0, "화면 비율  (" + VideoFrameLayout.MODE_NAMES[videoFrame.getMode()] + ")");
        m.add(0, 3, 0, engine.abA < 0 ? "A-B 구간 반복: A 지점 설정" : engine.abB < 0 ? "A-B 구간 반복: B 지점 설정" : "A-B 구간 반복 해제");
        m.add(0, 4, 0, "반복 재생  (" + repeatNames[engine.repeatMode] + ")");
        m.add(0, 5, 0, engine.sleepAt > 0 ? "수면 타이머  (" + Math.max(1, (engine.sleepAt - System.currentTimeMillis()) / 60000) + "분 남음)"
                : engine.sleepAtEnd ? "수면 타이머  (영상 끝날 때)" : "수면 타이머");
        m.add(0, 6, 0, "백그라운드 재생  (" + (bgOn ? "켜짐" : "꺼짐") + ")");
        if (pipSupported()) m.add(0, 7, 0, "팝업 재생 (PiP)");
        m.add(0, 8, 0, "스크린샷");
        m.add(0, 9, 0, engine.isSw() ? "HW 디코더로 전환" : "SW 디코더로 전환");
        m.add(0, 10, 0, "동영상 정보");
        m.add(0, 11, 0, "설정");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showSpeedDialog(); break;
                case 2: cycleAspect(); break;
                case 3: abRepeat(); break;
                case 4:
                    engine.repeatMode = (engine.repeatMode + 1) % 3;
                    showHud(engine.repeatMode == 1 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat,
                            "반복: " + repeatNames[engine.repeatMode], null, -1, 0);
                    hideHudSoon();
                    break;
                case 5: showSleepDialog(); break;
                case 6:
                    engine.backgroundOverride = !bgOn;
                    if (engine.backgroundOverride) requestNotificationPermission();
                    if (!engine.backgroundOverride && Prefs.bool(this, Prefs.BG_PLAY, false)) {
                        Prefs.put(this, Prefs.BG_PLAY, false);
                    }
                    Util.toast(this, engine.backgroundOverride || Prefs.bool(this, Prefs.BG_PLAY, false)
                            ? "백그라운드 재생: 홈으로 나가거나 화면을 꺼도 소리가 계속 재생됩니다" : "백그라운드 재생을 껐습니다");
                    break;
                case 7: enterPip(); break;
                case 8: screenshot(); break;
                case 9: switchDecoder(!engine.isSw(), true); break;
                case 10: showInfoDialog(); break;
                case 11: startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)); break;
                default: return false;
            }
            return true;
        });
        pm.setOnDismissListener(x -> hideSystemUi());
        ui.removeCallbacks(hideControlsRunnable);
        pm.show();
    }

    private void showSpeedDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(this, 18);
        box.setPadding(pad, pad, pad, 0);
        final TextView value = new TextView(this);
        value.setTextSize(24);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(0xFFFFFFFF);
        box.addView(value);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(75); // 0.25 .. 4.00 in 0.05 steps
        sb.setProgress(Math.round((engine.speed - 0.25f) / 0.05f));
        value.setText(Util.speed(engine.speed));
        box.addView(sb);
        sb.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                float sp = Math.round((0.25f + v * 0.05f) * 100f) / 100f;
                setSpeed(sp);
                value.setText(Util.speed(sp));
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Util.dp(this, 8), 0, 0);
        float[] presets = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        for (final float ps : presets) {
            Button b = new Button(this);
            b.setText(Util.speed(ps));
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                setSpeed(ps);
                sb.setProgress(Math.round((ps - 0.25f) / 0.05f));
                value.setText(Util.speed(ps));
            });
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(row);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("재생 속도").setView(box)
                .setPositiveButton("닫기", null).create();
        showImmersive(d);
    }

    private void setSpeed(float s) {
        engine.speed = Util.clamp(s, 0.25f, 4f);
        ExoPlayer p = engine.player();
        if (p != null && !longPressActive) p.setPlaybackParameters(new PlaybackParameters(engine.speed));
        updateSpeedChip();
    }

    private void abRepeat() {
        ExoPlayer p = engine.player();
        if (p == null) return;
        long pos = p.getCurrentPosition();
        if (engine.abA < 0) {
            engine.abA = pos;
            Util.toast(this, "A 지점: " + Util.time(pos) + " — B 지점에서 다시 선택하세요");
        } else if (engine.abB < 0) {
            if (pos <= engine.abA + 500) {
                Util.toast(this, "B 지점은 A 지점보다 뒤여야 합니다");
                return;
            }
            engine.abB = pos;
            p.seekTo(engine.abA);
            Util.toast(this, "A-B 반복: " + Util.time(engine.abA) + " ~ " + Util.time(engine.abB));
        } else {
            engine.abA = engine.abB = -1;
            Util.toast(this, "A-B 반복을 해제했습니다");
        }
        updateAbChip();
    }

    private void showSleepDialog() {
        final String[] items = {"끄기", "15분 후", "30분 후", "45분 후", "60분 후", "90분 후", "현재 동영상이 끝나면"};
        final int[] mins = {0, 15, 30, 45, 60, 90, -1};
        AlertDialog d = new AlertDialog.Builder(this).setTitle("수면 타이머")
                .setItems(items, (dlg, w) -> {
                    engine.setSleep(mins[w] > 0 ? System.currentTimeMillis() + mins[w] * 60000L : -1, mins[w] == -1);
                    Util.toast(this, w == 0 ? "수면 타이머를 껐습니다" : "수면 타이머: " + items[w]);
                }).create();
        showImmersive(d);
    }

    private void showInfoDialog() {
        ExoPlayer p = engine.player();
        MediaRef r = engine.current();
        if (p == null || r == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("제목: ").append(r.title).append('\n');
        if (r.path != null) sb.append("경로: ").append(r.path).append('\n');
        else sb.append("주소: ").append(r.uri).append('\n');
        long dur = p.getDuration();
        if (dur > 0 && dur != C.TIME_UNSET) sb.append("재생 시간: ").append(Util.time(dur)).append('\n');
        Format v = p.getVideoFormat();
        if (v != null) {
            sb.append("\n[영상]\n");
            sb.append("코덱: ").append(codecName(v.sampleMimeType));
            if (v.codecs != null) sb.append(" (").append(v.codecs).append(')');
            sb.append('\n');
            sb.append("해상도: ").append(v.width).append(" × ").append(v.height).append('\n');
            if (v.frameRate > 0) sb.append(String.format(Locale.US, "프레임: %.3f fps\n", v.frameRate));
            if (v.bitrate > 0) sb.append("비트레이트: ").append(v.bitrate / 1000).append(" kbps\n");
            sb.append("디코더: ").append(engine.isSw() ? "SW" : "HW");
            if (!engine.videoDecoder.isEmpty()) sb.append(" (").append(engine.videoDecoder).append(')');
            sb.append('\n');
        }
        Format a = p.getAudioFormat();
        if (a != null) {
            sb.append("\n[음성]\n");
            sb.append("코덱: ").append(codecName(a.sampleMimeType)).append('\n');
            if (a.channelCount > 0) sb.append("채널: ").append(a.channelCount).append('\n');
            if (a.sampleRate > 0) sb.append("샘플레이트: ").append(a.sampleRate).append(" Hz\n");
            if (a.language != null) sb.append("언어: ").append(Util.langName(a.language)).append('\n');
            if (!engine.audioDecoder.isEmpty()) sb.append("디코더: ").append(engine.audioDecoder).append('\n');
        }
        SubtitleParser.Track st = engine.currentSub();
        if (st != null) sb.append("\n[자막]\n").append(st.file).append(" · ").append(st.cues.size()).append("줄\n");
        AlertDialog d = new AlertDialog.Builder(this).setTitle("동영상 정보").setMessage(sb.toString().trim())
                .setPositiveButton("닫기", null).create();
        showImmersive(d);
    }

    // ------------------------------------------------------------------ screenshot

    private void screenshot() {
        ExoPlayer p = engine.player();
        if (p == null) return;
        VideoSize vs = p.getVideoSize();
        int w = vs.width > 0 ? vs.width : surface.getWidth();
        int h = vs.height > 0 ? vs.height : surface.getHeight();
        if (w <= 0 || h <= 0) return;
        float scale = Math.min(1f, 3840f / Math.max(w, h));
        final Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, bmp, result -> {
                if (result == PixelCopy.SUCCESS) saveScreenshot(bmp);
                else Util.toast(this, "스크린샷을 찍을 수 없습니다");
            }, ui);
        } catch (Exception e) {
            Util.toast(this, "스크린샷을 찍을 수 없습니다");
        }
    }

    private void saveScreenshot(final Bitmap bmp) {
        MediaRef r = engine.current();
        String base = (r != null ? r.title : "video").replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        final String name = base + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        new Thread(() -> {
            boolean ok = false;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MoaPlayer");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            try { ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, os); } finally { os.close(); }
                        }
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, done, null, null);
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MoaPlayer");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, name);
                    FileOutputStream os = new FileOutputStream(f);
                    try { ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, os); } finally { os.close(); }
                    MediaScannerConnection.scanFile(getApplicationContext(), new String[]{f.getAbsolutePath()}, null, null);
                }
            } catch (Exception ignored) {
            }
            final boolean fok = ok;
            ui.post(() -> Util.toast(this, fok ? "스크린샷 저장: 사진/MoaPlayer" : "스크린샷을 저장할 수 없습니다"));
        }, "screenshot").start();
    }

    // ------------------------------------------------------------------ PiP & background

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private boolean isInPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void enterPip() {
        if (!pipSupported()) {
            Util.toast(this, "이 기기는 팝업 재생(PiP)을 지원하지 않습니다");
            return;
        }
        try {
            boolean ok = enterPictureInPictureMode(pipParams());
            if (ok) inPipSession = true;
        } catch (Exception e) {
            Util.toast(this, "팝업 재생을 시작할 수 없습니다");
        }
    }

    private PictureInPictureParams pipParams() {
        float a = Util.clamp(videoFrame.videoAspect(), 0.42f, 2.38f);
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(Math.round(a * 1000), 1000));
        List<RemoteAction> actions = new ArrayList<>();
        ExoPlayer p = engine.player();
        boolean playing = p != null && p.isPlaying();
        actions.add(remote(3, R.drawable.ic_prev, "이전"));
        actions.add(remote(1, playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? "일시정지" : "재생"));
        actions.add(remote(2, R.drawable.ic_next, "다음"));
        b.setActions(actions);
        return b.build();
    }

    private RemoteAction remote(int cmd, int icon, String title) {
        Intent i = new Intent(ACTION_PIP).setPackage(getPackageName()).putExtra("cmd", cmd).putExtra("token", PIP_TOKEN);
        PendingIntent pi = PendingIntent.getBroadcast(this, 100 + cmd, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), title, title, pi);
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26 && isInPip()) {
            try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { }
        }
    }

    private boolean shouldBackground() {
        ExoPlayer p = engine.player();
        return (Prefs.bool(this, Prefs.BG_PLAY, false) || engine.backgroundOverride)
                && p != null && p.getPlayWhenReady() && !closing;
    }

    // ------------------------------------------------------------------ system UI & keys

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** Shows a dialog without bringing back the status and navigation bars. */
    @SuppressWarnings("deprecation")
    private void showImmersive(Dialog d) {
        Window w = d.getWindow();
        if (w != null) w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        d.show();
        if (w != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
            }
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        ui.removeCallbacks(hideControlsRunnable);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isInPip()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                setVolumeLevel(currentVolumeLevel() + 1);
                hideHudSoon();
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                setVolumeLevel(currentVolumeLevel() - 1);
                hideHudSoon();
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: {
                ExoPlayer p = engine.player();
                if (p != null) p.play();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PAUSE: {
                ExoPlayer p = engine.player();
                if (p != null) p.pause();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                skip(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                skip(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(seekSec * 1000L);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-seekSec * 1000L);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!controlsVisible) { seekBy(-seekSec * 1000L); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!controlsVisible) { seekBy(seekSec * 1000L); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!controlsVisible) { showControls(); return true; }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}
