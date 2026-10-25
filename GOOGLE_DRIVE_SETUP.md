# Google Drive Sync setup for FZ NOTES

This app uses Google Play Services AuthorizationClient and the Google Drive API's hidden `appDataFolder` scope. Backups are encrypted by FZ NOTES before upload. Backup files are managed inside the app; they do not appear in the normal Drive file list.

## One-time Google Cloud setup

1. Open https://console.cloud.google.com/ and create a project (for example, `FZ NOTES Sync`).
2. In **APIs & Services → Library**, enable **Google Drive API**.
3. Configure **Google Auth Platform / OAuth consent screen**. Enter the app name `FZ NOTES`, support email and developer contact email. While testing, add your Google account as a test user if Google asks for it.
4. In **APIs & Services → Credentials → Create credentials → OAuth client ID**, choose **Android**.
5. Enter Android package name: `com.fz.notes.chat`.
6. Enter the SHA-1 fingerprint for the key that signs the APK you will install. For the debug key, run `./gradlew signingReport` in the project and copy the SHA-1 under the debug variant. If you later sign a release APK with a different key, register that key's SHA-1 too.
7. Save the OAuth client. No client secret should be embedded in the Android app.

## First test

1. Build and install the app.
2. Open **Cloud backup → Settings → Google Drive → Connect Google account**.
3. Choose your Google account and approve the Drive app-data permission.
4. Save the encrypted backup password in FZ NOTES, then tap **Backup now**.
5. Use **Restore → Google Drive** to view the latest 30 backups and select one to restore.

## Notes

- Each Google account gets its own separate app-data folder.
- The app-data folder is hidden from the regular Google Drive UI and is only accessible to this app. If you need user-visible files in Drive, the implementation must be changed to a visible folder and a different scope.
- The Drive API does not charge per request in ordinary usage, but Google quota and OAuth policies apply.
- Google authorization depends on a compatible Google Play Services implementation. microG compatibility is not guaranteed.
- Automatic backup currently uses the app's existing save-triggered mechanism. A proper WorkManager background-sync pass and two-way multi-device merge/conflict handling should be added and tested separately before describing this as full real-time sync.
