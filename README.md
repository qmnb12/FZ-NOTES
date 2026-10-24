# Notes Chat

Telegram-style notes app. Har note ek chat message ki tarah send hota hai.
Topics, reply, edit, pin, search, image, encrypted cloud backup sab included.

## Structure
- `app/src/main/assets/index.html` : poora app (HTML/CSS/JS). UI/feature yahin badlein.
- `app/.../MainActivity.java` : WebView wrapper + JS bridge.
- `app/.../Cloud.java` : AES-256-GCM encryption (PBKDF2) + Nextcloud/WebDAV, Telegram bot, GitHub upload.

## Build (Code On The Go)
Project open karein, Gradle sync, phir Run. minSdk 29 (Android 10+).

## Cloud backup
Menu (top-right) > Cloud backup > Settings me details bharein.
- Nextcloud: folder URL (https://host/remote.php/dav/files/USER/folder), username, app password.
- Telegram: @BotFather se bot token, bot ko /start bhejein, Detect se chat id.
- GitHub: private repo + personal access token (Contents: read/write).
Backup file `notes-backup.ncb` password se encrypt hoti hai. Password phone ke Android Keystore me (optional) save ho sakta hai: Cloud backup > Save password on phone.

## Phone storage copy
Har change ke baad notes ki ek copy `Documents/NotesChat/notes-mirror.json` me apne aap save hoti hai (plain JSON, file manager me dikhti hai).
App data clear hone par app ye copy khud wapas le aata hai. App uninstall ke baad: menu > "Open backup file from phone" se wahi file chunein.
