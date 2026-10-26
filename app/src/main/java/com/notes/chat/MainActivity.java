package com.notes.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.Activity;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.PopupWindow;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.util.TypedValue;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.json.JSONArray;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.common.api.ApiException;
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
    private FrameLayout rootLayout;
    private FrameLayout nativeChatLayer;
    private RecyclerView nativeChatView;
    private TextView nativeEmptyView;
    private NativeChatAdapter nativeChatAdapter;
    private boolean nativeChatRequestedVisible;
    private boolean nativeChatBoundsReady;
    private int nativeChatLeft = -1, nativeChatTop = -1, nativeChatWidth = -1, nativeChatHeight = -1;
    private boolean nativeChatVisibilityTransitionRunning;
    private boolean nativeChatTransitionTargetVisible;
    private ValueCallback<Uri[]> filePicker;
    private PopupWindow nativeMessagePopup;
    private View nativeMessagePopupCard;
    private String nativeMessagePopupId = "";
    private boolean nativeMessagePopupClosing;
    private float nativeMessagePopupStartDx, nativeMessagePopupStartDy;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new SelectionWebView(this);
        // Long-pressing an attached image should open its viewer on tap, not invoke
        // WebView's image-selection/context action mode (Cut/Copy/Mono).
        web.setOnLongClickListener(view -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            if (hit == null) return false;
            int type = hit.getType();
            return type == WebView.HitTestResult.IMAGE_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        });
        rootLayout = new FrameLayout(this);
        rootLayout.setClipChildren(true);
        rootLayout.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        nativeChatLayer = new FrameLayout(this);
        nativeChatLayer.setBackgroundColor(Color.TRANSPARENT);
        nativeChatLayer.setClipChildren(true);
        nativeChatLayer.setClipToPadding(true);
        nativeChatLayer.setVisibility(View.GONE);

        nativeChatView = new RecyclerView(this);
        nativeChatView.setBackgroundColor(Color.TRANSPARENT);
        nativeChatView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        nativeChatView.setClipToPadding(false);
        nativeChatView.setPadding(0, dp(7), 0, dp(54));
        nativeChatView.setItemViewCacheSize(12);
        nativeChatView.setNestedScrollingEnabled(false);
        nativeChatView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setAddDuration(210);
        itemAnimator.setMoveDuration(230);
        itemAnimator.setRemoveDuration(190);
        itemAnimator.setChangeDuration(0);
        itemAnimator.setSupportsChangeAnimations(false);
        nativeChatView.setItemAnimator(itemAnimator);
        nativeChatAdapter = new NativeChatAdapter(this, new NativeChatAdapter.Listener() {
            @Override public void onSelectionChanged(java.util.List<String> selectedIds) {
                JSONArray ids = new JSONArray();
                for (String id : selectedIds) ids.put(id);
                final String quoted = JSONObject.quote(ids.toString());
                final float[] pocketOrigin = new float[]{Float.NaN, Float.NaN};
                if (web != null && nativeChatAdapter != null) {
                    String changedId = nativeChatAdapter.getLastSelectionChangedId();
                    int[] anchor = nativeChatAdapter.getBubbleCenterOnScreen(changedId);
                    if (anchor != null) {
                        int[] webLocation = new int[2];
                        web.getLocationOnScreen(webLocation);
                        float density = getResources().getDisplayMetrics().density;
                        pocketOrigin[0] = (anchor[0] - webLocation[0]) / density;
                        pocketOrigin[1] = (anchor[1] - webLocation[1]) / density;
                    }
                }
                final String originArgs = Float.isFinite(pocketOrigin[0]) && Float.isFinite(pocketOrigin[1])
                        ? "," + pocketOrigin[0] + "," + pocketOrigin[1] : "";
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript(
                            "window.nativeChatSelectionChanged&&window.nativeChatSelectionChanged(" + quoted + originArgs + ")", null);
                });
            }

            @Override public void onAction(String id, String action, int index) {
                final String js = "window.nativeChatAction&&window.nativeChatAction("
                        + JSONObject.quote(id) + "," + JSONObject.quote(action) + "," + index + ")";
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript(js, null);
                });
            }
        });
        nativeChatAdapter.attachRecyclerView(nativeChatView);
        nativeChatView.setAdapter(nativeChatAdapter);
        nativeChatView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float downX, downY, deltaX;
            private String noteId;
            private boolean tracking, swiping;

            @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        deltaX = 0f;
                        noteId = null;
                        tracking = !nativeChatAdapter.hasSelection();
                        swiping = false;
                        if (tracking) noteId = nativeChatAdapter.idAt(downX, downY);
                        tracking = tracking && noteId != null;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!tracking) return false;
                        if (nativeChatAdapter.hasSelection()) {
                            tracking = false;
                            return false;
                        }
                        deltaX = event.getX() - downX;
                        float deltaY = event.getY() - downY;
                        if (Math.abs(deltaX) > dp(12) && Math.abs(deltaX) > Math.abs(deltaY) * 1.25f) {
                            swiping = true;
                            rv.getParent().requestDisallowInterceptTouchEvent(true);
                            nativeChatAdapter.setSwipeOffset(noteId, deltaX);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!swiping) tracking = false;
                        return false;
                    default:
                        return swiping;
                }
            }

            @Override public void onTouchEvent(RecyclerView rv, MotionEvent event) {
                if (!swiping) return;
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    deltaX = event.getX() - downX;
                    nativeChatAdapter.setSwipeOffset(noteId, deltaX);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP;
                    nativeChatAdapter.finishSwipe(noteId, deltaX, commit);
                    swiping = false;
                    tracking = false;
                    noteId = null;
                    rv.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
        });
        nativeChatLayer.addView(nativeChatView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        nativeEmptyView = new TextView(this);
        nativeEmptyView.setText("Apna pehla note likho. Ye chat ki tarah send hoga.");
        nativeEmptyView.setTextSize(14);
        nativeEmptyView.setGravity(android.view.Gravity.CENTER);
        nativeEmptyView.setPadding(dp(28), dp(24), dp(28), dp(24));
        nativeEmptyView.setVisibility(View.GONE);
        nativeChatLayer.addView(nativeEmptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(nativeChatLayer, new FrameLayout.LayoutParams(0, 0));
        setContentView(rootLayout);

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
        if (nativeMessagePopup != null && nativeMessagePopup.isShowing()) {
            dismissNativeMessagePopup();
            return;
        }
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
            setDriveAuthDebug("Google authorization returned to app (resultCode=" + resultCode
                    + ", data=" + (data != null) + ").");
            try {
                // Read the Play Services result before interpreting RESULT_CANCELED;
                // otherwise the specific OAuth/API error gets hidden as a cancellation.
                if (data == null) {
                    throw new Exception("Google authorization returned no result data (resultCode="
                            + resultCode + ")");
                }
                AuthorizationResult result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                String token = result.getAccessToken();
                if (token == null || token.isEmpty()) throw new Exception("Google se access token nahi mila");
                setDriveAuthDebug("Google authorization returned an access token. Checking Drive access.");
                if (id >= 0) reply(id, true, token);
                else savePendingDriveAuthResult(true, token);
            } catch (Exception e) {
                String message = driveAuthorizationError(e);
                setDriveAuthDebug("Google authorization failed: " + message);
                if (id >= 0) reply(id, false, message);
                else savePendingDriveAuthResult(false, message);
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

        private boolean isImageHitTarget() {
            WebView.HitTestResult hit = getHitTestResult();
            if (hit == null) return false;
            int hitType = hit.getType();
            return hitType == WebView.HitTestResult.IMAGE_TYPE
                    || hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback) {
            // Never attach the text-selection toolbar to an image attachment.
            if (isImageHitTarget()) return null;
            // Keep Android's floating toolbar anchored to the selected text.
            return super.startActionMode(wrapSelectionCallback(callback), ActionMode.TYPE_FLOATING);
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            if (isImageHitTarget()) return null;
            return super.startActionMode(wrapSelectionCallback(callback), ActionMode.TYPE_FLOATING);
        }

        private void addSelectionMenuItem(Menu menu, int id, int order, String title, int showAsAction) {
            MenuItem item = menu.add(Menu.NONE, id, order, title);
            item.setShowAsAction(showAsAction);
        }

        private String selectionActionForItem(int id) {
            switch (id) {
                case 0x435059: return "copy";
                case 0x435554: return "cut";
                case 0x504153: return "paste";
                case 0x424F4C: return "b";
                case 0x495441: return "i";
                case 0x4D4F: return "mono";
                case 0x53454C: return "selectAll";
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
                private static final int MENU_PASTE = 0x504153;
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
                private static final int MENU_SELECT_ALL = 0x53454C;

                private void populateSelectionMenu(Menu menu) {
                    // The WebView callback adds platform actions such as Copy more than once
                    // on some Android builds. Rebuild the menu deterministically instead.
                    menu.clear();
                    // Keep the two most useful actions visible and stable; remaining actions stay in overflow.
                    addSelectionMenuItem(menu, MENU_COPY, 10, "Copy", MenuItem.SHOW_AS_ACTION_ALWAYS);
                    addSelectionMenuItem(menu, MENU_MONO, 20, "Mono", MenuItem.SHOW_AS_ACTION_ALWAYS);
                    addSelectionMenuItem(menu, MENU_CUT, 30, "Cut", MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    addSelectionMenuItem(menu, MENU_PASTE, 35, "Paste", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_SELECT_ALL, 40, "Select all", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_BOLD, 50, "Bold", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_ITALIC, 60, "Italic", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_CODE, 70, "Code", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_STRIKE, 80, "Strikethrough", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_UNDERLINE, 90, "Underline", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_QUOTE, 100, "Quote", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_SPOILER, 110, "Spoiler", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_LINK, 120, "Create link", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_MENTION, 130, "Create mention", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_DATE, 140, "Date", MenuItem.SHOW_AS_ACTION_NEVER);
                    addSelectionMenuItem(menu, MENU_REGULAR, 150, "Regular", MenuItem.SHOW_AS_ACTION_NEVER);
                }

                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    boolean created = original.onCreateActionMode(mode, menu);
                    if (created) {
                        populateSelectionMenu(menu);
                        evaluateJavascript("window.setNativeSelectionMenuOpen && window.setNativeSelectionMenuOpen(true)", null);
                    }
                    return created;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    boolean prepared = original.onPrepareActionMode(mode, menu);
                    // Rebuilding action items on every prepare makes the floating toolbar flicker
                    // while selection handles move. Rebuild only if our Mono item disappeared.
                    if (prepared && menu.findItem(0x4D4F) == null) {
                        populateSelectionMenu(menu);
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

    private static final String DRIVE_AUTH_PREFS = "fz_drive_authorization";

    private void setDriveAuthDebug(String message) {
        try {
            getSharedPreferences(DRIVE_AUTH_PREFS, MODE_PRIVATE).edit()
                    .putString("last_status", message == null ? "Unknown authorization status" : message)
                    .putLong("last_status_at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    private String getDriveAuthDebug() {
        return getSharedPreferences(DRIVE_AUTH_PREFS, MODE_PRIVATE)
                .getString("last_status", "No Google authorization attempt recorded yet");
    }

    private void savePendingDriveAuthResult(boolean ok, String data) {
        try {
            JSONObject result = new JSONObject();
            result.put("ok", ok);
            result.put("data", data == null ? "" : data);
            result.put("timestamp", System.currentTimeMillis());
            getSharedPreferences(DRIVE_AUTH_PREFS, MODE_PRIVATE).edit()
                    .putString("pending_result", result.toString()).apply();
        } catch (Exception e) {
            setDriveAuthDebug("Could not save returned authorization result: " + e.getMessage());
        }
    }

    private String takePendingDriveAuthResult() {
        SharedPreferences sp = getSharedPreferences(DRIVE_AUTH_PREFS, MODE_PRIVATE);
        String raw = sp.getString("pending_result", "");
        if (raw.isEmpty()) return "";
        sp.edit().remove("pending_result").apply();
        try {
            JSONObject result = new JSONObject(raw);
            long timestamp = result.optLong("timestamp", 0L);
            if (timestamp <= 0 || System.currentTimeMillis() - timestamp > 10 * 60 * 1000L) {
                setDriveAuthDebug("Previous Google authorization result expired. Tap Connect again.");
                return "";
            }
        } catch (Exception e) {
            setDriveAuthDebug("Saved Google authorization result was invalid.");
            return "";
        }
        return raw;
    }

    private String driveAuthorizationError(Exception error) {
        if (error instanceof ApiException) {
            int status = ((ApiException) error).getStatusCode();
            if (status == 10) {
                return "Google OAuth DEVELOPER_ERROR (10): Android OAuth client me package com.fz.notes.chat aur is APK ke signing SHA-1 ka match zaroori hai. GitHub Actions wali APK ka SHA-1 bhi Google Cloud Console me register karein, ya registered debug.keystore se build karein.";
            }
            if (status == 7) return "Google connection ke liye internet available nahi hai (NETWORK_ERROR)";
            if (status == 12501) return "Google account authorization cancel hua";
            return "Google authorization error " + status + ": "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return error.getMessage() == null ? "Google authorization failed: " + error.getClass().getSimpleName()
                : error.getMessage();
    }

    private void authorizeDrive(int callbackId) {
        try {
            setDriveAuthDebug("Starting Google authorization request.");
            AuthorizationRequest request = AuthorizationRequest.builder()
                    .setRequestedScopes(Collections.singletonList(
                            new Scope("https://www.googleapis.com/auth/drive.appdata")))
                    .build();
            Identity.getAuthorizationClient(this).authorize(request)
                    .addOnSuccessListener(result -> {
                        if (result.hasResolution()) {
                            setDriveAuthDebug("Google account/permission chooser opened; waiting for result.");
                            pendingDriveAuthId = callbackId;
                            try {
                                startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                        REQ_DRIVE_AUTH, null, 0, 0, 0);
                            } catch (Exception e) {
                                pendingDriveAuthId = -1;
                                String message = "Google permission screen nahi khul saki: " + e.getMessage();
                                setDriveAuthDebug(message);
                                reply(callbackId, false, message);
                            }
                        } else {
                            String token = result.getAccessToken();
                            if (token == null || token.isEmpty()) {
                                String message = "Google access token nahi mila";
                                setDriveAuthDebug(message);
                                reply(callbackId, false, message);
                            } else {
                                setDriveAuthDebug("Google authorization returned an access token; checking Drive access.");
                                reply(callbackId, true, token);
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        String message = driveAuthorizationError(e);
                        setDriveAuthDebug("Google authorization request failed: " + message);
                        reply(callbackId, false, message);
                    });
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Google authorization failed" : e.getMessage();
            setDriveAuthDebug("Google authorization request failed: " + message);
            reply(callbackId, false, message);
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
        public void showNativeMessageMenu(final String id, final String json) {
            runOnUiThread(() -> showNativeMessageMenuInternal(id, json));
        }

        @JavascriptInterface
        public void dismissNativeMessageMenu() {
            runOnUiThread(() -> dismissNativeMessagePopup());
        }

        @JavascriptInterface
        public void setNativeChatData(final String json) {
            runOnUiThread(() -> {
                if (nativeChatAdapter == null || nativeChatView == null) return;
                nativeChatAdapter.submitJson(json == null ? "{}" : json);
                boolean empty = nativeChatAdapter.getItemCount() == 0;
                nativeEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                boolean light = false;
                try { light = new JSONObject(json).optString("theme", "dark").equals("light"); }
                catch (Exception ignored) { }
                nativeEmptyView.setTextColor(light ? Color.rgb(99, 113, 137) : Color.rgb(147, 164, 184));
            });
        }

        @JavascriptInterface
        public void setNativeSelectionIds(final String json) {
            runOnUiThread(() -> {
                if (nativeChatAdapter != null) nativeChatAdapter.setSelectedIdsJson(json == null ? "[]" : json);
            });
        }

        @JavascriptInterface
        public void setNativeChatBounds(final int left, final int top, final int width, final int height) {
            runOnUiThread(() -> {
                if (nativeChatLayer == null || rootLayout == null) return;
                float density = getResources().getDisplayMetrics().density;
                int l = Math.max(0, Math.round(left * density));
                int t = Math.max(0, Math.round(top * density));
                int w = Math.max(0, Math.round(width * density));
                int h = Math.max(0, Math.round(height * density));
                nativeChatBoundsReady = w > 0 && h > 0;
                // ResizeObserver can report the same bounds many times during toolbar/dialog
                // transitions. Avoid forcing RecyclerView layout when its rectangle is unchanged.
                if (l != nativeChatLeft || t != nativeChatTop
                        || w != nativeChatWidth || h != nativeChatHeight) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) nativeChatLayer.getLayoutParams();
                    if (lp == null) lp = new FrameLayout.LayoutParams(w, h);
                    lp.leftMargin = l;
                    lp.topMargin = t;
                    lp.width = w;
                    lp.height = h;
                    nativeChatLayer.setLayoutParams(lp);
                    nativeChatLeft = l;
                    nativeChatTop = t;
                    nativeChatWidth = w;
                    nativeChatHeight = h;
                }
                updateNativeChatVisibility();
            });
        }

        @JavascriptInterface
        public void setNativeChatVisible(final boolean visible) {
            runOnUiThread(() -> {
                nativeChatRequestedVisible = visible;
                updateNativeChatVisibility();
            });
        }

        @JavascriptInterface
        public void scrollNativeChatToId(final String id) {
            runOnUiThread(() -> {
                if (nativeChatAdapter != null && id != null) nativeChatAdapter.scrollToId(id);
            });
        }

        @JavascriptInterface
        public void run(final int id, final String op, final String args) {
            if ("clipboardread".equals(op)) {
                runOnUiThread(() -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard == null || !clipboard.hasPrimaryClip()
                                || clipboard.getPrimaryClip() == null
                                || clipboard.getPrimaryClip().getItemCount() == 0) {
                            reply(id, true, "");
                            return;
                        }
                        CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                        reply(id, true, value == null ? "" : value.toString());
                    } catch (Exception e) {
                        reply(id, false, e.getMessage() == null ? "Clipboard read failed" : e.getMessage());
                    }
                });
                return;
            }
            if ("driveauth".equals(op)) {
                runOnUiThread(() -> authorizeDrive(id));
                return;
            }
            if ("driveauthdebug".equals(op)) {
                reply(id, true, getDriveAuthDebug());
                return;
            }
            if ("driveauthrecover".equals(op)) {
                reply(id, true, takePendingDriveAuthResult());
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
                    String result = Cloud.handle(MainActivity.this, op, new JSONObject(args));
                    if ("drivekey".equals(op)) {
                        setDriveAuthDebug("Google authorization passed; Drive appData access succeeded.");
                    } else if ("drivehistory".equals(op)) {
                        setDriveAuthDebug("Google Drive API access verified; backup listing succeeded.");
                    }
                    reply(id, true, result);
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message == null) message = e.toString();
                    if ("drivekey".equals(op) || "drivehistory".equals(op)) {
                        setDriveAuthDebug("Google Drive API failed during " + op + ": " + message);
                    }
                    reply(id, false, message);
                }
            }).start();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void dismissNativeMessagePopup() {
        dismissNativeMessagePopup(null);
    }

    private void dismissNativeMessagePopup(Runnable afterDismiss) {
        final PopupWindow popup = nativeMessagePopup;
        final View card = nativeMessagePopupCard;
        if (popup == null || card == null || !popup.isShowing()) {
            dismissNativeMessagePopupImmediately();
            if (afterDismiss != null) afterDismiss.run();
            return;
        }
        if (nativeMessagePopupClosing) return;
        nativeMessagePopupClosing = true;
        card.animate().cancel();
        card.animate()
                .alpha(0f)
                .translationX(nativeMessagePopupStartDx)
                .translationY(nativeMessagePopupStartDy)
                .scaleX(0.055f).scaleY(0.055f)
                .setDuration(225)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withLayer()
                .withEndAction(() -> {
                    if (nativeMessagePopup == popup) {
                        nativeMessagePopup = null;
                        nativeMessagePopupCard = null;
                        nativeMessagePopupId = "";
                    }
                    nativeMessagePopupClosing = false;
                    try { popup.dismiss(); } catch (Exception ignored) { }
                    if (afterDismiss != null) afterDismiss.run();
                })
                .start();
    }

    private void dismissNativeMessagePopupImmediately() {
        PopupWindow old = nativeMessagePopup;
        View oldCard = nativeMessagePopupCard;
        nativeMessagePopup = null;
        nativeMessagePopupCard = null;
        nativeMessagePopupId = "";
        nativeMessagePopupClosing = false;
        if (oldCard != null) oldCard.animate().cancel();
        if (old != null) {
            try { old.dismiss(); } catch (Exception ignored) { }
        }
    }

    private void showNativeMessageMenuInternal(String id, String json) {
        if (rootLayout == null || web == null) return;
        final JSONObject config;
        final JSONArray rows;
        try {
            config = new JSONObject(json == null ? "{}" : json);
            rows = config.optJSONArray("items");
            if (rows == null) return;
        } catch (Exception e) {
            return;
        }
        dismissNativeMessagePopupImmediately();
        final boolean light = "light".equals(config.optString("theme", "dark"));
        final int foreground = light ? Color.rgb(25, 35, 51) : Color.rgb(235, 243, 255);
        final int muted = light ? Color.rgb(95, 112, 136) : Color.rgb(155, 174, 200);
        final int accent = light ? Color.rgb(40, 111, 219) : Color.rgb(126, 180, 255);
        final int panel = light ? Color.rgb(250, 252, 255) : Color.rgb(18, 29, 46);
        final int border = light ? Color.rgb(223, 231, 243) : Color.rgb(47, 65, 89);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(9), dp(8), dp(8));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(panel);
        panelBackground.setCornerRadius(dp(20));
        panelBackground.setStroke(dp(1), border);
        card.setBackground(panelBackground);
        card.setElevation(dp(16));
        card.setAlpha(0f);
        card.setTranslationY(-dp(7));
        card.setScaleX(0.985f);
        card.setScaleY(0.985f);
        card.setClipToPadding(false);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("FZ NOTES  ·  " + ("topics".equals(config.optString("mode")) ? "ORGANIZE" : "MESSAGE"));
        eyebrow.setTextColor(accent);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setPadding(dp(10), dp(5), dp(10), dp(4));
        card.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(config.optString("title", "Message options"));
        heading.setTextColor(foreground);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setPadding(dp(10), dp(1), dp(10), dp(2));
        card.addView(heading);

        String previewText = config.optString("preview", "");
        if (!previewText.isEmpty()) {
            TextView preview = new TextView(this);
            preview.setText(previewText);
            preview.setTextColor(muted);
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            preview.setSingleLine(true);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            preview.setPadding(dp(10), 0, dp(10), dp(8));
            card.addView(preview);
        }
        View separator = new View(this);
        separator.setBackgroundColor(border);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        sepLp.setMargins(dp(7), dp(3), dp(7), dp(5));
        card.addView(separator, sepLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rowsLayout = new LinearLayout(this);
        rowsLayout.setOrientation(LinearLayout.VERTICAL);

        final String targetId = id == null ? "" : id;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject rowData = rows.optJSONObject(i);
            if (rowData == null) continue;
            final String key = rowData.optString("key", "");
            if (key.isEmpty()) continue;
            if ("divider".equals(key)) {
                View divider = new View(this);
                divider.setBackgroundColor(border);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dlp.setMargins(dp(10), dp(4), dp(10), dp(4));
                rowsLayout.addView(divider, dlp);
                continue;
            }
            final boolean danger = rowData.optBoolean("danger", false);
            LinearLayout action = new LinearLayout(this);
            action.setOrientation(LinearLayout.HORIZONTAL);
            action.setGravity(Gravity.CENTER_VERTICAL);
            action.setMinimumHeight(dp(42));
            action.setPadding(dp(8), dp(4), dp(10), dp(4));
            GradientDrawable actionBg = new GradientDrawable();
            actionBg.setColor(Color.TRANSPARENT);
            actionBg.setCornerRadius(dp(12));
            android.content.res.ColorStateList rippleColor =
                    android.content.res.ColorStateList.valueOf(light
                            ? Color.rgb(226, 237, 253) : Color.rgb(38, 56, 80));
            action.setBackground(new android.graphics.drawable.RippleDrawable(
                    rippleColor, actionBg, null));
            action.setClickable(true);
            action.setFocusable(true);

            TextView icon = new TextView(this);
            icon.setText(rowData.optString("icon", "•"));
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            icon.setGravity(Gravity.CENTER);
            icon.setTextColor(danger ? (light ? Color.rgb(190, 46, 60) : Color.rgb(255, 129, 143)) : accent);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setColor(danger
                    ? (light ? Color.rgb(255, 239, 240) : Color.rgb(64, 35, 48))
                    : (light ? Color.rgb(236, 243, 253) : Color.rgb(29, 44, 65)));
            iconBg.setCornerRadius(dp(10));
            iconBg.setStroke(dp(1), danger
                    ? (light ? Color.rgb(244, 213, 217) : Color.rgb(86, 49, 62)) : border);
            icon.setBackground(iconBg);
            action.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

            TextView label = new TextView(this);
            label.setText(rowData.optString("title", key));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setTextColor(danger ? (light ? Color.rgb(190, 46, 60) : Color.rgb(255, 129, 143)) : foreground);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.leftMargin = dp(11);
            action.addView(label, textLp);
            action.setContentDescription(label.getText());
            action.setOnClickListener(v -> {
                PopupWindow popup = nativeMessagePopup;
                nativeMessagePopup = null;
                nativeMessagePopupId = "";
                Runnable invoke = () -> {
                    if (web == null) return;
                    String js = "window.nativeMessageMenuAction&&window.nativeMessageMenuAction("
                            + JSONObject.quote(targetId) + "," + JSONObject.quote(key) + ")";
                    web.evaluateJavascript(js, null);
                };
                if (popup != null && popup.isShowing()) {
                    View content = popup.getContentView();
                    content.animate().cancel();
                    content.animate().alpha(0f).translationY(-dp(5)).scaleX(0.99f).scaleY(0.99f)
                            .setDuration(105)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.1f))
                            .withEndAction(() -> {
                                try { popup.dismiss(); } catch (Exception ignored) { }
                                invoke.run();
                            }).start();
                } else invoke.run();
            });
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionLp.setMargins(dp(2), dp(1), dp(2), dp(1));
            rowsLayout.addView(action, actionLp);
        }
        scroll.addView(rowsLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxMenuHeight = Math.max(dp(150), rootLayout.getHeight() - dp(170));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(430), maxMenuHeight)));

        int availableWidth = Math.max(dp(220), rootLayout.getWidth() - dp(80));
        int menuWidth = Math.min(dp(390), availableWidth);
        PopupWindow popup = new PopupWindow(card, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        popup.setClippingEnabled(true);
        popup.setElevation(dp(18));
        popup.setOnDismissListener(() -> {
            if (nativeMessagePopup == popup) {
                nativeMessagePopup = null;
                nativeMessagePopupId = "";
            }
            card.animate().cancel();
        });
        nativeMessagePopup = popup;
        nativeMessagePopupId = targetId;
        // Bottom-anchored popup overlays the chat without changing WebView or RecyclerView bounds.
        int bottomOffset = 0;
        try {
            popup.showAtLocation(rootLayout, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, bottomOffset);
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(225)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withLayer()
                    .start();
        } catch (Exception e) {
            dismissNativeMessagePopup();
        }
    }

    private void updateNativeChatVisibility() {
        if (nativeChatLayer == null) return;
        final boolean shouldShow = nativeChatRequestedVisible && nativeChatBoundsReady;
        if (shouldShow) {
            if (nativeChatLayer.getVisibility() == View.VISIBLE
                    && (!nativeChatVisibilityTransitionRunning || nativeChatTransitionTargetVisible)) return;
            nativeChatLayer.animate().cancel();
            nativeChatTransitionTargetVisible = true;
            nativeChatVisibilityTransitionRunning = true;
            if (nativeChatLayer.getVisibility() != View.VISIBLE) {
                nativeChatLayer.setAlpha(0f);
                nativeChatLayer.setTranslationY(dp(5));
                nativeChatLayer.setVisibility(View.VISIBLE);
            }
            nativeChatLayer.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        if (nativeChatTransitionTargetVisible) nativeChatVisibilityTransitionRunning = false;
                    })
                    .start();
            return;
        }
        if (nativeChatLayer.getVisibility() != View.VISIBLE
                || (nativeChatVisibilityTransitionRunning && !nativeChatTransitionTargetVisible)) return;
        nativeChatLayer.animate().cancel();
        nativeChatTransitionTargetVisible = false;
        nativeChatVisibilityTransitionRunning = true;
        nativeChatLayer.animate()
                .alpha(0f).translationY(dp(5))
                .setDuration(135)
                .setInterpolator(new AccelerateInterpolator(1.15f))
                .withEndAction(() -> {
                    nativeChatVisibilityTransitionRunning = false;
                    if (!nativeChatTransitionTargetVisible
                            && !(nativeChatRequestedVisible && nativeChatBoundsReady)) {
                        nativeChatLayer.setVisibility(View.GONE);
                        nativeChatLayer.setAlpha(1f);
                        nativeChatLayer.setTranslationY(0f);
                    }
                })
                .start();
    }

    private void reply(int id, boolean ok, String data) {
        final String js = "window.onNative(" + id + "," + ok + "," + JSONObject.quote(data) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }
}
