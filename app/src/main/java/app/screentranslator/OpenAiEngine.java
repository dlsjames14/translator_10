package app.screentranslator;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** ChatGPT (OpenAI API): reads the screenshot and translates it in one request. */
final class OpenAiEngine implements TranslationEngine {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;

    OpenAiEngine(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Translation.Result translateOnce(Bitmap image, Translation.Request request) throws TranslateException {
        String prompt = Prompts.once(request.language, request.showOriginal,
                request.history(Prompts.ONLINE_HISTORY_ITEMS, Prompts.ONLINE_HISTORY_CHARS));
        String content = complete(prompt, "Translate the text in this screenshot.", Http.jpegBase64(image),
                false).trim();
        if (content.isEmpty()) {
            content = Translation.NO_TEXT;
        }
        return new Translation.Result(content,
                request.showOriginal ? Translation.pairsFromCard(content) : null);
    }

    @Override
    public List<Translation.Line> translateLive(Bitmap image, Translation.Request request)
            throws TranslateException {
        String prompt = Prompts.live(request.language, request.shown,
                request.history(Prompts.ONLINE_HISTORY_ITEMS, Prompts.ONLINE_HISTORY_CHARS),
                request.waitForSentences ? request.waiting : null);
        String content = complete(prompt,
                "Check which text in this screenshot is new, and translate only that.",
                Http.jpegBase64(image), true);
        return Prompts.parseLines(content);
    }

    /** OpenAI's speech-to-text model (the older whisper-1 and gpt-4o-transcribe stop in February 2027). */
    static final String TRANSCRIBE_MODEL = "gpt-transcribe";
    private static final String TRANSCRIBE_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";

    @Override
    public List<Translation.Line> translateSpeech(byte[] wav, Translation.Request request, boolean microphone)
            throws TranslateException {
        // 1. Write down what's said (with the last words as context, for names and half sentences).
        String transcript = transcribe(wav, speechContext(request));
        if (!Translation.hasLetters(transcript)) {
            return new java.util.ArrayList<>();   // nothing was said
        }
        request.reading.onReading(java.util.Collections.singletonList(transcript));   // shown while translating
        // 2. Translate it as the continuation of what came before.
        String prompt = Prompts.speechText(request.language, microphone, request.shown,
                request.history(Prompts.ONLINE_HISTORY_ITEMS, Prompts.ONLINE_HISTORY_CHARS), request.waiting);
        return Prompts.parseLines(complete(prompt, transcript, null, true));
    }

    private String transcribe(byte[] wav, String context) throws TranslateException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new TranslateException("Add your OpenAI API key in the Screen Translator app first.", true);
        }
        String[] fields = context.isEmpty()
                ? new String[]{"model", TRANSCRIBE_MODEL, "response_format", "json"}
                : new String[]{"model", TRANSCRIBE_MODEL, "response_format", "json", "prompt", context};
        Http.Reply reply = Http.postFile(TRANSCRIBE_ENDPOINT, new String[]{"Authorization", "Bearer " + apiKey},
                fields, "file", "speech.wav", "audio/wav", wav);
        if (reply.code == 404 || (reply.code == 400 && reply.body.contains("model"))) {
            throw new TranslateException("OpenAI's speech-to-text model (" + TRANSCRIBE_MODEL + ") isn't "
                    + "available to your account.", true);
        }
        if (!reply.ok()) {
            throw describeError(reply);
        }
        try {
            return Prompts.string(new JSONObject(reply.body), "text").trim();
        } catch (JSONException e) {
            throw new TranslateException("OpenAI sent a transcript this app couldn't read.", false);
        }
    }

    /** The last words said (in the spoken language), which helps the transcription. */
    private static String speechContext(Translation.Request request) {
        StringBuilder context = new StringBuilder();
        for (Translation.Line line : request.shown) {
            context.append(line.original).append(' ');
        }
        for (Translation.Line line : request.waiting) {
            context.append(line.original).append(' ');
        }
        String text = context.toString().trim();
        return text.length() > 300 ? text.substring(text.length() - 300) : text;
    }

    @Override
    public String summarize(String text, String earlier, Translation.Request request) throws TranslateException {
        return Prompts.cleanSummary(complete(Prompts.summarySystem(request.language),
                Prompts.summaryInput(text, earlier), null, false));
    }

    @Override
    public void close() {
        // Nothing to free.
    }

    private String complete(String systemPrompt, String userText, String jpegBase64, boolean jsonReply)
            throws TranslateException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new TranslateException("Add your OpenAI API key in the Screen Translator app first.", true);
        }
        String imageUrl = jpegBase64 == null ? null : "data:image/jpeg;base64," + jpegBase64;
        boolean fastReasoning = true;
        boolean jsonFormat = jsonReply;
        String[] headers = {"Authorization", "Bearer " + apiKey};
        for (int attempt = 0; attempt < 3; attempt++) {
            Http.Reply reply = Http.postJson(ENDPOINT, headers,
                    requestBody(systemPrompt, userText, imageUrl, fastReasoning, jsonFormat));
            if (reply.code == 400) {
                // Some models don't accept every option. Drop the one they object to and ask again.
                String body = reply.body.toLowerCase(Locale.ROOT);
                if (fastReasoning && body.contains("reasoning")) {
                    fastReasoning = false;
                    continue;
                }
                if (jsonFormat && body.contains("response_format")) {
                    jsonFormat = false;
                    continue;
                }
            }
            if (!reply.ok()) {
                throw describeError(reply);
            }
            return readContent(reply.body);
        }
        throw new TranslateException("OpenAI couldn't handle the request. Try a different AI model.", true);
    }

    private String requestBody(String systemPrompt, String userText, String imageUrl, boolean fastReasoning,
                               boolean jsonFormat) throws TranslateException {
        try {
            Object userContent = userText;   // text only (summaries)
            if (imageUrl != null) {
                userContent = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", userText))
                        .put(new JSONObject()
                                .put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", imageUrl).put("detail", "high")));
            }
            JSONArray messages = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(new JSONObject().put("role", "user").put("content", userContent));
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_completion_tokens", 4000);
            if (fastReasoning) {
                body.put("reasoning_effort", "none");   // translation doesn't need long "thinking"
            }
            if (jsonFormat) {
                body.put("response_format", new JSONObject().put("type", "json_object"));
            }
            return body.toString();
        } catch (JSONException e) {
            throw new TranslateException("Couldn't prepare the request.", false);
        }
    }

    private static String readContent(String body) throws TranslateException {
        try {
            JSONObject message = new JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message");
            String text = Prompts.string(message, "content");
            if (text.trim().isEmpty()) {
                text = Prompts.string(message, "refusal");
            }
            return text;
        } catch (JSONException e) {
            throw new TranslateException("OpenAI sent a reply this app couldn't read.", false);
        }
    }

    private TranslateException describeError(Http.Reply reply) {
        String message = "";
        String code = "";
        try {
            JSONObject error = new JSONObject(reply.body).getJSONObject("error");
            message = Prompts.string(error, "message");
            code = Prompts.string(error, "code") + " " + Prompts.string(error, "type");
        } catch (JSONException ignored) {
            // Not JSON; fall back to the status code alone.
        }
        String detail = message.isEmpty() ? "" : "\n\nOpenAI says: " + message;
        switch (reply.code) {
            case 401:
                return new TranslateException("OpenAI didn't accept your API key. Open the Screen Translator app "
                        + "and paste the key again.", true);
            case 403:
                return new TranslateException("Your OpenAI account isn't allowed to use this model." + detail, true);
            case 404:
                return new TranslateException("The AI model \"" + model + "\" wasn't found. In the Screen "
                        + "Translator app, check the ChatGPT model name." + detail, true);
            case 429:
                if (code.contains("insufficient_quota")) {
                    return new TranslateException("Your OpenAI account is out of credit. Add credit at "
                            + "platform.openai.com (Settings → Billing), or switch to Gemini or the on-device AI.",
                            true);
                }
                return new TranslateException("Too many requests right now. Wait a few seconds and try again.",
                        false);
            default:
                if (reply.code >= 500) {
                    return new TranslateException("OpenAI's servers had a problem (" + reply.code
                            + "). Try again in a moment.", false);
                }
                return new TranslateException("OpenAI returned an error (" + reply.code + ")." + detail, false);
        }
    }
}
