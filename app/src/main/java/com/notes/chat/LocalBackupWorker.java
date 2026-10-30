package com.notes.chat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Creates password-encrypted local snapshots on the configured daily, weekly or monthly schedule. */
public final class LocalBackupWorker extends Worker {
    public LocalBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            Cloud.createScheduledLocalBackup(ctx);
            return Result.success();
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Local backup failed" : e.getMessage();
            if (message.length() > 240) message = message.substring(0, 240);
            ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE)
                    .edit().putString("lastError", message).apply();
            return Result.retry();
        }
    }
}
