package app.screentranslator;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the text in a screenshot on the phone itself (Google ML Kit: Korean and Latin-alphabet
 * text, offline, a fraction of a second). The on-device engine translates what it reads; with
 * Gemini and ChatGPT it's used to show the text being translated right away, before their answer.
 */
final class ScreenText {

    private TextRecognizer recognizer;

    /** Text blocks in reading order, each block's lines joined into one string. */
    synchronized List<String> read(Bitmap image) throws TranslateException {
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

    synchronized void close() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            recognizer = null;
        }
    }
}
