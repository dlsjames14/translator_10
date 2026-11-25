package app.screentranslator;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The floating panel used in Live mode. New translations are added at the bottom,
 * so it reads like a running transcript of everything new that appeared on screen.
 */
final class LivePanel {

    interface Actions {
        void onPauseToggle();

        void onClear();

        void onMove();

        void onClose();

        void onCopy(CharSequence text);

        /** Read one line aloud. */
        void onSpeak(CharSequence text);

        /** Turn reading new lines aloud on or off. */
        void onVoiceToggle();

        /** Switch between every line and short summaries. */
        void onSummaryToggle();
    }

    private static final int MAX_ITEMS = 150;

    final LinearLayout root;

    private final Context context;
    private final Actions actions;
    private final boolean dark;
    private final TextView dot;
    private final TextView status;
    private final TextView pauseChip;
    private final TextView voiceChip;
    private final TextView summaryChip;
    private final TextView empty;
    private final TextView title;
    private LinearLayout progress;   // the "in progress" part, kept at the bottom
    private final LinearLayout items;
    private final ScrollView scroll;

    LivePanel(Context context, String language, Actions actions) {
        this.context = context;
        this.actions = actions;
        this.dark = Ui.isDark(context);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(10), dp(10));
        root.setBackground(Ui.rounded(dark ? 0xF5202124 : 0xFAFFFFFF, dp(18),
                dark ? 0x33FFFFFF : 0x22000000, dp(1)));

        // Header: ● LIVE · ENGLISH / status ............ ⏸ 🔊 ≡ 🗑 ⇅ ✕
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotParams.rightMargin = dp(8);
        header.addView(dot, dotParams);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setText(("Live · " + language).toUpperCase(Locale.ROOT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.08f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(dark ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        titles.addView(title);
        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setTextColor(secondaryColor());
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pauseChip = chip("⏸");
        describe(pauseChip, "Pause");
        pauseChip.setOnClickListener(v -> actions.onPauseToggle());
        header.addView(pauseChip);
        voiceChip = chip("🔇");
        voiceChip.setOnClickListener(v -> actions.onVoiceToggle());
        header.addView(voiceChip, chipParams());
        setVoice(false);
        summaryChip = chip("≡");
        summaryChip.setOnClickListener(v -> actions.onSummaryToggle());
        header.addView(summaryChip, chipParams());
        setSummary(false);
        TextView clear = chip("🗑");
        describe(clear, "Clear the list and start fresh");
        clear.setOnClickListener(v -> actions.onClear());
        header.addView(clear, chipParams());
        TextView move = chip("⇅");
        describe(move, "Move the panel to the top or bottom");
        move.setOnClickListener(v -> actions.onMove());
        header.addView(move, chipParams());
        TextView close = chip("✕");
        describe(close, "Stop");
        close.setOnClickListener(v -> actions.onClose());
        header.addView(close, chipParams());

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Running list of translations.
        scroll = new ScrollView(context);
        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, 0, dp(6), dp(4));
        scroll.addView(items, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(6);
        root.addView(scroll, scrollParams);

        empty = new TextView(context);
        empty.setText("Watching for new text. Anything that isn't in " + language
                + " appears here as soon as it shows up. Tap a line to hear it, long-press to copy it." + LEGEND);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setLineSpacing(0, 1.15f);
        empty.setTextColor(secondaryColor());
        empty.setPadding(0, dp(6), 0, 0);
        items.addView(empty);
    }

    void setStatus(String text, int dotColor) {
        status.setText(text);
        dot.setTextColor(dotColor);
    }

    void setPaused(boolean paused) {
        pauseChip.setText(paused ? "▶" : "⏸");
        describe(pauseChip, paused ? "Resume" : "Pause");
    }

    /** Shows whether new lines are read aloud. */
    void setVoice(boolean on) {
        voiceChip.setText(on ? "🔊" : "🔇");
        describe(voiceChip, on ? "Stop reading new lines aloud" : "Read new lines aloud");
        voiceChip.setBackground(Ui.rounded(on ? (dark ? 0x669FA8DA : 0x333949AB) : (dark ? 0x26FFFFFF : 0x12000000),
                dp(15)));
    }

    /** For audio translation: a different title and starting text. */
    void setAudio(String titleText, String emptyText) {
        title.setText(titleText.toUpperCase(Locale.ROOT));
        empty.setText(emptyText + LEGEND);
    }

    /** What the buttons do (they're small icons), shown until the first translation arrives. */
    private static final String LEGEND = "\n\n⏸ pause  ·  🔇 read aloud  ·  ≡ summaries  ·  🗑 clear  ·  ⇅ move  ·  "
            + "✕ stop\n(Long-press a button to see what it does.)";

    /** Sets what a button is called for screen readers, and the label shown when it's long-pressed. */
    private static void describe(TextView chip, String text) {
        chip.setContentDescription(text);
        chip.setTooltipText(text);
    }

    /**
     * The greyed "in progress" part at the bottom: sentences waiting to finish (with a draft
     * translation) and text being translated right now. Empty lists hide it.
     */
    void setInProgress(List<Translation.Line> waiting, List<String> reading) {
        boolean hasWaiting = waiting != null && !waiting.isEmpty();
        boolean hasReading = reading != null && !reading.isEmpty();
        if (progress != null) {
            items.removeView(progress);
            progress = null;
        }
        if (!hasWaiting && !hasReading) {
            return;
        }
        items.removeView(empty);
        progress = new LinearLayout(context);
        progress.setOrientation(LinearLayout.VERTICAL);
        progress.setPadding(dp(10), dp(6), dp(10), dp(8));
        progress.setBackground(Ui.rounded(dark ? 0x14FFFFFF : 0x0A000000, dp(10)));
        int grey = dark ? 0x99FFFFFF : 0x8A000000;
        if (hasWaiting) {
            progress.addView(progressLabel("⏳ Waiting for the sentence to finish", grey));
            for (Translation.Line line : waiting) {
                if (!line.original.isEmpty()) {
                    progress.addView(progressText(line.original + " …", 13, grey, false));
                }
                progress.addView(progressText(line.translation + " …", 15, grey, true));
            }
        }
        if (hasReading) {
            if (hasWaiting) {
                View gap = new View(context);
                progress.addView(gap, new LinearLayout.LayoutParams(1, dp(6)));
            }
            progress.addView(progressLabel("⏳ Translating…", grey));
            int chars = 0;
            for (String text : reading) {
                if (chars > 400) {
                    progress.addView(progressText("…", 14, grey, false));
                    break;
                }
                progress.addView(progressText(text, 14, grey, false));
                chars += text.length();
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        items.addView(progress, params);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView progressLabel(String text, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.04f);
        label.setTextColor(color);
        return label;
    }

    private TextView progressText(String text, float sizeSp, int color, boolean italic) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (italic) {
            view.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        }
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    /** Keeps the "in progress" part below everything else. */
    private void keepProgressLast() {
        if (progress != null && items.indexOfChild(progress) != items.getChildCount() - 1) {
            items.removeView(progress);
            items.addView(progress);
        }
    }

    /** Shows whether summaries are on. */
    void setSummary(boolean on) {
        describe(summaryChip, on ? "Show every line" : "Show short summaries instead of every line");
        summaryChip.setBackground(Ui.rounded(on ? (dark ? 0x669FA8DA : 0x333949AB) : (dark ? 0x26FFFFFF : 0x12000000),
                dp(15)));
    }

    /**
     * Adds a short summary at the bottom. Tap it to hear it; long-press to copy it.
     *
     * @param lines the lines it summarizes, copied along with it
     */
    void addSummary(String summary, String lines) {
        items.removeView(empty);
        LinearLayout entry = new LinearLayout(context);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setPadding(dp(12), dp(8), dp(12), dp(9));
        entry.setBackground(Ui.rounded(dark ? 0x339FA8DA : 0x143949AB, dp(12)));
        TextView label = new TextView(context);
        label.setText("≡ SUMMARY");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.08f);
        label.setTextColor(dark ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        entry.addView(label);
        TextView text = new TextView(context);
        text.setText(summary);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setLineSpacing(0, 1.12f);
        text.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        entry.addView(text);
        final CharSequence copyText = summary + "\n\n" + lines;
        entry.setOnClickListener(v -> actions.onSpeak(summary));
        entry.setOnLongClickListener(v -> {
            actions.onCopy(copyText);
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        items.addView(entry, params);
        trimAndScroll();
    }

    /** Adds one batch of new translations at the bottom and scrolls to it. */
    void addLines(List<Translation.Line> lines, boolean showOriginal) {
        startBatch();
        for (Translation.Line line : lines) {
            LinearLayout entry = new LinearLayout(context);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(0, dp(6), 0, dp(2));
            if (showOriginal && !line.original.isEmpty()) {
                TextView original = new TextView(context);
                original.setText(line.original);
                original.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                original.setTextColor(secondaryColor());
                entry.addView(original);
            }
            TextView translation = new TextView(context);
            translation.setText(line.translation);
            translation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            translation.setLineSpacing(0, 1.12f);
            translation.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
            entry.addView(translation);
            final CharSequence copyText = line.translation;
            final CharSequence spoken = line.translation;
            entry.setOnClickListener(v -> actions.onSpeak(spoken));
            entry.setOnLongClickListener(v -> {
                actions.onCopy(copyText);
                return true;
            });
            items.addView(entry);
        }
        trimAndScroll();
    }

    /** A short message (an error or a pause notice), shown in the list in a different colour. */
    void addNote(String text) {
        items.removeView(empty);
        TextView note = new TextView(context);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        note.setTextColor(dark ? 0xFFFFB74D : 0xFFB45309);
        note.setPadding(0, dp(8), 0, dp(2));
        items.addView(note);
        trimAndScroll();
    }

    void clear() {
        items.removeAllViews();
        progress = null;
        items.addView(empty);
        scroll.scrollTo(0, 0);
    }

    private void startBatch() {
        items.removeView(empty);
        if (items.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(dark ? 0x26FFFFFF : 0x1A000000);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
            params.topMargin = dp(8);
            params.bottomMargin = dp(2);
            items.addView(divider, params);
        }
    }

    private void trimAndScroll() {
        keepProgressLast();
        while (items.getChildCount() > MAX_ITEMS) {
            items.removeViewAt(0);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView chip(String label) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(34));
        chip.setPadding(dp(8), dp(7), dp(8), dp(7));
        chip.setBackground(Ui.rounded(dark ? 0x26FFFFFF : 0x12000000, dp(15)));
        return chip;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(5);
        return params;
    }

    private int secondaryColor() {
        return dark ? 0xB3FFFFFF : 0xA6000000;
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }
}
