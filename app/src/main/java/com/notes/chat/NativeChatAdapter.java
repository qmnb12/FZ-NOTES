package com.notes.chat;

import android.animation.ValueAnimator;
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
import android.text.style.QuoteSpan;
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
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private RecyclerView recyclerView;
    private boolean lightTheme;
    private boolean showTopics = true;

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
        lightTheme = "light".equals(root.optString("theme", "dark"));
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
        if (recyclerView != null) {
            recyclerView.post(() -> {
                if (recyclerView != null && getItemCount() > 0) {
                    // Keep the newest note visible, just like the existing chat view.
                    recyclerView.scrollToPosition(getItemCount() - 1);
                }
            });
        }
    }

    private boolean lastThemeWasLight;
    private boolean lastShowTopics = true;
    private void notifyDataSetChangedThemeIfNeeded() {
        if (lastThemeWasLight != lightTheme || lastShowTopics != showTopics) {
            lastThemeWasLight = lightTheme;
            lastShowTopics = showTopics;
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
        selectedIds.clear();
        selectedIds.addAll(ids);
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

    void scrollToId(String id) {
        if (recyclerView == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                recyclerView.smoothScrollToPosition(i);
                return;
            }
        }
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
        return new Holder(new NoteRow(context));
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        Note note = items.get(position);
        ((NoteRow) holder.itemView).bind(note, selectedIds.contains(note.id), !selectedIds.isEmpty(),
                lightTheme, showTopics, position > 0 ? items.get(position - 1).date : null, listener,
                bitmapFor(note));
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
        private boolean suppressClick;
        private final int[] colors = new int[4];

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
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            body.setIncludeFontPadding(false);
            body.setLineSpacing(dp(1), 1.02f);
            body.setTextIsSelectable(false);
            body.setLinksClickable(true);
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setOnClickListener(v -> {
                if (boundId != null && !selectedIds.isEmpty()) toggleSelection(boundId);
            });
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            bodyLp.bottomMargin = dp(2);
            bubble.addView(body, bodyLp);

            dueView = new TextView(context);
            dueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dueView.setPadding(0, dp(2), 0, dp(2));
            bubble.addView(dueView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            checkSummary = new TextView(context);
            checkSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
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
            body.setOnLongClickListener(v -> longPress());
            imageView.setOnClickListener(v -> {
                if (boundId != null && listener != null) listener.onAction(boundId, "photo", 0);
            });
            imageView.setOnLongClickListener(v -> longPress());
            bubble.setOnClickListener(v -> {
                if (suppressClick) { suppressClick = false; return; }
                if (!selectedIds.isEmpty()) toggleSelection(boundId);
            });
        }

        private boolean longPress() {
            if (boundId == null || listener == null) return true;
            suppressClick = true;
            postDelayed(() -> suppressClick = false, 550);
            toggleSelection(boundId);
            return true;
        }

        private void toggleSelection(String id) {
            if (id == null) return;
            int index = indexOf(id);
            if (selectedIds.contains(id)) selectedIds.remove(id); else selectedIds.add(id);
            if (index >= 0) notifyItemChanged(index, "selection");
            if (listener != null) listener.onSelectionChanged(new ArrayList<>(selectedIds));
        }

        private int indexOf(String id) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
            return -1;
        }

        void bind(Note note, boolean selected, boolean selectionMode, boolean light, boolean topics,
                  String previousDate, Listener listener, Bitmap cachedBitmap) {
            boundId = note.id;
            setBackgroundColor(Color.TRANSPARENT);
            dateHeader.setVisibility(!note.date.equals(previousDate) ? VISIBLE : GONE);
            dateHeader.setText(note.date);
            dateHeader.setTextColor(light ? LIGHT_MUTED : DARK_MUTED);
            ((GradientDrawable) dateHeader.getBackground()).setColor(light ? Color.rgb(224, 232, 243) : Color.rgb(40, 53, 70));

            int bg = light ? LIGHT_BUBBLE : DARK_BUBBLE;
            int fg = light ? LIGHT_TEXT : DARK_TEXT;
            int muted = light ? LIGHT_MUTED : DARK_MUTED;
            int accent = light ? LIGHT_ACCENT : DARK_ACCENT;
            colors[0] = bg; colors[1] = fg; colors[2] = muted; colors[3] = accent;

            GradientDrawable bubbleBg = new GradientDrawable();
            bubbleBg.setColor(bg);
            bubbleBg.setCornerRadii(new float[]{dp(17),dp(17),dp(17),dp(17),dp(5),dp(5),dp(17),dp(17)});
            bubble.setBackground(bubbleBg);

            topicLabel.setVisibility(topics && !TextUtils.isEmpty(note.topic) ? VISIBLE : GONE);
            topicLabel.setText(note.topic);
            topicLabel.setTextColor(accent);
            replyView.setVisibility(TextUtils.isEmpty(note.reply) ? GONE : VISIBLE);
            replyView.setText("↪  " + note.reply);
            replyView.setTextColor(fg);
            GradientDrawable replyBg = new GradientDrawable();
            replyBg.setColor(light ? Color.rgb(241, 246, 253) : Color.rgb(24, 37, 54));
            replyBg.setCornerRadius(dp(5));
            replyView.setBackground(replyBg);
            replyView.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(note.id) && listener != null) listener.onAction(note.id, "reply", 0);
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
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']mono[\"']\\s*>(.*?)</span>", "<tt>$1</tt>");
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']code[\"']\\s*>(.*?)</span>", "<codeblock>$1</codeblock>");
            rich = rich.replaceAll("(?is)<span\\s+class=[\"']quote[\"']\\s*>(.*?)</span>", "<quoteblock>$1</quoteblock>");
            Spanned parsed = Html.fromHtml(rich, Html.FROM_HTML_MODE_COMPACT, null,
                    new NativeRichTagHandler(context, accent, light ? Color.rgb(238, 243, 251) : Color.rgb(22, 34, 49), dp(3), dp(8)));
            body.setText(parsed);
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
                    mark.setText(check.state == 1 ? "☑" : check.state == 2 ? "◉" : "□");
                    mark.setTextColor(check.state == 1 ? (light ? Color.rgb(24,134,75) : Color.rgb(95,212,122)) : accent);
                    mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                    checkRow.addView(mark, new LinearLayout.LayoutParams(dp(24), LayoutParams.WRAP_CONTENT));
                    TextView label = new TextView(context);
                    label.setText(check.text);
                    label.setTextColor(fg);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    label.setMaxLines(5);
                    checkRow.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                    final int taskIndex = i;
                    checkRow.setOnClickListener(v -> {
                        if (listener != null) listener.onAction(note.id, "check", taskIndex);
                    });
                    checks.addView(checkRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                }
            }

            clock.setText((note.pinned ? "📌 " : "") + (note.edited ? "edited · " : "") + note.clock);
            clock.setTextColor(muted);
            menu.setTextColor(muted);
            menu.setVisibility(selectionMode ? GONE : VISIBLE);
            indicator.setColors(light ? LIGHT_ACCENT : 0xff3390ec, muted);
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
            bubble.setScaleX(selected ? 0.998f : 1f);
            bubble.setScaleY(selected ? 0.998f : 1f);
            if (selected) {
                GradientDrawable selectedBg = new GradientDrawable();
                selectedBg.setColor(bg);
                selectedBg.setCornerRadii(new float[]{dp(17),dp(17),dp(17),dp(17),dp(5),dp(5),dp(17),dp(17)});
                selectedBg.setStroke(dp(1), light ? Color.rgb(112, 166, 245) : Color.rgb(57, 136, 245));
                bubble.setBackground(selectedBg);
                bubble.setElevation(dp(2));
            } else {
                bubble.setBackground(bubbleBg);
                bubble.setElevation(dp(1.5f));
            }
            bubble.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP && !selectedIds.isEmpty()) {
                    // Let the click handler toggle multi-selection; do not consume the event.
                    return false;
                }
                return false;
            });
        }

        void updateSelection(boolean selected, boolean selectionMode, boolean light) {
            indicator.setColors(light ? LIGHT_ACCENT : 0xff3390ec, light ? LIGHT_MUTED : DARK_MUTED);
            if (selectionMode) {
                indicator.setVisibility(VISIBLE);
                indicator.setChecked(selected, true);
            } else if (indicator.getVisibility() == VISIBLE) {
                indicator.setChecked(false, true, true);
            } else {
                indicator.setVisibility(INVISIBLE);
                indicator.setChecked(false, false, true);
            }
            menu.setVisibility(selectionMode ? GONE : VISIBLE);
            bubble.animate().cancel();
            bubble.animate().scaleX(selected ? 0.998f : 1f).scaleY(selected ? 0.998f : 1f)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
            if (selected) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(light ? LIGHT_BUBBLE : DARK_BUBBLE);
                background.setCornerRadii(new float[]{dp(17),dp(17),dp(17),dp(17),dp(5),dp(5),dp(17),dp(17)});
                background.setStroke(dp(1), light ? Color.rgb(112, 166, 245) : Color.rgb(57, 136, 245));
                bubble.setBackground(background);
            } else {
                GradientDrawable background = new GradientDrawable();
                background.setColor(light ? LIGHT_BUBBLE : DARK_BUBBLE);
                background.setCornerRadii(new float[]{dp(17),dp(17),dp(17),dp(17),dp(5),dp(5),dp(17),dp(17)});
                bubble.setBackground(background);
            }
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
        private float progress;
        private int accent = 0xff3390ec;
        private int outline = 0xff93a4b8;
        private ValueAnimator animator;
        SelectionIndicator(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
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
            animator.setDuration(190);
            animator.setInterpolator(new DecelerateInterpolator());
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
                Path path = new Path();
                path.moveTo(cx - radius * 0.45f, cy);
                path.lineTo(cx - radius * 0.08f, cy + radius * 0.34f);
                path.lineTo(cx + radius * 0.52f, cy - radius * 0.38f);
                canvas.drawPath(path, paint);
                paint.setAlpha(255);
            }
        }
    }

    private static final class NativeRichTagHandler implements Html.TagHandler {
        private static final class Mark { final int start; final String tag; Mark(int start, String tag) { this.start = start; this.tag = tag; } }
        private final Stack<Mark> marks = new Stack<>();
        private final Context context;
        private final int accent, codeBackground, stripeWidth, gapWidth;
        NativeRichTagHandler(Context context, int accent, int codeBackground, int stripeWidth, int gapWidth) {
            this.context = context; this.accent = accent; this.codeBackground = codeBackground;
            this.stripeWidth = stripeWidth; this.gapWidth = gapWidth;
        }
        @Override public void handleTag(boolean opening, String tag, Editable output, XMLReader xmlReader) {
            if (!"codeblock".equalsIgnoreCase(tag) && !"quoteblock".equalsIgnoreCase(tag)) return;
            if (opening) {
                marks.push(new Mark(output.length(), tag.toLowerCase(Locale.ROOT)));
                if ("codeblock".equalsIgnoreCase(tag) && output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
            } else {
                Mark mark = findMark(tag);
                if (mark == null) return;
                int start = Math.min(mark.start, output.length());
                int end = output.length();
                if (start >= end) return;
                if ("codeblock".equals(mark.tag)) {
                    output.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new BackgroundColorSpan(codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new LeadingCodeIndent(dpStatic(context, 7), accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    output.setSpan(new QuoteSpan(accent, stripeWidth, gapWidth), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new BackgroundColorSpan(codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
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
