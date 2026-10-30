package com.notes.chat;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted backup transport for password-based legacy archives and account-key NCB2 archives. Current UI uses Google Drive appDataFolder and scheduled/manual Telegram archives. */
public class Cloud {

    static final String FILE = "notes-backup.ncb";
    static final byte[] MAGIC = {'N', 'C', 'B', '1'};
    private static final Object TELEGRAM_DAILY_LOCK = new Object();

    private static String localCalendarDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    // ---------- entry point (called from the JS bridge) ----------
    static String handle(Context ctx, String op, JSONObject a) throws Exception {
        switch (op) {
            case "pwset":
                savePassword(ctx, a.getString("pw"));
                return "ok";
            case "pwget":
                return loadPassword(ctx);
            case "mirrorsave":
                mirrorSave(ctx, a.getString("text"));
                return "ok";
            case "mirrorload":
                return mirrorLoad(ctx);
            case "localencrypt": {
                String pw = a.getString("pw");
                if (pw.length() < 8) throw new Exception("Backup password must be at least 8 characters");
                byte[] encrypted = encrypt(a.getString("data"), pw);
                return Base64.encodeToString(encrypted, Base64.NO_WRAP);
            }
            case "localdecrypt": {
                String encoded = a.getString("data");
                String pw = a.getString("pw");
                if (pw.length() < 8) throw new Exception("Backup password is too short");
                byte[] encrypted;
                try {
                    encrypted = Base64.decode(encoded, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    throw new Exception("Encrypted backup file is damaged");
                }
                return decrypt(encrypted, pw);
            }
            case "pwdel":
                ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE).edit().remove("pw").apply();
                return "ok";
            case "localbackupconfigure":
                configureLocalBackup(ctx, a);
                return "ok";
            case "localbackupstatus":
                return localBackupStatus(ctx).toString();
            case "localbackupopen":
                createLocalBackupOnAppOpen(ctx);
                return "ok";
            case "localbackupmanual":
                return createManualLocalBackup(ctx, a);
            case "localautoread":
                return readLocalAutoBackup(ctx);
            case "drivekey": {
                String key = getOrCreateDriveKey(a.getString("token"));
                saveLocalSecret(ctx, "drive_key", key);
                return key;
            }
            case "tgsnapshot": {
                String snapshot = a.getString("data");
                // Keep independent snapshots for cloud/Telegram and on-device scheduling.
                if (!ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE).edit()
                        .putString("snapshot", snapshot).commit())
                    throw new Exception("Cloud backup snapshot could not be saved");
                if (!ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE).edit()
                        .putString("snapshot", snapshot).commit())
                    throw new Exception("Local backup snapshot could not be saved");
                return "ok";
            }
            case "tgconfigure":
                configureTelegramBackup(ctx, a);
                return "ok";
            case "tgstatus":
                return String.valueOf(ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE)
                        .getLong("lastSuccess", 0L));
            case "tgdailyclaim": {
                SharedPreferences sp = ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE);
                synchronized (TELEGRAM_DAILY_LOCK) {
                    String today = localCalendarDate();
                    String completed = sp.getString("dailyOpenDate", "");
                    String inFlight = sp.getString("dailyOpenInFlightDate", "");
                    long inFlightAt = sp.getLong("dailyOpenInFlightAt", 0L);
                    boolean activeClaim = today.equals(inFlight)
                            && System.currentTimeMillis() - inFlightAt < 30L * 60L * 1000L;
                    JSONObject result = new JSONObject();
                    if (today.equals(completed) || activeClaim) {
                        result.put("run", false);
                        result.put("date", today);
                        result.put("reason", today.equals(completed) ? "already_done" : "already_running");
                        return result.toString();
                    }
                    boolean saved = sp.edit()
                            .putString("dailyOpenInFlightDate", today)
                            .putLong("dailyOpenInFlightAt", System.currentTimeMillis())
                            .commit();
                    if (!saved) throw new Exception("Daily Telegram backup state save failed");
                    result.put("run", true);
                    result.put("date", today);
                    return result.toString();
                }
            }
            case "tgdailycomplete": {
                SharedPreferences sp = ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE);
                synchronized (TELEGRAM_DAILY_LOCK) {
                    String date = a.optString("date", "");
                    if (date.isEmpty() || !date.equals(sp.getString("dailyOpenInFlightDate", "")))
                        throw new Exception("Daily Telegram backup claim expired");
                    if (!sp.edit().putString("dailyOpenDate", date)
                            .remove("dailyOpenInFlightDate").remove("dailyOpenInFlightAt").commit())
                        throw new Exception("Daily Telegram backup completion could not be saved");
                    return "ok";
                }
            }
            case "tgdailyrelease": {
                SharedPreferences sp = ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE);
                synchronized (TELEGRAM_DAILY_LOCK) {
                    String date = a.optString("date", "");
                    if (date.equals(sp.getString("dailyOpenInFlightDate", ""))) {
                        sp.edit().remove("dailyOpenInFlightDate").remove("dailyOpenInFlightAt").commit();
                    }
                    return "ok";
                }
            }
            case "drivehistory":
                return driveHistory(a.getString("token")).toString();
            case "backup": {
                String key = a.optString("key", "");
                String pw = a.optString("pw", "");
                if (key.isEmpty() && pw.isEmpty()) throw new Exception("Backup encryption key is missing");
                byte[] enc = key.isEmpty() ? encrypt(a.getString("data"), pw)
                        : encryptWithKey(a.getString("data"), key);
                JSONArray ts = a.getJSONArray("targets");
                StringBuilder sb = new StringBuilder();
                String fid = "";
                int successCount = 0;
                int failureCount = 0;
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject t = ts.getJSONObject(i);
                    String type = t.getString("type");
                    String name = name(type);
                    try {
                        String r = upload(t, enc);
                        if ("tg".equals(type)) fid = r;
                        successCount++;
                        sb.append(name).append(" \u2713   ");
                    } catch (Exception e) {
                        failureCount++;
                        String reason = e.getMessage() == null ? "upload failed" : e.getMessage();
                        if (reason.length() > 120) reason = reason.substring(0, 120);
                        sb.append(name).append(" \u2717 (").append(reason).append(")   ");
                    }
                }
                JSONObject o = new JSONObject();
                o.put("msg", sb.toString().trim());
                o.put("fid", fid);
                o.put("successCount", successCount);
                o.put("failureCount", failureCount);
                return o.toString();
            }
            case "restore": {
                byte[] b = download(a.getJSONObject("source"));
                String key = a.optString("key", "");
                if (!key.isEmpty()) return decryptWithKey(b, key);
                return decrypt(b, a.getString("pw"));
            }
            case "tgchat": {
                String token = a.getString("token");
                JSONArray r = tgUpdates(token);
                for (int i = r.length() - 1; i >= 0; i--) {
                    JSONObject m = r.getJSONObject(i).optJSONObject("message");
                    if (m != null) return String.valueOf(m.getJSONObject("chat").getLong("id"));
                }
                throw new Exception("Pehle bot ko /start bhejein");
            }
        }
        throw new Exception("Unknown op");
    }

    static String name(String type) {
        switch (type) {
            case "drive": return "Google Drive";
            case "driveAuto": return "Google Drive auto-sync";
            case "webdav": return "Nextcloud";
            case "tg": return "Telegram";
            default: return "GitHub";
        }
    }

    // ---------- saved password (Android Keystore, key never leaves the device) ----------
    static SecretKey ksKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("notes_pw_key")) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("notes_pw_key",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey("notes_pw_key", null);
    }

    static void savePassword(Context ctx, String pw) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, ksKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(pw.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE).edit()
                .putString("pw", Base64.encodeToString(all, Base64.NO_WRAP)).apply();
    }

    static String loadPassword(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE);
        String b = sp.getString("pw", null);
        if (b == null) return "";
        byte[] all = Base64.decode(b, Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, ksKey(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, 12)));
        return new String(c.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    }

    // ---------- auto copy in phone storage: Documents/NotesChat/notes-mirror.json ----------
    static Uri mirrorUri(Context ctx) {
        Uri base = MediaStore.Files.getContentUri("external");
        Cursor c = ctx.getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{"notes-mirror.json", "Documents/NotesChat%"}, null);
        if (c == null) return null;
        try {
            if (c.moveToFirst()) return ContentUris.withAppendedId(base, c.getLong(0));
        } finally {
            c.close();
        }
        return null;
    }

    static void mirrorSave(Context ctx, String text) throws Exception {
        ContentResolver r = ctx.getContentResolver();
        Uri uri = mirrorUri(ctx);
        if (uri == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "notes-mirror.json");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/NotesChat");
            uri = r.insert(MediaStore.Files.getContentUri("external"), v);
        }
        if (uri == null) throw new Exception("Storage me file nahi ban payi");
        try (OutputStream o = r.openOutputStream(uri, "wt")) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String mirrorLoad(Context ctx) throws Exception {
        Uri uri = mirrorUri(ctx);
        if (uri == null) return "";
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    // ---------- encrypted local backups in Documents/FZ NOTES/Backups ----------
    private static final String LOCAL_BACKUP_HEADER = "FZ-NOTES-ENCRYPTED-BACKUP-V1\n";
    private static final String LOCAL_BACKUP_FILE = "FZ-Notes-AutoBackup.fzenc"; // legacy private copy
    private static final String LOCAL_BACKUP_PATH = "Documents/FZ NOTES/Backups/";
    private static final String LOCAL_AUTO_PREFIX = "FZ-Notes-AutoBackup-";

    static String saveLocalBackupFile(Context ctx, String filename, String contents) throws Exception {
        if (filename == null || filename.trim().isEmpty()) filename = "FZ-Notes-backup.fzenc";
        filename = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!filename.endsWith(".fzenc")) filename += ".fzenc";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, LOCAL_BACKUP_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        ContentResolver resolver = ctx.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) throw new Exception("Documents/FZ NOTES/Backups folder mein file nahi ban payi");
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("Backup file write nahi ho payi");
                out.write((contents == null ? "" : contents).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
        } catch (Exception e) {
            try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            throw e;
        }
        return LOCAL_BACKUP_PATH + filename;
    }

    private static String localBackupStamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static Uri latestLocalBackupUri(Context ctx, boolean automaticOnly) {
        Uri base = MediaStore.Files.getContentUri("external");
        String pattern = automaticOnly ? LOCAL_AUTO_PREFIX + "%.fzenc" : "%.fzenc";
        Cursor cursor = ctx.getContentResolver().query(base,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{LOCAL_BACKUP_PATH, pattern},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
        if (cursor == null) return null;
        try {
            if (cursor.moveToFirst()) return ContentUris.withAppendedId(base, cursor.getLong(0));
        } finally { cursor.close(); }
        return null;
    }

    private static int localBackupCount(Context ctx) {
        Cursor cursor = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{LOCAL_BACKUP_PATH, "%.fzenc"}, null);
        if (cursor == null) return 0;
        try { return cursor.getCount(); }
        finally { cursor.close(); }
    }

    static void configureLocalBackup(Context ctx, JSONObject a) throws Exception {
        String interval = a.optString("interval", "manual");
        if ("off".equals(interval)) interval = "manual";
        if (!"manual".equals(interval) && !"onopen".equals(interval) && !"dailyopen".equals(interval)
                && !"daily".equals(interval) && !"weekly".equals(interval) && !"monthly".equals(interval))
            throw new Exception("Unknown local backup schedule");
        SharedPreferences prefs = ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE);
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        wm.cancelUniqueWork("fz_notes_local_backup");

        String suppliedPassword = a.optString("pw", "");
        if (!suppliedPassword.isEmpty()) {
            if (suppliedPassword.length() < 8) throw new Exception("Local backup password must be at least 8 characters");
            saveLocalSecret(ctx, "local_backup_pw", suppliedPassword);
        }
        if (!"manual".equals(interval) && loadLocalSecret(ctx, "local_backup_pw").length() < 8)
            throw new Exception("Create a local backup password before enabling an automatic schedule");

        if (!prefs.edit().putString("interval", interval).remove("lastError").commit())
            throw new Exception("Local backup schedule could not be saved");
        if ("daily".equals(interval) || "weekly".equals(interval) || "monthly".equals(interval)) {
            long days = "daily".equals(interval) ? 1L : "weekly".equals(interval) ? 7L : 30L;
            PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                    LocalBackupWorker.class, days, TimeUnit.DAYS).build();
            wm.enqueueUniquePeriodicWork("fz_notes_local_backup", ExistingPeriodicWorkPolicy.UPDATE, work);
        }
        if (!"manual".equals(interval)) {
            createScheduledLocalBackup(ctx);
            if ("dailyopen".equals(interval))
                prefs.edit().putString("dailyOpenDate", localCalendarDate()).commit();
        }
    }

    static void createScheduledLocalBackup(Context ctx) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE);
        String interval = prefs.getString("interval", "manual");
        if ("manual".equals(interval) || "off".equals(interval)) return;
        String data = prefs.getString("snapshot", "");
        if (data == null || data.trim().isEmpty())
            throw new Exception("No saved notes snapshot is available for local backup");
        String pw = loadLocalSecret(ctx, "local_backup_pw");
        if (pw.length() < 8) throw new Exception("Scheduled local backup password is unavailable");
        String contents = LOCAL_BACKUP_HEADER
                + Base64.encodeToString(encrypt(data, pw), Base64.NO_WRAP);
        String filename = LOCAL_AUTO_PREFIX + localBackupStamp() + ".fzenc";
        saveLocalBackupFile(ctx, filename, contents);
        prefs.edit().putLong("lastSuccess", System.currentTimeMillis())
                .putString("lastFileName", filename).remove("lastError").commit();
    }

    static void createLocalBackupOnAppOpen(Context ctx) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE);
        String interval = prefs.getString("interval", "manual");
        if ("onopen".equals(interval)) {
            createScheduledLocalBackup(ctx);
        } else if ("dailyopen".equals(interval)) {
            String today = localCalendarDate();
            if (!today.equals(prefs.getString("dailyOpenDate", ""))) {
                createScheduledLocalBackup(ctx);
                prefs.edit().putString("dailyOpenDate", today).apply();
            }
        }
    }

    static String createManualLocalBackup(Context ctx, JSONObject a) throws Exception {
        String pw = a.optString("pw", "");
        if (!pw.isEmpty()) {
            if (pw.length() < 8) throw new Exception("Local backup password must be at least 8 characters");
            saveLocalSecret(ctx, "local_backup_pw", pw);
        } else pw = loadLocalSecret(ctx, "local_backup_pw");
        if (pw.length() < 8) throw new Exception("Create a local backup password before the first backup");
        String contents = LOCAL_BACKUP_HEADER
                + Base64.encodeToString(encrypt(a.getString("data"), pw), Base64.NO_WRAP);
        String filename = "FZ-Notes-Backup-" + localBackupStamp() + ".fzenc";
        String path = saveLocalBackupFile(ctx, filename, contents);
        ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE).edit()
                .putLong("lastManualSuccess", System.currentTimeMillis())
                .putString("lastManualFileName", filename).apply();
        return "Encrypted backup saved: " + path;
    }

    static JSONObject localBackupStatus(Context ctx) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences("notes_local_backup", Context.MODE_PRIVATE);
        JSONObject status = new JSONObject();
        status.put("interval", prefs.getString("interval", "manual"));
        status.put("lastSuccess", prefs.getLong("lastSuccess", 0L));
        status.put("lastManualSuccess", prefs.getLong("lastManualSuccess", 0L));
        status.put("lastError", prefs.getString("lastError", ""));
        status.put("hasBackup", latestLocalBackupUri(ctx, false) != null
                || new File(ctx.getFilesDir(), LOCAL_BACKUP_FILE).isFile());
        status.put("backupCount", localBackupCount(ctx));
        status.put("hasPassword", loadLocalSecret(ctx, "local_backup_pw").length() >= 8);
        status.put("path", LOCAL_BACKUP_PATH);
        return status;
    }

    static String readLocalAutoBackup(Context ctx) throws Exception {
        Uri uri = latestLocalBackupUri(ctx, true);
        if (uri != null) {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Automatic backup file open nahi hui");
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        }
        File legacy = new File(ctx.getFilesDir(), LOCAL_BACKUP_FILE);
        if (legacy.isFile()) {
            try (InputStream in = new FileInputStream(legacy)) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        }
        throw new Exception("Automatic local backup abhi available nahi hai");
    }

    // ---------- crypto ----------
    static SecretKeySpec key(String pw, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 200000, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    static byte[] encrypt(String plain, String pw) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        r.nextBytes(salt);
        r.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(pw, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(MAGIC);
        o.write(salt);
        o.write(iv);
        o.write(ct);
        return o.toByteArray();
    }

    static String decrypt(byte[] b, String pw) throws Exception {
        if (b.length < 48 || b[0] != 'N' || b[1] != 'C' || b[2] != 'B' || b[3] != '1')
            throw new Exception("Ye valid backup file nahi hai");
        byte[] salt = Arrays.copyOfRange(b, 4, 20);
        byte[] iv = Arrays.copyOfRange(b, 20, 32);
        byte[] ct = Arrays.copyOfRange(b, 32, b.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(pw, salt), new GCMParameterSpec(128, iv));
        try {
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new Exception("Password galat hai");
        }
    }

    /** App-scoped random key enables same-account restore without a user password. */
    static byte[] encryptWithKey(String plain, String keyB64) throws Exception {
        byte[] rawKey = Base64.decode(keyB64, Base64.DEFAULT);
        if (rawKey.length != 32) throw new Exception("Google backup key is invalid");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{'N','C','B','2'});
        o.write(iv);
        o.write(ct);
        return o.toByteArray();
    }

    static String decryptWithKey(byte[] b, String keyB64) throws Exception {
        if (b.length >= 4 && b[0] == 'N' && b[1] == 'C' && b[2] == 'B' && b[3] == '1')
            throw new Exception("LEGACY_PASSWORD_BACKUP: This older backup uses a password.");
        if (b.length < 32 || b[0] != 'N' || b[1] != 'C' || b[2] != 'B' || b[3] != '2')
            throw new Exception("Ye valid FZ NOTES encrypted backup nahi hai");
        byte[] rawKey = Base64.decode(keyB64, Base64.DEFAULT);
        if (rawKey.length != 32) throw new Exception("Google backup key is invalid");
        byte[] iv = Arrays.copyOfRange(b, 4, 16);
        byte[] ct = Arrays.copyOfRange(b, 16, b.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(128, iv));
        try {
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new Exception("Google account backup key does not match");
        }
    }

    static void saveLocalSecret(Context ctx, String prefName, String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, ksKey());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE).edit()
                .putString(prefName, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
    }

    static String loadLocalSecret(Context ctx, String prefName) throws Exception {
        String value = ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE)
                .getString(prefName, null);
        if (value == null || value.isEmpty()) return "";
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        if (all.length < 29) throw new Exception("Saved local backup secret is damaged");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, ksKey(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, 12)));
        return new String(c.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    }

    static String loadDriveKey(Context ctx) throws Exception {
        String value = ctx.getSharedPreferences("notes_sec", Context.MODE_PRIVATE)
                .getString("drive_key", null);
        if (value == null || value.isEmpty()) throw new Exception("Connect Google Drive once to set up the backup key");
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        if (all.length < 29) throw new Exception("Saved Google backup key is damaged");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, ksKey(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, 12)));
        return new String(c.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    }

    static String getOrCreateDriveKey(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        String listUrl = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder"
                + "&q=name%20%3D%20%27FZ-NOTES-KEY-1.bin%27&fields=files(id,name)&pageSize=10";
        JSONObject result = new JSONObject(new String(http("GET", listUrl, h, null, null), StandardCharsets.UTF_8));
        JSONArray files = result.optJSONArray("files");
        if (files != null && files.length() > 0) {
            String id = files.getJSONObject(0).getString("id");
            byte[] existing = http("GET", "https://www.googleapis.com/drive/v3/files/"
                    + URLEncoder.encode(id, "UTF-8") + "?alt=media", h, null, null);
            if (existing.length != 32) throw new Exception("Google Drive backup key file is invalid");
            return Base64.encodeToString(existing, Base64.NO_WRAP);
        }

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String boundary = "----fzkey" + System.currentTimeMillis();
        JSONObject meta = new JSONObject();
        meta.put("name", "FZ-NOTES-KEY-1.bin");
        JSONArray parents = new JSONArray();
        parents.put("appDataFolder");
        meta.put("parents", parents);
        meta.put("mimeType", "application/octet-stream");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + meta.toString() + "\r\n--" + boundary
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(key);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                h, body.toByteArray(), "multipart/related; boundary=" + boundary);
        return Base64.encodeToString(key, Base64.NO_WRAP);
    }

    static void configureTelegramBackup(Context ctx, JSONObject a) throws Exception {
        String token = a.optString("token", "").trim();
        String chat = a.optString("chat", "").trim();
        String interval = a.optString("interval", "manual");
        if (!"manual".equals(interval) && !"onopen".equals(interval) && !"dailyopen".equals(interval)
                && !"daily".equals(interval) && !"weekly".equals(interval) && !"monthly".equals(interval))
            throw new Exception("Unknown Telegram backup schedule");
        if (!"manual".equals(interval) && (token.isEmpty() || chat.isEmpty()))
            throw new Exception("Telegram bot token aur chat ID pehle save karein");
        ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE).edit()
                .putString("token", token).putString("chat", chat).putString("interval", interval).apply();
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        // Manual and app-open schedules are triggered by the UI, not a periodic worker.
        if ("manual".equals(interval) || "onopen".equals(interval) || "dailyopen".equals(interval)
                || token.isEmpty() || chat.isEmpty()) {
            wm.cancelUniqueWork("fz_notes_telegram_backup");
            return;
        }
        long days = "daily".equals(interval) ? 1L : "weekly".equals(interval) ? 7L : 30L;
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                TelegramBackupWorker.class, days, TimeUnit.DAYS)
                .setConstraints(constraints).build();
        wm.enqueueUniquePeriodicWork("fz_notes_telegram_backup", ExistingPeriodicWorkPolicy.UPDATE, work);
    }

    static void sendScheduledTelegramBackup(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences("notes_tg_backup", Context.MODE_PRIVATE);
        String token = sp.getString("token", "");
        String chat = sp.getString("chat", "");
        String interval = sp.getString("interval", "manual");
        String data = sp.getString("snapshot", "");
        if (token.isEmpty() || chat.isEmpty()
                || "manual".equals(interval) || "onopen".equals(interval) || "dailyopen".equals(interval))
            throw new Exception("Scheduled Telegram backup is not configured");
        if (data.isEmpty()) throw new Exception("Notes snapshot is empty");
        String key = loadDriveKey(ctx);
        byte[] encrypted = encryptWithKey(data, key);
        JSONObject target = new JSONObject();
        target.put("type", "tg");
        target.put("token", token);
        target.put("chat", chat);
        String fileId = upload(target, encrypted);
        sp.edit().putLong("lastSuccess", System.currentTimeMillis())
                .putString("lastFileId", fileId).putString("lastError", "").apply();
    }

    // ---------- upload / download per service ----------
    static String upload(JSONObject t, byte[] data) throws Exception {
        switch (t.getString("type")) {
            case "driveAuto": {
                String token = t.getString("token");
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                String query = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder"
                        + "&q=name%20%3D%20%27FZ-NOTES-AUTO.ncb%27%20and%20trashed%20%3D%20false"
                        + "&fields=files(id,name)&pageSize=10";
                JSONObject found = new JSONObject(new String(http("GET", query, h, null, null), StandardCharsets.UTF_8));
                JSONArray files = found.optJSONArray("files");
                if (files != null && files.length() > 0) {
                    String id = files.getJSONObject(0).getString("id");
                    // Google API supports HTTP method override for media updates.
                    Map<String, String> updateHeaders = new HashMap<>(h);
                    updateHeaders.put("X-HTTP-Method-Override", "PATCH");
                    http("POST", "https://www.googleapis.com/upload/drive/v3/files/"
                                    + URLEncoder.encode(id, "UTF-8") + "?uploadType=media",
                            updateHeaders, data, "application/octet-stream");
                    return id;
                }
                String boundary = "----fzdriveauto" + System.currentTimeMillis();
                JSONObject meta = new JSONObject();
                meta.put("name", "FZ-NOTES-AUTO.ncb");
                JSONArray parents = new JSONArray();
                parents.put("appDataFolder");
                meta.put("parents", parents);
                meta.put("mimeType", "application/octet-stream");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                        + meta.toString() + "\r\n--" + boundary
                        + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(data);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,modifiedTime",
                        h, body.toByteArray(), "multipart/related; boundary=" + boundary);
                return "";
            }
            case "drive": {
                String token = t.getString("token");
                String boundary = "----fzdrive" + System.currentTimeMillis();
                JSONObject meta = new JSONObject();
                meta.put("name", "FZ-Notes-" + System.currentTimeMillis() + ".ncb");
                JSONArray parents = new JSONArray();
                parents.put("appDataFolder");
                meta.put("parents", parents);
                meta.put("mimeType", "application/octet-stream");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                        + meta.toString() + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                body.write(data);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,createdTime",
                        h, body.toByteArray(), "multipart/related; boundary=" + boundary);
                return "";
            }
            case "webdav": {
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", basic(t.getString("user"), t.optString("pass", "")));
                http("PUT", webdavUrl(t), h, data, "application/octet-stream");
                return "";
            }
            case "gh": {
                String api = ghApi(t);
                Map<String, String> h = ghHeaders(t, "application/vnd.github+json");
                String sha = null;
                try {
                    JSONObject o = new JSONObject(new String(http("GET", api, h, null, null), StandardCharsets.UTF_8));
                    sha = o.optString("sha", null);
                } catch (Exception e) {
                    if (e.getMessage() == null || !e.getMessage().startsWith("HTTP 404")) throw e;
                }
                JSONObject b = new JSONObject();
                b.put("message", "Notes backup " + new java.util.Date());
                b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
                if (sha != null) b.put("sha", sha);
                http("PUT", api, h, b.toString().getBytes(StandardCharsets.UTF_8), "application/json");
                return "";
            }
            default: { // tg
                String boundary = "----nc" + System.currentTimeMillis();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                field(o, boundary, "chat_id", t.getString("chat"));
                field(o, boundary, "caption", "Notes backup (encrypted)");
                o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + FILE
                        + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(data);
                o.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                byte[] r = http("POST", "https://api.telegram.org/bot" + t.getString("token") + "/sendDocument",
                        null, o.toByteArray(), "multipart/form-data; boundary=" + boundary);
                JSONObject j = new JSONObject(new String(r, StandardCharsets.UTF_8));
                return j.getJSONObject("result").getJSONObject("document").getString("file_id");
            }
        }
    }

    static byte[] download(JSONObject t) throws Exception {
        switch (t.getString("type")) {
            case "drive": {
                String token = t.getString("token");
                String id = t.getString("id");
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                return http("GET", "https://www.googleapis.com/drive/v3/files/" + URLEncoder.encode(id, "UTF-8") + "?alt=media",
                        h, null, null);
            }
            case "webdav": {
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", basic(t.getString("user"), t.optString("pass", "")));
                return http("GET", webdavUrl(t), h, null, null);
            }
            case "gh":
                return http("GET", ghApi(t), ghHeaders(t, "application/vnd.github.raw+json"), null, null);
            default: { // tg
                String token = t.getString("token");
                String fid = t.optString("fid", "");
                if (fid.isEmpty()) {
                    JSONArray r = tgUpdates(token);
                    for (int i = r.length() - 1; i >= 0 && fid.isEmpty(); i--) {
                        JSONObject m = r.getJSONObject(i).optJSONObject("message");
                        if (m != null && m.has("document"))
                            fid = m.getJSONObject("document").getString("file_id");
                    }
                    if (fid.isEmpty())
                        throw new Exception("Bot ko backup file bhejein (ya forward karein), phir Restore dabayein");
                }
                String info = new String(http("GET", "https://api.telegram.org/bot" + token
                        + "/getFile?file_id=" + URLEncoder.encode(fid, "UTF-8"), null, null, null), StandardCharsets.UTF_8);
                String path = new JSONObject(info).getJSONObject("result").getString("file_path");
                return http("GET", "https://api.telegram.org/file/bot" + token + "/" + path, null, null, null);
            }
        }
    }

    static JSONArray driveHistory(String token) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        byte[] raw = http("GET",
                "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id,name,createdTime,modifiedTime,size)&orderBy=modifiedTime%20desc&pageSize=100",
                h, null, null);
        JSONObject response = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        JSONArray files = response.optJSONArray("files");
        JSONArray backups = new JSONArray();
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (file.optString("name", "").endsWith(".ncb")) backups.put(file);
        }
        return backups;
    }

    // ---------- helpers ----------
    static JSONArray tgUpdates(String token) throws Exception {
        String s = new String(http("GET", "https://api.telegram.org/bot" + token + "/getUpdates",
                null, null, null), StandardCharsets.UTF_8);
        return new JSONObject(s).getJSONArray("result");
    }

    static String webdavUrl(JSONObject t) throws Exception {
        return t.getString("url").replaceAll("/+$", "") + "/" + FILE;
    }

    static String ghApi(JSONObject t) {
        String path = t.optString("path", FILE);
        if (path.isEmpty()) path = FILE;
        return "https://api.github.com/repos/" + t.optString("repo") + "/contents/" + path;
    }

    static Map<String, String> ghHeaders(JSONObject t, String accept) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + t.getString("token"));
        h.put("Accept", accept);
        h.put("User-Agent", "NotesChat");
        return h;
    }

    static String basic(String u, String p) {
        return "Basic " + Base64.encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    static void field(ByteArrayOutputStream o, String boundary, String name, String val) throws Exception {
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + val + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static byte[] http(String method, String url, Map<String, String> headers, byte[] body, String ctype) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (headers != null)
                for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                if (ctype != null) c.setRequestProperty("Content-Type", ctype);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data;
            try (InputStream stream = in) {
                data = readAll(stream);
            }
            if (code < 200 || code >= 300) {
                String msg = new String(data, StandardCharsets.UTF_8);
                if (msg.length() > 100) msg = msg.substring(0, 100);
                throw new Exception("HTTP " + code + ": " + msg);
            }
            return data;
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
