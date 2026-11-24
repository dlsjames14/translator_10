package app.screentranslator;

import android.graphics.Bitmap;

import java.util.List;

/**
 * A way of turning a screenshot into a translation: ChatGPT, Gemini, or the on-device AI.
 * All methods are called on the same background thread. Every request carries the translation
 * memory, so earlier text is used as context and isn't translated twice.
 */
interface TranslationEngine {

    /** "Translate once": the whole translation as readable text (plus the pairs, when known). */
    Translation.Result translateOnce(Bitmap image, Translation.Request request) throws TranslateException;

    /** Live mode: only the text that the Live panel doesn't show yet ({@link Translation.Request#shown}). */
    List<Translation.Line> translateLive(Bitmap image, Translation.Request request) throws TranslateException;

    /**
     * Audio translation: what's said in a clip of speech (a WAV file, 16 kHz mono), sentence by
     * sentence, with translations. {@link Translation.Request#shown} holds what was just said (for
     * context) and {@link Translation.Request#waiting} a sentence the previous clip cut off.
     *
     * @param microphone true for the microphone, false for sound playing on the phone
     */
    List<Translation.Line> translateSpeech(byte[] wav, Translation.Request request, boolean microphone)
            throws TranslateException;

    /**
     * A short summary (1–3 sentences) in the target language.
     *
     * @param text    what to summarize (translations, possibly with their originals)
     * @param earlier what came before, for context only (may be empty)
     */
    String summarize(String text, String earlier, Translation.Request request) throws TranslateException;

    /** Frees whatever the engine holds (the on-device AI uses a lot of memory). */
    void close();
}
