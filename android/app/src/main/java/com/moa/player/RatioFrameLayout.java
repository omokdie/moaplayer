package com.moa.player;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** FrameLayout that keeps a 16:9 aspect ratio based on its width. */
public class RatioFrameLayout extends FrameLayout {
    public RatioFrameLayout(Context c) { super(c); }
    public RatioFrameLayout(Context c, AttributeSet a) { super(c, a); }
    public RatioFrameLayout(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = View.MeasureSpec.getSize(widthSpec);
        int h = w * 9 / 16;
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    }
}
