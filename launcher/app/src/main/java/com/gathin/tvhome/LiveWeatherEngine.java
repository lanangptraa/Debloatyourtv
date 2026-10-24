package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-time weather and solar/time-of-day state engine.
 * Configure coordinates (LATITUDE, LONGITUDE, LOCATION_NAME) below.
 * Supports automatic Open-Meteo synchronization plus manual time/weather simulation in WallpaperPickerActivity.
 */
final class LiveWeatherEngine {

    static final String PREFS_NAME = "live_weather_prefs";
    static final String CHOICE_LIVE_WEATHER = "live_weather";

    // Configure your location coordinates & display name below (e.g. -6.2088, 106.8456 for Jakarta)
    static final double LATITUDE = 0.0;
    static final double LONGITUDE = 0.0;
    static final String LOCATION_NAME = "inputyourlocation";

    // 6 Weather modes matching wallpapercycle.mp4
    static final int WEATHER_AUTO = -1;
    static final int WEATHER_CLEAR = 0;          // Cerah
    static final int WEATHER_PARTLY_CLOUDY = 1;  // Cerah berawan
    static final int WEATHER_CLOUDY = 2;         // Mendung
    static final int WEATHER_RAIN = 3;           // Hujan
    static final int WEATHER_THUNDERSTORM = 4;   // Badai petir
    static final int WEATHER_FOG = 5;            // Kabut

    private static final int[] PHASE_STARTS = new int[]{
            240,  // 04:00 Subuh
            310,  // 05:10 Fajar
            360,  // 06:00 Matahari terbit
            465,  // 07:45 Pagi
            660,  // 11:00 Siang
            900,  // 15:00 Sore
            1005, // 16:45 Golden hour
            1085, // 18:05 Senja
            1140  // 19:00 Malam
    };

    private static final String[] PHASE_NAMES = new String[]{
            "Subuh",
            "Fajar",
            "Matahari terbit",
            "Pagi",
            "Siang",
            "Sore",
            "Golden hour",
            "Senja",
            "Malam"
    };

    private static final long FETCH_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean fetching;

    private LiveWeatherEngine() {
    }

    static boolean isLiveWallpaperSelected(Context context) {
        SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return CHOICE_LIVE_WEATHER.equals(settings.getString(Config.WALLPAPER_CHOICE_KEY, ""));
    }

    /** Returns current minute of day (0..1439), or override_minute if set in preferences. */
    static int activeMinuteOfDay(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int overrideMin = prefs.getInt("override_minute", -1);
        if (overrideMin >= 0 && overrideMin < 1440) {
            return overrideMin;
        }
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /** Returns the active weather state (0..5), respecting manual override if set. */
    static int activeWeather(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int override = prefs.getInt("override_weather", WEATHER_AUTO);
        if (override >= WEATHER_CLEAR && override <= WEATHER_FOG) {
            return override;
        }
        return prefs.getInt("live_weather_state", WEATHER_PARTLY_CLOUDY);
    }

    static int getWeatherOverride(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("override_weather", WEATHER_AUTO);
    }

    static void setWeatherOverride(Context context, int weatherOrAuto) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("override_weather", weatherOrAuto)
                .apply();
    }

    static int getMinuteOverride(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("override_minute", -1);
    }

    static void setMinuteOverride(Context context, int minuteOrNegOne) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("override_minute", minuteOrNegOne)
                .apply();
    }

    static int tempC(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("temp_c", 29);
    }

    static int highC(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("high_c", 31);
    }

    static int lowC(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("low_c", 24);
    }

    /** Indonesian label for the 9 time-of-day phases shown in wallpapercycle.mp4. */
    static String phaseName(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        if (m >= 240 && m < 310) return "Subuh";             // 04:00 - 05:09
        if (m >= 310 && m < 360) return "Fajar";             // 05:10 - 05:59
        if (m >= 360 && m < 465) return "Matahari terbit";   // 06:00 - 07:44
        if (m >= 465 && m < 660) return "Pagi";              // 07:45 - 10:59
        if (m >= 660 && m < 900) return "Siang";             // 11:00 - 14:59
        if (m >= 900 && m < 1005) return "Sore";             // 15:00 - 16:44
        if (m >= 1005 && m < 1085) return "Golden hour";     // 16:45 - 18:04
        if (m >= 1085 && m < 1140) return "Senja";           // 18:05 - 18:59
        return "Malam";                                      // 19:00 - 03:59
    }

    /**
     * Returns the upcoming solar phase transition & countdown string matching wallpapercycle.mp4,
     * e.g. "Sore · Golden hour 16:45 · inputyourlocation".
     */
    static String phaseAndNextSummary(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        String current = phaseName(m);
        int nextIdx = 0;
        for (int i = 0; i < PHASE_STARTS.length; i++) {
            if (PHASE_STARTS[i] > m) {
                nextIdx = i;
                break;
            }
        }
        int nextMin = PHASE_STARTS[nextIdx];
        String nextName = PHASE_NAMES[nextIdx];
        int diff = (nextMin > m) ? (nextMin - m) : (1440 - m + nextMin);
        String nextTime = String.format(Locale.ROOT, "%02d:%02d", nextMin / 60, nextMin % 60);
        String dur = (diff >= 60)
                ? (diff / 60) + "j " + (diff % 60 > 0 ? (diff % 60) + "m" : "")
                : diff + " mnt";
        return current + " \u00B7 " + nextName + " " + nextTime + " (" + dur.trim() + ") \u00B7 " + LOCATION_NAME;
    }

    static String weatherLabelId(int weather) {
        switch (weather) {
            case WEATHER_CLEAR:
                return "Cerah";
            case WEATHER_PARTLY_CLOUDY:
                return "Cerah berawan";
            case WEATHER_CLOUDY:
                return "Mendung";
            case WEATHER_RAIN:
                return "Hujan";
            case WEATHER_THUNDERSTORM:
                return "Badai petir";
            case WEATHER_FOG:
                return "Kabut";
            default:
                return "Cerah berawan";
        }
    }

    /** Returns the vector icon resource matching the active weather and day/night cycle. */
    static int weatherIconRes(Context context) {
        int w = activeWeather(context);
        int m = activeMinuteOfDay(context);
        boolean isDay = m >= 330 && m < 1100;
        switch (w) {
            case WEATHER_CLEAR:
                return isDay ? R.drawable.ic_weather_clear_day : R.drawable.ic_weather_clear_night;
            case WEATHER_PARTLY_CLOUDY:
                return R.drawable.ic_weather_cloud;
            case WEATHER_CLOUDY:
                return R.drawable.ic_weather_overcast;
            case WEATHER_RAIN:
                return R.drawable.ic_weather_rain;
            case WEATHER_THUNDERSTORM:
                return R.drawable.ic_weather_storm;
            case WEATHER_FOG:
                return R.drawable.ic_weather_fog;
            default:
                return R.drawable.ic_weather_cloud;
        }
    }

    /** Formatted weather summary line for the Home Screen widget. */
    static String homeWeatherSummary(Context context) {
        int w = activeWeather(context);
        int t = tempC(context);
        int h = highC(context);
        int l = lowC(context);
        return t + "\u00B0  " + weatherLabelId(w) + "  H:" + h + "\u00B0 L:" + l + "\u00B0";
    }

    /** True when the Live Weather sky is in its bright daytime cream/ivory phase and needs dark text. */
    static boolean needsDarkText(Context context) {
        if (!isLiveWallpaperSelected(context)) return false;
        int m = activeMinuteOfDay(context);
        int w = activeWeather(context);
        if (w == WEATHER_THUNDERSTORM) return false;
        return m >= 360 && m <= 1075; // 06:00 to 17:55 has light cream/warm sky
    }

    /** Fetches real-time weather from Open-Meteo if configured and due. */
    static void fetchIfNeeded(Context context, Runnable onUpdated) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastFetch = prefs.getLong("last_fetch_ms", 0L);
        long now = System.currentTimeMillis();
        if (fetching || (now - lastFetch < FETCH_INTERVAL_MS && lastFetch > 0L)) {
            return;
        }
        if (LATITUDE == 0.0 && LONGITUDE == 0.0) {
            return;
        }
        fetching = true;
        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + LATITUDE
                        + "&longitude=" + LONGITUDE
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1";
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject root = new JSONObject(sb.toString());
                    JSONObject current = root.optJSONObject("current");
                    JSONObject daily = root.optJSONObject("daily");
                    int temp = 29;
                    int code = 1;
                    if (current != null) {
                        temp = (int) Math.round(current.optDouble("temperature_2m", 29.0));
                        code = current.optInt("weather_code", 1);
                    }
                    int high = Math.max(temp, 31);
                    int low = Math.min(temp, 24);
                    if (daily != null) {
                        JSONArray maxArr = daily.optJSONArray("temperature_2m_max");
                        JSONArray minArr = daily.optJSONArray("temperature_2m_min");
                        if (maxArr != null && maxArr.length() > 0) {
                            high = (int) Math.round(maxArr.optDouble(0, high));
                        }
                        if (minArr != null && minArr.length() > 0) {
                            low = (int) Math.round(minArr.optDouble(0, low));
                        }
                    }
                    int mappedWeather = mapWmoCode(code);
                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putInt("temp_c", temp)
                            .putInt("high_c", high)
                            .putInt("low_c", low)
                            .putInt("live_weather_state", mappedWeather)
                            .putLong("last_fetch_ms", System.currentTimeMillis())
                            .apply();
                    if (onUpdated != null) {
                        MAIN_HANDLER.post(onUpdated);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                fetching = false;
            }
        });
    }

    private static int mapWmoCode(int wmo) {
        if (wmo == 0) return WEATHER_CLEAR;
        if (wmo == 1 || wmo == 2) return WEATHER_PARTLY_CLOUDY;
        if (wmo == 3) return WEATHER_CLOUDY;
        if (wmo >= 45 && wmo <= 48) return WEATHER_FOG;
        if (wmo >= 95) return WEATHER_THUNDERSTORM;
        if (wmo >= 51 && wmo <= 86) return WEATHER_RAIN;
        return WEATHER_PARTLY_CLOUDY;
    }
}
