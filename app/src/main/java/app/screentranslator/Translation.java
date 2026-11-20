package app.screentranslator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small types and helpers shared by all translation engines. */
final class Translation {

    static final String NO_TEXT = "(No text found)";

    private Translation() {
    }

    /** One translated piece of text. */
    static final class Line {
        final String original;
        final String translation;

        Line(String original, String translation) {
            this.original = original == null ? "" : original;
            this.translation = translation == null ? "" : translation;
        }
    }

    /** Lets an engine report progress, e.g. "Starting the on-device AI…". */
    interface StatusListener {
        void onStatus(String message);
    }

    /** What to translate into, how to show it, and what was translated before. */
    static final class Request {
        final String language;
        final boolean showOriginal;
        final StatusListener status;
        /** Everything translated earlier (a copy), for context and to reuse translations. Never null. */
        final TranslationMemory memory;
        /** Live mode: what this Live panel already shows, oldest first. The AI leaves these out. */
        final List<Line> shown;
        /** Live mode: {@link #normalize} keys of everything this Live panel already shows. */
        final Set<String> shownKeys;

        Request(String language, boolean showOriginal, StatusListener status) {
            this(language, showOriginal, status, null, null, null);
        }

        Request(String language, boolean showOriginal, StatusListener status, TranslationMemory memory,
                List<Line> shown, Set<String> shownKeys) {
            this.language = language;
            this.showOriginal = showOriginal;
            this.status = status == null ? message -> { } : status;
            this.memory = memory == null ? new TranslationMemory() : memory;
            this.shown = shown == null ? Collections.<Line>emptyList() : shown;
            this.shownKeys = shownKeys == null ? Collections.<String>emptySet() : shownKeys;
        }

        /** Earlier translations to show the AI as context, oldest first (not the ones in {@link #shown}). */
        List<TranslationMemory.Entry> history(int maxItems, int maxChars) {
            return memory.recent(maxItems, maxChars, HISTORY_MAX_AGE_MS, shownKeys);
        }
    }

    /** Earlier translations older than this aren't used as context (probably a different app or video). */
    static final long HISTORY_MAX_AGE_MS = 20 * 60 * 1000L;

    /** What "Translate once" produced. */
    static final class Result {
        /** The text for the card. */
        final String text;
        /** The same translation as original/translation pairs, when known (else empty). */
        final List<Line> lines;

        Result(String text, List<Line> lines) {
            this.text = text;
            this.lines = lines == null ? Collections.<Line>emptyList() : lines;
        }
    }

    /**
     * Reads "original / → translation" blocks (the "show original" card format) back into pairs.
     * Blocks without an arrow are skipped.
     */
    static List<Line> pairsFromCard(String text) {
        List<Line> pairs = new ArrayList<>();
        if (text == null) {
            return pairs;
        }
        for (String block : text.trim().split("\\n\\s*\\n")) {
            StringBuilder original = new StringBuilder();
            StringBuilder translation = new StringBuilder();
            for (String raw : block.split("\n")) {
                String row = raw.trim();
                if (row.startsWith("→")) {
                    append(translation, row.substring(1).trim());
                } else if (translation.length() > 0) {
                    append(translation, row);   // a translation that runs over several lines
                } else {
                    append(original, row);
                }
            }
            if (original.length() > 0 && translation.length() > 0) {
                pairs.add(new Line(original.toString(), translation.toString()));
            }
        }
        return pairs;
    }

    private static void append(StringBuilder out, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(text);
    }

    /** Roughly how many sentences a text has (at least 1 for any text with letters). */
    static int sentenceCount(String text) {
        if (text == null || !hasLetters(text)) {
            return 0;
        }
        int count = 0;
        String trimmed = text.trim();
        boolean endsWithMark = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (".!?。！？…".indexOf(trimmed.charAt(i)) < 0) {
                continue;
            }
            int next = i + 1;
            while (next < trimmed.length() && ".!?。！？…\"'”’)」』".indexOf(trimmed.charAt(next)) >= 0) {
                next++;   // more marks, or closing quotes and brackets
            }
            if (next == trimmed.length()) {
                count++;
                endsWithMark = true;
                break;
            }
            if (Character.isWhitespace(trimmed.charAt(next)) || "。！？".indexOf(trimmed.charAt(i)) >= 0) {
                count++;
            }
            i = next - 1;
        }
        return Math.max(1, endsWithMark ? count : count + 1);
    }

    /** Lowercase, without spaces or punctuation: used to spot text that was already translated. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }

    /** True if the text contains at least one letter (in any alphabet). */
    static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }
}
