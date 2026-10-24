package com.notes.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
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

        web = new WebView(this);
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

    /** JS -> Java bridge for encrypted cloud backup (runs on a background thread). */
    class Bridge {
        @JavascriptInterface
        public void run(final int id, final String op, final String args) {
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
