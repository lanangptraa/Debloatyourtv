package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Real tvOS-style frosted glass blur over the active Home wallpaper.
 * Computes a downscaled Gaussian-approximated blurred Bitmap once when the wallpaper loads,
 * and renders it in screen-space inside any glass pill, Bento card, or Dock capsule at 60fps.
 */
final class FrostedGlass {

    private static volatile Bitmap blurredBitmap;
    private static volatile BitmapShader sharedShader;
    private static volatile Bitmap blurredFocusedBitmap;
    private static volatile BitmapShader sharedFocusedShader;
    private static volatile int targetScreenW = 1920;
    private static volatile int targetScreenH = 1080;

    private FrostedGlass() {
    }

    static void updateFromWallpaper(Bitmap src, int screenW, int screenH) {
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) return;
        try {
            targetScreenW = screenW > 0 ? screenW : 1920;
            targetScreenH = screenH > 0 ? screenH : 1080;
            int bw = 320;
            int bh = 180;
            Bitmap scaled = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(scaled);
            // Center-crop src into 320x180 to match ImageView.ScaleType.CENTER_CROP
            float scale = Math.max((float) bw / src.getWidth(), (float) bh / src.getHeight());
            float sw = src.getWidth() * scale;
            float sh = src.getHeight() * scale;
            float dx = (bw - sw) * 0.5f;
            float dy = (bh - sh) * 0.5f;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(src, null, new RectF(dx, dy, dx + sw, dy + sh), p);

            boxBlur(scaled, 3, 3);

            // Pre-bake focused creamy white glass bitmap once so runtime draw() does 1 pass instead of 3
            Bitmap focusedBmp = scaled.copy(Bitmap.Config.ARGB_8888, true);
            Canvas fc = new Canvas(focusedBmp);
            Paint fp = new Paint();
            fp.setShader(new LinearGradient(0, 0, 0, bh, 0xCCF8FAFC, 0xBFE6EBF2, Shader.TileMode.CLAMP));
            fc.drawRect(0, 0, bw, bh, fp);

            // Pre-bake unfocused milky frosted glass sheen directly into scaled bitmap
            Paint tp = new Paint();
            tp.setShader(new LinearGradient(0, 0, 0, bh, 0x1810141C, 0x2210141C, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, tp);
            tp.setShader(new LinearGradient(0, 0, 0, bh, 0x36FFFFFF, 0x22FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, tp);

            blurredBitmap = scaled;
            sharedShader = new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            blurredFocusedBitmap = focusedBmp;
            sharedFocusedShader = new BitmapShader(focusedBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 3-pass sliding-window horizontal + vertical box blur (approximates a true Gaussian blur in < 2ms).
     */
    private static void boxBlur(Bitmap bmp, int radius, int iterations) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        int[] tmp = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);

        int div = 2 * radius + 1;
        for (int iter = 0; iter < iterations; iter++) {
            // Horizontal pass
            for (int y = 0; y < h; y++) {
                int rSum = 0, gSum = 0, bSum = 0;
                int rowOffset = y * w;
                for (int i = -radius; i <= radius; i++) {
                    int px = pix[rowOffset + Math.max(0, Math.min(w - 1, i))];
                    rSum += (px >> 16) & 0xFF;
                    gSum += (px >> 8) & 0xFF;
                    bSum += px & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    tmp[rowOffset + x] = 0xFF000000
                            | ((rSum / div) << 16)
                            | ((gSum / div) << 8)
                            | (bSum / div);
                    int outIdx = Math.max(0, x - radius);
                    int inIdx = Math.min(w - 1, x + radius + 1);
                    int pOut = pix[rowOffset + outIdx];
                    int pIn = pix[rowOffset + inIdx];
                    rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                    gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                    bSum += (pIn & 0xFF) - (pOut & 0xFF);
                }
            }
            // Vertical pass
            for (int x = 0; x < w; x++) {
                int rSum = 0, gSum = 0, bSum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int py = Math.max(0, Math.min(h - 1, i));
                    int px = tmp[py * w + x];
                    rSum += (px >> 16) & 0xFF;
                    gSum += (px >> 8) & 0xFF;
                    bSum += px & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    pix[y * w + x] = 0xFF000000
                            | ((rSum / div) << 16)
                            | ((gSum / div) << 8)
                            | (bSum / div);
                    int outIdx = Math.max(0, y - radius);
                    int inIdx = Math.min(h - 1, y + radius + 1);
                    int pOut = tmp[outIdx * w + x];
                    int pIn = tmp[inIdx * w + x];
                    rSum += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                    gSum += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                    bSum += (pIn & 0xFF) - (pOut & 0xFF);
                }
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }

    /**
     * Draws a frosted-glass rounded rectangle at the given canvas rect, aligned with screenOffsetX/Y.
     * Pre-baked frost tints mean 1 fill pass + 1 border pass with zero per-frame object allocations.
     */
    static void drawGlassRect(Canvas c, RectF rect, float screenOffsetX, float screenOffsetY,
                              float radius, float density, boolean focused,
                              Matrix scratchMatrix, Paint blurPaint, Paint tintPaint, Paint borderPaint) {
        Bitmap bmp = focused ? blurredFocusedBitmap : blurredBitmap;
        BitmapShader shader = focused ? sharedFocusedShader : sharedShader;
        if (bmp != null && shader != null) {
            float sx = (float) targetScreenW / bmp.getWidth();
            float sy = (float) targetScreenH / bmp.getHeight();
            scratchMatrix.reset();
            scratchMatrix.setScale(sx, sy);
            scratchMatrix.postTranslate(-screenOffsetX, -screenOffsetY);
            shader.setLocalMatrix(scratchMatrix);
            blurPaint.setShader(shader);
            blurPaint.setAlpha(255);
            c.drawRoundRect(rect, radius, radius, blurPaint);
        } else {
            tintPaint.setShader(null);
            tintPaint.setColor(focused ? 0xD9F4F6FA : 0x4D2A3240);
            c.drawRoundRect(rect, radius, radius, tintPaint);
        }

        borderPaint.setShader(null);
        borderPaint.setStrokeWidth((focused ? 1.5f : 1.2f) * density);
        borderPaint.setColor(focused ? 0xFFFFFFFF : 0x78FFFFFF);
        c.drawRoundRect(rect, radius, radius, borderPaint);
    }

    /**
     * Attaches a live screen-space FrostedGlass Drawable to any View (Bento cards, Header pill, Top-right pill).
     */
    static GlassDrawable attachTo(View view, float cornerRadiusDp) {
        float density = view.getResources().getDisplayMetrics().density;
        GlassDrawable d = new GlassDrawable(view, cornerRadiusDp * density, density);
        view.setBackground(d);
        return d;
    }

    static final class GlassDrawable extends Drawable {
        private final View host;
        private final float radius;
        private final float density;
        private final int[] loc = new int[2];
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean focused;
        private boolean locValid;

        GlassDrawable(View host, float radius, float density) {
            this.host = host;
            this.radius = radius;
            this.density = density;
            borderPaint.setStyle(Paint.Style.STROKE);
        }

        void setFocusedState(boolean focused) {
            if (this.focused != focused) {
                this.focused = focused;
                invalidateSelf();
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            locValid = false;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float inset = 0.75f * density;
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            if (!locValid) {
                host.getLocationOnScreen(loc);
                locValid = loc[0] != 0 || loc[1] != 0;
            }
            drawGlassRect(canvas, rect, loc[0], loc[1], radius, density, focused,
                    matrix, blurPaint, tintPaint, borderPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
