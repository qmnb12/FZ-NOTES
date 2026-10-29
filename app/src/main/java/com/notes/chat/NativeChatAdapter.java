package com.notes.chat;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.Layout;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.XMLReader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native, recycled rendering for the FZ NOTES message timeline.
 * The WebView remains the source of truth for notes and backup; this adapter
 * renders only the visible timeline and reports user actions back to JavaScript.
 */
final class NativeChatAdapter extends RecyclerView.Adapter<NativeChatAdapter.Holder> {
    interface Listener {
        void onSelectionChanged(List<String> selectedIds);
        void onAction(String id, String action, int index);
    }

    private static final int TYPE_MESSAGE = 0;
private static final int DARK_BUBBLE = Color.rgb(27, 43, 64);
    private static final int DARK_TEXT = Color.rgb(237, 244, 255);
    private static final int DARK_MUTED = Color.rgb(147, 164, 184);
    private static final int DARK_ACCENT = Color.rgb(117, 170, 255);
    private static final int LIGHT_BUBBLE = Color.WHITE;
    private static final int LIGHT_TEXT = Color.rgb(25, 35, 51);
    private static final int LIGHT_MUTED = Color.rgb(99, 113, 137);
    private static final int LIGHT_ACCENT = Color.rgb(40, 111, 219);

    private final Context context;
    private final Listener listener;
    private final List<Note> items = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private RecyclerView recyclerView;
    private boolean lightTheme;
    private boolean showTopics = true;
    private String themeName = "dark";
    private int themeBg = Color.rgb(11, 17, 27);
    private int themeBar = Color.rgb(17, 27, 41);
    private int themeBubble = DARK_BUBBLE;
    private int themeText = DARK_TEXT;
    private int themeMuted = DARK_MUTED;
    private int themeAccent = DARK_ACCENT;
    private int themeBorder = Color.rgb(43, 59, 79);
    private float messageTextSizeDp = 16f;
    private float messageCornerRadiusDp = 17f;
    private String searchHighlightId = "";
    private String lastSelectionChangedId = "";

    NativeChatAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    void attachRecyclerView(RecyclerView view) {
        recyclerView = view;
    }

    void submitJson(String json) {
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return;
        }
        themeName = root.optString("theme", "dark");
        lightTheme = "light".equals(themeName);
        JSONObject palette = root.optJSONObject("palette");
        themeBg = paletteColor(palette, "bg", lightTheme ? Color.rgb(243, 246, 251) : Color.rgb(11, 17, 27));
        // Named and custom themes must use their actual surface luminance. An id-only
        // check misclassifies bright custom palettes and made Mono styling inconsistent.
        lightTheme = Color.luminance(themeBg) >= 0.50f;
        themeBar = paletteColor(palette, "bar", lightTheme ? Color.WHITE : Color.rgb(17, 27, 41));
        themeBubble = paletteColor(palette, "bub", lightTheme ? LIGHT_BUBBLE : DARK_BUBBLE);
        themeText = paletteColor(palette, "text", lightTheme ? LIGHT_TEXT : DARK_TEXT);
        themeMuted = paletteColor(palette, "mut", lightTheme ? LIGHT_MUTED : DARK_MUTED);
        themeAccent = paletteColor(palette, "acc", lightTheme ? LIGHT_ACCENT : DARK_ACCENT);
        themeBorder = blendColors(themeBar, themeText, lightTheme ? 0.13f : 0.12f);
        messageTextSizeDp = (float) root.optDouble("messageTextSize", 16d);
        messageTextSizeDp = Math.max(12f, Math.min(24f, messageTextSizeDp));
        messageCornerRadiusDp = (float) root.optDouble("messageCornerRadius", 17d);
        messageCornerRadiusDp = Math.max(4f, Math.min(28f, messageCornerRadiusDp));
        searchHighlightId = root.optString("searchHighlightId", "");
        showTopics = root.optBoolean("showTopics", true);
        final JSONArray selected = root.optJSONArray("selectedIds");
        final Set<String> newSelected = new HashSet<>();
        if (selected != null) {
            for (int i = 0; i < selected.length(); i++) {
                String id = selected.optString(i, "");
                if (!id.isEmpty()) newSelected.add(id);
            }
        }
        final JSONArray array = root.optJSONArray("items");
        final boolean forceFollowLatest = root.optBoolean("forceFollowLatest", false);
        final int oldItemCount = items.size();
        final String oldLastId = oldItemCount > 0 ? items.get(oldItemCount - 1).id : null;
        boolean wasAtBottom = oldItemCount == 0;
        if (recyclerView != null && oldItemCount > 0
                && recyclerView.getLayoutManager() instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager lm =
                    (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
            int lastVisible = lm.findLastVisibleItemPosition();
            // Only follow new messages when the user was already at the bottom.
            wasAtBottom = lastVisible >= oldItemCount - 1;
        }
        final Map<String, Note> previousById = new HashMap<>();
        for (Note old : items) previousById.put(old.id, old);
        final ArrayList<Note> next = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                Note old = previousById.get(id);
                String image = "";
                boolean hasImage = o.optBoolean("imagePresent", false);
                if (hasImage) {
                    if (o.has("image") && !o.isNull("image")) {
                        image = o.optString("image", "");
                    } else if (old != null) {
                        image = old.image;
                    }
                }
                Note note = new Note();
                note.id = id;
                note.time = o.optLong("time", 0);
                note.html = o.optString("html", "");
                note.image = image;
                note.topic = o.optString("topic", "");
                note.edited = o.optBoolean("edited", false);
                note.pinned = o.optBoolean("pinned", false);
                note.due = o.optLong("due", 0);
                note.reply = o.optString("reply", "");
                JSONArray checkArray = o.optJSONArray("checks");
                if (checkArray != null) {
                    for (int c = 0; c < checkArray.length(); c++) {
                        JSONObject check = checkArray.optJSONObject(c);
                        if (check != null) {
                            note.checks.add(new CheckItem(check.optString("text", ""), check.optInt("state", 0)));
                        }
                    }
                }
                note.date = formatDate(note.time);
                note.clock = formatClock(note.time);
                note.signature = makeSignature(note);
                next.add(note);
            }
        }

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return items.get(oldPosition).id.equals(next.get(newPosition).id);
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return items.get(oldPosition).signature.equals(next.get(newPosition).signature);
            }
        }, true);
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
        setSelectedIds(newSelected, true);
        notifyDataSetChangedThemeIfNeeded();
        final String newLastId = next.isEmpty() ? null : next.get(next.size() - 1).id;
        final boolean appendedLatestMessage = next.size() > oldItemCount
                && newLastId != null && !newLastId.equals(oldLastId);
        // A user-authored send must stay visible even if the keyboard just resized the
        // RecyclerView and made the previous bottom check stale. Incoming/background changes
        // still follow the timeline only when the user was already at the bottom.
        final boolean shouldFollowLatest = forceFollowLatest
                || (oldItemCount == 0 && !next.isEmpty())
                || (wasAtBottom && appendedLatestMessage);
        if (recyclerView != null && shouldFollowLatest) {
            recyclerView.post(() -> {
                if (recyclerView != null && getItemCount() > 0) {
                    recyclerView.scrollToPosition(getItemCount() - 1);
                }
            });
        }
    }

    private String lastBoundAppearance = "";
    private void notifyDataSetChangedThemeIfNeeded() {
        String appearance = themeName + "|" + themeBg + "|" + themeBar + "|" + themeBubble
                + "|" + themeText + "|" + themeMuted + "|" + themeAccent + "|"
                + messageTextSizeDp + "|" + messageCornerRadiusDp + "|" + showTopics + "|" + searchHighlightId;
        if (!lastBoundAppearance.equals(appearance)) {
            lastBoundAppearance = appearance;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    void setSelectedIdsJson(String json) {
        Set<String> ids = new HashSet<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (Exception ignored) { }
        setSelectedIds(ids, true);
    }

    private void setSelectedIds(Set<String> ids, boolean animate) {
        Set<String> old = new HashSet<>(selectedIds);
        boolean wasSelecting = !selectedIds.isEmpty();
        selectedIds.clear();
        selectedIds.addAll(ids);
        boolean isSelecting = !selectedIds.isEmpty();
        if (wasSelecting != isSelecting) {
            // All visible rows need the same mode transition: checkbox rail in, per-note menu out.
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "selection");
            return;
        }
        for (String id : old) {
            if (!selectedIds.contains(id)) notifyIdChanged(id, animate);
        }
        for (String id : selectedIds) {
            if (!old.contains(id)) notifyIdChanged(id, animate);
        }
    }

    private void notifyIdChanged(String id, boolean animate) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                notifyItemChanged(i, animate ? "selection" : "state");
                return;
            }
        }
    }

    private void toggleSelectionById(String id) {
        if (id == null) return;
        lastSelectionChangedId = id;
        boolean wasSelecting = !selectedIds.isEmpty();
        if (selectedIds.contains(id)) selectedIds.remove(id); else selectedIds.add(id);
        boolean isSelecting = !selectedIds.isEmpty();
        if (wasSelecting != isSelecting) {
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "selection");
        } else {
            notifyIdChanged(id, true);
        }
        if (listener != null) listener.onSelectionChanged(new ArrayList<>(selectedIds));
    }

    private void selectForContextMenu(String id) {
        if (id == null) return;
        // Telegram-like behavior: a first long-press selects the note before opening its
        // message menu. Keep the selection after the menu closes so the user can continue
        // selecting more notes or use the selection toolbar.
        if (!selectedIds.contains(id)) {
            toggleSelectionById(id);
        } else {
            lastSelectionChangedId = id;
        }
        if (listener != null) listener.onAction(id, "menu", 0);
    }

    void scrollToId(String id) {
        if (recyclerView == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                recyclerView.smoothScrollToPosition(i);
                return;
            }
        }
    }

    boolean hasSelection() {
        return !selectedIds.isEmpty();
    }

    // Used when a tap lands on a note while the full-window message menu is open.
    // It lets one tap both dismiss the menu and update multi-selection.
    void toggleSelectionFromPopupTap(String id) {
        if (id == null) return;
        toggleSelectionById(id);
    }

    String idAt(float x, float y) {
        if (recyclerView == null) return null;
        View child = recyclerView.findChildViewUnder(x, y);
        if (!(child instanceof NoteRow)) return null;
        RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(child);
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || position >= items.size()) return null;
        return items.get(position).id;
    }

    void setSwipeOffset(String id, float offset) {
        NoteRow row = findVisibleRow(id);
        if (row == null) return;
        row.bubble.animate().cancel();
        row.bubble.setTranslationX(Math.max(-dp(70), Math.min(dp(70), offset)));
    }

    void finishSwipe(String id, float offset, boolean commit) {
        NoteRow row = findVisibleRow(id);
        if (row != null) {
            row.bubble.animate().cancel();
            row.bubble.animate().translationX(0f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (!commit || id == null || Math.abs(offset) < dp(55) || listener == null) return;
        listener.onAction(id, offset < 0 ? "swipeReply" : "pinToggle", 0);
    }

    String getLastSelectionChangedId() {
        return lastSelectionChangedId;
    }

    int[] getBubbleCenterOnScreen(String id) {
        NoteRow row = findVisibleRow(id);
        if (row == null || row.bubble == null
                || row.bubble.getWidth() <= 0 || row.bubble.getHeight() <= 0) return null;
        int[] loc = new int[2];
        row.bubble.getLocationOnScreen(loc);
        return new int[]{loc[0] + row.bubble.getWidth() / 2,
                loc[1] + row.bubble.getHeight() / 2};
    }

    private NoteRow findVisibleRow(String id) {
        if (recyclerView == null || id == null) return null;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (!(child instanceof NoteRow)) continue;
            NoteRow row = (NoteRow) child;
            if (id.equals(row.boundId)) return row;
        }
        return null;
    }

    /** Flash the copied Mono/code text. Text matching is the fallback when HTML parsing changes span indexes. */
    void flashInlineCode(String noteId, String kind, int index) {
        flashInlineCode(noteId, kind, index, "");
    }

    void flashInlineCode(String noteId, String kind, int index, String requestedText) {
        if (!"mono".equals(kind) && !"code".equals(kind)) return;
        NoteRow row = findVisibleRow(noteId);
        if (row == null || row.body == null) return;

        CharSequence current = row.body.getText();
        android.text.Spannable styled;
        if (current instanceof android.text.Spannable) {
            styled = (android.text.Spannable) current;
        } else if (current instanceof Spanned) {
            row.body.setText(new SpannableStringBuilder(current), TextView.BufferType.SPANNABLE);
            CharSequence converted = row.body.getText();
            if (!(converted instanceof android.text.Spannable)) return;
            styled = (android.text.Spannable) converted;
        } else {
            return;
        }

        String wanted = normalizeInlineText(requestedText);
        InlineCopySpan target = null;
        InlineCopySpan indexCandidate = null;
        InlineCopySpan[] spans = styled.getSpans(0, styled.length(), InlineCopySpan.class);
        for (InlineCopySpan span : spans) {
            if (!noteId.equals(span.noteId) || !kind.equals(span.action)) continue;
            int spanStart = styled.getSpanStart(span);
            int spanEnd = styled.getSpanEnd(span);
            if (spanStart < 0 || spanEnd <= spanStart) continue;
            String spanText = normalizeInlineText(styled.subSequence(spanStart, spanEnd));
            if (index == span.index) indexCandidate = span;
            if (index == span.index && (wanted.isEmpty() || wanted.equals(spanText))) {
                target = span;
                break;
            }
            // HTML/TextView parsing can alter span indexes; text identity is a safer fallback.
            if (!wanted.isEmpty() && wanted.equals(spanText)) {
                target = span;
                break;
            }
        }
        if (target == null && indexCandidate != null && wanted.isEmpty()) target = indexCandidate;

        int start = target == null ? -1 : styled.getSpanStart(target);
        int end = target == null ? -1 : styled.getSpanEnd(target);
        if ((start < 0 || end <= start) && !wanted.isEmpty()) {
            int[] fallbackRange = findNormalizedTextRange(styled, wanted);
            if (fallbackRange != null) {
                start = fallbackRange[0];
                end = fallbackRange[1];
            }
        }
        // Do not silently exit just because the parser gave the Mono span a different index.
        if (start < 0 || end <= start) return;

        if (row.copyFlashAnimator != null) {
            row.copyFlashAnimator.cancel();
            row.copyFlashAnimator = null;
        }
        if (row.copyFlashText != null && row.copyFlashSpan != null
                && row.copyFlashText.getSpanStart(row.copyFlashSpan) >= 0) {
            row.copyFlashText.removeSpan(row.copyFlashSpan);
        }
        // Remove the existing Mono background/foreground spans while the pulse runs.
        // Otherwise Android's overlapping BackgroundColorSpan can paint over the animation.
        final ArrayList<BackgroundColorSpan> removedBackgrounds = new ArrayList<>();
        final ArrayList<Integer> backgroundFlags = new ArrayList<>();
        BackgroundColorSpan[] backgrounds = styled.getSpans(start, end, BackgroundColorSpan.class);
        for (BackgroundColorSpan span : backgrounds) {
            if (styled.getSpanStart(span) == start && styled.getSpanEnd(span) == end) {
                removedBackgrounds.add(span);
                backgroundFlags.add(styled.getSpanFlags(span));
                styled.removeSpan(span);
            }
        }
        final ArrayList<ForegroundColorSpan> removedForegrounds = new ArrayList<>();
        final ArrayList<Integer> foregroundFlags = new ArrayList<>();
        ForegroundColorSpan[] foregrounds = styled.getSpans(start, end, ForegroundColorSpan.class);
        for (ForegroundColorSpan span : foregrounds) {
            if (styled.getSpanStart(span) == start && styled.getSpanEnd(span) == end) {
                removedForegrounds.add(span);
                foregroundFlags.add(styled.getSpanFlags(span));
                styled.removeSpan(span);
            }
        }

        final InlineCopySpan flashTarget = target;
        final int originalFixedTextColor = flashTarget == null
                ? Color.TRANSPARENT : flashTarget.fixedTextColor;
        // Choose a visible highlight from the actual rendered bubble brightness.
        final boolean lightSurface = Color.luminance(themeBubble) >= 0.50f;
        final int originalTextColor = originalFixedTextColor != Color.TRANSPARENT
                ? originalFixedTextColor
                : (lightTheme ? Color.rgb(31, 83, 155) : Color.rgb(160, 202, 255));
        final int pulseTextColor = lightSurface ? Color.WHITE : Color.rgb(8, 16, 28);
        final int tintBase = lightSurface ? Color.rgb(12, 24, 48) : Color.WHITE;
        final int tintedAccent = blendColors(themeAccent, tintBase, lightSurface ? 0.20f : 0.50f);
        final int flashColor = Color.argb(230,
                Color.red(tintedAccent), Color.green(tintedAccent), Color.blue(tintedAccent));
        final int clearColor = Color.argb(0,
                Color.red(tintedAccent), Color.green(tintedAccent), Color.blue(tintedAccent));
        final ArgbEvaluator colorEvaluator = new ArgbEvaluator();
        final CopyFlashSpan pulse = new CopyFlashSpan(clearColor, originalTextColor);
        if (flashTarget != null) flashTarget.fixedTextColor = Color.TRANSPARENT;
        styled.setSpan(pulse, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        row.copyFlashText = styled;
        row.copyFlashSpan = pulse;

        android.view.animation.Interpolator easeOut =
                new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
        ValueAnimator enter = ValueAnimator.ofObject(colorEvaluator, clearColor, flashColor);
        enter.setDuration(75L);
        enter.setInterpolator(easeOut);
        enter.addUpdateListener(animation -> {
            pulse.setColor((Integer) animation.getAnimatedValue());
            pulse.setTextColor((Integer) colorEvaluator.evaluate(
                    animation.getAnimatedFraction(), originalTextColor, pulseTextColor));
            row.body.invalidate();
        });
        ValueAnimator exit = ValueAnimator.ofObject(colorEvaluator, flashColor, clearColor);
        exit.setDuration(165L);
        exit.setInterpolator(easeOut);
        exit.addUpdateListener(animation -> {
            pulse.setColor((Integer) animation.getAnimatedValue());
            pulse.setTextColor((Integer) colorEvaluator.evaluate(
                    1f - animation.getAnimatedFraction(), originalTextColor, pulseTextColor));
            row.body.invalidate();
        });

        // Capture final references for Java 8 anonymous-listener rules.
        final android.text.Spannable flashText = styled;
        final int flashStart = start;
        final int flashEnd = end;
        AnimatorSet animator = new AnimatorSet();
        animator.playSequentially(enter, exit);
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (flashText.getSpanStart(pulse) >= 0) flashText.removeSpan(pulse);
                for (int i = 0; i < removedBackgrounds.size(); i++) {
                    BackgroundColorSpan span = removedBackgrounds.get(i);
                    if (flashText.getSpanStart(span) < 0) flashText.setSpan(span, flashStart, flashEnd, backgroundFlags.get(i));
                }
                for (int i = 0; i < removedForegrounds.size(); i++) {
                    ForegroundColorSpan span = removedForegrounds.get(i);
                    if (flashText.getSpanStart(span) < 0) flashText.setSpan(span, flashStart, flashEnd, foregroundFlags.get(i));
                }
                if (flashTarget != null) flashTarget.fixedTextColor = originalFixedTextColor;
                row.body.invalidate();
                if (row.copyFlashAnimator == animation) {
                    row.copyFlashAnimator = null;
                    row.copyFlashText = null;
                    row.copyFlashSpan = null;
                }
            }
        });
        row.copyFlashAnimator = animator;
        animator.start();
    }

    private static String normalizeInlineText(CharSequence value) {
        if (value == null) return "";
        return value.toString().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * Locate a requested string while ignoring differences in whitespace/newlines.
     * The returned offsets are mapped back to the original TextView text.
     */
    private static int[] findNormalizedTextRange(CharSequence source, String requested) {
        String needle = normalizeInlineText(requested);
        if (source == null || needle.isEmpty()) return null;
        StringBuilder normalized = new StringBuilder();
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Integer> ends = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\u00A0') {
                if (normalized.length() == 0) continue;
                int last = normalized.length() - 1;
                if (normalized.charAt(last) == ' ') {
                    ends.set(last, i + 1);
                } else {
                    normalized.append(' ');
                    starts.add(i);
                    ends.add(i + 1);
                }
            } else {
                normalized.append(ch);
                starts.add(i);
                ends.add(i + 1);
            }
        }
        int match = normalized.indexOf(needle);
        if (match < 0) return null;
        int last = match + needle.length() - 1;
        if (last >= starts.size() || last >= ends.size()) return null;
        return new int[]{starts.get(match), ends.get(last)};
    }

    private static int paletteColor(JSONObject palette, String key, int fallback) {
        if (palette == null) return fallback;
        try {
            String value = palette.optString(key, "");
            if (!value.isEmpty()) return Color.parseColor(value);
        } catch (Exception ignored) { }
        return fallback;
    }

    private static int blendColors(int base, int overlay, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = Color.alpha(base), r = Color.red(base), g = Color.green(base), b = Color.blue(base);
        return Color.argb(
                Math.round(a + (Color.alpha(overlay) - a) * t),
                Math.round(r + (Color.red(overlay) - r) * t),
                Math.round(g + (Color.green(overlay) - g) * t),
                Math.round(b + (Color.blue(overlay) - b) * t));
    }

    @Override public long getItemId(int position) {
        if (items.isEmpty()) return Long.MIN_VALUE;
        return stableId(items.get(position).id);
    }

    private static long stableId(String id) {
        long hash = 1125899906842597L;
        for (int i = 0; i < id.length(); i++) hash = 31 * hash + id.charAt(i);
        return hash;
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public int getItemViewType(int position) { return TYPE_MESSAGE; }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        NoteRow row = new NoteRow(context);
        // Give every message the full chat-column width. Otherwise wrap-content
        // measurement can make the bubble's RIGHT gravity behave like left alignment.
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(row);
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        Note note = items.get(position);
        ((NoteRow) holder.itemView).bind(note, selectedIds.contains(note.id), !selectedIds.isEmpty(),
                lightTheme, showTopics, position > 0 ? items.get(position - 1).date : null, listener,
                bitmapFor(note), note.id.equals(searchHighlightId));
    }

    @Override public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
        if (!payloads.isEmpty() && !items.isEmpty() && holder.itemView instanceof NoteRow) {
            Note note = items.get(position);
            ((NoteRow) holder.itemView).updateSelection(selectedIds.contains(note.id),
                    !selectedIds.isEmpty(), lightTheme);
            return;
        }
        onBindViewHolder(holder, position);
    }

    private Bitmap bitmapFor(Note note) {
        if (TextUtils.isEmpty(note.image)) return null;
        return imageCache.get(imageKey(note));
    }

    private static String imageKey(Note note) {
        return note.id + ":" + note.image.length() + ":" + note.image.hashCode();
    }

    private void loadImage(Note note, ImageView target) {
        final String key = imageKey(note);
        target.setTag(key);
        Bitmap cached = imageCache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            target.setVisibility(View.VISIBLE);
            return;
        }
        target.setImageDrawable(null);
        target.setVisibility(View.GONE);
        final String data = note.image;
        if (TextUtils.isEmpty(data)) return;
        imageExecutor.execute(() -> {
            Bitmap bitmap = decodeImage(data);
            if (bitmap == null) return;
            imageCache.put(key, bitmap);
            if (target.getTag() != null && key.equals(target.getTag())) {
                target.post(() -> {
                    if (target.getTag() != null && key.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                        target.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private Bitmap decodeImage(String data) {
        try {
            int comma = data.indexOf(',');
            String encoded = comma >= 0 ? data.substring(comma + 1) : data;
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > 1600) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private final class NoteRow extends LinearLayout {
        private final TextView dateHeader;
        private final FrameLayout row;
        private final MaxWidthBubble bubble;
        private final SelectionIndicator indicator;
        private final TextView topicLabel;
        private final TextView replyView;
        private final ImageView imageView;
        private final TextView body;
        private final TextView dueView;
        private final LinearLayout checks;
        private final TextView checkSummary;
        private final TextView clock;
        private final TextView menu;
        private String boundId;
        // When selection mode is active, intercept the entire row gesture here.
        // Installing listeners on every nested child made taps depend on which
        // TextView/ImageView happened to receive the touch stream.
        private boolean selectionGestureActive;
        private boolean selectionGestureMoved;
        private float selectionGestureDownX, selectionGestureDownY;
        private ValueAnimator selectionAnimator;
        private Animator copyFlashAnimator;
        private android.text.Spannable copyFlashText;
        private CopyFlashSpan copyFlashSpan;
        private int bubbleFillColor = DARK_BUBBLE;
        private final int[] colors = new int[4];
         private final ArgbEvaluator selectionColorEvaluator = new ArgbEvaluator();

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            final int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                // Snapshot selection mode once per gesture. The first long-press
                // starts outside selection mode and must still reach the row's
                // long-click listener; taps after it are handled as row selections.
                selectionGestureActive = !selectedIds.isEmpty();
                selectionGestureMoved = false;
                selectionGestureDownX = event.getX();
                selectionGestureDownY = event.getY();
                if (selectionGestureActive) return true;
                return super.dispatchTouchEvent(event);
            }

            if (selectionGestureActive) {
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getX() - selectionGestureDownX) > dp(14)
                            || Math.abs(event.getY() - selectionGestureDownY) > dp(14)) {
                        selectionGestureMoved = true;
                    }
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP) {
                    boolean isTap = !selectionGestureMoved
                            && Math.abs(event.getX() - selectionGestureDownX) <= dp(14)
                            && Math.abs(event.getY() - selectionGestureDownY) <= dp(14);
                    selectionGestureActive = false;
                    if (isTap && boundId != null) toggleSelection(boundId);
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_CANCEL) {
                    selectionGestureActive = false;
                    return true;
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        NoteRow(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(dp(8), dp(1), dp(8), dp(1));

            dateHeader = new TextView(context);
            dateHeader.setGravity(Gravity.CENTER);
            dateHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            dateHeader.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(14));
            dateHeader.setBackground(chip);
            dateHeader.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            LayoutParams dateLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            dateLp.gravity = Gravity.CENTER_HORIZONTAL;
            dateLp.topMargin = dp(5);
            dateLp.bottomMargin = dp(3);
            addView(dateHeader, dateLp);

            row = new FrameLayout(context);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            bubble = new MaxWidthBubble(context);
            bubble.setOrientation(VERTICAL);
            bubble.setPadding(dp(10), dp(8), dp(9), dp(5));
            bubble.setClipToPadding(false);
            bubble.setClipChildren(false);
            bubble.setElevation(dp(1.5f));
            FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(bubble, bubbleLp);

            indicator = new SelectionIndicator(context);
            FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.LEFT | Gravity.CENTER_VERTICAL);
            row.addView(indicator, indicatorLp);
            bubble.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                indicator.setTranslationX(l - dp(31));
            });

            topicLabel = new TextView(context);
            topicLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            topicLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            topicLabel.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            LinearLayout.LayoutParams topicLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            topicLp.bottomMargin = dp(3);
            bubble.addView(topicLabel, topicLp);

            replyView = new TextView(context);
            replyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            replyView.setMaxLines(2);
            replyView.setEllipsize(TextUtils.TruncateAt.END);
            replyView.setPadding(dp(7), dp(4), dp(7), dp(4));
            LinearLayout.LayoutParams replyLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            replyLp.bottomMargin = dp(4);
            bubble.addView(replyView, replyLp);

            imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(true);
            imageView.setMaxHeight(dp(230));
            imageView.setVisibility(GONE);
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(190));
            imageLp.bottomMargin = dp(4);
            bubble.addView(imageView, imageLp);

            body = new TextView(context);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            body.setIncludeFontPadding(false);
            body.setLineSpacing(dp(1), 1.02f);
            // Allow selecting/copying text inside a note. Long-press on the text
            // should use Android's text-selection toolbar; the separate ⋮ button
            // and long-press on other message areas still open message actions.
            body.setTextIsSelectable(true);
            body.setLinksClickable(true);
            body.setMovementMethod(LinkMovementMethod.getInstance());
            // Prevent Android's pressed-link highlight from flashing when a Mono span is crossed while scrolling.
            body.setHighlightColor(Color.TRANSPARENT);
            body.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            // In selection mode a normal tap on message text must select/unselect the
            // whole note, rather than being swallowed by LinkMovementMethod for spans.
            // When selection mode is off, touches pass through unchanged so Mono copies
            // and ordinary links keep their one-tap behaviour.
            // Replaced after all message children have been built by
            // installSelectionTapHandlers(), so every part of a selected message
            // (text, timestamp, image, reply and checklist) toggles on a single tap.
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            bodyLp.bottomMargin = dp(2);
            bubble.addView(body, bodyLp);

            dueView = new TextView(context);
            dueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dueView.setPadding(0, dp(2), 0, dp(2));
            dueView.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            bubble.addView(dueView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            checkSummary = new TextView(context);
            checkSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            checkSummary.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            summaryLp.topMargin = dp(2);
            bubble.addView(checkSummary, summaryLp);

            checks = new LinearLayout(context);
            checks.setOrientation(VERTICAL);
            LinearLayout.LayoutParams checksLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            checksLp.topMargin = dp(1);
            bubble.addView(checks, checksLp);

            LinearLayout footer = new LinearLayout(context);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            footerLp.topMargin = dp(2);
            bubble.addView(footer, footerLp);

            clock = new TextView(context);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            clock.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            footer.addView(clock, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            menu = new TextView(context);
            menu.setText("⋮");
            menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            menu.setGravity(Gravity.CENTER);
            menu.setMinWidth(dp(24));
            menu.setPadding(dp(4), 0, 0, 0);
            footer.addView(menu, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(24)));
            menu.setOnClickListener(v -> {
                if (boundId != null && listener != null) listener.onAction(boundId, "menu", 0);
            });

            setOnLongClickListener(v -> longPress());
            row.setOnLongClickListener(v -> longPress());
            bubble.setOnLongClickListener(v -> longPress());
            // Do not override the TextView's native long-press selection gesture.
            imageView.setOnClickListener(v -> {
                if (boundId == null || listener == null) return;
                if (!selectedIds.isEmpty()) toggleSelection(boundId);
                else listener.onAction(boundId, "photo", 0);
            });
            imageView.setOnLongClickListener(v -> longPress());
            bubble.setOnClickListener(v -> {
                if (!selectedIds.isEmpty()) toggleSelection(boundId);
            });
        }

        private boolean longPress() {
            if (boundId == null || listener == null) return true;
            // First long-press selects the note and opens its message menu.
            // Once selection is active, a further long-press only toggles the
            // target note; it must not reopen the message menu.
            if (!selectedIds.isEmpty()) {
                toggleSelection(boundId);
            } else {
                selectForContextMenu(boundId);
            }
            return true;
        }

        private void toggleSelection(String id) {
            toggleSelectionById(id);
        }

        private int indexOf(String id) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
            return -1;
        }

        void bind(Note note, boolean selected, boolean selectionMode, boolean light, boolean topics,
                  String previousDate, Listener listener, Bitmap cachedBitmap, boolean searchHighlighted) {
            if (selectionAnimator != null) {
                selectionAnimator.cancel();
                selectionAnimator = null;
            }
            boundId = note.id;
            setBackgroundColor(Color.TRANSPARENT);
            dateHeader.setVisibility(!note.date.equals(previousDate) ? VISIBLE : GONE);
            dateHeader.setText(note.date);
            dateHeader.setTextColor(themeMuted);
            ((GradientDrawable) dateHeader.getBackground()).setColor(themeBar);

            int bg = themeBubble;
            int fg = themeText;
            int muted = themeMuted;
            int accent = themeAccent;
            colors[0] = bg; colors[1] = fg; colors[2] = muted; colors[3] = accent;

            GradientDrawable bubbleBg = new GradientDrawable();
            bubbleBg.setColor(bg);
            bubbleBg.setCornerRadii(new float[]{dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(5),dp(5),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp)});
            // Matching border width keeps the drawable's inner content geometry identical when selected.
            bubbleBg.setStroke(dp(1), themeBorder);
            bubble.setBackground(bubbleBg);

            topicLabel.setVisibility(topics && !TextUtils.isEmpty(note.topic) ? VISIBLE : GONE);
            topicLabel.setText(note.topic);
            topicLabel.setTextColor(accent);
            replyView.setVisibility(TextUtils.isEmpty(note.reply) ? GONE : VISIBLE);
            replyView.setText("↪  " + note.reply);
            replyView.setTextColor(fg);
            GradientDrawable replyBg = new GradientDrawable();
            replyBg.setColor(themeBar);
            replyBg.setCornerRadius(dp(5));
            replyView.setBackground(replyBg);
            replyView.setOnClickListener(v -> {
                if (TextUtils.isEmpty(note.id) || listener == null) return;
                if (!selectedIds.isEmpty()) toggleSelection(note.id);
                else listener.onAction(note.id, "reply", 0);
            });

            if (!TextUtils.isEmpty(note.image)) {
                imageView.setVisibility(VISIBLE);
                if (cachedBitmap != null) imageView.setImageBitmap(cachedBitmap); else loadImage(note, imageView);
            } else {
                imageView.setTag(null);
                imageView.setImageDrawable(null);
                imageView.setVisibility(GONE);
            }

            String rich = note.html == null ? "" : note.html;
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']mono[\"']\\s*>(.*?)</span>", "<monospan>$1</monospan>");
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']code[\"']\\s*>(.*?)</span>", "<codeblock>$1</codeblock>");
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']quote[\"']\\s*>(.*?)</span>", "<quoteblock>$1</quoteblock>");
            Spanned parsed = Html.fromHtml(rich, Html.FROM_HTML_MODE_COMPACT, null,
                    new NativeRichTagHandler(context, accent, themeBg, lightTheme, dp(3), dp(8), NativeChatAdapter.this, note.id));
            body.setText(parsed);
            // CSS px and Telegram-style chat sizes are density-independent; SP would
            // enlarge notes again when the Android system font scale is above 1.0.
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, messageTextSizeDp * 0.88f);
            body.setTextColor(fg);
            body.setLinkTextColor(accent);
            body.setVisibility(TextUtils.isEmpty(note.html) ? GONE : VISIBLE);

            if (note.due > 0) {
                dueView.setVisibility(VISIBLE);
                dueView.setText("◷ " + new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(new Date(note.due)));
                dueView.setTextColor(muted);
            } else dueView.setVisibility(GONE);

            checks.removeAllViews();
            if (note.checks.isEmpty()) {
                checkSummary.setVisibility(GONE);
                checks.setVisibility(GONE);
            } else {
                checkSummary.setVisibility(VISIBLE);
                checks.setVisibility(VISIBLE);
                int done = 0;
                for (CheckItem item : note.checks) if (item.state == 1) done++;
                checkSummary.setText(done + "/" + note.checks.size() + " ho gaya");
                checkSummary.setTextColor(muted);
                for (int i = 0; i < note.checks.size(); i++) {
                    CheckItem check = note.checks.get(i);
                    LinearLayout checkRow = new LinearLayout(context);
                    checkRow.setGravity(Gravity.CENTER_VERTICAL);
                    checkRow.setOrientation(HORIZONTAL);
                    checkRow.setPadding(0, dp(2), 0, dp(2));
                    TextView mark = new TextView(context);
                    mark.setText(check.state == 1 ? "☑" : check.state == 2 ? "✕" : "□");
                    int stateColor = check.state == 1
                            ? (light ? Color.rgb(24,134,75) : Color.rgb(95,212,122))
                            : check.state == 2
                                    ? (light ? Color.rgb(207,54,66) : Color.rgb(255,105,116))
                                    : accent;
                    mark.setTextColor(stateColor);
                    mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                    checkRow.addView(mark, new LinearLayout.LayoutParams(dp(24), LayoutParams.WRAP_CONTENT));
                    TextView label = new TextView(context);
                    label.setText(check.text);
                    label.setTextColor(stateColor);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    label.setMaxLines(5);
                    if (check.state == 1) {
                        label.setPaintFlags(label.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    }
                    checkRow.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                    final int taskIndex = i;
                    checkRow.setOnClickListener(v -> {
                        if (listener == null) return;
                        if (!selectedIds.isEmpty()) toggleSelection(note.id);
                        else listener.onAction(note.id, "check", taskIndex);
                    });
                    checks.addView(checkRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                }
            }

            clock.setText((note.pinned ? "📌 " : "") + (note.edited ? "edited · " : "") + note.clock);
            clock.setTextColor(muted);
            menu.setTextColor(muted);
            menu.animate().cancel();
            menu.setAlpha(1f);
            menu.setScaleX(1f);
            menu.setScaleY(1f);
            // Keep the footer's menu slot reserved; GONE changes bubble width/height on selection.
            menu.setVisibility(selectionMode ? INVISIBLE : VISIBLE);
            indicator.setColors(accent, muted);
            if (selectionMode) {
                indicator.setVisibility(VISIBLE);
                indicator.setChecked(selected, true);
            } else if (indicator.getVisibility() == VISIBLE) {
                indicator.setChecked(false, true, true);
            } else {
                indicator.setVisibility(INVISIBLE);
                indicator.setChecked(false, false, true);
            }
            bubble.animate().cancel();
            bubble.setTranslationX(0f);
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
            if (selected) {
                GradientDrawable selectedBg = new GradientDrawable();
                selectedBg.setColor(blendColors(bg, accent, light ? 0.14f : 0.26f));
                bubbleFillColor = blendColors(bg, accent, light ? 0.14f : 0.26f);
                selectedBg.setCornerRadii(new float[]{dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(5),dp(5),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp)});
                selectedBg.setStroke(dp(1), accent);
                bubble.setBackground(selectedBg);
                bubble.setElevation(dp(2));
            } else {
                bubbleFillColor = bg;
                if (searchHighlighted) {
                    GradientDrawable searchBg = new GradientDrawable();
                    searchBg.setColor(bg);
                    searchBg.setCornerRadii(new float[]{dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(5),dp(5),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp)});
                    searchBg.setStroke(dp(2), accent);
                    bubble.setBackground(searchBg);
                    bubble.setElevation(dp(4));
                } else {
                    bubble.setBackground(bubbleBg);
                    bubble.setElevation(dp(1.5f));
                }
            }
            // Selection-mode taps are intercepted centrally by dispatchTouchEvent().
        }

        private void animateMenuVisibility(boolean show) {
            menu.animate().cancel();
            if (!show) {
                if (menu.getVisibility() != VISIBLE) return;
                menu.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f)
                        .setDuration(125)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> {
                            if (!selectedIds.isEmpty()) menu.setVisibility(INVISIBLE);
                        })
                        .start();
                return;
            }
            if (menu.getVisibility() != VISIBLE) {
                menu.setAlpha(0f);
                menu.setScaleX(0.82f);
                menu.setScaleY(0.82f);
                menu.setVisibility(VISIBLE);
            }
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }

        void updateSelection(boolean selected, boolean selectionMode, boolean light) {
            indicator.setColors(themeAccent, themeMuted);
            if (selectionMode) {
                indicator.setVisibility(VISIBLE);
                indicator.setChecked(selected, true);
            } else if (indicator.getVisibility() == VISIBLE) {
                indicator.setChecked(false, true, true);
            } else {
                indicator.setVisibility(INVISIBLE);
                indicator.setChecked(false, false, true);
            }
            animateMenuVisibility(!selectionMode);
            bubble.animate().cancel();
            if (selectionAnimator != null) {
                selectionAnimator.cancel();
                selectionAnimator = null;
            }
            final int targetColor = selected
                    ? blendColors(themeBubble, themeAccent, light ? 0.14f : 0.26f)
                    : themeBubble;
            final int startColor = bubbleFillColor;
            GradientDrawable background = new GradientDrawable();
            background.setColor(startColor);
            background.setCornerRadii(new float[]{dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp),dp(5),dp(5),dp(messageCornerRadiusDp),dp(messageCornerRadiusDp)});
            if (selected) {
                background.setStroke(dp(1), themeAccent);
            }
            bubble.setBackground(background);
            bubble.setElevation(dp(selected ? 2f : 1.5f));
            // Keep the selected message response on the hardware compositor; avoid layout changes.
            // Never scale/translate the bubble itself: selection must not alter its visible size.
            bubble.animate().translationX(0f).scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withLayer()
                    .start();
            selectionAnimator = ValueAnimator.ofFloat(0f, 1f);
            selectionAnimator.setDuration(240);
            selectionAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            selectionAnimator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                bubbleFillColor = (Integer) selectionColorEvaluator.evaluate(fraction, startColor, targetColor);
                background.setColor(bubbleFillColor);
            });
            selectionAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (selectionAnimator == animation) selectionAnimator = null;
                    bubbleFillColor = targetColor;
                    background.setColor(targetColor);
                }
            });
            selectionAnimator.start();
        }
    }

    private final class MaxWidthBubble extends LinearLayout {
        MaxWidthBubble(Context context) {
            super(context);
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = MeasureSpec.getSize(widthMeasureSpec);
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                int maxWidth = Math.max(dp(120), (int) (size * 0.88f));
                if (size > maxWidth) widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View itemView) { super(itemView); }
    }

    private final class SelectionIndicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private float progress;
        private int accent = 0xff3390ec;
        private int outline = 0xff93a4b8;
        private ValueAnimator animator;
        SelectionIndicator(Context context) {
            super(context);
            // Keep drawing hardware-accelerated. Software layers caused selection stutter.
        }
        void setColors(int accent, int outline) { this.accent = accent; this.outline = outline; invalidate(); }
        void setChecked(boolean checked, boolean animated) { setChecked(checked, animated, false); }
        void setChecked(boolean checked, boolean animated, boolean hideWhenDone) {
            float target = checked ? 1f : 0f;
            if (checked) setVisibility(VISIBLE);
            if (animator != null) { animator.cancel(); animator = null; }
            if (Math.abs(progress - target) < 0.001f) {
                if (hideWhenDone && !checked) setVisibility(INVISIBLE);
                invalidate();
                return;
            }
            if (!animated || !isAttachedToWindow()) {
                progress = target;
                invalidate();
                if (hideWhenDone && !checked) setVisibility(INVISIBLE);
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(230);
            animator.setInterpolator(new DecelerateInterpolator(1.5f));
            animator.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (animator == animation) animator = null;
                    if (hideWhenDone && !checked && progress <= 0.001f) setVisibility(INVISIBLE);
                }
            });
            animator.start();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.42f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            paint.setAlpha((int) (255 * progress));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.7f));
            paint.setColor(progress > 0.01f ? accent : outline);
            canvas.drawCircle(cx, cy, radius, paint);
            if (progress > 0.001f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(Color.WHITE);
                paint.setAlpha((int) (255 * progress));
                checkPath.reset();
                checkPath.moveTo(cx - radius * 0.45f, cy);
                checkPath.lineTo(cx - radius * 0.08f, cy + radius * 0.34f);
                checkPath.lineTo(cx + radius * 0.52f, cy - radius * 0.38f);
                canvas.drawPath(checkPath, paint);
                paint.setAlpha(255);
            }
        }
    }

    private static final class NativeRichTagHandler implements Html.TagHandler {
        private static final class Mark {
            final int start;
            final String tag;
            final int index;
            Mark(int start, String tag, int index) { this.start = start; this.tag = tag; this.index = index; }
        }
        private final Stack<Mark> marks = new Stack<>();
        private final Context context;
        private final int accent, codeBackground, stripeWidth, gapWidth;
        private final boolean lightTheme;
        private final NativeChatAdapter adapter;
        private final String noteId;
        private int monoIndex;
        private int codeIndex;

        NativeRichTagHandler(Context context, int accent, int codeBackground, boolean lightTheme,
                             int stripeWidth, int gapWidth, NativeChatAdapter adapter, String noteId) {
            this.context = context; this.accent = accent; this.codeBackground = codeBackground;
            this.lightTheme = lightTheme;
            this.stripeWidth = stripeWidth; this.gapWidth = gapWidth; this.adapter = adapter; this.noteId = noteId;
        }
        @Override public void handleTag(boolean opening, String tag, Editable output, XMLReader xmlReader) {
            boolean code = "codeblock".equalsIgnoreCase(tag);
            boolean quote = "quoteblock".equalsIgnoreCase(tag);
            boolean mono = "monospan".equalsIgnoreCase(tag);
            if (!code && !quote && !mono) return;
            if (opening) {
                int index = code ? codeIndex++ : mono ? monoIndex++ : -1;
                marks.push(new Mark(output.length(), tag.toLowerCase(Locale.ROOT), index));
                if (code && output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
                return;
            }
            Mark mark = findMark(tag);
            if (mark == null) return;
            int start = Math.min(mark.start, output.length());
            int end = output.length();
            if (start >= end) return;
            if ("codeblock".equals(mark.tag)) {
                output.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new BackgroundColorSpan(codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new LeadingCodeIndent(dpStatic(context, 7), accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new InlineCopySpan(adapter, noteId, "code", mark.index, Color.TRANSPARENT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ("monospan".equals(mark.tag)) {
                // Make inline code instantly distinguishable from prose, while keeping
                // it as a real clickable span so one tap copies only this Mono value.
                // Theme mode, not the exact accent hex, determines the Mono surface.
                // This keeps light custom themes light even when their accent is customized.
                int monoBg = lightTheme ? Color.rgb(226, 238, 255) : Color.rgb(12, 24, 40);
                int monoFg = lightTheme ? Color.rgb(31, 83, 155) : Color.rgb(160, 202, 255);
                output.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new RelativeSizeSpan(0.90f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new BackgroundColorSpan(monoBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new ForegroundColorSpan(monoFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new InlineCopySpan(adapter, noteId, "mono", mark.index, monoFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                output.setSpan(new QuoteSpan(accent, stripeWidth, gapWidth), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new BackgroundColorSpan(codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        private Mark findMark(String tag) {
            for (int i = marks.size() - 1; i >= 0; i--) {
                if (marks.get(i).tag.equalsIgnoreCase(tag)) {
                    Mark mark = marks.remove(i);
                    return mark;
                }
            }
            return null;
        }
    }

    private static final class CopyFlashSpan extends android.text.style.CharacterStyle
            implements android.text.style.UpdateAppearance {
        private int color;
        private int textColor;
        CopyFlashSpan(int color, int textColor) { this.color = color; this.textColor = textColor; }
        int getColor() { return color; }
        void setColor(int color) { this.color = color; }
        void setTextColor(int color) { this.textColor = color; }
        @Override public void updateDrawState(TextPaint textPaint) {
            textPaint.bgColor = color;
            textPaint.setColor(textColor);
        }
    }

    private static final class InlineCopySpan extends ClickableSpan {
        private final NativeChatAdapter adapter;
        private final String noteId;
        private final String action;
        private final int index;
        private int fixedTextColor;
        InlineCopySpan(NativeChatAdapter adapter, String noteId, String action, int index, int fixedTextColor) {
            this.adapter = adapter; this.noteId = noteId; this.action = action; this.index = index; this.fixedTextColor = fixedTextColor;
        }
        @Override public void onClick(View widget) {
            if (adapter == null || noteId == null) return;
            if (!adapter.selectedIds.isEmpty()) {
                adapter.toggleSelectionById(noteId);
            } else if (adapter.listener != null) {
                // Start the visual pulse from this exact native span immediately.
                // Do not wait for a WebView/clipboard bridge round-trip to rediscover it.
                if ("mono".equals(action) || "code".equals(action)) {
                    adapter.flashInlineCode(noteId, action, index, "");
                }
                adapter.listener.onAction(noteId, action, index);
            }
        }
        @Override public void updateDrawState(TextPaint ds) {
            ds.setUnderlineText(false);
            // ClickableSpan normally overwrites ForegroundColorSpan with the link color.
            // Keep Mono in its own calm code color instead of flashing blue while scrolling/tapping.
            if (fixedTextColor != Color.TRANSPARENT) ds.setColor(fixedTextColor);
        }
    }

    private static final class LeadingCodeIndent implements android.text.style.LeadingMarginSpan {
        private final int indent, color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LeadingCodeIndent(int indent, int color) { this.indent = indent; this.color = color; }
        @Override public int getLeadingMargin(boolean first) { return indent + dpStatic(null, 4); }
        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                                                 int bottom, CharSequence text, int start, int end, boolean first,
                                                 Layout layout) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            float left = x + (dir < 0 ? -indent : 0);
            c.drawRect(left, top + dpStatic(null, 2), left + dpStatic(null, 2), bottom - dpStatic(null, 2), paint);
        }
    }

    private static int dpStatic(Context context, float value) {
        float density = context == null ? 1f : context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
    private int dp(float value) { return dpStatic(context, value); }

    private static String makeSignature(Note note) {
        String checks = "";
        for (CheckItem item : note.checks) checks += item.text + ":" + item.state + ";";
        return note.time + "|" + note.html.length() + ":" + note.html.hashCode()
                + "|" + note.image.length() + ":" + note.image.hashCode()
                + "|" + note.topic + "|" + note.edited + "|" + note.pinned + "|" + note.due
                + "|" + note.reply + "|" + checks;
    }

    private static String formatDate(long millis) {
        if (millis <= 0) millis = System.currentTimeMillis();
        return new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(millis));
    }
    private static String formatClock(long millis) {
        if (millis <= 0) millis = System.currentTimeMillis();
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(millis));
    }

    private static final class Note {
        String id = "", html = "", image = "", topic = "", reply = "", date = "", clock = "", signature = "";
        long time, due;
        boolean edited, pinned;
        final List<CheckItem> checks = new ArrayList<>();
    }
    private static final class CheckItem {
        final String text;
        final int state;
        CheckItem(String text, int state) { this.text = text; this.state = state; }
    }
}
