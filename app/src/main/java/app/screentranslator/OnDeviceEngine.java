package app.screentranslator;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translation that runs entirely on the phone, free and offline:
 * 1. Google ML Kit reads the text on the screenshot (Korean and Latin-alphabet languages).
 * 2. Google's Gemma 4 model translates it, seeing the whole screen's text for context.
 */
final class OnDeviceEngine implements TranslationEngine {

    private static final int MAX_TOKENS = 4096;     // prompt + answer
    private static final int BATCH_CHARS = 1200;    // text translated per request
    private static final int CONTEXT_CHARS = 600;   // other on-screen text shown for context
    private static final int HISTORY_ITEMS = 8;     // earlier translations shown for context...
    private static final int HISTORY_CHARS = 700;   // ...kept short, so the phone stays quick
    private static final int SUMMARY_CHARS = 2500;
    private static final int SUMMARY_CONTEXT_CHARS = 600;
    private static final int SPEECH_HISTORY_ITEMS = 4;
    private static final Map<String, Object> NO_THINKING = Collections.singletonMap("enable_thinking", false);

    private final Context context;
    private final ModelStore.Spec spec;
    private final ScreenText screenText = new ScreenText();
    private Engine engine;
    private boolean engineCanListen;   // started with its audio part (for audio translation)
    private String backendName = "";

    OnDeviceEngine(Context context, ModelStore.Spec spec) {
        this.context = context.getApplicationContext();
        this.spec = spec;
    }

    @Override
    public Translation.Result translateOnce(Bitmap image, Translation.Request request) throws TranslateException {
        List<String> blocks = readText(image);
        if (blocks.isEmpty()) {
            return new Translation.Result(Translation.NO_TEXT, null);
        }
        request.reading.onReading(blocks);   // shown while Gemma translates
        String[] translations = translateNew(blocks, Collections.<String>emptyList(), request);
        StringBuilder out = new StringBuilder();
        List<Translation.Line> lines = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            String original = blocks.get(i);
            String translation = translations[i] == null || translations[i].isEmpty() ? original : translations[i];
            if (out.length() > 0) {
                out.append("\n\n");
            }
            boolean same = Translation.normalize(original).equals(Translation.normalize(translation));
            if (request.showOriginal && !same) {
                out.append(original).append("\n→ ").append(translation);
            } else {
                out.append(translation);
            }
            if (!same) {
                lines.add(new Translation.Line(original, translation));
            }
        }
        return new Translation.Result(out.toString(), lines);
    }

    @Override
    public List<Translation.Line> translateLive(Bitmap image, Translation.Request request)
            throws TranslateException {
        List<String> blocks = readText(image);
        // Step 1: is it already on the Live panel? Then leave it out (but keep it as context).
        List<String> shown = new ArrayList<>();
        for (Translation.Line line : request.shown) {
            shown.add(Translation.normalize(line.original));
        }
        List<String> fresh = new ArrayList<>();
        List<String> alreadyShown = new ArrayList<>();
        Set<String> freshKeys = new HashSet<>();
        for (String block : blocks) {
            String key = Translation.normalize(block);
            if (key.isEmpty()) {
                continue;
            }
            if (request.shownKeys.contains(key) || nearlyMatches(key, shown)) {
                alreadyShown.add(block);
            } else if (freshKeys.add(key)) {
                fresh.add(block);
            }
        }
        List<Translation.Line> lines = new ArrayList<>();
        if (fresh.isEmpty()) {
            return lines;   // nothing new on screen: no need to run the AI at all
        }
        request.reading.onReading(fresh);   // shown while Gemma translates
        // Step 2: translate what's new (reusing earlier translations of the same text).
        String[] translations = translateNew(fresh, alreadyShown, request);
        for (int i = 0; i < fresh.size(); i++) {
            if (translations[i] != null && !translations[i].isEmpty()) {
                lines.add(new Translation.Line(fresh.get(i), translations[i],
                        Translation.looksComplete(fresh.get(i))));
            }
        }
        return lines;
    }

    @Override
    public String summarize(String text, String earlier, Translation.Request request) throws TranslateException {
        Engine ai = engine(request, false);
        request.status.onStatus("Summarizing on this phone…");
        ConversationConfig config = new ConversationConfig(
                Contents.Companion.of(Prompts.summarySystem(request.language)),
                Collections.emptyList(),
                Collections.emptyList(),
                new SamplerConfig(20, 0.9, 0.3, 0));
        String input = Prompts.summaryInput(clip(text, SUMMARY_CHARS), clip(earlier, SUMMARY_CONTEXT_CHARS));
        try (Conversation conversation = ai.createConversation(config)) {
            return Prompts.cleanSummary(conversation.sendMessage(input, NO_THINKING).toString());
        } catch (Exception e) {
            throw new TranslateException("The on-device AI stopped with an error: " + e.getMessage(), false);
        } catch (OutOfMemoryError e) {
            close();
            throw new TranslateException("The phone ran out of memory. Close some apps, or choose the smaller "
                    + "model (E2B) in Screen Translator.", true);
        }
    }

    @Override
    public List<Translation.Line> translateSpeech(byte[] wav, Translation.Request request, boolean microphone)
            throws TranslateException {
        Engine ai = engine(request, true);
        request.status.onStatus("Listening and translating on this phone…");
        ConversationConfig config = new ConversationConfig(
                Contents.Companion.of(Prompts.onDeviceSpeechSystem(request.language)),
                Collections.emptyList(),
                Collections.emptyList(),
                new SamplerConfig(20, 0.9, 0.3, 0));
        StringBuilder earlier = new StringBuilder();
        int from = Math.max(0, request.shown.size() - SPEECH_HISTORY_ITEMS);
        for (Translation.Line line : request.shown.subList(from, request.shown.size())) {
            earlier.append("- ").append(line.original.replace('\n', ' ')).append(" → ")
                    .append(line.translation.replace('\n', ' ')).append('\n');
        }
        String waiting = request.waiting.isEmpty() ? "" : request.waiting.get(request.waiting.size() - 1).original;
        Contents message = Contents.Companion.of(
                new Content.AudioBytes(wav),
                new Content.Text(Prompts.onDeviceSpeech(request.language, clip(earlier.toString().trim(),
                        HISTORY_CHARS), waiting)));
        try (Conversation conversation = ai.createConversation(config)) {
            return Prompts.parseOnDeviceSpeech(conversation.sendMessage(message, NO_THINKING).toString());
        } catch (Exception e) {
            throw new TranslateException("The on-device AI couldn't listen to this clip: " + e.getMessage(), false);
        } catch (OutOfMemoryError e) {
            close();
            throw new TranslateException("The phone ran out of memory. Close some apps, or choose the smaller "
                    + "model (E2B) in Screen Translator.", true);
        }
    }

    /**
     * Translates texts, first checking the memory: text translated before gets its earlier
     * translation straight away, and only text that's really new goes to Gemma.
     */
    private String[] translateNew(List<String> texts, List<String> otherText, Translation.Request request)
            throws TranslateException {
        String[] result = new String[texts.size()];
        List<String> unknown = new ArrayList<>();
        List<Integer> where = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            TranslationMemory.Entry known = request.memory.find(texts.get(i));
            if (known != null && !known.original.isEmpty()) {
                result[i] = known.translation;
            } else {
                unknown.add(texts.get(i));
                where.add(i);
            }
        }
        if (!unknown.isEmpty()) {
            String[] translated = translateAll(unknown, otherText, request);
            for (int i = 0; i < translated.length; i++) {
                result[where.get(i)] = translated[i];
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            engine = null;
            engineCanListen = false;
        }
        screenText.close();
    }

    // ----------------------------------------------------------------------------------------
    // Step 1: read the text

    /** Text blocks in reading order (ML Kit, on the phone). */
    private List<String> readText(Bitmap image) throws TranslateException {
        return screenText.read(image);
    }

    // ----------------------------------------------------------------------------------------
    // Step 2: translate with Gemma

    private String[] translateAll(List<String> items, List<String> otherText, Translation.Request request)
            throws TranslateException {
        String context = join(otherText, CONTEXT_CHARS);
        String history = history(request);
        String[] result = new String[items.size()];
        int start = 0;
        while (start < items.size()) {
            int end = start;
            int chars = 0;
            do {
                chars += items.get(end).length();
                end++;
            } while (end < items.size() && chars + items.get(end).length() <= BATCH_CHARS);
            List<String> batch = items.subList(start, end);
            // Earlier batches of this same screen also give context to later ones.
            String batchContext = start == 0 ? context : join(items.subList(0, start), CONTEXT_CHARS);
            String[] translated = translateBatch(batch, batchContext, history, request);
            System.arraycopy(translated, 0, result, start, translated.length);
            start = end;
        }
        return result;
    }

    /** The last few translations (what the Live panel shows, then the memory), as "original → translation". */
    private static String history(Translation.Request request) {
        List<String> rows = new ArrayList<>();
        int chars = 0;
        for (int i = request.shown.size() - 1; i >= 0 && rows.size() < HISTORY_ITEMS; i--) {
            Translation.Line line = request.shown.get(i);
            String row = "- " + line.original + " → " + line.translation;
            if (chars + row.length() > HISTORY_CHARS) {
                break;
            }
            chars += row.length();
            rows.add(0, row);
        }
        if (rows.isEmpty()) {
            for (TranslationMemory.Entry entry : request.history(HISTORY_ITEMS, HISTORY_CHARS)) {
                rows.add(entry.original.isEmpty() ? "- " + entry.translation
                        : "- " + entry.original + " → " + entry.translation);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String row : rows) {
            out.append(row.replace('\n', ' ')).append('\n');
        }
        return out.toString().trim();
    }

    private static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);   // keep the newest
    }

    private String[] translateBatch(List<String> batch, String context, String history, Translation.Request request)
            throws TranslateException {
        Engine ai = engine(request, false);
        request.status.onStatus("Translating on this phone…");
        ConversationConfig config = new ConversationConfig(
                Contents.Companion.of(Prompts.onDeviceSystem(request.language)),
                Collections.emptyList(),
                Collections.emptyList(),
                new SamplerConfig(20, 0.9, 0.3, 0));
        try (Conversation conversation = ai.createConversation(config)) {
            Message reply = conversation.sendMessage(
                    Prompts.onDeviceItems(request.language, batch, context, history), NO_THINKING);
            return Prompts.parseNumbered(reply.toString(), batch.size());
        } catch (Exception e) {
            throw new TranslateException("The on-device AI stopped with an error: " + e.getMessage(), false);
        } catch (OutOfMemoryError e) {
            close();
            throw new TranslateException("The phone ran out of memory. Close some apps, or choose the smaller "
                    + "model (E2B) in Screen Translator.", true);
        }
    }

    /**
     * Loads Gemma the first time it's needed (a few seconds), then keeps it ready.
     *
     * @param listen also start Gemma's audio part (for audio translation); it's started only once needed,
     *               because it uses more memory
     */
    private Engine engine(Translation.Request request, boolean listen) throws TranslateException {
        if (engine != null && (engineCanListen || !listen)) {
            return engine;
        }
        if (engine != null) {
            close();   // restart with the audio part switched on
        }
        File model = ModelStore.file(context, spec);
        if (!ModelStore.isReady(context, spec)) {
            throw new TranslateException("The on-device AI isn't downloaded yet. Open Screen Translator and "
                    + "tap Download under On-device AI.", true);
        }
        request.status.onStatus(listen ? "Starting the on-device AI's listening (a few seconds)…"
                : "Starting the on-device AI (a few seconds the first time)…");
        String cacheDir = context.getCacheDir().getAbsolutePath();
        try {
            engine = start(model, new Backend.GPU(), cacheDir, listen);   // much faster when the phone supports it
            backendName = "GPU";
        } catch (Throwable gpuProblem) {
            try {
                engine = start(model, new Backend.CPU(), cacheDir, listen);
                backendName = "CPU";
            } catch (Throwable cpuProblem) {
                if (listen) {
                    throw new TranslateException("The on-device AI couldn't start listening on this phone ("
                            + cpuProblem.getMessage() + "). Use Gemini or ChatGPT for audio translation.", true);
                }
                throw new TranslateException("The on-device AI couldn't start on this phone ("
                        + cpuProblem.getMessage() + "). Try the smaller model, or use Gemini instead.", true);
            }
        }
        engineCanListen = listen;
        return engine;
    }

    private static Engine start(File model, Backend backend, String cacheDir, boolean listen) {
        Engine ai = new Engine(new EngineConfig(model.getAbsolutePath(), backend, null,
                listen ? new Backend.CPU() : null, MAX_TOKENS, null, cacheDir));
        try {
            ai.initialize();
            return ai;
        } catch (RuntimeException e) {
            try {
                ai.close();
            } catch (RuntimeException ignored) {
                // Nothing to free.
            }
            throw e;
        }
    }

    String backendName() {
        return backendName;
    }

    // ----------------------------------------------------------------------------------------
    // Helpers

    private static String join(List<String> texts, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (String text : texts) {
            if (out.length() + text.length() > maxChars) {
                break;
            }
            out.append("- ").append(text).append('\n');
        }
        return out.toString().trim();
    }

    /** True if the text is already shown: the same, a slightly different reading, or a piece of it. */
    private static boolean nearlyMatches(String key, List<String> shownKeys) {
        for (String shown : shownKeys) {
            if (TranslationMemory.coveredBy(key, shown)) {
                return true;
            }
        }
        return false;
    }
}
