package app.screentranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers what was translated while the bubble is on: each original text and its translation.
 * It's used three ways:
 * <ol>
 * <li>Text that was already translated isn't translated again: its earlier translation is reused
 * (on-device), or the AI is told to leave it out (Gemini, ChatGPT).</li>
 * <li>Earlier lines are given to the AI as context, so a conversation, video or story is translated
 * consistently (names, tone, who is speaking) and sentences that continue across lines make sense.</li>
 * <li>Summaries know what came before.</li>
 * </ol>
 * Thread-safe. Kept only in the phone's memory, and forgotten when the bubble is turned off, the
 * target language changes, or Clear is tapped in the Live panel.
 */
final class TranslationMemory {

    /** One remembered translation. */
    static final class Entry {
        final String original;      // "" when only the translation is known
        final String translation;
        final String key;           // Translation.normalize(original)
        final long at;              // when it was added (System.currentTimeMillis)

        Entry(String original, String translation, long at) {
            this.original = original == null ? "" : original.trim();
            this.translation = translation == null ? "" : translation.trim();
            this.key = Translation.normalize(this.original);
            this.at = at;
        }
    }

    static final int MAX_ENTRIES = 600;
    private static final double NEAR_MATCH = 0.85;
    private static final int MIN_NEAR_LENGTH = 6;
    private static final int MAX_TEXT_LENGTH = 600;     // longer texts are shortened when remembered

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();   // oldest first
    private final Map<String, Entry> byKey = new HashMap<>();
    private String language = "";

    /** Forgets everything when the target language changes: translations into another language don't help. */
    synchronized void useLanguage(String newLanguage) {
        String value = newLanguage == null ? "" : newLanguage.trim();
        if (!value.equalsIgnoreCase(language)) {
            clear();
            language = value;
        }
    }

    synchronized void add(String original, String translation) {
        add(new Entry(shorten(original), shorten(translation), System.currentTimeMillis()));
    }

    /** Remembers a translation whose original isn't known (Translate once without "show original"). */
    synchronized void addTranslationOnly(String translation) {
        add(new Entry("", shorten(translation), System.currentTimeMillis()));
    }

    synchronized void addAll(Collection<Translation.Line> lines) {
        for (Translation.Line line : lines) {
            add(line.original, line.translation);
        }
    }

    private void add(Entry entry) {
        if (entry.translation.isEmpty()) {
            return;
        }
        if (!entry.key.isEmpty()) {
            Entry old = byKey.remove(entry.key);
            if (old != null) {
                entries.remove(old);   // keep only the newest translation of the same text
            }
            byKey.put(entry.key, entry);
        }
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            Entry oldest = entries.removeFirst();
            if (byKey.get(oldest.key) == oldest) {
                byKey.remove(oldest.key);
            }
        }
    }

    /** The earlier translation of exactly this text (ignoring spaces, punctuation and case), or null. */
    synchronized Entry find(String original) {
        String key = Translation.normalize(original);
        return key.isEmpty() ? null : byKey.get(key);
    }

    /**
     * The newest entries that fit in the limits, returned oldest first (the order they appeared).
     *
     * @param skipKeys entries whose key is in here are left out (e.g. already listed elsewhere)
     */
    synchronized List<Entry> recent(int maxItems, int maxChars, long maxAgeMs, Set<String> skipKeys) {
        List<Entry> picked = new ArrayList<>();
        long oldest = maxAgeMs > 0 ? System.currentTimeMillis() - maxAgeMs : Long.MIN_VALUE;
        int chars = 0;
        Iterator<Entry> newestFirst = entries.descendingIterator();
        while (newestFirst.hasNext() && picked.size() < maxItems) {
            Entry entry = newestFirst.next();
            if (entry.at < oldest) {
                break;
            }
            if (skipKeys != null && !entry.key.isEmpty() && skipKeys.contains(entry.key)) {
                continue;
            }
            int size = entry.original.length() + entry.translation.length();
            if (chars + size > maxChars) {
                break;
            }
            chars += size;
            picked.add(entry);
        }
        Collections.reverse(picked);
        return picked;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void clear() {
        entries.clear();
        byKey.clear();
    }

    /** A copy for the background thread, so the AI sees one consistent picture. */
    synchronized TranslationMemory copy() {
        TranslationMemory copy = new TranslationMemory();
        copy.language = language;
        copy.entries.addAll(entries);
        copy.byKey.putAll(byKey);
        return copy;
    }

    // ----------------------------------------------------------------------------------------
    // Comparing texts

    /** True if two texts are the same, or nearly the same (a slightly different reading). */
    static boolean nearlySame(String keyA, String keyB) {
        if (keyA.equals(keyB)) {
            return true;
        }
        if (keyA.length() < MIN_NEAR_LENGTH || keyB.length() < MIN_NEAR_LENGTH) {
            return false;
        }
        return similarity(pairsOf(keyA), pairsOf(keyB)) >= NEAR_MATCH;
    }

    /**
     * True if new text adds nothing to text already shown: the same, a slightly different reading,
     * or a piece of it (e.g. a message partly scrolled off screen). Text that's longer than what was
     * shown (a message that's now fully visible) is new.
     */
    static boolean coveredBy(String newKey, String shownKey) {
        if (newKey.isEmpty() || shownKey.isEmpty()) {
            return false;
        }
        if (nearlySame(newKey, shownKey)) {
            return true;
        }
        return newKey.length() >= MIN_NEAR_LENGTH && shownKey.contains(newKey);
    }

    /** Character pairs of a text: used to treat slightly different readings of the same text as equal. */
    static Set<String> pairsOf(String key) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < key.length(); i++) {
            result.add(key.substring(i, i + 2));
        }
        return result;
    }

    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String pair : a) {
            if (b.contains(pair)) {
                shared++;
            }
        }
        int union = a.size() + b.size() - shared;
        return union == 0 ? 0 : shared / (double) union;
    }

    private static String shorten(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) + "…" : value;
    }
}
