package me.cns.sendspin.android;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Voice search: the built-in microphone (unadvertised, but it works: VOICE_RECOGNITION at 16 kHz) records while the
 * centre button is held. levels holds the recent loudness for the waveform. The speech becomes text on the iPod when a
 * local model is installed (files/stt: y1-stt, our small whisper.cpp front end, and a ggml model; see docs/NOTES.md), otherwise the
 * Engine sends it to Home Assistant's speech-to-text through the Y1 Player integration.
 */
final class Voice {
    static final int RATE = 16000;
    static final int MAX_MS = 10000;           // a search, not a speech (and a cloudhook message stays small)
    private static final int CHUNK = RATE / 20;   // 50 ms

    final float[] levels = new float[48];       // 0..1, a ring: levelAt is the next to write
    volatile int levelAt;
    volatile boolean recording;
    private AudioRecord rec;
    private Thread thread;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    /** Start recording; false if the microphone is busy or missing. */
    synchronized boolean start() {
        if (recording) return true;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; return false; }
            rec.startRecording();
        } catch (RuntimeException e) {
            if (rec != null) rec.release();
            rec = null;
            return false;
        }
        pcm.reset();
        java.util.Arrays.fill(levels, 0f);
        recording = true;
        final AudioRecord r = rec;
        thread = new Thread(new Runnable() { public void run() {
            short[] s = new short[CHUNK];
            byte[] b = new byte[CHUNK * 2];
            int total = 0;
            while (recording && total < RATE * MAX_MS / 1000) {
                int n = r.read(s, 0, s.length);
                if (n <= 0) break;
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) s[i] * s[i];
                    b[2 * i] = (byte) s[i];
                    b[2 * i + 1] = (byte) (s[i] >> 8);
                }
                synchronized (pcm) { pcm.write(b, 0, 2 * n); }
                total += n;
                double db = 20 * Math.log10(Math.sqrt(sum / n) / 32768 + 1e-9);   // -90 (silence) .. 0 (full scale)
                levels[levelAt % levels.length] = (float) Math.max(0, Math.min(1, (db + 62) / 50));
                levelAt++;
            }
            recording = false;
        } }, "voice");
        thread.start();
        return true;
    }

    /** Stop and return what was recorded (16-bit little-endian mono PCM at RATE). */
    byte[] stop() {
        recording = false;
        Thread t = thread;
        if (t != null) try { t.join(1000); } catch (InterruptedException ignored) { }
        synchronized (this) {
            if (rec != null) { try { rec.stop(); } catch (RuntimeException ignored) { } rec.release(); rec = null; }
        }
        synchronized (pcm) { return pcm.toByteArray(); }
    }

    /** True if there was speech at all (not just a press with nothing said). */
    static boolean anySpeech(byte[] pcm) {
        int loud = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            if (v > 2500 || v < -2500) loud++;
        }
        return loud > RATE / 50;     // 20 ms of clearly-above-the-room sound
    }

    // ---- on the iPod: whisper.cpp, when installed in files/stt (y1-stt, model.bin)

    static boolean hasLocal(File dir) { return new File(dir, "y1-stt").canExecute() && new File(dir, "model.bin").isFile(); }

    /**
     * Just the speech: quiet before and after is cut (less for the encoder to chew on), with a little margin. "Quiet" is
     * measured against this recording's own floor: the Y1's microphone hears its room at rms 100 to 1500.
     */
    static byte[] trim(byte[] pcm) {
        int chunk = CHUNK * 2, n = pcm.length / chunk, first = -1, last = -1;
        if (n < 4) return pcm;
        double[] rms = new double[n];
        for (int k = 0; k < n; k++) {
            long sum = 0;
            for (int i = k * chunk; i < (k + 1) * chunk; i += 2) { int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); sum += (long) v * v; }
            rms[k] = Math.sqrt(sum / (double) CHUNK);
        }
        double[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        double loud = Math.max(sorted[n / 5] * 2.5, 450);  // well above the quietest fifth
        for (int k = 0; k < n; k++) if (rms[k] > loud) { if (first < 0) first = k * chunk; last = (k + 1) * chunk; }
        if (first < 0) return pcm;
        int pad = RATE * 2 * 2 / 5;        // 400 ms either side: soft word ends (the "-ter" of "better") are quiet
        int a = Math.max(0, first - pad), b = Math.min(pcm.length, last + pad);
        return java.util.Arrays.copyOfRange(pcm, a, b);
    }

    /** The local engine's text, or null if there is none (or it failed). */
    static String local(File dir, byte[] pcm) {
        File bin = new File(dir, "y1-stt"), model = new File(dir, "model.bin");
        if (!hasLocal(dir)) return null;
        File raw = new File(dir, "voice.pcm");
        try {
            FileOutputStream o = new FileOutputStream(raw);
            try { o.write(pcm); } finally { o.close(); }
            Process p = new ProcessBuilder(bin.getPath(), model.getPath(), raw.getPath()).start();
            p.getOutputStream().close();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (p.waitFor() != 0) return null;
            String t = new String(out.toByteArray(), "UTF-8").replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
            return unrepeat(t.replaceAll("[.!?]+$", ""));
        } catch (Exception e) {
            return null;
        } finally {
            raw.delete();
        }
    }

    /** Whisper on a short window can loop ("give you better give you better ..."): keep the phrase once. */
    static String unrepeat(String t) {
        String[] w = t.replaceAll("[.,!?]", "").trim().split("\\s+");
        for (int len = 1; len <= w.length / 2; len++) {
            int same = 0;
            for (int i = len; i < w.length; i++) if (w[i].equalsIgnoreCase(w[i - len])) same++;
            if (same >= (w.length - len) * 0.7) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < len; i++) { if (b.length() > 0) b.append(' '); b.append(w[i]); }
                return b.toString();
            }
        }
        return t;
    }
}
