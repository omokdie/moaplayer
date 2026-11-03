package com.moa.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;

import androidx.media3.common.text.Cue;

import java.util.ArrayList;
import java.util.List;

/** Draws outlined subtitle text (external or embedded) and bitmap subtitles (PGS/VobSub). */
public class SubtitleView extends View {
    private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint stroke = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private CharSequence text;
    private boolean top;
    private final List<Cue> bitmapCues = new ArrayList<>();
    private final Rect videoRect = new Rect();

    private float scale = 1f;
    private int color = Color.WHITE;
    private boolean outline = true;
    private boolean background = false;
    private float bottomFraction = 0.06f;
    private boolean compact;

    private StaticLayout fillLayout, strokeLayout;
    private int layoutWidth = -1;
    private float layoutTextSize = -1;

    public SubtitleView(Context c) { super(c); init(); }
    public SubtitleView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        stroke.setTypeface(fill.getTypeface());
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.BLACK);
        bgPaint.setColor(0x99000000);
        fill.setShadowLayer(3f, 0f, 1.5f, 0x80000000);
    }

    public void setStyle(float scale, int color, boolean outline, boolean background, float bottomFraction) {
        this.scale = scale;
        this.color = color;
        this.outline = outline;
        this.background = background;
        this.bottomFraction = bottomFraction;
        invalidateLayout();
    }

    /** Smaller margins and text while in picture-in-picture. */
    public void setCompact(boolean c) {
        compact = c;
        invalidateLayout();
    }

    public void setVideoRect(int l, int t, int r, int b) {
        videoRect.set(l, t, r, b);
        if (!bitmapCues.isEmpty()) invalidate();
    }

    public void setText(CharSequence t, boolean atTop) {
        if (t != null && t.length() == 0) t = null;
        if (TextUtils.equals(t, text) && atTop == top && bitmapCues.isEmpty()) return;
        text = t;
        top = atTop;
        bitmapCues.clear();
        invalidateLayout();
    }

    /** Cues from the player (embedded tracks). */
    public void setCues(List<Cue> cues) {
        bitmapCues.clear();
        if (cues == null || cues.isEmpty()) {
            text = null;
            invalidateLayout();
            return;
        }
        StringBuilderSpannable sb = new StringBuilderSpannable();
        boolean allTop = true;
        for (Cue c : cues) {
            if (c.bitmap != null) {
                bitmapCues.add(c);
                continue;
            }
            if (c.text == null || c.text.length() == 0) continue;
            sb.append(SubtitleParser.trim(c.text));
            boolean isTop = (c.lineType == Cue.LINE_TYPE_FRACTION && c.line != Cue.DIMEN_UNSET && c.line < 0.4f)
                    || (c.lineType == Cue.LINE_TYPE_NUMBER && c.line != Cue.DIMEN_UNSET && c.line >= 0);
            if (!isTop) allTop = false;
        }
        text = sb.length() == 0 ? null : sb.build();
        top = text != null && allTop;
        invalidateLayout();
    }

    public void clear() {
        text = null;
        bitmapCues.clear();
        invalidateLayout();
    }

    private void invalidateLayout() {
        fillLayout = null;
        strokeLayout = null;
        invalidate();
    }

    private float textSizePx() {
        int w = getWidth(), h = getHeight();
        float base = Math.min(w, h) * (compact ? 0.075f : 0.056f);
        float minPx = 14 * getResources().getDisplayMetrics().scaledDensity;
        if (!compact) base = Math.max(base, minPx);
        return base * scale;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        invalidateLayout();
    }

    @SuppressWarnings("deprecation")
    private void buildLayouts() {
        int side = Math.max(getWidth() / 14, (int) (8 * getResources().getDisplayMetrics().density));
        int width = Math.max(1, getWidth() - side * 2);
        float ts = textSizePx();
        fill.setTextSize(ts);
        fill.setColor(color);
        stroke.setTextSize(ts);
        stroke.setStrokeWidth(Math.max(2f, ts * 0.13f));
        fillLayout = make(text, fill, width);
        if (outline) {
            CharSequence plain = text;
            if (text instanceof Spanned) {
                SpannableString ss = new SpannableString(text);
                for (Object o : ss.getSpans(0, ss.length(), ForegroundColorSpan.class)) ss.removeSpan(o);
                for (Object o : ss.getSpans(0, ss.length(), BackgroundColorSpan.class)) ss.removeSpan(o);
                plain = ss;
            }
            strokeLayout = make(plain, stroke, width);
        } else {
            strokeLayout = null;
        }
        layoutWidth = width;
        layoutTextSize = ts;
    }

    private static StaticLayout make(CharSequence t, TextPaint p, int width) {
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.06f)
                .setIncludePad(false)
                .build();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!bitmapCues.isEmpty()) drawBitmaps(canvas);
        if (text == null) return;
        if (fillLayout == null || layoutWidth != Math.max(1, getWidth() - Math.max(getWidth() / 14, (int) (8 * getResources().getDisplayMetrics().density)) * 2)
                || layoutTextSize != textSizePx()) {
            buildLayouts();
        }
        int side = (getWidth() - layoutWidth) / 2;
        int h = fillLayout.getHeight();
        float margin = compact ? getHeight() * 0.04f : getHeight() * bottomFraction;
        float y = top ? Math.max(getHeight() * 0.05f, margin) : getHeight() - margin - h;
        canvas.save();
        canvas.translate(side, y);
        if (background) {
            for (int i = 0; i < fillLayout.getLineCount(); i++) {
                float l = fillLayout.getLineLeft(i), r = fillLayout.getLineRight(i);
                float pad = fill.getTextSize() * 0.25f;
                RectF rc = new RectF(l - pad, fillLayout.getLineTop(i), r + pad, fillLayout.getLineBottom(i));
                canvas.drawRoundRect(rc, pad, pad, bgPaint);
            }
        }
        if (strokeLayout != null) strokeLayout.draw(canvas);
        fillLayout.draw(canvas);
        canvas.restore();
    }

    private void drawBitmaps(Canvas canvas) {
        Rect vr = videoRect.isEmpty() ? new Rect(0, 0, getWidth(), getHeight()) : videoRect;
        for (Cue c : bitmapCues) {
            Bitmap b = c.bitmap;
            if (b == null) continue;
            float vw = vr.width(), vh = vr.height();
            float pos = c.position != Cue.DIMEN_UNSET ? c.position : 0.5f;
            float line = c.line != Cue.DIMEN_UNSET ? c.line : 0.9f;
            float w = c.size != Cue.DIMEN_UNSET ? c.size * vw : vw * 0.5f;
            float h = c.bitmapHeight != Cue.DIMEN_UNSET ? c.bitmapHeight * vh : w * b.getHeight() / Math.max(1f, b.getWidth());
            float x = vr.left + pos * vw;
            float y = vr.top + line * vh;
            if (c.positionAnchor == Cue.ANCHOR_TYPE_MIDDLE) x -= w / 2;
            else if (c.positionAnchor == Cue.ANCHOR_TYPE_END) x -= w;
            if (c.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE) y -= h / 2;
            else if (c.lineAnchor == Cue.ANCHOR_TYPE_END) y -= h;
            canvas.drawBitmap(b, null, new RectF(x, y, x + w, y + h), bmpPaint);
        }
    }

    /** Tiny helper to join char sequences with new lines while keeping spans. */
    private static final class StringBuilderSpannable {
        private final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        void append(CharSequence s) {
            if (s == null || s.length() == 0) return;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }

        int length() { return sb.length(); }

        CharSequence build() { return sb; }
    }
}
