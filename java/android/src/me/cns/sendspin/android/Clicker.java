package me.cns.sendspin.android;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Vibrator;

/**
 * The click of the wheel: a few-millisecond tick played through the speaker or headphones, plus a buzz from
 * the motor. Both are generated here, so they do not depend on Android's system sound-effect settings.
 */
public final class Clicker {
    private static final int RATE = 22050;
    private final Vibrator vib;
    private final AudioTrack tick, thunk;

    public Clicker(Context c) {
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        vib = v != null && v.hasVibrator() ? v : null;
        tick = make(2600, 0.035, 0.9f);    // light high tick for each wheel step
        thunk = make(1100, 0.06, 1.0f);    // lower, fuller click for a press
    }

    /** A decaying sine burst held in a static AudioTrack, so replaying it is nearly instant. */
    private static AudioTrack make(double hz, double seconds, float gain) {
        try {
            int n = (int) (RATE * seconds);
            short[] pcm = new short[n];
            for (int i = 0; i < n; i++) {
                double env = Math.exp(-6.0 * i / n);
                pcm[i] = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * env * gain * 30000);
            }
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC);
            t.write(pcm, 0, n);
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void play(AudioTrack t) {
        if (t == null) return;
        try {
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (RuntimeException ignored) { }
    }

    private long lastBuzz;
    private static final float[] LEVEL = { 0.04f, 0.10f, 0.25f };   // soft, medium, loud

    private void buzz(int ms) {
        long now = android.os.SystemClock.uptimeMillis();
        if (vib == null || now - lastBuzz < ms + 20) return;   // a new pulse would cut the last one short
        lastBuzz = now;
        vib.vibrate(ms);
    }

    private static void level(AudioTrack t, int l) {
        if (t != null) t.setStereoVolume(LEVEL[Math.max(0, Math.min(2, l))], LEVEL[Math.max(0, Math.min(2, l))]);
    }

    /** mode bits: 1 = sound, 2 = vibration; l = loudness 0 soft, 1 medium, 2 loud. */
    public void step(int mode, int l) {
        if ((mode & 1) != 0) { level(tick, l); play(tick); }
        if ((mode & 2) != 0) buzz(30);
    }

    public void press(int mode, int l) {
        if ((mode & 1) != 0) { level(thunk, l); play(thunk); }
        if ((mode & 2) != 0) buzz(55);
    }
}
