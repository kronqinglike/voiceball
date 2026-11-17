package com.yuhua.voicebox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class BallView extends View {
    public boolean recording = false;
    public boolean collapsed = false;
    public boolean pressed = false;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strip = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BallView(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(7));
        p.setColor(0xFF2ECC71);
        strip.setStyle(Paint.Style.FILL);
        strip.setColor(0xE6FFFFFF);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setRecording(boolean v) {
        recording = v;
        p.setColor(v ? 0xFFE74C3C : 0xFF2ECC71);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (collapsed) {
            // 贴边的那条白条
            RectF r = new RectF(dp(3), dp(3), w - dp(3), h - dp(3));
            strip.setColor(0xD9FFFFFF);
            c.drawRoundRect(r, dp(7), dp(7), strip);
            return;
        }
        float cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) / 2f - p.getStrokeWidth();
        // 中间透空，只画一圈
        if (recording) {
            Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
            inner.setStyle(Paint.Style.FILL);
            inner.setColor(0x33E74C3C);
            c.drawCircle(cx, cy, rad, inner);
        }
        c.drawCircle(cx, cy, rad, p);
    }
}
