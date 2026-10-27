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
 * local model is installed (files/stt: whisper.cpp's whisper-cli and a ggml model, see docs/NOTES.md), otherwise the
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

    // ---- on the iPod: whisper.cpp, when installed in files/stt (whisper-cli, model.bin)

    /** The local engine's text, or null if there is none (or it failed). */
    static String local(File dir, byte[] pcm) {
        File bin = new File(dir, "whisper-cli"), model = new File(dir, "model.bin");
        if (!bin.canExecute() || !model.isFile()) return null;
        File wav = new File(dir, "voice.wav");
        try {
            writeWav(wav, pcm);
            // the encoder's window shrinks to the speech (1500 frames is 30 s): much faster for a few words
            int ac = Math.min(1500, Math.max(256, (int) (pcm.length / 2.0 / RATE * 50) + 64));
            Process p = new ProcessBuilder(bin.getPath(), "-m", model.getPath(), "-f", wav.getPath(), "-l", "en", "-t", "2",
                "-nt", "-np", "-ac", String.valueOf(ac), "-bs", "1", "-bo", "1").redirectErrorStream(false).start();
            p.getOutputStream().close();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (p.waitFor() != 0) return null;
            String t = new String(out.toByteArray(), "UTF-8").replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
            return t;
        } catch (Exception e) {
            return null;
        } finally {
            wav.delete();
        }
    }

    private static void writeWav(File f, byte[] pcm) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes("US-ASCII")).putInt(36 + pcm.length).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes("US-ASCII")).putInt(pcm.length);
            o.write(h.array());
            o.write(pcm);
        } finally { o.close(); }
    }
}
