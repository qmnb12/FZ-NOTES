package com.notes.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "notes_task_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        String taskId = intent.getStringExtra("taskId");
        String title = intent.getStringExtra("title");
        if (title == null || title.trim().isEmpty()) title = "Your task is due";

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

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
    }
}
