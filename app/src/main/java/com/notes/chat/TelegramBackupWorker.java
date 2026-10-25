package com.notes.chat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Executes scheduled encrypted Telegram archive uploads when network is available. */
public final class TelegramBackupWorker extends Worker {
    public TelegramBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            Cloud.sendScheduledTelegramBackup(ctx);
            return Result.success();
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Telegram backup failed" : e.getMessage();
            if (message.length() > 240) message = message.substring(0, 240);
            ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE).edit()
                    .putString("lastError", message).apply();
            return Result.retry();
        }
    }
}
