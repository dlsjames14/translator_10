package app.screentranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Live mode's bookkeeping of lines: what the panel already shows, and which sentences are still
 * unfinished. Each time the AI reports the text on screen, {@link #take} decides what's new and
 * ready to show:
 * <ul>
 * <li>Text already shown (or a slightly different reading, or a piece of it) is left out.</li>
 * <li>An unfinished sentence (still being typed, spoken or revealed) waits. When more words appear,
 * the longer version replaces it. It's shown once it's finished, once it stops growing for
 * {@link #HOLD_STABLE_MS}, once it has waited {@link #HOLD_MAX_MS} in total, or once it's gone from
 * the screen (e.g. the subtitle changed), so nothing is ever lost.</li>
 * </ul>
 * Not thread-safe: used on the main thread only. Times are in milliseconds from any steady clock.
 */
final class LiveLines {

    static final long HOLD_STABLE_MS = 2500;
    static final long HOLD_MAX_MS = 10_000;
    static final int SHOWN_ITEMS = Prompts.ONLINE_SHOWN_ITEMS;   // lines given to the AI as "already translated"
    static final int SHOWN_CHARS = Prompts.ONLINE_SHOWN_CHARS;
    private static final int SEEN_LIMIT = 3000;
    private static final int MIN_PIECE = 2;          // shortest unfinished piece that's matched to its sentence
    private static final int EDGE_LINES = 5;         // newest shown lines checked for cut-off beginnings and ends
    private static final int MIN_EDGE = 3;

    /** An unfinished sentence, held back until it's finished. */
    private static final class Waiting {
        Translation.Line line;
        final String key;
        final long firstSeen;     // when its first piece appeared
        final long lastGrew;      // when it last got longer

        Waiting(Translation.Line line, String key, long firstSeen, long lastGrew) {
            this.line = line;
            this.key = key;
            this.firstSeen = firstSeen;
            this.lastGrew = lastGrew;
        }
    }

    /** Told about every line that goes on the panel (to remember it). */
    interface Listener {
        void onAccepted(Translation.Line line);
    }

    private final LinkedHashSet<String> shownKeys = new LinkedHashSet<>();          // everything shown
    private final ArrayDeque<Translation.Line> shownLines = new ArrayDeque<>();     // the newest, for the AI
    private final LinkedHashMap<String, Waiting> waiting = new LinkedHashMap<>();
    private final Listener listener;
    boolean waitForSentences;

    LiveLines(boolean waitForSentences, Listener listener) {
        this.waitForSentences = waitForSentences;
        this.listener = listener == null ? line -> { } : listener;
    }

    /**
     * Sorts the lines the AI found on screen. Returns the ones to show now, oldest first:
     * unfinished sentences that are gone from the screen, then the new lines in reading order.
     */
    List<Translation.Line> take(List<Translation.Line> lines, long now) {
        List<Translation.Line> fresh = new ArrayList<>();
        Set<String> stillThere = new HashSet<>();
        for (Translation.Line line : lines) {
            String original = line.original.isEmpty() ? line.translation : line.original;
            String key = Translation.normalize(original);
            if (key.isEmpty() || alreadyShown(key)) {
                continue;
            }
            boolean sameLanguage = !line.original.isEmpty() && key.equals(Translation.normalize(line.translation));
            if (sameLanguage) {
                markShown(key, null);   // already in the target language: nothing to show
                dropWaitingWithin(key);
                continue;
            }
            if (waitForSentences && !line.complete) {
                Waiting held = hold(key, line, now);
                stillThere.add(held.key);
                if (now - held.lastGrew < HOLD_STABLE_MS && now - held.firstSeen < HOLD_MAX_MS) {
                    continue;   // probably still growing: wait for the rest of the sentence
                }
                waiting.remove(held.key);   // it stopped growing: it's as finished as it gets
            } else {
                dropWaitingWithin(key);   // a finished sentence replaces its unfinished pieces
            }
            accept(key, line);
            fresh.add(line);
        }
        List<Translation.Line> result = release(held -> !stillThere.contains(held.key));
        result.addAll(fresh);
        return result;
    }

    /**
     * Unfinished sentences that are finished after all: they waited too long, or the screen has been
     * still (screenStill) since they last grew, e.g. a sentence without a full stop. Oldest first.
     */
    List<Translation.Line> releaseFinished(long now, boolean screenStill) {
        if (waiting.isEmpty()) {
            return new ArrayList<>();
        }
        return release(held -> now - held.firstSeen >= HOLD_MAX_MS
                || (screenStill && now - held.lastGrew >= HOLD_STABLE_MS));
    }

    /** Everything waiting, shown now (e.g. when "wait for sentences" is turned off). */
    List<Translation.Line> releaseAll() {
        return release(held -> true);
    }

    boolean hasWaiting() {
        return !waiting.isEmpty();
    }

    /** The unfinished sentences, for the AI ("still unfinished, include it again"). */
    List<Translation.Line> waitingLines() {
        List<Translation.Line> lines = new ArrayList<>();
        for (Waiting held : waiting.values()) {
            lines.add(held.line);
        }
        return lines;
    }

    /** The newest lines the panel shows, oldest first: the AI leaves these out and continues from them. */
    List<Translation.Line> shownForAi() {
        List<Translation.Line> picked = new ArrayList<>();
        int chars = 0;
        Iterator<Translation.Line> newestFirst = shownLines.descendingIterator();
        while (newestFirst.hasNext() && picked.size() < SHOWN_ITEMS) {
            Translation.Line line = newestFirst.next();
            int size = line.original.length() + line.translation.length();
            if (chars + size > SHOWN_CHARS && !picked.isEmpty()) {
                break;
            }
            chars += size;
            picked.add(0, line);
        }
        return picked;
    }

    Set<String> shownKeys() {
        return new HashSet<>(shownKeys);
    }

    /** A fresh start (🗑): only the newest few lines stay as "already translated" (they may still be on screen). */
    void forgetOlder(int keep) {
        while (shownLines.size() > keep) {
            shownLines.removeFirst();
        }
        waiting.clear();
    }

    /**
     * True if this text, a slightly different reading of it, or a piece of it is already on the panel.
     * For the newest few lines, even a short beginning or end counts (e.g. the part of a joined-up
     * subtitle that's still on screen).
     */
    boolean alreadyShown(String key) {
        if (shownKeys.contains(key)) {
            return true;
        }
        int fromNewest = 0;
        Iterator<Translation.Line> newestFirst = shownLines.descendingIterator();
        while (newestFirst.hasNext()) {
            String shown = Translation.normalize(newestFirst.next().original);
            if (key.length() > shown.length() && partOf(shown, key)) {
                continue;   // it's a longer version (more words appeared since): that's new
            }
            if (TranslationMemory.coveredBy(key, shown)) {
                return true;
            }
            if (fromNewest++ < EDGE_LINES && key.length() >= MIN_EDGE && key.length() < shown.length()
                    && (shown.startsWith(key) || shown.endsWith(key))) {
                return true;
            }
        }
        return false;
    }

    /** True if the piece is part of the (longer or equal) text: how an unfinished sentence grows. */
    private static boolean partOf(String piece, String text) {
        return piece.length() >= MIN_PIECE && text.contains(piece);
    }

    // ----------------------------------------------------------------------------------------

    private void accept(String key, Translation.Line line) {
        markShown(key, line);
        listener.onAccepted(line);
    }

    private void markShown(String key, Translation.Line line) {
        shownKeys.add(key);
        if (shownKeys.size() > SEEN_LIMIT) {
            Iterator<String> oldest = shownKeys.iterator();
            oldest.next();
            oldest.remove();
        }
        if (line != null) {
            shownLines.addLast(line);
            while (shownLines.size() > SHOWN_ITEMS) {
                shownLines.removeFirst();
            }
        }
    }

    /**
     * Holds back an unfinished sentence. If it's a longer version of one already waiting (more words
     * have appeared), it replaces that one and keeps its start time.
     */
    private Waiting hold(String key, Translation.Line line, long now) {
        Waiting same = waiting.get(key);
        if (same != null) {
            same.line = line;   // unchanged: keep waiting
            return same;
        }
        long firstSeen = now;
        long lastGrew = now;
        Iterator<Waiting> others = waiting.values().iterator();
        while (others.hasNext()) {
            Waiting other = others.next();
            if (partOf(other.key, key)) {
                firstSeen = Math.min(firstSeen, other.firstSeen);   // it grew: more words appeared
                others.remove();
            } else if (partOf(key, other.key)) {
                return other;   // only part of it is visible now (e.g. the start scrolled away): keep waiting
            } else if (TranslationMemory.nearlySame(other.key, key)) {
                firstSeen = Math.min(firstSeen, other.firstSeen);   // the same text, read slightly differently
                lastGrew = other.lastGrew;
                others.remove();
            }
        }
        Waiting held = new Waiting(line, key, firstSeen, lastGrew);
        waiting.put(key, held);
        return held;
    }

    /** Forgets unfinished pieces that this finished text includes. */
    private void dropWaitingWithin(String key) {
        Iterator<Waiting> held = waiting.values().iterator();
        while (held.hasNext()) {
            String piece = held.next().key;
            if (partOf(piece, key) || TranslationMemory.nearlySame(piece, key)) {
                held.remove();
            }
        }
    }

    private interface Test {
        boolean release(Waiting held);
    }

    private List<Translation.Line> release(Test test) {
        List<Translation.Line> released = new ArrayList<>();
        Iterator<Waiting> held = waiting.values().iterator();
        while (held.hasNext()) {
            Waiting next = held.next();
            if (!test.release(next)) {
                continue;
            }
            held.remove();
            if (!alreadyShown(next.key)) {
                accept(next.key, next.line);
                released.add(next.line);
            }
        }
        return released;
    }
}
