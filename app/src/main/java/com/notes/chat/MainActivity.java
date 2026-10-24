package com.notes.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.Activity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_IMAGE = 1;
    private static final int REQ_FILE = 2;
    private int fileReplyId = -1;
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
                        reply(id, true, new String(Cloud.readAll(in), StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        reply(id, false, "File padh nahi paye");
                    }
                }).start();
            } else {
                reply(id, false, "Koi file nahi chuni");
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
            return super.startActionMode(wrapSelectionCallback(callback));
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return super.startActionMode(wrapSelectionCallback(callback), type);
        }

        private ActionMode.Callback wrapSelectionCallback(ActionMode.Callback original) {
            return new ActionMode.Callback() {
                private static final int MENU_MONO = 0x4D4F;
                private static final int MENU_CODE = 0x434F;

                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    boolean created = original.onCreateActionMode(mode, menu);
                    if (created) {
                        MenuItem item = menu.add(Menu.NONE, MENU_MONO, 100, "Mono");
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                        MenuItem code = menu.add(Menu.NONE, MENU_CODE, 101, "Code");
                        code.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                    }
                    return created;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    return original.onPrepareActionMode(mode, menu);
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    if (item.getItemId() == MENU_MONO) {
                        evaluateJavascript("window.applyMonoFromNative && window.applyMonoFromNative()", null);
                        mode.finish();
                        return true;
                    }
                    if (item.getItemId() == MENU_CODE) {
                        evaluateJavascript("window.applyCodeFromNative && window.applyCodeFromNative()", null);
                        mode.finish();
                        return true;
                    }
                    return original.onActionItemClicked(mode, item);
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    original.onDestroyActionMode(mode);
                }
            };
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
                    try {
                        startActivityForResult(i, REQ_FILE);
                    } catch (Exception e) {
                        reply(id, false, "File picker nahi khula");
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
