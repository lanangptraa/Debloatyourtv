package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen tvOS-inspired "Choose Wallpaper" picker with:
 * 1. "Live Weather" interactive procedural wallpaper panel (Time & Weather simulator)
 * 2. Photo Wallpaper collections ("Siklus Foto", "Landscape", "City & Night", "Minimal") preserved intact.
 */
public final class WallpaperPickerActivity extends Activity {

    private static final int CAT_LIVE_WEATHER = 0;
    private static final int CAT_AUTO_CYCLE = 1;
    private static final int CAT_LANDSCAPE = 2;
    private static final int CAT_CITY_NIGHT = 3;
    private static final int CAT_MINIMAL = 4;
    private static final Interpolator EASE_OUT = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private final List<File> allFiles = new ArrayList<>();
    private final List<File> shownFiles = new ArrayList<>();
    private final Map<String, Bitmap> thumbCache = new HashMap<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);

    private RecyclerView grid;
    private GridAdapter adapter;
    private View liveWeatherPanel;
    private LiveWeatherView livePreviewView;
    private TextView previewClockText, previewWeatherText, previewLocText, liveStatusDesc;
    private View btnApplyLiveAuto, btnApplyLiveCustom, btnTimeScrubber;
    private TextView scrubberTimeText, scrubberPhaseText, labelWaktuKicker;
    private TimeScrubberView timeScrubberBar;
    private TextView btnWeatherClear, btnWeatherPartly, btnWeatherCloudy;
    private TextView btnWeatherRain, btnWeatherStorm, btnWeatherFog;

    private View catLiveWeather, catAutoCycle, catLandscape, catCityNight, catMinimal;
    private TextView wallpaperSubtitle;
    private int activeCategory = CAT_LIVE_WEATHER;
    private String currentSelection = "";
    private float density;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_picker);
        density = getResources().getDisplayMetrics().density;

        SharedPreferences settings = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        SharedPreferences rotation = getSharedPreferences("wallpaper_rotation", MODE_PRIVATE);
        String savedChoice = settings.getString(Config.WALLPAPER_CHOICE_KEY, "");
        currentSelection = LiveWeatherEngine.CHOICE_LIVE_WEATHER.equals(savedChoice)
                ? LiveWeatherEngine.CHOICE_LIVE_WEATHER
                : rotation.getString("image", savedChoice);

        File dir = Config.dir(this);
        if (dir != null) {
            allFiles.addAll(WallpaperRotation.images(new File(dir, "wallpapers")));
        }

        wallpaperSubtitle = findViewById(R.id.wallpaperSubtitle);
        catLiveWeather = findViewById(R.id.catLiveWeather);
        catAutoCycle = findViewById(R.id.catAutoCycle);
        catLandscape = findViewById(R.id.catLandscape);
        catCityNight = findViewById(R.id.catCityNight);
        catMinimal = findViewById(R.id.catMinimal);

        liveWeatherPanel = findViewById(R.id.liveWeatherPanel);
        livePreviewView = findViewById(R.id.livePreviewView);
        if (livePreviewView != null) {
            livePreviewView.setPreviewMode(true);
            livePreviewView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 11.5f * density);
                }
            });
            livePreviewView.setClipToOutline(true);
        }

        previewClockText = findViewById(R.id.previewClockText);
        previewWeatherText = findViewById(R.id.previewWeatherText);
        previewLocText = findViewById(R.id.previewLocText);
        liveStatusDesc = findViewById(R.id.liveStatusDesc);
        btnApplyLiveAuto = findViewById(R.id.btnApplyLiveAuto);
        btnApplyLiveCustom = findViewById(R.id.btnApplyLiveCustom);
        btnTimeScrubber = findViewById(R.id.btnTimeScrubber);
        scrubberTimeText = findViewById(R.id.scrubberTimeText);
        scrubberPhaseText = findViewById(R.id.scrubberPhaseText);
        labelWaktuKicker = findViewById(R.id.labelWaktuKicker);
        timeScrubberBar = findViewById(R.id.timeScrubberBar);

        btnWeatherClear = findViewById(R.id.btnWeatherClear);
        btnWeatherPartly = findViewById(R.id.btnWeatherPartly);
        btnWeatherCloudy = findViewById(R.id.btnWeatherCloudy);
        btnWeatherRain = findViewById(R.id.btnWeatherRain);
        btnWeatherStorm = findViewById(R.id.btnWeatherStorm);
        btnWeatherFog = findViewById(R.id.btnWeatherFog);

        setUpLiveWeatherControls();

        bindCategory(catLiveWeather, R.id.iconCatLiveWeather, R.id.titleCatLiveWeather, CAT_LIVE_WEATHER);
        bindCategory(catAutoCycle, R.id.iconCatAutoCycle, R.id.titleCatAutoCycle, CAT_AUTO_CYCLE);
        bindCategory(catLandscape, R.id.iconCatLandscape, R.id.titleCatLandscape, CAT_LANDSCAPE);
        bindCategory(catCityNight, R.id.iconCatCityNight, R.id.titleCatCityNight, CAT_CITY_NIGHT);
        bindCategory(catMinimal, R.id.iconCatMinimal, R.id.titleCatMinimal, CAT_MINIMAL);

        grid = findViewById(R.id.wallpaperGrid);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setItemAnimator(null);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        LiveWeatherEngine.fetchIfNeeded(this, this::refreshLivePreviewUi);

        selectCategory(CAT_LIVE_WEATHER);
        catLiveWeather.requestFocus();
    }

    private void setUpLiveWeatherControls() {
        bindPillFocus(btnApplyLiveAuto, () -> applyLiveWallpaper(true));
        bindPillFocus(btnApplyLiveCustom, () -> applyLiveWallpaper(false));

        if (btnTimeScrubber != null) {
            btnTimeScrubber.setOnFocusChangeListener((v, focused) -> {
                if (scrubberTimeText != null) scrubberTimeText.setTextColor(focused ? 0xFF14171C : 0xFFFFFFFF);
                if (scrubberPhaseText != null) scrubberPhaseText.setTextColor(focused ? 0xFF14171C : 0xE6FFFFFF);
                if (labelWaktuKicker != null) labelWaktuKicker.setTextColor(focused ? 0xB314171C : 0x99FFFFFF);
                if (timeScrubberBar != null) timeScrubberBar.setFocusedState(focused);
            });
            btnTimeScrubber.setOnClickListener(v -> {
                LiveWeatherEngine.setMinuteOverride(this, -1);
                refreshLivePreviewUi();
                Toast.makeText(this, "Waktu dikembalikan ke Jam TV Otomatis", Toast.LENGTH_SHORT).show();
            });
            btnTimeScrubber.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int cur = LiveWeatherEngine.activeMinuteOfDay(this);
                    int next = (cur - 20 + 1440) % 1440;
                    LiveWeatherEngine.setMinuteOverride(this, next);
                    refreshLivePreviewUi();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int cur = LiveWeatherEngine.activeMinuteOfDay(this);
                    int next = (cur + 20) % 1440;
                    LiveWeatherEngine.setMinuteOverride(this, next);
                    refreshLivePreviewUi();
                    return true;
                }
                return false;
            });
        }

        bindWeatherButton(btnWeatherClear, LiveWeatherEngine.WEATHER_CLEAR);
        bindWeatherButton(btnWeatherPartly, LiveWeatherEngine.WEATHER_PARTLY_CLOUDY);
        bindWeatherButton(btnWeatherCloudy, LiveWeatherEngine.WEATHER_CLOUDY);
        bindWeatherButton(btnWeatherRain, LiveWeatherEngine.WEATHER_RAIN);
        bindWeatherButton(btnWeatherStorm, LiveWeatherEngine.WEATHER_THUNDERSTORM);
        bindWeatherButton(btnWeatherFog, LiveWeatherEngine.WEATHER_FOG);

        refreshLivePreviewUi();
    }

    private void bindPillFocus(View btn, Runnable onClick) {
        if (!(btn instanceof TextView)) return;
        TextView tv = (TextView) btn;
        tv.setOnFocusChangeListener((v, focused) -> {
            tv.setTextColor(focused ? 0xFF14171C : 0xFFFFFFFF);
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                    .setDuration(130).setInterpolator(EASE_OUT).start();
        });
        tv.setOnClickListener(v -> onClick.run());
    }

    private void bindWeatherButton(TextView btn, int weatherMode) {
        if (btn == null) return;
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                LiveWeatherEngine.setWeatherOverride(this, weatherMode);
            }
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                    .setDuration(130).setInterpolator(EASE_OUT).start();
            refreshLivePreviewUi();
        });
        btn.setOnClickListener(v -> {
            LiveWeatherEngine.setWeatherOverride(this, weatherMode);
            refreshLivePreviewUi();
            applyLiveWallpaper(false);
        });
    }

    private void refreshLivePreviewUi() {
        int minute = LiveWeatherEngine.activeMinuteOfDay(this);
        int weather = LiveWeatherEngine.activeWeather(this);
        int overrideMin = LiveWeatherEngine.getMinuteOverride(this);
        int overrideWeather = LiveWeatherEngine.getWeatherOverride(this);

        String timeStr = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
        String phase = LiveWeatherEngine.phaseName(minute);

        if (scrubberTimeText != null) scrubberTimeText.setText(timeStr);
        if (scrubberPhaseText != null) {
            scrubberPhaseText.setText(phase + (overrideMin >= 0 ? " (Simulasi)" : " (Otomatis)"));
        }
        if (timeScrubberBar != null) timeScrubberBar.setMinuteOfDay(minute);

        if (previewClockText != null) previewClockText.setText(timeStr);
        if (previewWeatherText != null) previewWeatherText.setText(LiveWeatherEngine.homeWeatherSummary(this));
        if (previewLocText != null) previewLocText.setText(LiveWeatherEngine.phaseAndNextSummary(minute));

        boolean darkText = weather != LiveWeatherEngine.WEATHER_THUNDERSTORM && minute >= 360 && minute <= 1075;
        int primary = darkText ? 0xFF1E232B : 0xFFFFFFFF;
        int secondary = darkText ? 0xD9282F3A : 0xE6FFFFFF;
        int muted = darkText ? 0xB3323A46 : 0xB8FFFFFF;
        if (previewClockText != null) previewClockText.setTextColor(primary);
        if (previewWeatherText != null) previewWeatherText.setTextColor(secondary);
        if (previewLocText != null) previewLocText.setTextColor(muted);

        if (liveStatusDesc != null) {
            String modeDesc = (overrideWeather == LiveWeatherEngine.WEATHER_AUTO && overrideMin < 0)
                    ? "Mode Otomatis: Real-time mengikuti cuaca & waktu (" + LiveWeatherEngine.LOCATION_NAME + ")"
                    : "Pratinjau: " + phase + " (" + timeStr + ") · Cuaca: " + LiveWeatherEngine.weatherLabelId(weather);
            liveStatusDesc.setText(modeDesc);
        }

        updateWeatherButtonState(btnWeatherClear, weather == LiveWeatherEngine.WEATHER_CLEAR);
        updateWeatherButtonState(btnWeatherPartly, weather == LiveWeatherEngine.WEATHER_PARTLY_CLOUDY);
        updateWeatherButtonState(btnWeatherCloudy, weather == LiveWeatherEngine.WEATHER_CLOUDY);
        updateWeatherButtonState(btnWeatherRain, weather == LiveWeatherEngine.WEATHER_RAIN);
        updateWeatherButtonState(btnWeatherStorm, weather == LiveWeatherEngine.WEATHER_THUNDERSTORM);
        updateWeatherButtonState(btnWeatherFog, weather == LiveWeatherEngine.WEATHER_FOG);

        if (livePreviewView != null) livePreviewView.invalidate();
    }

    private static void updateWeatherButtonState(TextView btn, boolean selected) {
        if (btn == null) return;
        btn.setSelected(selected);
        boolean focused = btn.isFocused();
        btn.setTextColor((focused || selected) ? 0xFF14171C : 0xD9FFFFFF);
    }

    private void applyLiveWallpaper(boolean resetToAuto) {
        if (resetToAuto) {
            LiveWeatherEngine.setMinuteOverride(this, -1);
            LiveWeatherEngine.setWeatherOverride(this, LiveWeatherEngine.WEATHER_AUTO);
        }
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .putString(Config.WALLPAPER_CHOICE_KEY, LiveWeatherEngine.CHOICE_LIVE_WEATHER)
                .commit();
        String msg = resetToAuto
                ? "Live Weather Otomatis (" + LiveWeatherEngine.LOCATION_NAME + ") diaktifkan"
                : "Live Weather (" + LiveWeatherEngine.weatherLabelId(LiveWeatherEngine.activeWeather(this)) + ") diaktifkan";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_WALLPAPER_CHANGED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void bindCategory(View catView, int iconId, int titleId, int category) {
        ImageView icon = findViewById(iconId);
        TextView title = findViewById(titleId);
        catView.setOnFocusChangeListener((v, focused) -> {
            updateCategoryStyle(catView, icon, title, focused, activeCategory == category);
            if (focused && activeCategory != category) {
                selectCategory(category);
            }
        });
        catView.setOnClickListener(v -> {
            selectCategory(category);
            if (category == CAT_LIVE_WEATHER) {
                if (btnApplyLiveAuto != null) btnApplyLiveAuto.requestFocus();
            } else if (category == CAT_AUTO_CYCLE) {
                applyAutoCycleNow();
            } else {
                focusFirstGridItem();
            }
        });
        catView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (category == CAT_LIVE_WEATHER) {
                    if (btnApplyLiveAuto != null) btnApplyLiveAuto.requestFocus();
                } else {
                    focusFirstGridItem();
                }
                return true;
            }
            return false;
        });
    }

    private void focusFirstGridItem() {
        if (shownFiles.isEmpty()) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
        if (vh != null) {
            View frame = vh.itemView.findViewById(R.id.cardFrame);
            if (frame != null) frame.requestFocus();
        }
    }

    private void selectCategory(int category) {
        activeCategory = category;
        updateCategoryStyle(catLiveWeather, findViewById(R.id.iconCatLiveWeather),
                findViewById(R.id.titleCatLiveWeather), catLiveWeather.isFocused(), category == CAT_LIVE_WEATHER);
        updateCategoryStyle(catAutoCycle, findViewById(R.id.iconCatAutoCycle),
                findViewById(R.id.titleCatAutoCycle), catAutoCycle.isFocused(), category == CAT_AUTO_CYCLE);
        updateCategoryStyle(catLandscape, findViewById(R.id.iconCatLandscape),
                findViewById(R.id.titleCatLandscape), catLandscape.isFocused(), category == CAT_LANDSCAPE);
        updateCategoryStyle(catCityNight, findViewById(R.id.iconCatCityNight),
                findViewById(R.id.titleCatCityNight), catCityNight.isFocused(), category == CAT_CITY_NIGHT);
        updateCategoryStyle(catMinimal, findViewById(R.id.iconCatMinimal),
                findViewById(R.id.titleCatMinimal), catMinimal.isFocused(), category == CAT_MINIMAL);

        if (category == CAT_LIVE_WEATHER) {
            if (liveWeatherPanel != null) liveWeatherPanel.setVisibility(View.VISIBLE);
            if (grid != null) grid.setVisibility(View.GONE);
            if (wallpaperSubtitle != null) {
                wallpaperSubtitle.setText("Wallpaper TV yang hidup · Ikut cuaca & waktu (" + LiveWeatherEngine.LOCATION_NAME + ")");
            }
            refreshLivePreviewUi();
            return;
        }

        if (liveWeatherPanel != null) liveWeatherPanel.setVisibility(View.GONE);
        if (grid != null) grid.setVisibility(View.VISIBLE);

        if (wallpaperSubtitle != null) {
            int slot = WallpaperRotation.currentSlot();
            if (category == CAT_AUTO_CYCLE) {
                wallpaperSubtitle.setText("Siklus Foto Otomatis · Waktu saat ini: "
                        + WallpaperRotation.slotLabel(slot) + " (Klik OK untuk sinkronisasi foto)");
            } else {
                wallpaperSubtitle.setText("Koleksi foto resolusi tinggi (dapat diganti kapan saja dengan Live Weather).");
            }
        }

        shownFiles.clear();
        if (category == CAT_AUTO_CYCLE) {
            List<File> ordered = new ArrayList<>(allFiles);
            Collections.sort(ordered, (a, b) -> {
                boolean aHero = a.getName().startsWith("00-") || a.getName().startsWith("99-");
                boolean bHero = b.getName().startsWith("00-") || b.getName().startsWith("99-");
                if (aHero != bHero) return aHero ? -1 : 1;
                int sa = WallpaperRotation.slotOfFile(a.getName());
                int sb = WallpaperRotation.slotOfFile(b.getName());
                if (sa != sb) return Integer.compare(sa, sb);
                return a.getName().compareToIgnoreCase(b.getName());
            });
            shownFiles.addAll(ordered);
        } else {
            for (File f : allFiles) {
                if (categoryOf(f.getName()) == category) {
                    shownFiles.add(f);
                }
            }
            if (shownFiles.isEmpty()) shownFiles.addAll(allFiles);
        }
        adapter.notifyDataSetChanged();
    }

    private static void updateCategoryStyle(View container, ImageView icon, TextView title,
                                            boolean focused, boolean selected) {
        if (container == null) return;
        container.setSelected(selected);
        int fg = focused ? 0xFF14171C : (selected ? 0xFFFFFFFF : 0xB3FFFFFF);
        if (icon != null) icon.setColorFilter(fg);
        if (title != null) title.setTextColor(fg);
    }

    private static int categoryOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("night") || lower.contains("city") || lower.contains("aurora")
                || lower.contains("starlit") || lower.contains("moonlit") || lower.contains("dusk")) {
            return CAT_CITY_NIGHT;
        }
        if (lower.contains("misty") || lower.contains("desert") || lower.contains("dunes")
                || lower.contains("winter") || lower.contains("calm") || lower.contains("golden")) {
            return CAT_MINIMAL;
        }
        return CAT_LANDSCAPE;
    }

    private static String prettyTitle(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceFirst("^\\d+-", "");
        String[] parts = base.split("[-_]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : fileName;
    }

    private void applyAutoCycleNow() {
        int slot = WallpaperRotation.currentSlot();
        File picked = WallpaperRotation.pickForSlot(allFiles, slot, "", true);
        if (picked == null && !allFiles.isEmpty()) picked = allFiles.get(0);
        if (picked == null) return;
        String name = picked.getName();
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .putString(Config.WALLPAPER_CHOICE_KEY, "auto_cycle")
                .commit();
        getSharedPreferences("wallpaper_rotation", MODE_PRIVATE)
                .edit()
                .putString("image", name)
                .putInt("last_slot", slot)
                .putLong("changed", System.currentTimeMillis())
                .commit();
        Toast.makeText(this, "Siklus Foto Aktif (" + WallpaperRotation.slotLabel(slot) + ")", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_WALLPAPER_CHANGED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void applyWallpaper(File file) {
        String name = file.getName();
        int slot = WallpaperRotation.currentSlot();
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .putString(Config.WALLPAPER_CHOICE_KEY, name)
                .commit();
        getSharedPreferences("wallpaper_rotation", MODE_PRIVATE)
                .edit()
                .putString("image", name)
                .putInt("last_slot", slot)
                .putLong("changed", System.currentTimeMillis())
                .commit();
        Toast.makeText(this, prettyTitle(name) + " (" + WallpaperRotation.slotShortName(WallpaperRotation.slotOfFile(name)) + ") diterapkan", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_WALLPAPER_CHANGED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    private final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_wallpaper_card, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            File file = shownFiles.get(position);
            String name = file.getName();
            h.boundName = name;
            String baseTitle = prettyTitle(name);
            int fileSlot = WallpaperRotation.slotOfFile(name);
            String slotTag = WallpaperRotation.slotShortName(fileSlot);
            h.title.setText(activeCategory == CAT_AUTO_CYCLE ? (slotTag + " · " + baseTitle) : baseTitle);
            h.cardFrame.setSelected(name.equals(currentSelection));

            Bitmap cached = thumbCache.get(name);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                h.thumb.setImageDrawable(null);
                loader.execute(() -> {
                    Bitmap decoded = decodeThumb(file);
                    if (decoded != null) {
                        h.thumb.post(() -> {
                            thumbCache.put(name, decoded);
                            if (name.equals(h.boundName)) {
                                h.thumb.setImageBitmap(decoded);
                            }
                        });
                    }
                });
            }

            h.cardFrame.setOnClickListener(v -> applyWallpaper(file));
        }

        @Override
        public int getItemCount() {
            return shownFiles.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View cardFrame;
            final ImageView thumb;
            final TextView title;
            String boundName;

            Holder(View v) {
                super(v);
                cardFrame = v.findViewById(R.id.cardFrame);
                thumb = v.findViewById(R.id.thumb);
                title = v.findViewById(R.id.title);

                thumb.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 11.5f * density);
                    }
                });
                thumb.setClipToOutline(true);

                cardFrame.setOnFocusChangeListener((view, focused) -> {
                    title.setTextColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
                    view.animate().cancel();
                    view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                            .setDuration(140).setInterpolator(EASE_OUT).start();
                });

                cardFrame.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int pos = getBindingAdapterPosition();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos != RecyclerView.NO_POSITION && pos % 3 == 0) {
                        if (activeCategory == CAT_AUTO_CYCLE) catAutoCycle.requestFocus();
                        else if (activeCategory == CAT_CITY_NIGHT) catCityNight.requestFocus();
                        else if (activeCategory == CAT_MINIMAL) catMinimal.requestFocus();
                        else catLandscape.requestFocus();
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private static Bitmap decodeThumb(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (w / (sample * 2) >= 360 && h / (sample * 2) >= 200) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }
}
