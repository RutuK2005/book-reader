package app.folio.reader;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/**
 * The soft rustle of a page being turned. There is no recording behind it: the sound is a fifth
 * of a second of noise, filtered so it brightens as the page sweeps over and shaped to swell
 * and die away. That keeps the app free of bundled audio files.
 */
final class PageSound {
    private static final int RATE = 22050;
    private AudioTrack track;

    PageSound() {
        try {
            short[] pcm = synthesize();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
        } catch (Throwable t) {
            // No sound is fine; reading must not depend on the audio system.
            track = null;
        }
    }

    void play() {
        if (track == null) return;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            track.reloadStaticData();
            track.play();
        } catch (Throwable ignored) {
            // A busy audio device just means a silent page turn.
        }
    }

    void release() {
        if (track == null) return;
        try {
            track.release();
        } catch (Throwable ignored) {
            // Already gone.
        }
        track = null;
    }

    private static short[] synthesize() {
        int n = RATE * 21 / 100;
        short[] out = new short[n];
        Random rnd = new Random(11);
        float low = 0f, prev = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float white = rnd.nextFloat() * 2f - 1f;
            // A low-pass whose cutoff opens as the page comes over, then high-passed so the
            // result is papery hiss rather than rumble.
            float a = 0.10f + 0.50f * t;
            low += a * (white - low);
            float paper = low - prev * 0.86f;
            prev = low;
            // Quick rise, a swell two thirds of the way through, then a soft tail.
            float rise = Math.min(1f, t / 0.07f);
            float swell = 0.55f + 0.45f * (float) Math.exp(-Math.pow((t - 0.62f) / 0.16f, 2));
            float fall = (float) Math.pow(1f - t, 1.6f);
            float v = paper * rise * swell * fall * 2.1f;
            out[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 9000f);
        }
        return out;
    }
}
