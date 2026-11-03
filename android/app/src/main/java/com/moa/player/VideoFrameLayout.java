package com.moa.player;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Lays out the video surface for the selected aspect mode, zoom and pan. */
public class VideoFrameLayout extends FrameLayout {
    public static final int FIT = 0, STRETCH = 1, CROP = 2, ORIGINAL = 3, R16_9 = 4, R4_3 = 5;
    public static final String[] MODE_NAMES = {"화면에 맞춤", "늘이기", "꽉 채우기(자르기)", "원본 크기 (100%)", "16:9", "4:3"};

    public interface OnVideoRectListener {
        void onVideoRect(int l, int t, int r, int b);
    }

    private int videoW, videoH;
    private float par = 1f;
    private int mode = FIT;
    private float zoom = 1f, panX, panY;
    private OnVideoRectListener rectListener;

    public VideoFrameLayout(Context c) { super(c); }
    public VideoFrameLayout(Context c, AttributeSet a) { super(c, a); }

    public void setOnVideoRectListener(OnVideoRectListener l) {
        rectListener = l;
    }

    public void setVideoSize(int w, int h, float pixelRatio) {
        videoW = w;
        videoH = h;
        par = pixelRatio > 0 ? pixelRatio : 1f;
        requestLayout();
    }

    public void setMode(int m) {
        mode = m;
        requestLayout();
    }

    public int getMode() {
        return mode;
    }

    public float getZoom() {
        return zoom;
    }

    public void setZoomPan(float z, float px, float py) {
        zoom = z;
        panX = px;
        panY = py;
        requestLayout();
    }

    public float getPanX() { return panX; }
    public float getPanY() { return panY; }

    public float videoAspect() {
        if (videoW <= 0 || videoH <= 0) return 16f / 9f;
        return videoW * par / videoH;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        if (getChildCount() == 0) return;
        float aspect = videoAspect();
        if (mode == R16_9) aspect = 16f / 9f;
        else if (mode == R4_3) aspect = 4f / 3f;
        float w, h;
        float screen = H > 0 ? W / (float) H : 1f;
        switch (mode) {
            case STRETCH:
                w = W;
                h = H;
                break;
            case CROP:
                if (screen > aspect) { w = W; h = W / aspect; } else { h = H; w = H * aspect; }
                break;
            case ORIGINAL:
                if (videoW > 0) { w = videoW * par; h = videoH; } else { w = W; h = H; }
                break;
            default:
                if (screen > aspect) { h = H; w = H * aspect; } else { w = W; h = W / aspect; }
                break;
        }
        w *= zoom;
        h *= zoom;
        // keep the panned video from leaving the screen completely
        float maxPx = Math.max(0, (w - W) / 2f) + W * 0.25f * (zoom > 1f ? 1 : 0);
        float maxPy = Math.max(0, (h - H) / 2f) + H * 0.25f * (zoom > 1f ? 1 : 0);
        panX = Util.clamp(panX, -maxPx, maxPx);
        panY = Util.clamp(panY, -maxPy, maxPy);
        int left = Math.round((W - w) / 2f + panX);
        int top = Math.round((H - h) / 2f + panY);
        int iw = Math.round(w), ih = Math.round(h);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih, MeasureSpec.EXACTLY));
            c.layout(left, top, left + iw, top + ih);
        }
        if (rectListener != null) {
            rectListener.onVideoRect(Math.max(0, left), Math.max(0, top), Math.min(W, left + iw), Math.min(H, top + ih));
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(0, widthSpec), getDefaultSize(0, heightSpec));
    }
}
