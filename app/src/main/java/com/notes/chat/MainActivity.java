package com.notes.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.graphics.Rect;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.Activity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_IMAGE = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_SAVE_FILE = 3;
    private static final int REQ_DRIVE_AUTH = 904;
    private int pendingDriveAuthId = -1;
    private int fileReplyId = -1;
    private int saveFileReplyId = -1;
    private String pendingSaveText;
    private WebView web;
    private ValueCallback<Uri[]> filePicker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new SelectionWebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // notes are saved with localStorage
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // links open in browser
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Select image"), REQ_IMAGE);
                } catch (Exception e) {
                    filePicker = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onPause() {
        // Best-effort flush when the user backgrounds the app. Avoid triggering this
        // while a Google authorization resolution is already in progress.
        if (web != null && pendingDriveAuthId < 0) {
            try {
                web.evaluateJavascript(
                        "(function(){if(window.flushCloudBackupBeforeClose){window.flushCloudBackupBeforeClose();return 'queued'}return 'skip'})()",
                        null);
            } catch (Exception ignored) { }
        }
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript(
                "(function(){return window.handleAppBack ? window.handleAppBack() : false})()",
                value -> {
                    if (!"true".equals(value)) {
                        if (Build.VERSION.SDK_INT >= 33) {
                            MainActivity.super.onBackPressed();
                        } else {
                            MainActivity.super.onBackPressed();
                        }
                    }
                });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IMAGE && filePicker != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePicker.onReceiveValue(result);
            filePicker = null;
        } else if (requestCode == REQ_FILE) {
            final int id = fileReplyId;
            fileReplyId = -1;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri u = data.getData();
                new Thread(() -> {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) throw new IllegalStateException("Backup file open nahi hui");
                        String imported = new String(Cloud.readAll(in), StandardCharsets.UTF_8);
                        reply(id, true, imported);
                    } catch (Exception e) {
                        String message = e.getMessage();
                        reply(id, false, message == null ? "Backup file padh nahi paye" : "Backup file padh nahi paye: " + message);
                    }
                }).start();
            } else {
                reply(id, false, "Koi file nahi chuni");
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            final int id = saveFileReplyId;
            final String text = pendingSaveText;
            saveFileReplyId = -1;
            pendingSaveText = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                new Thread(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IllegalStateException("Backup file nahi likh paye");
                        out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        reply(id, true, "Backup file saved");
                    } catch (Exception e) {
                        reply(id, false, e.getMessage() == null ? "Backup save failed" : e.getMessage());
                    }
                }).start();
            } else {
                reply(id, false, "Backup save cancel hua");
            }
        } else if (requestCode == REQ_DRIVE_AUTH) {
            int id = pendingDriveAuthId;
            pendingDriveAuthId = -1;
            try {
                if (resultCode != RESULT_OK || data == null) throw new Exception("Google authorization cancel hua");
                AuthorizationResult result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                String token = result.getAccessToken();
                if (token == null || token.isEmpty()) throw new Exception("Google se access token nahi mila");
                reply(id, true, token);
            } catch (Exception e) {
                reply(id, false, e.getMessage() == null ? "Google authorization failed" : e.getMessage());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Wrap WebView's contextual selection action mode and add a Mono action.
     * This avoids relying on a nonexistent WebView setter API.
     */
    private class SelectionWebView extends WebView {
        SelectionWebView(Activity context) { super(context); }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback) {
            // Use the floating selection toolbar anchored to selected text, not
            // the legacy primary action bar at the top of the window.
            return super.startActionMode(wrapSelectionCallback(callback), ActionMode.TYPE_FLOATING);
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return super.startActionMode(wrapSelectionCallback(callback), ActionMode.TYPE_FLOATING);
        }

        private boolean hasMenuTitle(Menu menu, String title) {
            for (int i = 0; i < menu.size(); i++) {
                CharSequence current = menu.getItem(i).getTitle();
                if (current != null && title.equalsIgnoreCase(current.toString())) return true;
            }
            return false;
        }

        private void addSelectionMenuItem(Menu menu, int id, int order, String title, int showAsAction) {
            for (int i = 0; i < menu.size(); i++) {
                MenuItem existing = menu.getItem(i);
                CharSequence current = existing.getTitle();
                if (current != null && title.equalsIgnoreCase(current.toString())) {
                    // Promote platform-provided Cut/Copy actions too, so Mono
                    // can sit beside them rather than being buried in overflow.
                    existing.setShowAsAction(showAsAction);
                    return;
                }
            }
            MenuItem item = menu.add(Menu.NONE, id, order, title);
            item.setShowAsAction(showAsAction);
        }

        private String selectionActionForItem(int id) {
            switch (id) {
                case 0x435059: return "copy";
                case 0x435554: return "cut";
                case 0x424F4C: return "b";
                case 0x495441: return "i";
                case 0x4D4F: return "mono";
                case 0x434F: return "code";
                case 0x535452: return "s";
                case 0x554E44: return "u";
                case 0x51554F: return "quote";
                case 0x53504F: return "spoiler";
                case 0x4C4E4B: return "link";
                case 0x4D454E: return "mention";
                case 0x444154: return "date";
                case 0x524547: return "reg";
                default: return null;
            }
        }

        private ActionMode.Callback wrapSelectionCallback(ActionMode.Callback original) {
            // Keep Callback2 so WebView's exact selection rectangle is preserved.
            // Wrapping it as a plain Callback loses that rectangle and pins the
            // floating toolbar near the top of the screen instead of the text.
            return new ActionMode.Callback2() {
                private static final int MENU_COPY = 0x435059;
                private static final int MENU_CUT = 0x435554;
                private static final int MENU_BOLD = 0x424F4C;
                private static final int MENU_ITALIC = 0x495441;
                private static final int MENU_MONO = 0x4D4F;
                private static final int MENU_CODE = 0x434F;
                private static final int MENU_STRIKE = 0x535452;
                private static final int MENU_UNDERLINE = 0x554E44;
                private static final int MENU_QUOTE = 0x51554F;
                private static final int MENU_SPOILER = 0x53504F;
                private static final int MENU_LINK = 0x4C4E4B;
                private static final int MENU_MENTION = 0x4D454E;
                private static final int MENU_DATE = 0x444154;
                private static final int MENU_REGULAR = 0x524547;

                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    boolean created = original.onCreateActionMode(mode, menu);
                    if (created) {
                        addSelectionMenuItem(menu, MENU_COPY, 100, "Copy", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, MENU_CUT, 101, "Cut", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, MENU_MONO, 102, "Mono", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, MENU_BOLD, 103, "Bold", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_ITALIC, 104, "Italic", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_CODE, 105, "Code", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_STRIKE, 106, "Strikethrough", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_UNDERLINE, 107, "Underline", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_QUOTE, 108, "Quote", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_SPOILER, 109, "Spoiler", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_LINK, 110, "Create link", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_MENTION, 111, "Create mention", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_DATE, 112, "Date", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_REGULAR, 113, "Regular", MenuItem.SHOW_AS_ACTION_NEVER);
                        evaluateJavascript("window.setNativeSelectionMenuOpen && window.setNativeSelectionMenuOpen(true)", null);
                    }
                    return created;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    boolean prepared = original.onPrepareActionMode(mode, menu);
                    if (prepared) {
                        // WebView/platform may refresh the menu during prepare;
                        // restore our actions every time and keep Mono visible.
                        addSelectionMenuItem(menu, MENU_COPY, 100, "Copy", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, MENU_CUT, 101, "Cut", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, 0x53454C, 102, "Select all", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_MONO, 103, "Mono", MenuItem.SHOW_AS_ACTION_ALWAYS);
                        addSelectionMenuItem(menu, MENU_BOLD, 104, "Bold", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_ITALIC, 105, "Italic", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_CODE, 106, "Code", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_STRIKE, 107, "Strikethrough", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_UNDERLINE, 108, "Underline", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_QUOTE, 109, "Quote", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_SPOILER, 110, "Spoiler", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_LINK, 111, "Create link", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_MENTION, 112, "Create mention", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_DATE, 113, "Date", MenuItem.SHOW_AS_ACTION_NEVER);
                        addSelectionMenuItem(menu, MENU_REGULAR, 114, "Regular", MenuItem.SHOW_AS_ACTION_NEVER);
                    }
                    return prepared;
                }

                @Override
                public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                    if (original instanceof ActionMode.Callback2) {
                        ((ActionMode.Callback2) original).onGetContentRect(mode, view, outRect);
                    } else {
                        outRect.set(0, 0, view.getWidth(), view.getHeight());
                    }
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    String action = selectionActionForItem(item.getItemId());
                    if (action != null) {
                        evaluateJavascript("window.nativeSelectionAction && window.nativeSelectionAction("
                                + JSONObject.quote(action) + ")", null);
                        mode.finish();
                        return true;
                    }
                    return original.onActionItemClicked(mode, item);
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    original.onDestroyActionMode(mode);
                    evaluateJavascript("window.setNativeSelectionMenuOpen && window.setNativeSelectionMenuOpen(false)", null);
                }
            };
        }
    }

    private void authorizeDrive(int callbackId) {
        try {
            AuthorizationRequest request = AuthorizationRequest.builder()
                    .setRequestedScopes(Collections.singletonList(
                            new Scope("https://www.googleapis.com/auth/drive.appdata")))
                    .build();
            Identity.getAuthorizationClient(this).authorize(request)
                    .addOnSuccessListener(result -> {
                        if (result.hasResolution()) {
                            pendingDriveAuthId = callbackId;
                            try {
                                startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                        REQ_DRIVE_AUTH, null, 0, 0, 0);
                            } catch (Exception e) {
                                pendingDriveAuthId = -1;
                                reply(callbackId, false, "Google permission screen nahi khul saki: " + e.getMessage());
                            }
                        } else {
                            String token = result.getAccessToken();
                            if (token == null || token.isEmpty()) reply(callbackId, false, "Google access token nahi mila");
                            else reply(callbackId, true, token);
                        }
                    })
                    .addOnFailureListener(e -> reply(callbackId, false,
                            e.getMessage() == null ? "Google authorization failed" : e.getMessage()));
        } catch (Exception e) {
            reply(callbackId, false, e.getMessage() == null ? "Google authorization failed" : e.getMessage());
        }
    }

    private void handleReminder(int callbackId, String rawArgs) {
        try {
            JSONObject data = new JSONObject(rawArgs);
            String taskId = data.optString("id", "task");
            String title = data.optString("title", "NotesChat task");
            long due = data.optLong("due", 0L);
            boolean done = data.optBoolean("done", false);
            Intent intent = new Intent(this, ReminderReceiver.class);
            intent.putExtra("taskId", taskId);
            intent.putExtra("title", title);
            int requestCode = taskId.hashCode();
            PendingIntent pending = PendingIntent.getBroadcast(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (done || due <= System.currentTimeMillis()) {
                if (alarms != null) alarms.cancel(pending);
                reply(callbackId, true, "Reminder cancelled or due time passed");
                return;
            }
            if (alarms == null) throw new IllegalStateException("Alarm service unavailable");
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 501);
            }
            reply(callbackId, true, "Reminder scheduled");
        } catch (Exception e) {
            reply(callbackId, false, e.getMessage() == null ? "Reminder failed" : e.getMessage());
        }
    }

    /** JS -> Java bridge for encrypted cloud backup (runs on a background thread). */
    class Bridge {
        @JavascriptInterface
        public void run(final int id, final String op, final String args) {
            if ("driveauth".equals(op)) {
                runOnUiThread(() -> authorizeDrive(id));
                return;
            }
            if ("reminder".equals(op)) {
                runOnUiThread(() -> handleReminder(id, args));
                return;
            }
            if ("pickfile".equals(op)) {
                runOnUiThread(() -> {
                    fileReplyId = id;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"});
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_FILE);
                    } catch (Exception e) {
                        fileReplyId = -1;
                        reply(id, false, "Backup file picker nahi khula");
                    }
                });
                return;
            }
            if ("savefile".equals(op)) {
                final String text;
                final String filename;
                try {
                    JSONObject payload = new JSONObject(args);
                    text = payload.optString("text", "");
                    filename = payload.optString("filename", "FZ-Notes-backup.json");
                } catch (Exception e) {
                    reply(id, false, "Backup data invalid hai");
                    return;
                }
                runOnUiThread(() -> {
                    saveFileReplyId = id;
                    pendingSaveText = text;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_TITLE, filename);
                    try {
                        startActivityForResult(i, REQ_SAVE_FILE);
                    } catch (Exception e) {
                        saveFileReplyId = -1;
                        pendingSaveText = null;
                        reply(id, false, "Backup save dialog nahi khula");
                    }
                });
                return;
            }
            new Thread(() -> {
                try {
                    reply(id, true, Cloud.handle(MainActivity.this, op, new JSONObject(args)));
                } catch (Exception e) {
                    String m = e.getMessage();
                    reply(id, false, m == null ? e.toString() : m);
                }
            }).start();
        }
    }

    private void reply(int id, boolean ok, String data) {
        final String js = "window.onNative(" + id + "," + ok + "," + JSONObject.quote(data) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }
}
