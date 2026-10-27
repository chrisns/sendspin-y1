package me.cns.sendspin.android;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.PowerManager;

/**
 * "Find my Y1": a button in Home Assistant (HaClient.entity("find"), default input_button.y1_find) makes the device ring a loud
 * two-note chime for a minute, until any button is pressed. It rings on the alarm stream at full volume, so neither the
 * volume limit nor a quiet setting can hide it, and Android plays alarms on the speaker even with headphones connected.
 */
public final class Finder {
    private static final int RATE = 22050;
    private final Context app;
    private AudioTrack track;
    private PowerManager.WakeLock wake;
    private int savedVolume = -1;
    public volatile boolean ringing;

    public Finder(Context c) { app = c; }

    /** One chime cycle: E6 then C6, each with a bell-like decay, then a pause (about 1.4 s). */
    private static short[] chime() {
        int n = (int) (RATE * 1.4);
        short[] pcm = new short[n];
        double[][] notes = { { 1318.5, 0.0 }, { 1046.5, 0.42 } };
        for (double[] note : notes) {
            int start = (int) (note[1] * RATE), len = (int) (0.9 * RATE);
            for (int i = 0; i < len && start + i < n; i++) {
                double t = (double) i / RATE, env = Math.exp(-t * 5.0) * Math.min(1, t * 200);
                double v = Math.sin(2 * Math.PI * note[0] * t) * 0.7 + Math.sin(4 * Math.PI * note[0] * t) * 0.2;
                int s = pcm[start + i] + (int) (v * env * 30000);
                pcm[start + i] = (short) Math.max(-32767, Math.min(32767, s));
            }
        }
        return pcm;
    }

    public synchronized void ring(int seconds) {
        stop();
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            short[] pcm = chime();
            track = new AudioTrack(AudioManager.STREAM_ALARM, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                pcm.length * 2, AudioTrack.MODE_STATIC);
            track.write(pcm, 0, pcm.length);
            int loops = Math.max(1, (int) (seconds / 1.4));
            track.setLoopPoints(0, pcm.length, loops);
            track.play();
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "ab:find");
            wake.acquire(seconds * 1000L);
            ringing = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { stop(); }
            }, seconds * 1000L + 500);
        } catch (RuntimeException e) {
            android.util.Log.i("ab", "find: could not ring: " + e);
            stop();
        }
    }

    /** Stop ringing (any button press); true if it was ringing. */
    public synchronized boolean stop() {
        boolean was = ringing;
        ringing = false;
        if (track != null) { try { track.stop(); track.release(); } catch (RuntimeException ignored) { } track = null; }
        if (wake != null) { try { if (wake.isHeld()) wake.release(); } catch (RuntimeException ignored) { } wake = null; }
        if (savedVolume >= 0) {
            try { ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0); }
            catch (RuntimeException ignored) { }
            savedVolume = -1;
        }
        return was;
    }
}
