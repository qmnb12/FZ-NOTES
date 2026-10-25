# FZ NOTES 1.4.4 — Automatic Google Drive sync

## Initial setup
1. Create/select a Google Cloud project and enable Google Drive API.
2. Configure Google Auth Platform and create an Android OAuth client for `com.fz.notes.chat` plus the SHA-1 of the APK signing certificate.
3. While OAuth audience is in **Testing**, add the Google account as a test user. Without this, authorization may be denied.
4. Install FZ NOTES, open **⋮ → Cloud backup → Google Drive settings**, and connect the Google account.

## Automatic backup and restore
- Creating/editing notes schedules a debounced Google Drive automatic backup.
- When opening the app with Drive already connected, it checks/syncs notes to Drive.
- When the app is backgrounded, it requests a best-effort immediate backup using a recently obtained token.
- Automatic sync updates the rolling `FZ-NOTES-AUTO.ncb` backup. **Backup now** creates a separate restore point.
- If app data was cleared or the app is fresh and you connect the same Google account, FZ NOTES checks the latest Drive backup and restores it automatically.
- Manual restore is also available under **⋮ → Cloud backup → Restore notes**.

Background/on-close backup is best-effort and requires a valid, not-expired authorization token and network. Keep the app's JSON export as a separate safety copy for critical notes.

## Encryption model
FZ NOTES encrypts backup data with AES-256-GCM and a fresh random IV per backup. A random 256-bit account key is stored as a binary file in the app's Google Drive `appDataFolder`; a local copy is wrapped by Android Keystore for scheduled Telegram archive work. This lets a fresh install using the same Google account retrieve the key without asking the user for an encryption password.

**Security trade-off:** the key is stored in the same app-only Drive folder as the backups. This is passwordless account-bound protection, not zero-knowledge/end-to-end encryption. An authorized instance of this app on the same Google account can retrieve the key. Secure your Google account and app permissions. If the hidden key file is deleted or lost, newer NCB2 backups cannot be decrypted.

## Telegram archive
Telegram settings are separate from Google Drive settings. Open **⋮ → Cloud backup → Backup schedule** to configure the bot token, chat ID and Manual/Daily/Weekly/Monthly (every 30 days) schedule. The archive is encrypted with the same account key; the primary automatic restore source remains Google Drive. WorkManager scheduling is approximate and depends on network/battery restrictions.

## Older backups
Older NCB1 backups are password encrypted. If one is selected for restore, enter its original backup password. New NCB2 backups use the account key and do not require a user-entered password.

## OAuth note
No OAuth client secret is embedded in the APK. The Android OAuth client must match the exact package name and signing certificate SHA-1 used by the installed APK.
