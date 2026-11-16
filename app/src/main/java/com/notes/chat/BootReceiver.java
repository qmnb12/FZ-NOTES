package com.notes.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager alarms do not survive a reboot (or a package update), so every
 * pending task reminder is re-armed from the persisted reminder list.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            return;
        }
        try {
            ReminderReceiver.rescheduleAll(context);
        } catch (Exception ignored) {
        }
    }
}
