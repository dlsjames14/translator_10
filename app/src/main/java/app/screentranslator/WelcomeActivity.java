package app.screentranslator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The first-run guide: a few simple steps that get a new user from "just installed" to "ready to
 * translate": what the app does, which engine to use, getting its key (or downloading the
 * on-device AI), and the language. Everything can be changed later on the main screen.
 */
public class WelcomeActivity extends Activity {

    /** Set in the result when the user tapped "Start now" on the last step. */
    static final String EXTRA_START_NOW = "start_now";

    private static final int PAGE_WELCOME = 0;
    private static final int PAGE_ENGINE = 1;
    private static final int PAGE_SETUP = 2;
    private static final int PAGE_LANGUAGE = 3;
    private static final int PAGE_DONE = 4;
    private static final int PAGES = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private LinearLayout content;
    private LinearLayout dots;
    private Button backButton;
    private Button nextButton;
    private int page;
    private String engine;

    // Set while their page is showing
    private EditText keyField;
    private TextView keyNote;
    private TextView downloadStatus;
    private Button downloadButton;
    private CheckBox mobileDataBox;
    private Spinner languageList;
    private EditText otherLanguage;

    private final Runnable downloadTicker = new Runnable() {
        @Override
        public void run() {
            refreshDownload();
            if (page == PAGE_SETUP && Prefs.ENGINE_ON_DEVICE.equals(engine)) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        engine = Prefs.ENGINE_GEMINI;   // recommended for most people
        drawBehindSystemBars();
        setContentView(buildFrame());
        showPage(savedInstanceState == null ? PAGE_WELCOME : savedInstanceState.getInt("page", PAGE_WELCOME));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        state.putString("engine", engine);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        engine = state.getString("engine", engine);
        showPage(state.getInt("page", page));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(downloadTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page == PAGE_SETUP && Prefs.ENGINE_ON_DEVICE.equals(engine)) {
            handler.post(downloadTicker);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (page > PAGE_WELCOME) {
            showPage(page - 1);
        } else {
            super.onBackPressed();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Frame: dots and "Skip" at the top, the page, "Back" and "Next" at the bottom

    private View buildFrame() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22), dp(10), dp(8), 0);
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(dots, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button skip = new Button(this, null, android.R.attr.borderlessButtonStyle);
        skip.setText("Skip");
        skip.setAllCaps(false);
        skip.setTextColor(secondaryColor());
        skip.setOnClickListener(v -> finishGuide(false));
        top.addView(skip);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(16));
        backButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        backButton.setText("Back");
        backButton.setAllCaps(false);
        backButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        backButton.setOnClickListener(v -> showPage(page - 1));
        bottom.addView(backButton);
        View gap = new View(this);
        bottom.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        nextButton = new Button(this);
        nextButton.setAllCaps(false);
        nextButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nextButton.setTextColor(Color.WHITE);
        nextButton.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        nextButton.setMinWidth(dp(150));
        nextButton.setMinHeight(dp(52));
        nextButton.setOnClickListener(v -> onNext());
        bottom.addView(nextButton);
        root.addView(bottom);
        return root;
    }

    private void showPage(int number) {
        page = Math.max(PAGE_WELCOME, Math.min(PAGES - 1, number));
        handler.removeCallbacks(downloadTicker);
        content.removeAllViews();
        keyField = null;
        downloadStatus = null;
        languageList = null;
        switch (page) {
            case PAGE_WELCOME:
                buildWelcome();
                break;
            case PAGE_ENGINE:
                buildEngine();
                break;
            case PAGE_SETUP:
                buildSetup();
                break;
            case PAGE_LANGUAGE:
                buildLanguage();
                break;
            default:
                buildDone();
                break;
        }
        backButton.setVisibility(page == PAGE_WELCOME ? View.INVISIBLE : View.VISIBLE);
        nextButton.setText(page == PAGE_WELCOME ? "Get started" : page == PAGE_DONE ? "Start now" : "Next");
        dots.removeAllViews();
        for (int i = 0; i < PAGES; i++) {
            View dot = new View(this);
            boolean current = i == page;
            dot.setBackground(Ui.rounded(current ? accentColor() : (dark() ? 0x33FFFFFF : 0x22000000), dp(4)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(current ? 22 : 8), dp(8));
            params.rightMargin = dp(6);
            dots.addView(dot, params);
        }
    }

    private void onNext() {
        switch (page) {
            case PAGE_ENGINE:
                prefs.setEngine(engine);
                showPage(PAGE_SETUP);
                return;
            case PAGE_SETUP:
                if (keyField != null) {
                    String key = keyField.getText().toString().trim();
                    if (key.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("No key yet")
                                .setMessage("The app needs the key to translate. You can also add it later on "
                                        + "the main screen.")
                                .setPositiveButton("Add it later", (dialog, which) -> showPage(PAGE_LANGUAGE))
                                .setNegativeButton("Go back", null)
                                .show();
                        return;
                    }
                    saveKey(key);
                }
                showPage(PAGE_LANGUAGE);
                return;
            case PAGE_LANGUAGE:
                saveLanguage();
                showPage(PAGE_DONE);
                return;
            case PAGE_DONE:
                finishGuide(true);
                return;
            default:
                showPage(page + 1);
        }
    }

    private void finishGuide(boolean startNow) {
        if (keyField != null && !keyField.getText().toString().trim().isEmpty()) {
            saveKey(keyField.getText().toString());
        }
        if (languageList != null) {
            saveLanguage();
        }
        prefs.setWelcomeDone();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_START_NOW, startNow));
        finish();
    }

    // ----------------------------------------------------------------------------------------
    // Pages

    private void buildWelcome() {
        add(text("👋", 44), 20);
        TextView title = heading("Welcome to Screen Translator");
        add(title, 8);
        add(body("Understand any app, chat, game, video or conversation in your own language."), 8);
        add(feature("🖼", "Translate what's on screen",
                "Tap the floating bubble, then drag a box over the text."), 26);
        add(feature("●", "Live",
                "Keeps translating chats, subtitles and games as new text appears."), 16);
        add(feature("🎧", "Audio",
                "Translates what's being said, in videos or around you."), 16);
        add(feature("🔊", "Read aloud",
                "Hear translations in a natural voice."), 16);
        add(body("Setting up takes about two minutes."), 28);
    }

    private void buildEngine() {
        add(heading("Choose how to translate"), 12);
        add(body("You can change this any time."), 6);
        final View[] cards = new View[3];
        cards[0] = choice("✨", "Gemini (Google)", "Recommended",
                "Free, fast and very good. Needs internet and a free Google key (2 minutes to get).",
                Prefs.ENGINE_GEMINI, cards);
        cards[1] = choice("📱", "On this phone", null,
                "Free and private, works without internet. Needs a one-time " + ModelStore.E2B.sizeText()
                        + " download and a newer phone (8 GB of memory or more).",
                Prefs.ENGINE_ON_DEVICE, cards);
        cards[2] = choice("💬", "ChatGPT (OpenAI)", null,
                "Very good, but paid per use (a fraction of a cent per translation). Needs an OpenAI "
                        + "account with credit.",
                Prefs.ENGINE_OPENAI, cards);
        for (View card : cards) {
            add(card, 14);
        }
        paintChoices(cards);
    }

    private void buildSetup() {
        if (Prefs.ENGINE_ON_DEVICE.equals(engine)) {
            buildDownload();
            return;
        }
        boolean gemini = Prefs.ENGINE_GEMINI.equals(engine);
        add(heading(gemini ? "Get your free Gemini key" : "Add your OpenAI key"), 12);
        add(body(gemini
                ? "A key is like a password that lets this app use Google's AI for you. It's free."
                : "A key lets this app use OpenAI's AI for you. OpenAI charges your account a fraction of a "
                + "cent per translation."), 6);
        String[] steps = gemini
                ? new String[]{
                "Tap the button below. Google AI Studio opens; sign in with your Google account.",
                "Tap \"Create API key\" (accept the terms if asked), then tap Copy.",
                "Come back here and tap Paste."}
                : new String[]{
                "Tap the button below and sign in to OpenAI (platform.openai.com).",
                "Add a few dollars of credit under Billing, if you haven't already.",
                "Tap \"Create new secret key\", then Copy.",
                "Come back here and tap Paste."};
        for (int i = 0; i < steps.length; i++) {
            add(step(i + 1, steps[i]), 14);
        }
        Button open = new Button(this);
        open.setText(gemini ? "Open Google AI Studio ↗" : "Open OpenAI ↗");
        open.setAllCaps(false);
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        open.setOnClickListener(v -> openLink(gemini ? MainActivity.GEMINI_KEYS_URL : MainActivity.OPENAI_KEYS_URL));
        add(open, 18);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        keyField = new EditText(this);
        keyField.setHint(gemini ? "Your Gemini key" : "Your OpenAI key (sk-…)");
        keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setText(gemini ? prefs.geminiKey() : prefs.openAiKey());
        row.addView(keyField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button paste = new Button(this);
        paste.setText("Paste");
        paste.setAllCaps(false);
        paste.setTextColor(Color.WHITE);
        paste.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        paste.setOnClickListener(v -> {
            String copied = Ui.clipboardText(this);
            if (copied.isEmpty()) {
                Toast.makeText(this, "Nothing copied yet. Copy the key on the website first.", Toast.LENGTH_LONG)
                        .show();
            } else {
                keyField.setText(copied);
            }
        });
        row.addView(paste);
        add(row, 12);
        keyNote = body("");
        add(keyNote, 4);
        keyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshKeyNote();
            }
        });
        refreshKeyNote();
        add(small("🔒 The key is saved only on this phone."), 10);
    }

    private void refreshKeyNote() {
        if (keyField == null || keyNote == null) {
            return;
        }
        String key = keyField.getText().toString().trim();
        String warning = Ui.keyWarning(engine, key);
        if (key.isEmpty()) {
            keyNote.setText("");
        } else if (warning != null) {
            keyNote.setText("⚠ " + warning);
            keyNote.setTextColor(warningColor());
        } else {
            keyNote.setText("✓ Key added");
            keyNote.setTextColor(okColor());
        }
    }

    private void buildDownload() {
        add(heading("Download the on-device AI"), 12);
        add(body("Google's Gemma 4 AI runs right on your phone. It's a one-time download of "
                + ModelStore.E2B.sizeText() + "; after that, translations work without internet and nothing "
                + "leaves your phone."), 6);
        int ram = ModelStore.phoneRamGb(this);
        if (ram > 0 && ram < 8) {
            TextView warning = body("⚠ This phone has about " + ram + " GB of memory. The on-device AI needs 8 GB "
                    + "or more, so it may be slow or not start. Gemini is a better choice here.");
            warning.setTextColor(warningColor());
            add(warning, 12);
        }
        prefs.setOnDeviceModel(ModelStore.E2B.id);
        downloadButton = new Button(this);
        downloadButton.setAllCaps(false);
        downloadButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        downloadButton.setText("Download now (" + ModelStore.E2B.sizeText() + ")");
        downloadButton.setOnClickListener(v -> {
            try {
                ModelStore.startDownload(this, ModelStore.E2B, mobileDataBox.isChecked());
            } catch (IllegalStateException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
            refreshDownload();
        });
        add(downloadButton, 18);
        mobileDataBox = new CheckBox(this);
        mobileDataBox.setText("Also use mobile data (otherwise it waits for Wi-Fi)");
        mobileDataBox.setChecked(prefs.downloadOverMobileData());
        add(mobileDataBox, 6);
        downloadStatus = body("");
        add(downloadStatus, 10);
        add(small("You don't have to wait here: it keeps downloading in the background. Tap Next."), 10);
        handler.post(downloadTicker);
    }

    private void refreshDownload() {
        if (downloadStatus == null) {
            return;
        }
        if (ModelStore.isReady(this, ModelStore.E2B)) {
            downloadStatus.setText("✓ Downloaded and ready.");
            downloadStatus.setTextColor(okColor());
            downloadButton.setVisibility(View.GONE);
            mobileDataBox.setVisibility(View.GONE);
            return;
        }
        ModelStore.Progress progress = ModelStore.progress(this, ModelStore.E2B);
        if (progress != null) {
            downloadStatus.setText(ModelStore.describe(progress, ModelStore.E2B));
            downloadStatus.setTextColor(secondaryColor());
            downloadButton.setVisibility(View.GONE);
        } else {
            downloadStatus.setText("");
            downloadButton.setVisibility(View.VISIBLE);
        }
    }

    private void buildLanguage() {
        add(heading("Translate into"), 12);
        add(body("Which language do you want to read and hear?"), 6);
        languageList = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                Ui.languageLabels());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageList.setAdapter(adapter);
        add(languageList, 16);
        otherLanguage = new EditText(this);
        otherLanguage.setHint("Type a language, e.g. Tamil");
        otherLanguage.setSingleLine(true);
        otherLanguage.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        add(otherLanguage, 8);
        String current = prefs.welcomeDone() ? prefs.language() : Ui.phoneLanguage();
        int index = Ui.languageIndex(current);
        languageList.setSelection(index >= 0 ? index : Ui.LANGUAGES.length);
        otherLanguage.setText(index >= 0 ? "" : current);
        otherLanguage.setVisibility(index >= 0 ? View.GONE : View.VISIBLE);
        languageList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                otherLanguage.setVisibility(position == Ui.LANGUAGES.length ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        add(small("Text that's already in this language is left as it is."), 12);
    }

    private void saveLanguage() {
        if (languageList == null) {
            return;
        }
        int position = languageList.getSelectedItemPosition();
        String value = position >= 0 && position < Ui.LANGUAGES.length
                ? Ui.LANGUAGES[position][0] : otherLanguage.getText().toString().trim();
        prefs.setLanguage(value.isEmpty() ? Prefs.DEFAULT_LANGUAGE : value);
    }

    private void buildDone() {
        add(text("🎉", 44), 20);
        add(heading("You're all set"), 8);
        add(body("Here's how it works:"), 8);
        add(step(1, "Tap Start now. Android asks to let the app show on top of other apps and to share your "
                + "screen: allow both."), 16);
        add(step(2, "Open any app. A round bubble floats on the screen."), 12);
        add(step(3, "Tap the bubble, then drag a box over the text you want to understand (or tap once for "
                + "the whole screen)."), 12);
        add(step(4, "To turn it off, drag the bubble onto the ✕ at the bottom of the screen."), 12);
        add(small("Try ● Live for chats and subtitles, and 🎧 Audio for speech: both are at the bottom of the "
                + "screen after tapping the bubble."), 18);
    }

    // ----------------------------------------------------------------------------------------
    // Building blocks

    private View choice(String emoji, String title, String badge, String note, String id, View[] all) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setTag(id);
        TextView icon = text(emoji, 26);
        card.addView(icon, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(name);
        if (badge != null) {
            TextView tag = text(badge, 12);
            tag.setTextColor(Color.WHITE);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            tag.setBackground(Ui.rounded(0xFF2E7D32, dp(10)));
            LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagParams.leftMargin = dp(8);
            titleRow.addView(tag, tagParams);
        }
        words.addView(titleRow);
        TextView detail = body(note);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        words.addView(detail, detailParams);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.setOnClickListener(v -> {
            engine = id;
            paintChoices(all);
        });
        return card;
    }

    private void paintChoices(View[] cards) {
        for (View card : cards) {
            if (card == null) {
                continue;
            }
            boolean chosen = engine.equals(card.getTag());
            card.setBackground(Ui.rounded(chosen ? (dark() ? 0x333949AB : 0x143949AB) : (dark() ? 0x14FFFFFF : 0xFFFFFFFF),
                    dp(18), chosen ? accentColor() : (dark() ? 0x22FFFFFF : 0x1A000000), dp(chosen ? 2 : 1)));
        }
    }

    private View feature(String emoji, String title, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView icon = text(emoji, 22);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(name);
        words.addView(body(note));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View step(int number, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView circle = text(String.valueOf(number), 14);
        circle.setTypeface(Typeface.DEFAULT_BOLD);
        circle.setTextColor(Color.WHITE);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(Ui.rounded(Ui.ACCENT, dp(14)));
        row.addView(circle, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView words = body(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = dp(12);
        row.addView(words, params);
        return row;
    }

    private void saveKey(String key) {
        if (Prefs.ENGINE_GEMINI.equals(engine)) {
            prefs.setGeminiKey(key);
        } else if (Prefs.ENGINE_OPENAI.equals(engine)) {
            prefs.setOpenAiKey(key);
        }
    }

    private void add(View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        content.addView(view, params);
    }

    private TextView heading(String value) {
        TextView view = text(value, 26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String value) {
        TextView view = text(value, 16);
        view.setTextColor(secondaryColor());
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    private TextView small(String value) {
        TextView view = text(value, 14);
        view.setTextColor(secondaryColor());
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private TextView text(String value, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void drawBehindSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    private boolean dark() {
        return Ui.isDark(this);
    }

    private int accentColor() {
        return dark() ? Ui.ACCENT_ON_DARK : Ui.ACCENT;
    }

    private int secondaryColor() {
        return dark() ? 0xB3FFFFFF : 0xB3000000;
    }

    private int warningColor() {
        return dark() ? 0xFFFFB74D : 0xFFB45309;
    }

    private int okColor() {
        return dark() ? 0xFF81C784 : 0xFF2E7D32;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
