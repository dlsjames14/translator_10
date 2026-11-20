package app.screentranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private static final Map<String, Object> NO_THINKING = Collections.singletonMap("enable_thinking", false);

    private final Context context;
    private final ModelStore.Spec spec;
    private TextRecognizer recognizer;
    private Engine engine;
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
        // Step 2: translate what's new (reusing earlier translations of the same text).
        String[] translations = translateNew(fresh, alreadyShown, request);
        for (int i = 0; i < fresh.size(); i++) {
            if (translations[i] != null && !translations[i].isEmpty()) {
                lines.add(new Translation.Line(fresh.get(i), translations[i]));
            }
        }
        return lines;
    }

    @Override
    public String summarize(String text, String earlier, Translation.Request request) throws TranslateException {
        Engine ai = engine(request);
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
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            recognizer = null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Step 1: read the text

    /** Text blocks in reading order, each block's lines joined into one string. */
    private List<String> readText(Bitmap image) throws TranslateException {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        }
        Text result;
        try {
            result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new TranslateException("Couldn't read the text on the screen (" + e.getMessage() + ").", false);
        }
        List<Text.TextBlock> found = new ArrayList<>(result.getTextBlocks());
        Collections.sort(found, (a, b) -> {
            Rect boxA = a.getBoundingBox();
            Rect boxB = b.getBoundingBox();
            int topA = boxA == null ? 0 : boxA.top;
            int topB = boxB == null ? 0 : boxB.top;
            if (topA != topB) {
                return Integer.compare(topA, topB);
            }
            return Integer.compare(boxA == null ? 0 : boxA.left, boxB == null ? 0 : boxB.left);
        });
        List<String> blocks = new ArrayList<>();
        for (Text.TextBlock block : found) {
            StringBuilder joined = new StringBuilder();
            for (Text.Line line : block.getLines()) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(line.getText().trim());
            }
            String text = joined.toString().trim();
            if (Translation.hasLetters(text)) {
                blocks.add(text);
            }
        }
        return blocks;
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
        Engine ai = engine(request);
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

    /** Loads Gemma the first time it's needed (a few seconds), then keeps it ready. */
    private Engine engine(Translation.Request request) throws TranslateException {
        if (engine != null) {
            return engine;
        }
        File model = ModelStore.file(context, spec);
        if (!ModelStore.isReady(context, spec)) {
            throw new TranslateException("The on-device AI isn't downloaded yet. Open Screen Translator and "
                    + "tap Download under On-device AI.", true);
        }
        request.status.onStatus("Starting the on-device AI (a few seconds the first time)…");
        String cacheDir = context.getCacheDir().getAbsolutePath();
        try {
            engine = start(model, new Backend.GPU(), cacheDir);   // much faster when the phone supports it
            backendName = "GPU";
        } catch (Throwable gpuProblem) {
            try {
                engine = start(model, new Backend.CPU(), cacheDir);
                backendName = "CPU";
            } catch (Throwable cpuProblem) {
                throw new TranslateException("The on-device AI couldn't start on this phone ("
                        + cpuProblem.getMessage() + "). Try the smaller model, or use Gemini instead.", true);
            }
        }
        return engine;
    }

    private static Engine start(File model, Backend backend, String cacheDir) {
        Engine ai = new Engine(new EngineConfig(model.getAbsolutePath(), backend, null, null, MAX_TOKENS, null,
                cacheDir));
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
