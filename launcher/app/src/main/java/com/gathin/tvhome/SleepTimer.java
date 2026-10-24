package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * TV Sleep Timer controlled from the Control Center "Timer" tile.
 * - Normal click: increments the sleep timer by 30 minutes up to 3 hours (30m -> 60m -> 90m -> 120m -> 150m -> 180m -> Off).
 * - Fast double-click (<= 420ms) OR Long-press (hold OK): immediately deactivates the sleep timer (Off).
 * - Uses both Handler and AlarmManager (ELAPSED_REALTIME_WAKEUP) + InputKeyService & native PowerService standby.
 */
final class SleepTimer {

    private static final int STEP_MINUTES = 30;
    private static final int MAX_MINUTES = 180; // 3 hours
    private static final long DOUBLE_CLICK_WINDOW_MS = 420L;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static Context appContext;
    private static int selectedMinutes;
    private static long deadlineElapsedMs;
    private static long lastClickElapsedMs;

    private static final Runnable POWER_OFF_ACTION = () -> executePowerOff(appContext);

    private SleepTimer() {
    }

    /**
     * Handles a click on the Timer card.
     * If two clicks occur within DOUBLE_CLICK_WINDOW_MS (420ms), immediately deactivates the timer.
     * Otherwise steps +30 minutes up to 180 minutes (3 hours), then cycles back to 0 (Off).
     */
    static int onCardClick(Context context) {
        appContext = context.getApplicationContext();
        ensureAccessibilityBound(appContext);

        long now = SystemClock.elapsedRealtime();
        if (lastClickElapsedMs > 0L && (now - lastClickElapsedMs) <= DOUBLE_CLICK_WINDOW_MS) {
            lastClickElapsedMs = 0L;
            return deactivate(context);
        }
        lastClickElapsedMs = now;

        if (!isActive()) {
            selectedMinutes = STEP_MINUTES;
        } else {
            selectedMinutes += STEP_MINUTES;
            if (selectedMinutes > MAX_MINUTES) {
                selectedMinutes = 0;
            }
        }

        if (selectedMinutes > 0) {
            schedulePowerOff(appContext, selectedMinutes);
        } else {
            cancelScheduledPowerOff(appContext);
        }
        return selectedMinutes;
    }

    /** Immediately turns off (cancels) the sleep timer (called on fast double-click or long-press hold). */
    static int deactivate(Context context) {
        appContext = context.getApplicationContext();
        selectedMinutes = 0;
        deadlineElapsedMs = 0L;
        lastClickElapsedMs = 0L;
        cancelScheduledPowerOff(appContext);
        return 0;
    }

    private static void schedulePowerOff(Context context, int minutes) {
        HANDLER.removeCallbacks(POWER_OFF_ACTION);
        long durationMs = minutes * 60_000L;
        deadlineElapsedMs = SystemClock.elapsedRealtime() + durationMs;
        HANDLER.postDelayed(POWER_OFF_ACTION, durationMs);

        try {
            Settings.Global.putInt(context.getContentResolver(), "power_sleep_timer", 0);
        } catch (Throwable ignored) {
        }

        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                PendingIntent pi = alarmPendingIntent(context);
                am.cancel(pi);
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedMs, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelScheduledPowerOff(Context context) {
        HANDLER.removeCallbacks(POWER_OFF_ACTION);
        deadlineElapsedMs = 0L;
        try {
            Settings.Global.putInt(context.getContentResolver(), "power_sleep_timer", 0);
        } catch (Throwable ignored) {
        }
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(alarmPendingIntent(context));
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent intent = new Intent(context, SleepTimerReceiver.class);
        return PendingIntent.getBroadcast(context, 7701, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void executePowerOff(Context context) {
        selectedMinutes = 0;
        deadlineElapsedMs = 0L;
        HANDLER.removeCallbacks(POWER_OFF_ACTION);
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx != null) {
            ensureAccessibilityBound(ctx);
        }
        if (InputKeyService.performPowerOff()) {
            return;
        }
        if (ctx != null) {
            try {
                // Triggers MediaTek/Xiaomi native PowerService sleep standby when WRITE_SECURE_SETTINGS is granted
                if (Settings.Global.putInt(ctx.getContentResolver(), "power_sleep_timer", 10)) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                ctx.startActivity(new Intent(ctx, ClockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    private static void ensureAccessibilityBound(Context context) {
        try {
            String svc = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService";
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains("InputKeyService")) {
                String next = (enabled == null || enabled.isEmpty() || "null".equals(enabled))
                        ? svc : enabled + ":" + svc;
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Throwable ignored) {
        }
    }

    static boolean isActive() {
        if (selectedMinutes <= 0 || deadlineElapsedMs <= 0L) return false;
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
            selectedMinutes = 0;
            deadlineElapsedMs = 0L;
            return false;
        }
        return true;
    }

    static int remainingMinutes() {
        if (!isActive()) return 0;
        long remMs = deadlineElapsedMs - SystemClock.elapsedRealtime();
        return Math.max(1, (int) Math.ceil(remMs / 60_000.0));
    }

    static String cardTitle() {
        int rem = remainingMinutes();
        return rem > 0 ? "Timer \u00B7 " + rem + "m" : "Timer";
    }

    static String popupHeadline(int minutes) {
        if (minutes <= 0) {
            return "Timer TV Mati \u00B7 Nonaktif";
        }
        if (minutes == 180) {
            return "Timer TV Mati \u00B7 180 Menit (3 Jam)";
        }
        return "Timer TV Mati \u00B7 " + minutes + " Menit";
    }

    static String popupSubtitle(int minutes) {
        if (minutes <= 0) {
            return "Timer waktu tidur TV telah dimatikan";
        }
        return "Klik lagi +30m (maks 3 jam)  \u2022  Klik 2\u00D7 cepat / tahan OK: Nonaktif";
    }
}
