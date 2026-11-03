package com.moa.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/** Foreground service that keeps audio playing in the background with a media notification. */
public class PlaybackService extends Service {
    private static final int NOTIF_ID = 7;
    static final String ACTION_TOGGLE = "com.moa.player.TOGGLE";
    static final String ACTION_NEXT = "com.moa.player.NEXT";
    static final String ACTION_PREV = "com.moa.player.PREV";
    static final String ACTION_STOP = "com.moa.player.STOP";
    static final String ACTION_DISMISS = "com.moa.player.DISMISS";

    private static boolean running;
    private static boolean starting;

    private MediaSession session;
    private PlayerEngine engine;
    private boolean foreground;

    public static boolean isRunning() {
        return running || starting;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            starting = true;
        } catch (Exception e) {
            // Background start not allowed: keep playing while the process lives.
        }
    }

    /**
     * Ends background mode. Sent as a command instead of stopService(): a service started with
     * startForegroundService() must reach startForeground() before it may stop, or the app crashes.
     */
    public static void stop(Context c) {
        if (!running && !starting) return;
        try {
            c.startService(new Intent(c, PlaybackService.class).setAction(ACTION_DISMISS));
        } catch (Exception e) {
            c.stopService(new Intent(c, PlaybackService.class));
        }
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean isPlaying) { update(); }
        @Override public void onPlaybackStateChanged(int state) { update(); }
        @Override public void onMediaItemTransition(androidx.media3.common.MediaItem item, int reason) { update(); }
        @Override public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) { update(); }
        @Override public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters p) { update(); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        engine = PlayerEngine.get(this);
        session = new MediaSession(this, "MoaPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { ExoPlayer p = engine.player(); if (p != null) p.play(); }
            @Override public void onPause() { ExoPlayer p = engine.player(); if (p != null) p.pause(); }
            @Override public void onSkipToNext() { PlayerActivity.requestSkip(PlaybackService.this, 1); }
            @Override public void onSkipToPrevious() { PlayerActivity.requestSkip(PlaybackService.this, -1); }
            @Override public void onSeekTo(long pos) { ExoPlayer p = engine.player(); if (p != null) p.seekTo(pos); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setActive(true);
        engine.addListener(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        starting = false;
        // Always satisfy the startForegroundService() contract first.
        if (!foreground) {
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        }
        if (ACTION_DISMISS.equals(a)) {
            engine.backgroundActive = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        ExoPlayer p = engine.player();
        if (ACTION_TOGGLE.equals(a)) {
            if (p != null) { if (p.isPlaying()) p.pause(); else p.play(); }
        } else if (ACTION_NEXT.equals(a)) {
            PlayerActivity.requestSkip(this, 1);
        } else if (ACTION_PREV.equals(a)) {
            PlayerActivity.requestSkip(this, -1);
        } else if (ACTION_STOP.equals(a)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        if (p == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        engine.backgroundActive = true;
        update();
        return START_NOT_STICKY;
    }

    private void stopPlayback() {
        engine.savePosition();
        ExoPlayer p = engine.player();
        if (p != null) p.pause();
        engine.backgroundActive = false;
        PlayerActivity.finishIfOpen();
        engine.release();
        stopSelf();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        ExoPlayer p = engine.player();
        MediaRef r = engine.current();
        boolean playing = p != null && p.isPlaying();
        String title = r != null ? r.title : getString(R.string.app_name);
        long dur = p != null ? p.getDuration() : 0;
        if (dur == C.TIME_UNSET) dur = 0;
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        p != null ? p.getCurrentPosition() : 0, playing ? engine.speed : 0f)
                .build());

        Intent open = new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_ATTACH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, App.CH_PLAYBACK)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(playing ? "재생 중" : "일시정지")
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(action(ACTION_STOP, 14))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "이전", action(ACTION_PREV, 11)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "일시정지" : "재생", action(ACTION_TOGGLE, 12)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "다음", action(ACTION_NEXT, 13)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "닫기", action(ACTION_STOP, 14)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void update() {
        if (!foreground) return;
        if (engine.player() == null) return;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        starting = false;
        engine.removeListener(listener);
        engine.backgroundActive = false;
        if (session != null) session.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
