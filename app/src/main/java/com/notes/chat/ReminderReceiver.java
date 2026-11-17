package com.notes.chat;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "notes_task_reminders";
    private static final String PREFS = "notes_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        String taskId = intent.getStringExtra("taskId");
        String title = intent.getStringExtra("title");
        long dueAt = intent.getLongExtra("dueAt", 0L);
        if (title == null || title.trim().isEmpty()) title = "Your task is due";

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Reminders for NotesChat task lists");
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                context, taskId == null ? 0 : taskId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("NotesChat reminder")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        manager.notify(taskId == null ? (int) System.currentTimeMillis() : taskId.hashCode(), notification);
        if (dueAt > 0L) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("notified_" + (taskId == null ? "task" : taskId), dueAt).apply();
        }
    }

    /** Fire an overdue reminder once for each task due-time value. */
    static void showNow(Context context, String taskId, String title, long dueAt) {
        if (title == null || title.trim().isEmpty()) title = "Your task is due";
        String reminderKey = "notified_" + (taskId == null ? "task" : taskId);
        SharedPreferences reminderPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (reminderPrefs.getLong(reminderKey, 0L) == dueAt) return;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Reminders for NotesChat task lists");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                context, taskId == null ? 0 : taskId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("NotesChat reminder")
                .setContentText(title)
                .setStyle(new Notification.BigTextStyle().bigText(title))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build();
        manager.notify(taskId == null ? (int) System.currentTimeMillis() : taskId.hashCode(), notification);
        reminderPrefs.edit().putLong(reminderKey, dueAt).apply();
    }

    private static PendingIntent alarmIntent(Context context, String taskId, String title, long dueAt) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("taskId", taskId);
        intent.putExtra("title", title);
        intent.putExtra("dueAt", dueAt);
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Schedule the alarm and remember it so a reboot can restore it. */
    static void schedule(Context context, String taskId, String title, long dueAt) {
        if (taskId == null || taskId.isEmpty() || dueAt <= 0L) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pending = alarmIntent(context, taskId, title, dueAt);
        scheduleAlarm(alarms, dueAt, pending);
        try {
            JSONObject saved = new JSONObject()
                    .put("title", title == null ? "" : title)
                    .put("due", dueAt);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("task_" + taskId, saved.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Prefer exact alarms when the user has allowed them, so reminders do not
     * drift by minutes inside Doze. Fall back to the inexact API otherwise
     * (e.g. fresh Android 13+ installs where exact alarms are denied).
     */
    private static void scheduleAlarm(AlarmManager alarms, long dueAt, PendingIntent pending) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending);
                return;
            }
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending);
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending);
        }
    }

    /** Cancel the alarm and forget the persisted reminder. */
    static void cancel(Context context, String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        cancelAlarmOnly(context, taskId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("task_" + taskId).apply();
    }

    private static void cancelAlarmOnly(Context context, String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        alarms.cancel(alarmIntent(context, taskId, null, 0L));
    }

    /**
     * Reconcile alarms with the authoritative task list from the app.
     * Reminders that are no longer in the list (deleted notes, cleared due
     * dates, restores) are cancelled so no stale notification can fire.
     */
    static synchronized void sync(Context context, JSONArray items) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        Set<String> active = new HashSet<>();
        int count = items == null ? 0 : Math.min(items.length(), 20000);
        for (int i = 0; i < count; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String taskId = item.optString("id", "").trim();
            if (!taskId.matches("[0-9]{1,20}")) continue;
            String title = item.optString("title", "");
            if (title.length() > 500) title = title.substring(0, 500);
            if (title.trim().isEmpty()) title = "Your task is due";
            long due = item.optLong("due", 0L);
            boolean done = item.optBoolean("done", false);
            if (done || due <= 0L) {
                cancelAlarmOnly(context, taskId);
                prefs.edit().remove("task_" + taskId).apply();
                continue;
            }
            if (due > now) {
                active.add(taskId);
                schedule(context, taskId, title, due);
            } else {
                cancelAlarmOnly(context, taskId);
                active.add(taskId);
                showNow(context, taskId, title, due);
            }
        }
        for (Map.Entry<String, ?> entry : new HashMap<String, Object>(prefs.getAll()).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("task_")) continue;
            String taskId = key.substring(5);
            if (active.contains(taskId)) continue;
            cancelAlarmOnly(context, taskId);
            prefs.edit().remove(key).apply();
        }
    }

    /** Re-arm every reminder after a reboot or app update. */
    static void rescheduleAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : new HashMap<String, Object>(prefs.getAll()).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("task_") || !(entry.getValue() instanceof String)) continue;
            String taskId = key.substring(5);
            try {
                JSONObject saved = new JSONObject((String) entry.getValue());
                String title = saved.optString("title", "Your task is due");
                long due = saved.optLong("due", 0L);
                if (due <= 0L) {
                    prefs.edit().remove(key).apply();
                    continue;
                }
                if (due > now) schedule(context, taskId, title, due);
                else showNow(context, taskId, title, due);
            } catch (Exception ignored) {
                prefs.edit().remove(key).apply();
            }
        }
    }
}
