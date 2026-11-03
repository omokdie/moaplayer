package com.moa.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Full screen touch layer implementing MX Player style gestures:
 * tap = show/hide controls, double tap left/right = seek, double tap center = play/pause,
 * horizontal swipe = seek, vertical swipe left = brightness, right = volume,
 * pinch = zoom, long press = 2x speed.
 */
public class GestureView extends View {

    public interface Callback {
        boolean isLocked();
        boolean gestureEnabled(int type);
        int doubleTapMode(); // 0 seek, 1 play/pause only, 2 off
        void onSingleTap();
        void onDoubleTap(int zone); // -1 left, 0 center, 1 right
        void onLongPressStart();
        void onLongPressEnd();
        void onSeekStart();
        void onSeekMove(float fractionOfWidth);
        void onSeekEnd();
        void onBrightnessStart();
        void onBrightnessMove(float fractionOfHeight);
        void onVolumeStart();
        void onVolumeMove(float fractionOfHeight);
        void onVerticalEnd();
        void onScaleStart();
        void onScale(float factor, float dx, float dy);
        void onScaleEnd();
    }

    public static final int G_SEEK = 1, G_BRIGHT = 2, G_VOLUME = 3, G_ZOOM = 4, G_LONG = 5;

    private static final int MODE_NONE = 0, MODE_SEEK = 1, MODE_BRIGHT = 2, MODE_VOLUME = 3, MODE_SCALE = 4, MODE_LONG = 5, MODE_IGNORE = 6;

    private Callback cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;
    private final int touchSlop;
    private final int doubleTapTimeout;
    private final int doubleTapSlop;
    private final float edge;

    private int mode = MODE_NONE;
    private float downX, downY;
    private long downTime;
    private boolean multiTouchSeen;

    private boolean pendingSingle;
    private float lastTapX, lastTapY;
    private long lastTapUp;

    private long chainUntil;
    private int chainZone;

    private float lastFocusX, lastFocusY;

    private final Runnable singleTap = new Runnable() {
        @Override public void run() {
            pendingSingle = false;
            if (cb != null) cb.onSingleTap();
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (mode != MODE_NONE || cb == null || cb.isLocked() || !cb.gestureEnabled(G_LONG)) return;
            mode = MODE_LONG;
            cb.onLongPressStart();
        }
    };

    public GestureView(Context c) { this(c, null); }

    public GestureView(Context c, AttributeSet a) {
        super(c, a);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop() * 2;
        doubleTapSlop = vc.getScaledDoubleTapSlop();
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        edge = 24 * c.getResources().getDisplayMetrics().density;
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                if (cb == null || cb.isLocked() || !cb.gestureEnabled(G_ZOOM)) return false;
                if (mode == MODE_SEEK) cb.onSeekEnd();
                else if (mode == MODE_BRIGHT || mode == MODE_VOLUME) cb.onVerticalEnd();
                else if (mode == MODE_LONG) cb.onLongPressEnd();
                mode = MODE_SCALE;
                lastFocusX = d.getFocusX();
                lastFocusY = d.getFocusY();
                cb.onScaleStart();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector d) {
                if (mode != MODE_SCALE) return false;
                float dx = d.getFocusX() - lastFocusX, dy = d.getFocusY() - lastFocusY;
                lastFocusX = d.getFocusX();
                lastFocusY = d.getFocusY();
                cb.onScale(d.getScaleFactor(), dx, dy);
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector d) {
                if (mode == MODE_SCALE) cb.onScaleEnd();
                mode = MODE_IGNORE;
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 19) scaleDetector.setQuickScaleEnabled(false);
    }

    public void setCallback(Callback c) {
        cb = c;
    }

    private int zoneOf(float x) {
        float w = getWidth();
        if (x < w * 0.38f) return -1;
        if (x > w * 0.62f) return 1;
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (cb == null) return false;
        scaleDetector.onTouchEvent(e);
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = SystemClock.uptimeMillis();
                multiTouchSeen = false;
                mode = MODE_NONE;
                h.removeCallbacks(longPress);
                if (!cb.isLocked()) h.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouchSeen = true;
                h.removeCallbacks(longPress);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (cb.isLocked() || mode == MODE_SCALE || mode == MODE_IGNORE || mode == MODE_LONG) return true;
                if (e.getPointerCount() > 1) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (mode == MODE_NONE) {
                    if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) return true;
                    h.removeCallbacks(longPress);
                    boolean nearEdge = downY < edge || downY > getHeight() - edge || downX < edge || downX > getWidth() - edge;
                    if (nearEdge) {
                        mode = MODE_IGNORE;
                        return true;
                    }
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (cb.gestureEnabled(G_SEEK)) {
                            mode = MODE_SEEK;
                            cb.onSeekStart();
                        } else mode = MODE_IGNORE;
                    } else if (downX < getWidth() / 2f) {
                        if (cb.gestureEnabled(G_BRIGHT)) {
                            mode = MODE_BRIGHT;
                            cb.onBrightnessStart();
                        } else mode = MODE_IGNORE;
                    } else {
                        if (cb.gestureEnabled(G_VOLUME)) {
                            mode = MODE_VOLUME;
                            cb.onVolumeStart();
                        } else mode = MODE_IGNORE;
                    }
                    // re-base so the slop does not cause a jump
                    downX = e.getX();
                    downY = e.getY();
                    dx = 0;
                    dy = 0;
                }
                if (mode == MODE_SEEK) cb.onSeekMove(dx / Math.max(1, getWidth()));
                else if (mode == MODE_BRIGHT) cb.onBrightnessMove(-dy / Math.max(1, getHeight()));
                else if (mode == MODE_VOLUME) cb.onVolumeMove(-dy / Math.max(1, getHeight()));
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(longPress);
                int m = mode;
                mode = MODE_NONE;
                if (m == MODE_SEEK) { cb.onSeekEnd(); return true; }
                if (m == MODE_BRIGHT || m == MODE_VOLUME) { cb.onVerticalEnd(); return true; }
                if (m == MODE_LONG) { cb.onLongPressEnd(); return true; }
                if (m != MODE_NONE || multiTouchSeen || action == MotionEvent.ACTION_CANCEL) return true;
                long now = SystemClock.uptimeMillis();
                if (now - downTime > ViewConfiguration.getLongPressTimeout()) return true;
                handleTap(e.getX(), e.getY(), now);
                return true;
            }
            default:
                return true;
        }
    }

    private void handleTap(float x, float y, long now) {
        int dt = cb.isLocked() ? 2 : cb.doubleTapMode();
        int zone = zoneOf(x);
        // continuing a seek chain: every further tap on the same side seeks again immediately
        if (dt == 0 && now < chainUntil && zone == chainZone && zone != 0) {
            chainUntil = now + 700;
            h.removeCallbacks(singleTap);
            pendingSingle = false;
            cb.onDoubleTap(zone);
            return;
        }
        if (dt == 2) {
            cb.onSingleTap();
            return;
        }
        if (pendingSingle && now - lastTapUp < doubleTapTimeout
                && Math.abs(x - lastTapX) < doubleTapSlop * 2 && Math.abs(y - lastTapY) < doubleTapSlop * 2) {
            h.removeCallbacks(singleTap);
            pendingSingle = false;
            if (dt == 1) {
                cb.onDoubleTap(0);
            } else {
                cb.onDoubleTap(zone);
                if (zone != 0) {
                    chainZone = zone;
                    chainUntil = now + 700;
                }
            }
            return;
        }
        pendingSingle = true;
        lastTapX = x;
        lastTapY = y;
        lastTapUp = now;
        h.removeCallbacks(singleTap);
        h.postDelayed(singleTap, doubleTapTimeout);
    }
}
