# FZ NOTES — Google Drive backup and Telegram archive

## Google Drive passwordless backup

1. Create a Google Cloud project, enable Google Drive API, and configure Google Auth Platform.
2. Create an Android OAuth client for package `com.fz.notes.chat` and the SHA-1 for the APK signing key.
3. If the OAuth app is still in **Testing**, add the Google account you want to test, or authorization can be denied.
4. Install the APK, open **⋮ → Cloud backup → Google Drive settings**, and tap **Connect Google account**.
5. Tap **Back up now**. When moving to another phone, connect the **same Google account**, then use **Restore notes** and pick the backup.

FZ NOTES generates a random 256-bit AES key and stores it in the app's hidden Google Drive `appDataFolder`. Each backup uses AES-256-GCM with a fresh random IV. The app caches a locally wrapped copy of the key using Android Keystore so the Telegram scheduler can run without asking for a password.

**Security trade-off:** the key is stored in the same app-only Drive folder as the backups. This enables passwordless same-account restore, but it is not zero-knowledge end-to-end encryption: an instance of this app authorized for that same Google account can retrieve the key. Keep your Google account and app authorization secure. The hidden folder is not visible in the regular Drive UI, and Google documents that it is accessible only to the app that created it. If the folder is deleted or the key is lost, those NCB2 backups cannot be decrypted.

## Telegram archive and schedule

1. Open **⋮ → Cloud backup → Google Drive settings**.
2. Enter the Telegram bot token and chat ID. Use **Detect** after sending `/start` to your bot if you need to find the chat ID.
3. Choose **Manual only**, **Daily**, **Weekly**, or **Monthly (every 30 days)**.
4. Tap **Save settings**. To send an archive immediately, tap **Save backup to Telegram now**.

Telegram archives are encrypted with the same account key, but Telegram is a separate archive destination; the app's Restore screen currently restores from Google Drive, not from Telegram. Scheduled backups use Android WorkManager and run when network is available, so the execution time is approximate rather than a guaranteed exact clock time. A first-time Google authorization is needed to provision or retrieve the shared key.

## Old backups

Backups created by earlier versions use a password-based NCB1 format. The Restore flow detects these older backups and asks for their original backup password. New backups use the account-key NCB2 format and do not ask for a user-entered encryption password.

## Notes

- No OAuth client secret should be embedded in the APK.
- Each Google account has its own app-data area and therefore its own random backup key.
- AppData contents may be removed by the user or when the app's data folder is deleted; keep a separate exported JSON backup for critical notes.
