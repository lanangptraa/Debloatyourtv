package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating tvOS-inspired Bento Grid Glass Control Center at the top right of the screen.
 * Each card uses live screen-space FrostedGlass blur over the active wallpaper without an outer box.
 */
public final class QuickSettingsActivity extends Activity {

    private static final android.view.animation.Interpolator EASE_OUT =
            new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private final Handler popupHandler = new Handler(Looper.getMainLooper());
    private View timerPopupPill;
    private TextView timerPopupTitle;
    private TextView timerPopupSub;
    private boolean timerLongPressConsumed;
    private final Runnable hideTimerPopup = () -> {
        if (timerPopupPill != null && timerPopupPill.getVisibility() == View.VISIBLE) {
            timerPopupPill.animate().alpha(0f).translationY(-10f)
                    .setDuration(180).withEndAction(() -> timerPopupPill.setVisibility(View.GONE)).start();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());

        timerPopupPill = findViewById(R.id.timerPopupPill);
        timerPopupTitle = findViewById(R.id.timerPopupTitle);
        timerPopupSub = findViewById(R.id.timerPopupSub);
        if (timerPopupPill != null) {
            FrostedGlass.attachTo(timerPopupPill, 26f);
        }

        View headerPill = findViewById(R.id.headerPill);
        if (headerPill != null) {
            FrostedGlass.attachTo(headerPill, 19f);
        }

        View root = findViewById(R.id.controlCenterRoot);
        if (root != null) {
            root.forceHasOverlappingRendering(false);
            root.setAlpha(0f);
            root.animate().alpha(1f)
                    .setDuration(120).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }

        TextView subWifi = findViewById(R.id.subWifi);
        if (subWifi != null) subWifi.setText(currentWifiLabel());

        bindCard(R.id.cardPower, 20f, R.id.iconPower, R.id.titlePower, 0, () -> {
            finish();
            SleepTimer.executePowerOff(this);
        });

        TextView titleTimer = findViewById(R.id.titleInput);
        if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
        bindCard(R.id.cardInput, 16f, R.id.iconInput, R.id.titleInput, 0, () -> {
            int mins = SleepTimer.onCardClick(this);
            if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
            showTimerPopup(mins);
        });
        View cardTimer = findViewById(R.id.cardInput);
        if (cardTimer != null) {
            final Runnable holdDeactivate = () -> {
                if (!timerLongPressConsumed) {
                    timerLongPressConsumed = true;
                    int mins = SleepTimer.deactivate(this);
                    if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
                    showTimerPopup(mins);
                }
            };
            cardTimer.setLongClickable(true);
            cardTimer.setOnLongClickListener(v -> {
                popupHandler.removeCallbacks(holdDeactivate);
                timerLongPressConsumed = true;
                int mins = SleepTimer.deactivate(this);
                if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
                showTimerPopup(mins);
                return true;
            });
            cardTimer.setOnKeyListener((v, keyCode, event) -> {
                boolean isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
                if (!isOk) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        timerLongPressConsumed = false;
                        popupHandler.removeCallbacks(holdDeactivate);
                        popupHandler.postDelayed(holdDeactivate, 420L);
                    } else if (!timerLongPressConsumed) {
                        popupHandler.removeCallbacks(holdDeactivate);
                        timerLongPressConsumed = true;
                        int mins = SleepTimer.deactivate(this);
                        if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
                        showTimerPopup(mins);
                        return true;
                    }
                    return timerLongPressConsumed;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    popupHandler.removeCallbacks(holdDeactivate);
                    if (timerLongPressConsumed || (event.getEventTime() - event.getDownTime()) >= 420L) {
                        if (!timerLongPressConsumed) {
                            int mins = SleepTimer.deactivate(this);
                            if (titleTimer != null) titleTimer.setText(SleepTimer.cardTitle());
                            showTimerPopup(mins);
                        }
                        timerLongPressConsumed = false;
                        return true;
                    }
                }
                return false;
            });
        }

        bindCard(R.id.cardWallpaper, 16f, R.id.iconWallpaper, R.id.titleWallpaper, 0, () ->
                launch(new Intent(this, WallpaperPickerActivity.class)));

        bindCard(R.id.cardWifi, 16f, R.id.iconWifi, R.id.titleWifi, R.id.subWifi, () ->
                launch(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));

        bindCard(R.id.cardBluetooth, 16f, R.id.iconBluetooth, R.id.titleBluetooth, 0, () ->
                launchWithFallback(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        bindCard(R.id.cardDisplay, 16f, R.id.iconDisplay, R.id.titleDisplay, 0, () ->
                launch(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));

        bindCard(R.id.cardAmbient, 16f, R.id.iconAmbient, R.id.titleAmbient, R.id.subAmbient, () ->
                launch(new Intent(this, ClockActivity.class)));

        bindCard(R.id.btnQuickLayout, 14f, R.id.iconQuickLayout, 0, 0, () ->
                launch(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)));

        bindCard(R.id.btnQuickSound, 14f, R.id.iconQuickSound, 0, 0, () ->
                launchWithFallback(new Intent(Settings.ACTION_SOUND_SETTINGS),
                        SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));

        bindCard(R.id.btnQuickApps, 14f, R.id.iconQuickApps, 0, 0, () ->
                launch(SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));

        bindCard(R.id.btnQuickSettings, 14f, R.id.iconQuickSettings, 0, 0, () ->
                launch(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        View first = findViewById(R.id.cardPower);
        if (first != null) first.requestFocus();
    }

    private void bindCard(int cardId, float radiusDp, int iconId, int titleId, int subId, Runnable onClick) {
        View card = findViewById(cardId);
        if (card == null) return;
        final FrostedGlass.GlassDrawable glass = FrostedGlass.attachTo(card, radiusDp);
        ImageView icon = iconId != 0 ? findViewById(iconId) : null;
        TextView title = titleId != 0 ? findViewById(titleId) : null;
        TextView sub = subId != 0 ? findViewById(subId) : null;

        boolean initialFocus = card.isFocused();
        glass.setFocusedState(initialFocus);
        updateCardColors(icon, title, sub, initialFocus);

        card.setOnFocusChangeListener((v, focused) -> {
            glass.setFocusedState(focused);
            updateCardColors(icon, title, sub, focused);
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                    .setDuration(120).setInterpolator(EASE_OUT).start();
        });
        card.setOnClickListener(v -> onClick.run());
    }

    private void showTimerPopup(int minutes) {
        if (timerPopupPill == null || timerPopupTitle == null || timerPopupSub == null) return;
        popupHandler.removeCallbacks(hideTimerPopup);
        timerPopupTitle.setText(SleepTimer.popupHeadline(minutes));
        timerPopupSub.setText(SleepTimer.popupSubtitle(minutes));
        if (timerPopupPill.getVisibility() != View.VISIBLE) {
            timerPopupPill.setVisibility(View.VISIBLE);
            timerPopupPill.setAlpha(0f);
            timerPopupPill.setTranslationY(-10f);
        }
        timerPopupPill.animate().cancel();
        timerPopupPill.animate().alpha(1f).translationY(0f)
                .setDuration(150).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        popupHandler.postDelayed(hideTimerPopup, 3000L);
    }

    @Override
    public void finish() {
        popupHandler.removeCallbacks(hideTimerPopup);
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static void updateCardColors(ImageView icon, TextView title, TextView sub, boolean focused) {
        if (icon != null) icon.setColorFilter(focused ? 0xFF14171C : 0xFFFFFFFF);
        if (title != null) title.setTextColor(focused ? 0xFF14171C : 0xFFFFFFFF);
        if (sub != null) sub.setTextColor(focused ? 0xB314171C : 0xCCFFFFFF);
    }

    private void launch(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void launchWithFallback(Intent primary, Intent fallback) {
        try {
            startActivity(primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        launch(fallback);
    }

    private String currentWifiLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean online = false, wifi = false, ethernet = false;
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) return getString(R.string.offline);
            if (ethernet) return getString(R.string.ethernet);
            if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
                }
                return "Connected";
            }
        } catch (Exception ignored) {
        }
        return "Connected";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }
}
