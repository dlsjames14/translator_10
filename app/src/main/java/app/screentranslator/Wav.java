package app.screentranslator;

/** Sound helpers for audio translation: WAV files, and converting sound to 16 kHz mono. */
final class Wav {

    private Wav() {
    }

    /** A WAV file (16-bit mono) of the given sound. */
    static byte[] fromPcm16(short[] pcm, int sampleRate) {
        int dataBytes = pcm.length * 2;
        byte[] wav = new byte[44 + dataBytes];
        writeText(wav, 0, "RIFF");
        writeInt(wav, 4, 36 + dataBytes);
        writeText(wav, 8, "WAVE");
        writeText(wav, 12, "fmt ");
        writeInt(wav, 16, 16);                 // format chunk size
        writeShort(wav, 20, 1);                // PCM
        writeShort(wav, 22, 1);                // mono
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, sampleRate * 2);     // bytes per second
        writeShort(wav, 32, 2);                // bytes per frame
        writeShort(wav, 34, 16);               // bits per sample
        writeText(wav, 36, "data");
        writeInt(wav, 40, dataBytes);
        for (int i = 0; i < pcm.length; i++) {
            wav[44 + i * 2] = (byte) pcm[i];
            wav[45 + i * 2] = (byte) (pcm[i] >> 8);
        }
        return wav;
    }

    /** Joins clips into one. */
    static short[] join(java.util.List<short[]> clips) {
        int total = 0;
        for (short[] clip : clips) {
            total += clip.length;
        }
        short[] joined = new short[total];
        int at = 0;
        for (short[] clip : clips) {
            System.arraycopy(clip, 0, joined, at, clip.length);
            at += clip.length;
        }
        return joined;
    }

    static double seconds(short[] pcm, int sampleRate) {
        return pcm.length / (double) sampleRate;
    }

    /**
     * Turns sound of any rate and channel count into 16 kHz mono, keeping its position between
     * calls so a stream converts smoothly.
     */
    static final class Converter {
        private final int channels;
        private final double step;   // input frames per output sample
        private double position;     // where the next output sample falls, in input frames
        private double previous;     // the last input frame of the previous call

        Converter(int sampleRate, int channels) {
            this.channels = Math.max(1, channels);
            this.step = sampleRate / (double) SpeechChunker.RATE;
        }

        /** Converts interleaved samples; returns how many were written to out. */
        int convert(short[] in, int count, short[] out) {
            int frames = count / channels;
            if (channels == 1 && step == 1.0) {
                System.arraycopy(in, 0, out, 0, frames);
                return frames;
            }
            if (frames == 0) {
                return 0;
            }
            int written = 0;
            // position -1 is the last frame of the previous call, so positions between calls work too.
            while (position < frames - 1 && written < out.length) {
                int index = (int) Math.floor(position);
                double fraction = position - index;
                double a = sample(in, index);
                double b = sample(in, index + 1);
                out[written++] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                        Math.round(a + (b - a) * fraction)));
                position += step;
            }
            previous = sample(in, frames - 1);
            position -= frames;
            return written;
        }

        private double sample(short[] in, int frame) {
            return frame < 0 ? previous : mono(in, frame);
        }

        private double mono(short[] in, int frame) {
            if (channels == 1) {
                return in[frame];
            }
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += in[frame * channels + c];
            }
            return sum / channels;
        }
    }

    private static void writeText(byte[] out, int at, String text) {
        for (int i = 0; i < text.length(); i++) {
            out[at + i] = (byte) text.charAt(i);
        }
    }

    private static void writeInt(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }

    private static void writeShort(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }
}
