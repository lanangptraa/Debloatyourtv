package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom 24-hour solar gradient slider bar with circular thumb knob matching wallpapercycle.mp4.
 */
public final class TimeScrubberView extends View {

    private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackGradPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int minuteOfDay = 960;
    private boolean focusedState = false;
    private int cachedW = -1;

    private static final int[] GRAD_COLORS = new int[]{
            0xFF232B3E, // 00:00 Malam
            0xFF5D4B6B, // 05:00 Subuh
            0xFFE5AD82, // 06:30 Sunrise
            0xFFF3EFE4, // 12:00 Siang
            0xFFE6A170, // 17:15 Golden Hour
            0xFF4A3D59, // 18:45 Senja
            0xFF232B3E  // 24:00 Malam
    };
    private static final float[] GRAD_POS = new float[]{
            0.0f, 0.20f, 0.27f, 0.50f, 0.72f, 0.79f, 1.0f
    };

    public TimeScrubberView(Context context) {
        this(context, null);
    }

    public TimeScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        trackBgPaint.setColor(0xFF252A36);
        thumbShadowPaint.setColor(0x55000000);
        thumbOuterPaint.setColor(0xFFFFFFFF);
        thumbInnerPaint.setColor(0xFF141824);
    }

    public void setMinuteOfDay(int minute) {
        int clamped = ((minute % 1440) + 1440) % 1440;
        if (this.minuteOfDay != clamped) {
            this.minuteOfDay = clamped;
            invalidate();
        }
    }

    public void setFocusedState(boolean focused) {
        if (this.focusedState != focused) {
            this.focusedState = focused;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float padX = 8f * density;
        float trackW = Math.max(1f, w - padX * 2f);

        if (w != cachedW) {
            cachedW = w;
            trackGradPaint.setShader(new LinearGradient(
                    padX, 0f, padX + trackW, 0f,
                    GRAD_COLORS, GRAD_POS, Shader.TileMode.CLAMP));
        }

        float cy = h * 0.5f;
        float trackHalfH = 2.6f * density;
        float r = trackHalfH;

        // 1. Full background track
        trackBgPaint.setColor(focusedState ? 0x3814171C : 0xFF252A36);
        canvas.drawRoundRect(padX, cy - trackHalfH, padX + trackW, cy + trackHalfH, r, r, trackBgPaint);

        // 2. Active gradient track up to current time knob (plus subtle full-width gradient preview)
        float progress = minuteOfDay / 1439f;
        float thumbX = padX + trackW * progress;
        canvas.drawRoundRect(padX, cy - trackHalfH, Math.max(padX + r * 2f, thumbX), cy + trackHalfH, r, r, trackGradPaint);

        // 3. Circular thumb knob
        float thumbR = (focusedState ? 7.5f : 6.5f) * density;
        canvas.drawCircle(thumbX, cy + 1f * density, thumbR + 1f * density, thumbShadowPaint);
        thumbOuterPaint.setColor(focusedState ? 0xFF14171C : 0xFFFFFFFF);
        canvas.drawCircle(thumbX, cy, thumbR, thumbOuterPaint);

        // At night/subuh, draw inner dark ring like wallpapercycle.mp4 (20:45 / 19:59 frames)
        boolean isNight = minuteOfDay < 330 || minuteOfDay >= 1110;
        if (isNight || focusedState) {
            thumbInnerPaint.setColor(focusedState ? 0xFFFFFFFF : 0xFF161C2C);
            canvas.drawCircle(thumbX, cy, thumbR * 0.45f, thumbInnerPaint);
        }
    }
}
