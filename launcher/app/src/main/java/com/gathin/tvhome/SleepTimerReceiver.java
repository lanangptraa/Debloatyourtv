package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager wakeup receiver that guarantees the TV Sleep Timer powers off the TV
 * even when another app (YouTube, Netflix/OnStream, Live TV) has been in the foreground for hours.
 */
public final class SleepTimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SleepTimer.executePowerOff(context);
    }
}
