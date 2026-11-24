package app.screentranslator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;

/**
 * Listens for audio translation, on its own thread: either to the sound playing on the phone
 * (videos, streams, voice messages; through the screen-sharing permission), or to the microphone.
 * The sound is converted to 16 kHz mono and cut into clips of speech at the pauses
 * ({@link SpeechChunker}); each clip is handed over as it's ready.
 * <p>
 * Calls can't be captured: Android doesn't allow it. Apps can also opt out of having their sound
 * captured; those stay silent here.
 */
final class AudioListener {

    interface Callback {
        /** A clip of speech, 16 kHz mono. Called on the listening thread. */
        void onClip(short[] pcm);

        /** Listening stopped because of a problem. Called on the listening thread. */
        void onProblem(String message);
    }

    private static final int[] RATES = {SpeechChunker.RATE, 48000, 44100};

    private final AudioRecord record;
    private final int sampleRate;
    private final int channels;
    private final SpeechChunker chunker;
    private final Callback callback;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean muted;

    /**
     * @param projection the screen-sharing session, to hear the phone's sound; null for the microphone
     * @param maxClipSeconds the longest clip (the engine's limit)
     * @throws IllegalStateException with a readable message if listening can't start
     */
    AudioListener(Context context, MediaProjection projection, int maxClipSeconds, Callback callback) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("Screen Translator isn't allowed to record audio yet.");
        }
        this.callback = callback;
        this.chunker = new SpeechChunker(maxClipSeconds, callback::onClip);
        AudioRecord made = null;
        int madeRate = 0;
        int madeChannels = 1;
        RuntimeException lastProblem = null;
        // Ask for 16 kHz mono; some phones only record at 48 or 44.1 kHz, so try those too.
        for (int rate : RATES) {
            try {
                made = create(projection, rate);
                if (made.getState() == AudioRecord.STATE_INITIALIZED) {
                    madeRate = rate;
                    madeChannels = made.getChannelCount();
                    break;
                }
                made.release();
                made = null;
            } catch (RuntimeException e) {
                lastProblem = e;
                made = null;
            }
        }
        if (made == null) {
            throw new IllegalStateException(projection != null
                    ? "Couldn't listen to the phone's sound" + reason(lastProblem)
                    : "Couldn't use the microphone (another app may be using it)" + reason(lastProblem));
        }
        record = made;
        sampleRate = madeRate;
        channels = Math.max(1, madeChannels);
    }

    private static String reason(RuntimeException problem) {
        return problem == null || problem.getMessage() == null ? "." : ": " + problem.getMessage();
    }

    @SuppressWarnings("MissingPermission")   // checked in the constructor
    private static AudioRecord create(MediaProjection projection, int rate) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(minBuffer, rate * 2 / 2);   // at least half a second
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(buffer);
        if (projection != null) {
            // Sound from other apps: media (videos, music, streams), games, and anything unlabelled.
            // The app's own read-aloud voice is labelled "assistant", so it isn't heard.
            builder.setAudioPlaybackCaptureConfig(new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build());
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        return builder.build();
    }

    void start() {
        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            record.release();
            throw new IllegalStateException("Couldn't start listening: " + e.getMessage());
        }
        running = true;
        thread = new Thread(this::listen, "AudioListener");
        thread.start();
    }

    /** While muted (the app itself is speaking), what's heard counts as silence. */
    void setMuted(boolean muted) {
        this.muted = muted;
    }

    void stop() {
        running = false;
        Thread listening = thread;
        if (listening != null) {
            listening.interrupt();
            try {
                listening.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            record.stop();
        } catch (IllegalStateException ignored) {
            // Wasn't recording.
        }
        record.release();
    }

    private void listen() {
        short[] in = new short[sampleRate / 10 * channels];            // 100 ms
        short[] out = new short[SpeechChunker.RATE / 10 + 16];
        short[] silence = new short[out.length];
        Wav.Converter converter = new Wav.Converter(sampleRate, channels);
        try {
            while (running) {
                int read = record.read(in, 0, in.length);
                if (read < 0) {
                    if (running) {
                        callback.onProblem("Listening stopped (audio error " + read + ").");
                    }
                    return;
                }
                if (read == 0) {
                    continue;
                }
                int converted = converter.convert(in, read, out);
                chunker.feed(muted ? silence : out, converted);
            }
            chunker.flush();   // the last words before stopping
        } catch (RuntimeException e) {
            if (running) {
                callback.onProblem("Listening stopped: " + e.getMessage());
            }
        }
    }
}
