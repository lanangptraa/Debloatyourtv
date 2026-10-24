package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final Context context;
    private final FrameLayout root;
    private final ImageView image;
    private final LiveWeatherView liveWeatherView;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context;
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        liveWeatherView = new LiveWeatherView(context);
        liveWeatherView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        liveWeatherView.setVisibility(View.GONE);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(liveWeatherView, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (LiveWeatherEngine.isLiveWallpaperSelected(context)) {
                activateLiveWeatherMode();
            } else if (!files.isEmpty()) {
                showFirst();
            }
        }
    }

    private void activateLiveWeatherMode() {
        image.setVisibility(View.GONE);
        liveWeatherView.setVisibility(View.VISIBLE);
        root.setBackground(null);
        displayed = LiveWeatherEngine.CHOICE_LIVE_WEATHER;
        if (caption != null) caption.setAlpha(0f);
        int minute = LiveWeatherEngine.activeMinuteOfDay(context);
        int weather = LiveWeatherEngine.activeWeather(context);
        liveWeatherView.updateFrostedGlassSnapshot(width, height, minute, weather, System.currentTimeMillis());
        liveWeatherView.invalidate();
        invalidateTree(root);
        LiveWeatherEngine.fetchIfNeeded(context, () -> {
            if (!closed && LiveWeatherEngine.isLiveWallpaperSelected(context)) {
                int m = LiveWeatherEngine.activeMinuteOfDay(context);
                int w = LiveWeatherEngine.activeWeather(context);
                liveWeatherView.updateFrostedGlassSnapshot(width, height, m, w, System.currentTimeMillis());
                liveWeatherView.invalidate();
                invalidateTree(root);
            }
        });
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        int currentSlot = WallpaperRotation.currentSlot();
        int savedSlot = preferences.getInt("last_slot", -1);
        String previous = preferences.getString("image", "");
        File first;
        if (savedSlot != currentSlot) {
            first = WallpaperRotation.pickForSlot(files, currentSlot, previous, true);
        } else {
            first = WallpaperRotation.find(files, previous);
            if (first == null) first = WallpaperRotation.pickForSlot(files, currentSlot, previous, true);
        }
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous) || savedSlot != currentSlot) {
                    preferences.edit().putString("image", displayed)
                            .putInt("last_slot", currentSlot)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    /** Immediately reloads the selected or time-slot wallpaper without recreating MainActivity. */
    void reloadNow() {
        active = true;
        update();
    }

    void update() {
        if (!active || closed) return;
        if (LiveWeatherEngine.isLiveWallpaperSelected(context)) {
            activateLiveWeatherMode();
            return;
        }
        if (liveWeatherView.getVisibility() == View.VISIBLE) {
            liveWeatherView.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
        }
        if (loading || files.isEmpty()) return;
        int currentSlot = WallpaperRotation.currentSlot();
        int savedSlot = preferences.getInt("last_slot", -1);
        String previous = preferences.getString("image", "");
        long changed = preferences.getLong("changed", 0);
        File selected;
        if (savedSlot != currentSlot) {
            selected = WallpaperRotation.pickForSlot(files, currentSlot, displayed, true);
        } else {
            selected = WallpaperRotation.find(files, previous);
            if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
                selected = WallpaperRotation.pickForSlot(files, currentSlot, previous, false);
            }
        }
        if (selected == null) selected = WallpaperRotation.pick(files, previous, order, random);
        if (selected == null) return;
        if (selected.getName().equals(displayed)) {
            if (savedSlot != currentSlot) {
                preferences.edit().putInt("last_slot", currentSlot).apply();
            }
            return;
        }
        File next = selected;
        final int targetSlot = currentSlot;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, true);
                displayed = next.getName();
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putInt("last_slot", targetSlot)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(context.getResources().getFont(R.font.outfit_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap, boolean animate) {
        Bitmap old = current;
        current = bitmap;
        if (animate && old != null && !old.isRecycled()) {
            Drawable[] layers = new Drawable[]{
                    new BitmapDrawable(root.getResources(), old),
                    new BitmapDrawable(root.getResources(), bitmap)
            };
            TransitionDrawable transition = new TransitionDrawable(layers);
            transition.setCrossFadeEnabled(false);
            image.setImageDrawable(transition);
            transition.startTransition(380);
            invalidateTree(root);
            if (old != bitmap) image.postDelayed(old::recycle, 650);
        } else {
            image.setImageBitmap(bitmap);
            invalidateTree(root);
            if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
        }
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                invalidateTree(vg.getChildAt(i));
            }
        }
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            FrostedGlass.updateFromWallpaper(decoded, this.width, this.height);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
