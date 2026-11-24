package app.screentranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a stream of sound (16 kHz, mono, 16-bit) into clips of speech, at the natural pauses
 * between sentences, so each clip can be translated on its own:
 * <ul>
 * <li>A clip starts when someone starts speaking (with a moment of sound before it, so the first
 * word isn't cut).</li>
 * <li>It ends after a pause of {@link #END_PAUSE_MS}, or at {@code maxSeconds} at the latest. Then
 * it's cut at the quietest moment of its last few seconds, and the rest starts the next clip.</li>
 * <li>Clips with hardly any speech in them (a cough, a click) are dropped.</li>
 * </ul>
 * "Speech" here simply means sound clearly louder than the background. The background is measured
 * from the quietest moments of the last few seconds, so it follows a noisy room or music under a
 * video, but not the speech itself. Not thread-safe: feed it from one thread.
 */
final class SpeechChunker {

    static final int RATE = 16000;
    static final int FRAME = 480;                    // 30 ms
    static final long END_PAUSE_MS = 700;

    private static final int FRAME_MS = 30;
    private static final int START_FRAMES = 3;       // 90 ms of sound starts a clip
    private static final int PRE_ROLL_FRAMES = 10;   // 300 ms kept from before the start
    private static final int END_PAUSE_FRAMES = (int) (END_PAUSE_MS / FRAME_MS);
    private static final int TAIL_FRAMES = 7;        // 210 ms of the pause kept at the end
    private static final int MIN_CLIP_FRAMES = 33;   // a clip is at least ~1 s long...
    private static final int MIN_VOICED_FRAMES = 10; // ...with at least 300 ms of sound in it
    private static final int CUT_SEARCH_FRAMES = 84; // a long clip is cut in its last 2.5 s
    private static final double MIN_LEVEL = 250;     // quieter than this is never speech
    private static final double LOUDER_THAN_BACKGROUND = 2.5;
    private static final int BACKGROUND_FRAMES = 100;     // the background is measured over 3 s...
    private static final double BACKGROUND_PERCENTILE = 0.15;   // ...from its quietest 15%

    interface Listener {
        void onClip(short[] pcm);
    }

    private final Listener listener;
    private final int maxFrames;
    private final short[] frame = new short[FRAME];
    private int frameFill;

    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private final List<short[]> clip = new ArrayList<>();
    private final List<Double> levels = new ArrayList<>();
    private boolean inSpeech;
    private int startRun;
    private int voicedFrames;
    private int pauseRun;
    private final double[] recent = new double[BACKGROUND_FRAMES];   // levels of the last 3 s
    private int recentCount;
    private int recentNext;
    private double background;   // background level (RMS)

    SpeechChunker(int maxSeconds, Listener listener) {
        this.maxFrames = Math.max(MIN_CLIP_FRAMES * 2, maxSeconds * 1000 / FRAME_MS);
        this.listener = listener;
    }

    /** Adds sound. */
    void feed(short[] samples, int count) {
        for (int i = 0; i < count; i++) {
            frame[frameFill++] = samples[i];
            if (frameFill == FRAME) {
                process(frame.clone());
                frameFill = 0;
            }
        }
    }

    /** Ends the current clip now (e.g. when listening stops), if it has speech in it. */
    void flush() {
        if (inSpeech) {
            emit(clip.size());
        }
        reset();
    }

    /** True while someone seems to be speaking (a clip is being collected). */
    boolean inSpeech() {
        return inSpeech;
    }

    // ----------------------------------------------------------------------------------------

    private void process(short[] samples) {
        double level = rms(samples);
        updateBackground(level);
        boolean voiced = level > threshold();

        if (!inSpeech) {
            preRoll.addLast(samples);
            while (preRoll.size() > PRE_ROLL_FRAMES) {
                preRoll.removeFirst();
            }
            startRun = voiced ? startRun + 1 : 0;
            if (startRun >= START_FRAMES) {
                inSpeech = true;
                for (short[] earlier : preRoll) {
                    clip.add(earlier);
                    levels.add(rms(earlier));
                }
                preRoll.clear();
                voicedFrames = startRun;
                pauseRun = 0;
            }
            return;
        }

        clip.add(samples);
        levels.add(level);
        if (voiced) {
            voicedFrames++;
            pauseRun = 0;
        } else {
            pauseRun++;
        }
        if (pauseRun >= END_PAUSE_FRAMES && clip.size() >= MIN_CLIP_FRAMES) {
            emit(clip.size() - pauseRun + TAIL_FRAMES);   // a pause: the sentence (probably) ended
            reset();
        } else if (pauseRun >= END_PAUSE_FRAMES + MIN_CLIP_FRAMES) {
            reset();   // only a short sound, then a long pause: nothing to translate
        } else if (clip.size() >= maxFrames) {
            cutLongClip();
        }
    }

    /** A clip reached the maximum length: cut it at its quietest moment near the end. */
    private void cutLongClip() {
        int from = Math.max(MIN_CLIP_FRAMES, clip.size() - CUT_SEARCH_FRAMES);
        int cut = clip.size();
        double quietest = Double.MAX_VALUE;
        for (int i = from; i < clip.size(); i++) {
            // Average over three frames, so a single quiet frame inside a word doesn't count.
            double around = (levels.get(i - 1) + levels.get(i) + levels.get(Math.min(i + 1, clip.size() - 1))) / 3;
            if (around < quietest) {
                quietest = around;
                cut = i;
            }
        }
        List<short[]> rest = new ArrayList<>(clip.subList(cut, clip.size()));
        List<Double> restLevels = new ArrayList<>(levels.subList(cut, levels.size()));
        emit(cut);
        clip.clear();
        levels.clear();
        clip.addAll(rest);
        levels.addAll(restLevels);
        voicedFrames = 0;
        for (double level : restLevels) {
            if (level > threshold()) {
                voicedFrames++;
            }
        }
        pauseRun = 0;
    }

    private void emit(int frames) {
        int count = Math.min(frames, clip.size());
        if (count <= 0 || voicedFramesIn(count) < MIN_VOICED_FRAMES) {
            return;
        }
        short[] pcm = new short[count * FRAME];
        for (int i = 0; i < count; i++) {
            System.arraycopy(clip.get(i), 0, pcm, i * FRAME, FRAME);
        }
        listener.onClip(pcm);
    }

    private int voicedFramesIn(int frames) {
        double threshold = threshold();
        int voiced = 0;
        for (int i = 0; i < frames; i++) {
            if (levels.get(i) > threshold) {
                voiced++;
            }
        }
        return Math.max(voiced, Math.min(voicedFrames, frames));
    }

    private double threshold() {
        return Math.max(MIN_LEVEL, background * LOUDER_THAN_BACKGROUND);
    }

    /** The background is the level of the quietest moments in the last few seconds. */
    private void updateBackground(double level) {
        recent[recentNext] = level;
        recentNext = (recentNext + 1) % BACKGROUND_FRAMES;
        recentCount = Math.min(recentCount + 1, BACKGROUND_FRAMES);
        if (recentCount < 10) {
            background = 0;   // not enough heard yet
            return;
        }
        double[] sorted = java.util.Arrays.copyOf(recent, recentCount);
        java.util.Arrays.sort(sorted);
        background = sorted[(int) (recentCount * BACKGROUND_PERCENTILE)];
    }

    private void reset() {
        inSpeech = false;
        clip.clear();
        levels.clear();
        startRun = 0;
        voicedFrames = 0;
        pauseRun = 0;
    }

    static double rms(short[] samples) {
        double sum = 0;
        for (short sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }
}
