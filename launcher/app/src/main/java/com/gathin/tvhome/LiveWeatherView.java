package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Procedural 60fps Live Weather & Time-of-Day Wallpaper ("Wallpaper TV yang hidup")
 * faithfully matching wallpapercycle.mp4:
 * - 9 smooth time-of-day phases (Subuh, Fajar, Matahari terbit, Pagi, Siang, Sore, Golden hour, Senja, Malam)
 * - Ukiyo-e crimson-vermilion Sun with veiled horizon base & warm ivory Moon with radial bloom
 * - 3 layered organic mountain ridges with parallel topographic contour lines & valley mist waves
 * - Billowing multi-crest topographic wave clouds with 6 parallel contour strokes (matching f24.png)
 * - 6 weather states (Cerah, Cerah berawan, Mendung, Hujan, Badai petir, Kabut)
 */
public final class LiveWeatherView extends View {

    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mountainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ridgeContourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mistLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path farMountainPath = new Path();
    private final Path midMountainPath = new Path();
    private final Path nearMountainPath = new Path();
    private final Path farCrestPath = new Path();
    private final Path midCrestPath = new Path();
    private final Path nearCrestPath = new Path();
    private final Path mistPath = new Path();
    private final Path waveFillPath = new Path();
    private final Path waveCrestPath = new Path();
    private final Path lightningPath = new Path();
    private final Path lightningBranchPath = new Path();

    private int cachedW = -1;
    private int cachedH = -1;

    // 48 deterministic star positions (xNorm, yNorm, sizeDp, phaseOffset)
    private static final int STAR_COUNT = 48;
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starR = new float[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];

    // 95 deterministic rain drop seeds (xNorm, yNorm, speed, length)
    private static final int RAIN_COUNT = 95;
    private final float[] rainX = new float[RAIN_COUNT];
    private final float[] rainY = new float[RAIN_COUNT];
    private final float[] rainSpeed = new float[RAIN_COUNT];
    private final float[] rainLen = new float[RAIN_COUNT];

    private int lastGlassMinute = -1;
    private int lastGlassWeather = -99;
    private Bitmap glassBuffer;
    private Canvas glassCanvas;
    private boolean previewMode;

    public LiveWeatherView(Context context) {
        this(context, null);
    }

    public LiveWeatherView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initSeeds();
        ridgeContourPaint.setStyle(Paint.Style.STROKE);
        ridgeContourPaint.setStrokeCap(Paint.Cap.ROUND);
        ridgeContourPaint.setStrokeJoin(Paint.Join.ROUND);

        mistLinePaint.setStyle(Paint.Style.STROKE);
        mistLinePaint.setStrokeCap(Paint.Cap.ROUND);

        cloudStrokePaint.setStyle(Paint.Style.STROKE);
        cloudStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        cloudStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        rainPaint.setStyle(Paint.Style.STROKE);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);

        lightningPaint.setStyle(Paint.Style.STROKE);
        lightningPaint.setStrokeCap(Paint.Cap.ROUND);
        lightningPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setPreviewMode(boolean preview) {
        this.previewMode = preview;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void initSeeds() {
        long seed = 0x5EEDBEEFL;
        for (int i = 0; i < STAR_COUNT; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            starX[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            starY[i] = 0.04f + (((seed >>> 16) & 0xFFFF) / 65535f) * 0.46f;
            starR[i] = 0.9f + (i % 3) * 0.65f;
            starPhase[i] = i * 0.73f;
        }
        for (int i = 0; i < RAIN_COUNT; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            rainX[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            rainY[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            rainSpeed[i] = 0.65f + (i % 5) * 0.14f;
            rainLen[i] = 19f + (i % 4) * 7f;
        }
    }

    private void ensureMountainPaths(int w, int h) {
        if (w == cachedW && h == cachedH && w > 0 && h > 0) return;
        cachedW = w;
        cachedH = h;

        // 1. Far distant ridge (sweeping left crest, central saddle, tall right-center peak matching wallpapercycle.mp4)
        farCrestPath.reset();
        farCrestPath.moveTo(-w * 0.02f, h * 0.64f);
        farCrestPath.cubicTo(w * 0.10f, h * 0.55f, w * 0.22f, h * 0.52f, w * 0.36f, h * 0.59f);
        farCrestPath.cubicTo(w * 0.48f, h * 0.65f, w * 0.62f, h * 0.54f, w * 0.77f, h * 0.48f);
        farCrestPath.cubicTo(w * 0.88f, h * 0.44f, w * 0.95f, h * 0.53f, w * 1.02f, h * 0.57f);

        farMountainPath.set(farCrestPath);
        farMountainPath.lineTo(w * 1.02f, h);
        farMountainPath.lineTo(-w * 0.02f, h);
        farMountainPath.close();

        // 2. Mid mountain ridge (left-foreground ridge, deep central mist valley, secondary right peak)
        midCrestPath.reset();
        midCrestPath.moveTo(-w * 0.02f, h * 0.61f);
        midCrestPath.cubicTo(w * 0.11f, h * 0.53f, w * 0.23f, h * 0.58f, w * 0.37f, h * 0.68f);
        midCrestPath.cubicTo(w * 0.49f, h * 0.76f, w * 0.61f, h * 0.64f, w * 0.73f, h * 0.57f);
        midCrestPath.cubicTo(w * 0.84f, h * 0.51f, w * 0.93f, h * 0.61f, w * 1.02f, h * 0.66f);

        midMountainPath.set(midCrestPath);
        midMountainPath.lineTo(w * 1.02f, h);
        midMountainPath.lineTo(-w * 0.02f, h);
        midMountainPath.close();

        // 3. Foreground ridge (darkest silhouette with distinct right-side peak grounding the dock)
        nearCrestPath.reset();
        nearCrestPath.moveTo(-w * 0.02f, h * 0.77f);
        nearCrestPath.cubicTo(w * 0.15f, h * 0.68f, w * 0.31f, h * 0.72f, w * 0.49f, h * 0.80f);
        nearCrestPath.cubicTo(w * 0.64f, h * 0.85f, w * 0.76f, h * 0.67f, w * 0.85f, h * 0.66f);
        nearCrestPath.cubicTo(w * 0.92f, h * 0.65f, w * 0.97f, h * 0.72f, w * 1.02f, h * 0.75f);

        nearMountainPath.set(nearCrestPath);
        nearMountainPath.lineTo(w * 1.02f, h);
        nearMountainPath.lineTo(-w * 0.02f, h);
        nearMountainPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Context ctx = getContext();
        int minute = LiveWeatherEngine.activeMinuteOfDay(ctx);
        int weather = LiveWeatherEngine.activeWeather(ctx);
        long nowMs = System.currentTimeMillis();

        drawScene(canvas, w, h, minute, weather, nowMs);

        // Keep FrostedGlass synced with the Live Weather backdrop on the main Home screen
        if (!previewMode && (minute != lastGlassMinute || weather != lastGlassWeather)) {
            lastGlassMinute = minute;
            lastGlassWeather = weather;
            updateFrostedGlassSnapshot(w, h, minute, weather, nowMs);
        }

        if (getVisibility() == VISIBLE && isAttachedToWindow()) {
            long delay = (weather == LiveWeatherEngine.WEATHER_RAIN
                    || weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) ? 32L : 64L;
            postInvalidateDelayed(delay);
        }
    }

    /** Renders a 320x180 snapshot of the live scene into FrostedGlass so Dock & pills match the sky. */
    void updateFrostedGlassSnapshot(int screenW, int screenH, int minute, int weather, long nowMs) {
        try {
            if (glassBuffer == null) {
                glassBuffer = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
                glassCanvas = new Canvas(glassBuffer);
            }
            int savedW = cachedW, savedH = cachedH;
            drawScene(glassCanvas, 320, 180, minute, weather, nowMs);
            cachedW = -1;
            cachedH = -1;
            ensureMountainPaths(savedW, savedH);
            FrostedGlass.updateFromWallpaper(glassBuffer, screenW > 0 ? screenW : 1920, screenH > 0 ? screenH : 1080);
        } catch (Throwable ignored) {
        }
    }

    private void drawScene(Canvas canvas, int w, int h, int minute, int weather, long nowMs) {
        ensureMountainPaths(w, h);
        float scale = w / 1280f;
        ScenePalette pal = computePalette(minute, weather);

        // Lightning flash check for Badai petir
        float lightningFlash = 0f;
        if (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) {
            long cycle = nowMs % 5200L;
            if (cycle > 4100L && cycle < 4380L) {
                lightningFlash = 1f - Math.abs(cycle - 4240L) / 140f;
            } else if (cycle > 4460L && cycle < 4620L) {
                lightningFlash = 0.65f * (1f - Math.abs(cycle - 4540L) / 80f);
            }
        }

        int skyTop = lightningFlash > 0f ? Sky.lerpColor(pal.skyTop, 0xFFD8E2F0, lightningFlash * 0.45f) : pal.skyTop;
        int skyMid = lightningFlash > 0f ? Sky.lerpColor(pal.skyMid, 0xFFCBD6E6, lightningFlash * 0.40f) : pal.skyMid;
        int skyBot = lightningFlash > 0f ? Sky.lerpColor(pal.skyBottom, 0xFFB8C6DA, lightningFlash * 0.35f) : pal.skyBottom;

        // 1. Sky background gradient
        skyPaint.setShader(new LinearGradient(0f, 0f, 0f, h * 0.85f,
                new int[]{skyTop, skyMid, skyBot},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h, skyPaint);

        // 2. Twinkling stars at Night / Subuh / Fajar / Senja
        if (pal.starAlpha > 0.02f && weather != LiveWeatherEngine.WEATHER_RAIN
                && weather != LiveWeatherEngine.WEATHER_THUNDERSTORM
                && weather != LiveWeatherEngine.WEATHER_FOG) {
            float timeSec = (nowMs % 60000L) / 1000f;
            float weatherStarDim = (weather == LiveWeatherEngine.WEATHER_CLOUDY) ? 0.30f
                    : (weather == LiveWeatherEngine.WEATHER_PARTLY_CLOUDY ? 0.75f : 1f);
            for (int i = 0; i < STAR_COUNT; i++) {
                float twinkle = 0.52f + 0.48f * (float) Math.sin(timeSec * 2.1f + starPhase[i]);
                int a = Math.round(255 * pal.starAlpha * weatherStarDim * twinkle);
                if (a <= 4) continue;
                starPaint.setColor((a << 24) | 0xFFF7E8);
                canvas.drawCircle(starX[i] * w, starY[i] * h, starR[i] * scale, starPaint);
            }
        }

        // 3. Celestial body (Ukiyo-e crimson-vermilion Sun or warm ivory Moon) along the sky arc
        float cx = pal.bodyX * w;
        float cy = pal.bodyY * h;
        float radius = w * 0.044f;
        float bodyVis = pal.bodyAlpha;
        if (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) bodyVis *= 0.08f;
        else if (weather == LiveWeatherEngine.WEATHER_RAIN) bodyVis *= 0.22f;
        else if (weather == LiveWeatherEngine.WEATHER_CLOUDY) bodyVis *= 0.42f;
        else if (weather == LiveWeatherEngine.WEATHER_FOG) bodyVis *= 0.38f;

        if (bodyVis > 0.03f) {
            // Wide soft atmospheric bloom matching f09.png
            int haloCore = (Math.round(110 * bodyVis) << 24) | (pal.bodyGlow & 0x00FFFFFF);
            int haloMid = (Math.round(42 * bodyVis) << 24) | (pal.bodyGlow & 0x00FFFFFF);
            int haloEdge = pal.bodyGlow & 0x00FFFFFF;
            glowPaint.setShader(new RadialGradient(cx, cy, radius * 3.8f,
                    new int[]{haloCore, haloMid, haloEdge},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius * 3.8f, glowPaint);

            // Ukiyo-e gradient disc: rich top, warm mid, veiled horizon-blended bottom (matching f09.png)
            int topCol = (Math.round(255 * bodyVis) << 24) | (pal.bodyTop & 0x00FFFFFF);
            int midCol = (Math.round(240 * bodyVis) << 24) | (pal.bodyBottom & 0x00FFFFFF);
            int botMist = Sky.lerpColor(pal.bodyBottom, pal.skyMid, pal.isDay ? 0.68f : 0.18f);
            int botCol = (Math.round((pal.isDay ? 195 : 245) * bodyVis) << 24) | (botMist & 0x00FFFFFF);
            bodyPaint.setShader(new LinearGradient(cx, cy - radius, cx, cy + radius,
                    new int[]{topCol, midCol, botCol},
                    new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, bodyPaint);
        }

        // 4. Topographic Ukiyo-e Wave Clouds (matching f24.png, 15:45 Mendung, 15:50 Hujan, 15:56 Badai petir)
        float drift = ((nowMs % 140000L) / 140000f);
        if (weather == LiveWeatherEngine.WEATHER_PARTLY_CLOUDY
                || weather == LiveWeatherEngine.WEATHER_CLOUDY
                || weather == LiveWeatherEngine.WEATHER_RAIN
                || weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) {
            drawTopographicWaveClouds(canvas, w, h, drift, pal, weather, scale);
        }

        // 5. Forked lightning bolt during Badai petir
        if (lightningFlash > 0.15f) {
            drawLightningBolt(canvas, w, h, lightningFlash, scale);
        }

        // 6. Far mountain ridge + parallel topographic ridge contour lines
        int farBot = Sky.lerpColor(pal.farMountain, pal.skyBottom, 0.38f);
        mountainPaint.setShader(new LinearGradient(0f, h * 0.46f, 0f, h * 0.85f,
                pal.farMountain, farBot, Shader.TileMode.CLAMP));
        canvas.drawPath(farMountainPath, mountainPaint);
        drawRidgeContours(canvas, farCrestPath, pal.contourColor, 4, 4.2f * scale, 1.15f * scale, 56);

        // 7. Gentle valley mist / wind contour lines between far and mid ridges
        drawValleyMistLines(canvas, w, h, drift, pal, 0.55f, 0.67f, 4, scale);

        // 8. Mid mountain ridge + parallel topographic ridge contour lines
        int midBot = Sky.lerpColor(pal.midMountain, pal.nearMountain, 0.42f);
        mountainPaint.setShader(new LinearGradient(0f, h * 0.53f, 0f, h * 0.92f,
                pal.midMountain, midBot, Shader.TileMode.CLAMP));
        canvas.drawPath(midMountainPath, mountainPaint);
        drawRidgeContours(canvas, midCrestPath, pal.contourColor, 5, 4.6f * scale, 1.2f * scale, 64);

        // 9. Lower valley wind contour lines (visible in the central valley above the dock)
        drawValleyMistLines(canvas, w, h, (drift + 0.37f) % 1f, pal, 0.67f, 0.81f, 5, scale);

        // 10. Foreground mountain ridge + subtle crest contour lines
        mountainPaint.setShader(new LinearGradient(0f, h * 0.66f, 0f, h,
                pal.nearMountain, Sky.lerpColor(pal.nearMountain, 0xFF0A0D14, 0.22f), Shader.TileMode.CLAMP));
        canvas.drawPath(nearMountainPath, mountainPaint);
        drawRidgeContours(canvas, nearCrestPath, pal.contourColor, 3, 4.5f * scale, 1.1f * scale, 42);

        // 11. Fog banks for Kabut (or light valley haze for Hujan/Mendung)
        if (weather == LiveWeatherEngine.WEATHER_FOG) {
            drawFogLayers(canvas, w, h, drift, pal, 1.0f, scale);
        } else if (weather == LiveWeatherEngine.WEATHER_RAIN || weather == LiveWeatherEngine.WEATHER_CLOUDY) {
            drawFogLayers(canvas, w, h, drift, pal, 0.30f, scale);
        }

        // 12. Falling diagonal rain streaks for Hujan & Badai petir
        if (weather == LiveWeatherEngine.WEATHER_RAIN || weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) {
            drawRain(canvas, w, h, nowMs, weather == LiveWeatherEngine.WEATHER_THUNDERSTORM, pal);
        }
    }

    /**
     * Draws parallel topographic contour lines following a mountain ridge crest (matching wallpapercycle.mp4).
     */
    private void drawRidgeContours(Canvas canvas, Path crestPath, int contourColor,
                                   int lines, float stepY, float strokeW, int baseAlpha) {
        ridgeContourPaint.setStrokeWidth(Math.max(1f, strokeW));
        int rgb = contourColor & 0x00FFFFFF;
        for (int i = 0; i < lines; i++) {
            int alpha = Math.max(8, baseAlpha - i * (baseAlpha / (lines + 1)));
            ridgeContourPaint.setColor((alpha << 24) | rgb);
            canvas.save();
            canvas.translate(0f, i * stepY);
            canvas.drawPath(crestPath, ridgeContourPaint);
            canvas.restore();
        }
    }

    /**
     * Draws billowing Ukiyo-e / topographic wave clouds with soft gradient fill and 6 parallel contour strokes,
     * faithfully matching f24.png (15:48), 15:45 Mendung, 15:50 Hujan, and 15:56 Badai petir.
     */
    private void drawTopographicWaveClouds(Canvas canvas, int w, int h, float drift,
                                           ScenePalette pal, int weather, float scale) {
        if (weather == LiveWeatherEngine.WEATHER_PARTLY_CLOUDY) {
            // Cerah berawan: 2 delicate localized wave-cloud ribbons with parallel contour lines
            drawSingleWaveCloudBand(canvas, w, h,
                    w * 0.44f, w * 0.96f, h * 0.26f, h * 0.022f, h * 0.085f,
                    drift * 6.283f, 3, pal.cloudFillColor, pal.cloudStrokeColor, 52, 65, 5, scale);
            drawSingleWaveCloudBand(canvas, w, h,
                    w * 0.05f, w * 0.58f, h * 0.19f, h * 0.018f, h * 0.075f,
                    drift * 6.283f + 2.1f, 3, pal.cloudFillColor, pal.cloudStrokeColor, 40, 52, 4, scale);
            return;
        }

        // Mendung, Hujan, Badai petir: 3 full-width overlapping multi-peak topographic wave-cloud layers
        int fillAlpha = (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) ? 118 : 92;
        int strokeAlpha = (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) ? 105 : 88;

        // Upper wave cloud band
        drawSingleWaveCloudBand(canvas, w, h,
                -w * 0.08f, w * 1.08f, h * 0.19f, h * 0.028f, h * 0.12f,
                drift * 6.283f, 5, pal.cloudFillColor, pal.cloudStrokeColor,
                fillAlpha, strokeAlpha, 6, scale);

        // Mid wave cloud band
        drawSingleWaveCloudBand(canvas, w, h,
                -w * 0.08f, w * 1.08f, h * 0.29f, h * 0.032f, h * 0.13f,
                -drift * 6.283f + 1.7f, 4, pal.cloudFillColor, pal.cloudStrokeColor,
                Math.round(fillAlpha * 0.92f), strokeAlpha, 6, scale);

        // Lower wave cloud band above the mountains
        drawSingleWaveCloudBand(canvas, w, h,
                -w * 0.08f, w * 1.08f, h * 0.39f, h * 0.025f, h * 0.11f,
                drift * 6.283f + 3.4f, 5, pal.cloudFillColor, pal.cloudStrokeColor,
                Math.round(fillAlpha * 0.80f), Math.round(strokeAlpha * 0.85f), 5, scale);
    }

    private void drawSingleWaveCloudBand(Canvas canvas, int w, int h,
                                         float startX, float endX, float baseY, float ampY, float bandDepth,
                                         float phaseRad, int humps, int fillRgb, int strokeRgb,
                                         int fillAlpha, int strokeAlpha, int contourCount, float scale) {
        float span = endX - startX;
        float segW = span / humps;

        waveCrestPath.reset();
        float x0 = startX;
        float y0 = baseY + (float) Math.sin(phaseRad) * ampY * 0.55f;
        waveCrestPath.moveTo(x0, y0);

        for (int i = 0; i < humps; i++) {
            float sx = startX + i * segW;
            float ex = sx + segW;
            float crestH = ampY * (0.75f + 0.35f * (float) Math.sin(phaseRad + i * 1.6f));
            float ey = baseY + (float) Math.sin(phaseRad + (i + 1) * 1.3f) * ampY * 0.55f;
            waveCrestPath.cubicTo(
                    sx + segW * 0.32f, baseY - crestH,
                    sx + segW * 0.68f, baseY - crestH * 0.65f,
                    ex, ey);
        }

        // 1. Soft gradient fill below the wave crest
        waveFillPath.set(waveCrestPath);
        waveFillPath.lineTo(endX, baseY + bandDepth);
        waveFillPath.lineTo(startX, baseY + bandDepth);
        waveFillPath.close();

        int rgbFill = fillRgb & 0x00FFFFFF;
        cloudFillPaint.setShader(new LinearGradient(0f, baseY - ampY, 0f, baseY + bandDepth,
                new int[]{(fillAlpha << 24) | rgbFill, ((fillAlpha / 2) << 24) | rgbFill, 0x00000000 | rgbFill},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(waveFillPath, cloudFillPaint);

        // 2. Parallel topographic contour lines inside the wave band (matching f24.png)
        cloudStrokePaint.setStrokeWidth(Math.max(1.1f, 1.25f * scale));
        int rgbStroke = strokeRgb & 0x00FFFFFF;
        float stepY = 4.2f * scale;
        for (int c = 0; c < contourCount; c++) {
            int a = Math.max(12, strokeAlpha - c * (strokeAlpha / (contourCount + 1)));
            cloudStrokePaint.setColor((a << 24) | rgbStroke);
            canvas.save();
            canvas.translate(0f, c * stepY);
            canvas.drawPath(waveCrestPath, cloudStrokePaint);
            canvas.restore();
        }
    }

    private void drawValleyMistLines(Canvas canvas, int w, int h, float drift,
                                     ScenePalette pal, float yStartNorm, float yEndNorm, int count, float scale) {
        mistLinePaint.setStrokeWidth(Math.max(1.2f, 1.35f * scale));
        int baseColor = pal.mistColor & 0x00FFFFFF;
        for (int i = 0; i < count; i++) {
            float progress = (i + 0.5f) / count;
            float y = h * (yStartNorm + (yEndNorm - yStartNorm) * progress);
            float phase = (float) ((drift * Math.PI * 2.0) + i * 1.4f);
            float xShift = (float) Math.sin(phase) * w * 0.035f;
            float span = w * (0.26f + (i % 2) * 0.14f);
            float startX = w * (0.14f + ((i * 0.23f) % 0.54f)) + xShift;
            float amp = h * 0.009f * (i % 2 == 0 ? 1f : -1f);

            int alpha = Math.round(52 + 26 * (float) Math.cos(phase));
            mistLinePaint.setColor((alpha << 24) | baseColor);

            mistPath.reset();
            mistPath.moveTo(startX, y);
            mistPath.cubicTo(startX + span * 0.33f, y - amp,
                    startX + span * 0.66f, y + amp,
                    startX + span, y);
            canvas.drawPath(mistPath, mistLinePaint);
        }
    }

    private void drawFogLayers(Canvas canvas, int w, int h, float drift, ScenePalette pal, float intensity, float scale) {
        int rgb = pal.mistColor & 0x00FFFFFF;
        int topAlpha = Math.round(135 * intensity);
        int midAlpha = Math.round(188 * intensity);
        fogPaint.setShader(new LinearGradient(0f, h * 0.34f, 0f, h,
                new int[]{0x00000000 | rgb, (topAlpha << 24) | rgb, (midAlpha << 24) | rgb},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, h * 0.34f, w, h, fogPaint);

        if (intensity > 0.6f) {
            drawValleyMistLines(canvas, w, h, drift, pal, 0.42f, 0.78f, 6, scale);
        }
    }

    private void drawRain(Canvas canvas, int w, int h, long nowMs, boolean storm, ScenePalette pal) {
        int count = storm ? RAIN_COUNT : 68;
        float windDx = storm ? -w * 0.018f : -w * 0.010f;
        rainPaint.setStrokeWidth(Math.max(1.2f, w * 0.00105f));
        int rgb = (pal.mistColor & 0x00FFFFFF);
        int alpha = storm ? 125 : 95;
        rainPaint.setColor((alpha << 24) | rgb);

        float t = (nowMs % 10000L) / 1000f;
        for (int i = 0; i < count; i++) {
            float prog = (rainY[i] + t * rainSpeed[i] * (storm ? 1.55f : 1.15f)) % 1.0f;
            float x = ((rainX[i] - prog * 0.08f + 1.0f) % 1.0f) * w;
            float y = prog * h;
            float len = rainLen[i] * (h / 720f);
            canvas.drawLine(x, y, x + windDx, y + len, rainPaint);
        }
    }

    private void drawLightningBolt(Canvas canvas, int w, int h, float flash, float scale) {
        float bx = w * 0.66f;
        float by = h * 0.05f;
        lightningPath.reset();
        lightningPath.moveTo(bx, by);
        lightningPath.lineTo(bx - w * 0.024f, by + h * 0.13f);
        lightningPath.lineTo(bx + w * 0.010f, by + h * 0.14f);
        lightningPath.lineTo(bx - w * 0.030f, by + h * 0.30f);
        lightningPath.lineTo(bx - w * 0.006f, by + h * 0.31f);
        lightningPath.lineTo(bx - w * 0.044f, by + h * 0.50f);

        lightningBranchPath.reset();
        lightningBranchPath.moveTo(bx - w * 0.024f, by + h * 0.13f);
        lightningBranchPath.lineTo(bx - w * 0.058f, by + h * 0.24f);
        lightningBranchPath.lineTo(bx - w * 0.072f, by + h * 0.31f);

        int glowAlpha = Math.round(145 * flash);
        lightningPaint.setStrokeWidth(6.5f * scale);
        lightningPaint.setColor((glowAlpha << 24) | 0xDCE8FF);
        canvas.drawPath(lightningPath, lightningPaint);
        lightningPaint.setStrokeWidth(3.5f * scale);
        canvas.drawPath(lightningBranchPath, lightningPaint);

        int coreAlpha = Math.round(250 * flash);
        lightningPaint.setStrokeWidth(2.2f * scale);
        lightningPaint.setColor((coreAlpha << 24) | 0xFFFFFF);
        canvas.drawPath(lightningPath, lightningPaint);
        lightningPaint.setStrokeWidth(1.3f * scale);
        canvas.drawPath(lightningBranchPath, lightningPaint);
    }

    private static final class ScenePalette {
        int skyTop, skyMid, skyBottom;
        int bodyTop, bodyBottom, bodyGlow;
        float bodyX, bodyY, bodyAlpha;
        boolean isDay;
        float starAlpha;
        int farMountain, midMountain, nearMountain;
        int contourColor, mistColor, cloudFillColor, cloudStrokeColor;
    }

    /** Anchor stops across the 24h cycle matching wallpapercycle.mp4's color progression. */
    private static final class TimeStop {
        final int minute;
        final int skyTop, skyMid, skyBottom;
        final int farMtn, midMtn, nearMtn;
        final int bodyTop, bodyBot, bodyGlow;
        final float starAlpha;

        TimeStop(int minute, int skyTop, int skyMid, int skyBottom,
                 int farMtn, int midMtn, int nearMtn,
                 int bodyTop, int bodyBot, int bodyGlow, float starAlpha) {
            this.minute = minute;
            this.skyTop = skyTop;
            this.skyMid = skyMid;
            this.skyBottom = skyBottom;
            this.farMtn = farMtn;
            this.midMtn = midMtn;
            this.nearMtn = nearMtn;
            this.bodyTop = bodyTop;
            this.bodyBot = bodyBot;
            this.bodyGlow = bodyGlow;
            this.starAlpha = starAlpha;
        }
    }

    private static final TimeStop[] STOPS = new TimeStop[]{
            // 00:00 Malam (deep midnight indigo matching 20:45 / 19:59 in grid_01.png)
            new TimeStop(0, 0xFF101625, 0xFF192236, 0xFF243048,
                    0xFF222C42, 0xFF192133, 0xFF101624,
                    0xFFFBF6E5, 0xFFE8E0C5, 0xFFFFF5D6, 0.96f),
            // 04:45 Subuh (twilight violet-plum matching 04:51 in grid_01.png)
            new TimeStop(285, 0xFF282038, 0xFF4B3756, 0xFF7A566E,
                    0xFF473652, 0xFF362840, 0xFF251B2E,
                    0xFFD4B5C8, 0xFFB896AC, 0xFFE3C2D4, 0.68f),
            // 05:25 Fajar
            new TimeStop(325, 0xFF6B6478, 0xFF9C7E82, 0xFFD1A289,
                    0xFF7D6B75, 0xFF63545E, 0xFF4B3F47,
                    0xFFE0624B, 0xFFED9E78, 0xFFF29B76, 0.22f),
            // 06:20 Matahari terbit (matching 07:20 in grid_01.png)
            new TimeStop(380, 0xFFDFD7CA, 0xFFE7D8C5, 0xFFE9C5A3,
                    0xFFB7ABA0, 0xFF988E84, 0xFF7A7168,
                    0xFFDC473A, 0xFFED8D68, 0xFFF39C74, 0.0f),
            // 08:45 Pagi
            new TimeStop(525, 0xFFF1EEE6, 0xFFEEE9DD, 0xFFE4E0D4,
                    0xFFB6BEB8, 0xFF97A39D, 0xFF7A8680,
                    0xFFDA3F37, 0xFFEB8466, 0xFFF09375, 0.0f),
            // 12:00 Siang (warm ivory-cream sky & sage-slate ridges matching 12:07 in grid_01.png)
            new TimeStop(720, 0xFFF4F1E8, 0xFFF1EDE2, 0xFFE6E2D6,
                    0xFFB8C0BA, 0xFF99A59F, 0xFF7A8781,
                    0xFFDC3B34, 0xFFEC8064, 0xFFF19072, 0.0f),
            // 15:40 Sore (warm parchment cream matching 15:40 / 16:01 in grid_01.png & grid_02.png)
            new TimeStop(940, 0xFFF0EADF, 0xFFEDE2D1, 0xFFE5D2BE,
                    0xFFB5BCB6, 0xFF969F99, 0xFF78827C,
                    0xFFDE433A, 0xFFEC8868, 0xFFF29674, 0.0f),
            // 17:15 Golden hour (warm amber-apricot sky & bronze ridges matching 17:14 in grid_01.png)
            new TimeStop(1035, 0xFFD8B89C, 0xFFE2B48E, 0xFFE6A376,
                    0xFFA69482, 0xFF877667, 0xFF695B4F,
                    0xFFDD4F36, 0xFFEE9262, 0xFFF5A26F, 0.0f),
            // 18:35 Senja
            new TimeStop(1115, 0xFF3E334A, 0xFF5E4662, 0xFF876071,
                    0xFF4A3952, 0xFF392B40, 0xFF281E2E,
                    0xFFC8A9BC, 0xFFAD8DA2, 0xFFD9B8CC, 0.55f),
            // 19:45 Malam (deep midnight indigo matching 19:59 / 20:45 in grid_01.png)
            new TimeStop(1185, 0xFF101625, 0xFF192236, 0xFF243048,
                    0xFF222C42, 0xFF192133, 0xFF101624,
                    0xFFFBF6E5, 0xFFE8E0C5, 0xFFFFF5D6, 0.96f),
            // 24:00 Malam
            new TimeStop(1440, 0xFF101625, 0xFF192236, 0xFF243048,
                    0xFF222C42, 0xFF192133, 0xFF101624,
                    0xFFFBF6E5, 0xFFE8E0C5, 0xFFFFF5D6, 0.96f)
    };

    private static ScenePalette computePalette(int minuteOfDay, int weather) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        TimeStop a = STOPS[0], b = STOPS[STOPS.length - 1];
        for (int i = 0; i < STOPS.length - 1; i++) {
            if (m >= STOPS[i].minute && m <= STOPS[i + 1].minute) {
                a = STOPS[i];
                b = STOPS[i + 1];
                break;
            }
        }
        float t = (b.minute == a.minute) ? 0f : (m - a.minute) / (float) (b.minute - a.minute);

        ScenePalette p = new ScenePalette();
        p.skyTop = Sky.lerpColor(a.skyTop, b.skyTop, t);
        p.skyMid = Sky.lerpColor(a.skyMid, b.skyMid, t);
        p.skyBottom = Sky.lerpColor(a.skyBottom, b.skyBottom, t);
        p.farMountain = Sky.lerpColor(a.farMtn, b.farMtn, t);
        p.midMountain = Sky.lerpColor(a.midMtn, b.midMtn, t);
        p.nearMountain = Sky.lerpColor(a.nearMtn, b.nearMtn, t);
        p.bodyTop = Sky.lerpColor(a.bodyTop, b.bodyTop, t);
        p.bodyBottom = Sky.lerpColor(a.bodyBot, b.bodyBot, t);
        p.bodyGlow = Sky.lerpColor(a.bodyGlow, b.bodyGlow, t);
        p.starAlpha = a.starAlpha + (b.starAlpha - a.starAlpha) * t;

        // Compute Sun (05:15..18:15) vs Moon (18:15..05:15) arc position
        if (m >= 315 && m <= 1095) {
            p.isDay = true;
            float u = (m - 315f) / (1095f - 315f); // 0..1
            p.bodyX = 0.74f - 0.14f * (float) Math.sin(u * Math.PI);
            p.bodyY = 0.56f - 0.36f * (float) Math.sin(u * Math.PI);
            p.bodyAlpha = 1.0f;
        } else {
            p.isDay = false;
            int nightMin = (m > 1095) ? (m - 1095) : (m + 1440 - 1095);
            float u = nightMin / 660f; // 0..1
            p.bodyX = 0.72f - 0.12f * (float) Math.sin(u * Math.PI);
            p.bodyY = 0.48f - 0.25f * (float) Math.sin(u * Math.PI);
            p.bodyAlpha = 0.94f;
        }

        // Apply weather atmospheric grading matching wallpapercycle.mp4
        if (weather == LiveWeatherEngine.WEATHER_CLOUDY || weather == LiveWeatherEngine.WEATHER_RAIN) {
            float overcastMix = (weather == LiveWeatherEngine.WEATHER_RAIN) ? 0.48f : 0.28f;
            int slateTop = p.isDay ? 0xFFDAD8CE : 0xFF1B2230;
            int slateMid = p.isDay ? 0xFFD2D0C6 : 0xFF232B3A;
            int slateBot = p.isDay ? 0xFFC7C6BC : 0xFF2B3445;
            p.skyTop = Sky.lerpColor(p.skyTop, slateTop, overcastMix);
            p.skyMid = Sky.lerpColor(p.skyMid, slateMid, overcastMix);
            p.skyBottom = Sky.lerpColor(p.skyBottom, slateBot, overcastMix);
            p.farMountain = Sky.lerpColor(p.farMountain, 0xFF8C9691, overcastMix * 0.65f);
            p.midMountain = Sky.lerpColor(p.midMountain, 0xFF727C78, overcastMix * 0.65f);
            p.nearMountain = Sky.lerpColor(p.nearMountain, 0xFF58625E, overcastMix * 0.65f);
        } else if (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) {
            // Deep slate-teal storm palette matching 15:56 Badai petir in grid_02.png
            p.skyTop = Sky.lerpColor(p.skyTop, 0xFF535D60, 0.82f);
            p.skyMid = Sky.lerpColor(p.skyMid, 0xFF606A6D, 0.82f);
            p.skyBottom = Sky.lerpColor(p.skyBottom, 0xFF6E787B, 0.82f);
            p.farMountain = Sky.lerpColor(p.farMountain, 0xFF475154, 0.84f);
            p.midMountain = Sky.lerpColor(p.midMountain, 0xFF384144, 0.84f);
            p.nearMountain = Sky.lerpColor(p.nearMountain, 0xFF293033, 0.84f);
        } else if (weather == LiveWeatherEngine.WEATHER_FOG) {
            int fogCol = p.isDay ? 0xFFDFDBD0 : 0xFF323948;
            p.skyTop = Sky.lerpColor(p.skyTop, fogCol, 0.55f);
            p.skyMid = Sky.lerpColor(p.skyMid, fogCol, 0.65f);
            p.skyBottom = Sky.lerpColor(p.skyBottom, fogCol, 0.78f);
            p.farMountain = Sky.lerpColor(p.farMountain, fogCol, 0.65f);
            p.midMountain = Sky.lerpColor(p.midMountain, fogCol, 0.45f);
        }

        // Topographic wave cloud & ridge contour colors matching f24.png and grid_02.png
        if (weather == LiveWeatherEngine.WEATHER_THUNDERSTORM) {
            p.cloudFillColor = 0xFF3E474A;
            p.cloudStrokeColor = 0xFF2B3336;
            p.contourColor = 0xFF8C989C;
        } else if (p.isDay) {
            p.cloudFillColor = Sky.lerpColor(p.farMountain, p.skyMid, 0.45f);
            p.cloudStrokeColor = Sky.lerpColor(p.midMountain, 0xFF58625E, 0.35f);
            p.contourColor = Sky.lerpColor(p.nearMountain, 0xFF3E4744, 0.35f);
        } else {
            p.cloudFillColor = Sky.lerpColor(p.farMountain, 0xFF3A4660, 0.5f);
            p.cloudStrokeColor = 0xFF5C6B8A;
            p.contourColor = 0xFF5A6888;
        }
        p.mistColor = Sky.lerpColor(p.skyBottom, 0xFFFFFFFF, p.isDay ? 0.42f : 0.25f);
        return p;
    }
}
