package app.screentranslator;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;

/** Small drawing helpers shared by the screens and overlays. */
final class Ui {

    static final int ACCENT = 0xFF3949AB;
    static final int ACCENT_ON_DARK = 0xFF9FA8DA;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radiusPx);
        return shape;
    }

    static GradientDrawable rounded(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable shape = rounded(color, radiusPx);
        shape.setStroke(strokePx, strokeColor);
        return shape;
    }

    /**
     * The "Translate into" list: the name the AI is given, and what the list shows. "Other…" (typed
     * by hand) comes after these.
     */
    static final String[][] LANGUAGES = {
            {"English", "English"},
            {"Korean", "Korean · 한국어"},
            {"Filipino", "Filipino (Tagalog)"},
            {"Japanese", "Japanese · 日本語"},
            {"Chinese (Simplified)", "Chinese, Simplified · 简体中文"},
            {"Traditional Chinese", "Chinese, Traditional · 繁體中文"},
            {"Vietnamese", "Vietnamese · Tiếng Việt"},
            {"Thai", "Thai · ภาษาไทย"},
            {"Indonesian", "Indonesian · Bahasa Indonesia"},
            {"Malay", "Malay · Bahasa Melayu"},
            {"Nepali", "Nepali · नेपाली"},
            {"Khmer", "Khmer · ខ្មែរ"},
            {"Burmese", "Burmese · မြန်မာ"},
            {"Mongolian", "Mongolian · Монгол"},
            {"Uzbek", "Uzbek · Oʻzbek"},
            {"Kazakh", "Kazakh · Қазақ"},
            {"Sinhala", "Sinhala · සිංහල"},
            {"Bengali", "Bengali · বাংলা"},
            {"Hindi", "Hindi · हिन्दी"},
            {"Urdu", "Urdu · اردو"},
            {"Russian", "Russian · Русский"},
            {"Spanish", "Spanish · Español"},
            {"Portuguese", "Portuguese · Português"},
            {"French", "French · Français"},
            {"German", "German · Deutsch"},
            {"Italian", "Italian · Italiano"},
            {"Turkish", "Turkish · Türkçe"},
            {"Arabic", "Arabic · العربية"},
    };

    /** Where a language is in {@link #LANGUAGES} (ignoring case), or -1 if it's typed by hand. */
    static int languageIndex(String language) {
        String value = language == null ? "" : language.trim();
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i][0].equalsIgnoreCase(value)) {
                return i;
            }
        }
        // Names typed in earlier versions ("Tagalog", "Chinese").
        if (value.equalsIgnoreCase("Tagalog")) {
            return 2;
        }
        if (value.equalsIgnoreCase("Chinese") || value.equalsIgnoreCase("Mandarin")) {
            return 4;
        }
        return -1;
    }

    /** The list's labels, with "Other…" at the end. */
    static String[] languageLabels() {
        String[] labels = new String[LANGUAGES.length + 1];
        for (int i = 0; i < LANGUAGES.length; i++) {
            labels[i] = LANGUAGES[i][1];
        }
        labels[LANGUAGES.length] = "Other… (type it)";
        return labels;
    }

    /** The phone's own language, if it's in the list; else English. */
    static String phoneLanguage() {
        String english = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH);
        if (java.util.Locale.getDefault().getLanguage().equals("zh")) {
            String country = java.util.Locale.getDefault().getCountry();
            return "TW".equals(country) || "HK".equals(country) ? "Traditional Chinese" : "Chinese (Simplified)";
        }
        if (java.util.Locale.getDefault().getLanguage().equals("fil")
                || java.util.Locale.getDefault().getLanguage().equals("tl")) {
            return "Filipino";
        }
        int index = languageIndex(english);
        return index >= 0 ? LANGUAGES[index][0] : Prefs.DEFAULT_LANGUAGE;
    }

    /**
     * A friendly warning if a pasted key looks wrong (a common slip: copying the wrong thing, or only
     * part of it), or null if it looks fine. Never blocks: key formats can change.
     */
    static String keyWarning(String engine, String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains(" ") || value.contains("\n")) {
            return "There's a space in the key. Copy it again with the Copy button on the website.";
        }
        if (Prefs.ENGINE_OPENAI.equals(engine)) {
            if (!value.startsWith("sk-")) {
                return "OpenAI keys start with \"sk-\". Check you copied the whole key.";
            }
            return value.length() < 40 ? "This key looks too short. Check you copied the whole key." : null;
        }
        return value.length() < 30 ? "This key looks too short. Check you copied the whole key." : null;
    }

    /** The text on the clipboard (for a Paste button), or "". */
    static String clipboardText(Context context) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            return "";
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        return text == null ? "" : text.toString().trim();
    }
}
