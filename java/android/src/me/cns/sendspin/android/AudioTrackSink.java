package me.cns.sendspin.android;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.util.concurrent.LinkedBlockingQueue;
import me.cns.sendspin.SendspinClient;

/**
 * Plays Sendspin PCM through AudioTrack. A writer thread owns the AudioTrack so the network
 * thread never blocks on audio. First chunk of a stream is held until its server timestamp
 * (translated by the clock offset) so playback starts when the server intended; after that
 * AudioTrack's own clock runs the stream. No drift correction yet (needs the time filter).
 * Each stream gets its own queue and writer, so a writer that is slow to die can never take
 * chunks meant for its successor.
 */
public final class AudioTrackSink implements SendspinClient.AudioSink, SendspinClient.SinkControl {
    private static final class Chunk {
        final long ts; final byte[] pcm;
        Chunk(long ts, byte[] pcm) { this.ts = ts; this.pcm = pcm; }
    }

    /** One stream: its queue, its writer, and a stop flag the writer checks between writes. */
    private static final class Stream {
        final LinkedBlockingQueue<Chunk> queue = new LinkedBlockingQueue<Chunk>(512);
        volatile boolean stopped;
        volatile int flushes;   // bumped by clear() so a writer waiting to start drops its stale chunk
        Thread thread;
    }

    private static final Chunk END = new Chunk(-1, new byte[0]);
    private static final Chunk FLUSH = new Chunk(-1, new byte[0]);

    private final SendspinClient[] clientRef = new SendspinClient[1];
    private final long t0;   // must match the client's local clock origin (set via attach)
    private volatile Stream current;
    private volatile float gain = 1f;   // server volume/mute, applied under the system STREAM_MUSIC volume
    public volatile long underruns, firstChunkLateUs;
    private final SendspinClient.Log log;

    public AudioTrackSink(long clientT0Nanos, SendspinClient.Log log) { this.t0 = clientT0Nanos; this.log = log; }

    public void attach(SendspinClient c) { clientRef[0] = c; }

    public void format(int sampleRate, int channels, int bitDepth) {
        end();
        final int r = sampleRate, c = channels, b = bitDepth;
        final Stream s = new Stream();
        s.thread = new Thread(new Runnable() { public void run() { loop(s, r, c, b); } }, "sendspin-audio");
        current = s;
        s.thread.start();
    }

    public void write(long serverTimestampUs, byte[] pcm, int off, int len) {
        Stream s = current;
        if (s == null) return; // no stream/start yet, or after stream/end
        byte[] copy = new byte[len];
        System.arraycopy(pcm, off, copy, 0, len);
        if (!s.queue.offer(new Chunk(serverTimestampUs, copy))) underruns++; // consumer stalled; drop
    }

    /** stream/clear: drop buffered audio but keep the writer; the next chunk is timed afresh. */
    public void clear() {
        Stream s = current;
        if (s == null) return;
        s.flushes++;   // only the network thread writes this
        s.queue.clear();
        s.queue.offer(FLUSH);
    }

    public void setVolume(int volume, boolean muted) {
        float v = Math.max(0, Math.min(100, volume)) / 100f;
        gain = muted ? 0f : v * v;   // square law: closer to how loud each step sounds than linear
    }

    /** stream/end: stop now; queued audio is dropped, not played out. */
    public void end() {
        Stream s = current;
        if (s == null) return;
        current = null;
        s.stopped = true;
        s.queue.clear();
        s.queue.offer(END);
        s.thread.interrupt();   // wakes a writer waiting to start; a blocked track.write returns within its buffer
        try { s.thread.join(2000); } catch (InterruptedException ignored) { }
        if (s.thread.isAlive()) log.log("audio: writer still busy after 2 s, left to exit on its own");
    }

    private void loop(Stream s, int rate, int channels, int bits) {
        int chanCfg = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int enc = bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
        int min = AudioTrack.getMinBufferSize(rate, chanCfg, enc);
        AudioTrack track;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, chanCfg, enc, Math.max(min * 4, 16384),
                AudioTrack.MODE_STREAM);
        } catch (RuntimeException e) {
            log.log("audio: cannot create track: " + e);
            return;
        }
        boolean started = false;
        float applied = -1f;
        long playStartMs = 0, written = 0;
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                log.log("audio: track not initialised (no free track slot?)");
                return;
            }
            while (!s.stopped) {
                Chunk c = s.queue.take();
                if (c == END) break;
                if (c == FLUSH) {
                    if (started) { track.pause(); track.flush(); started = false; }
                    continue;
                }
                if (!started) {
                    long offset = clientRef[0].offsetUs();
                    long localTarget = c.ts - offset;                  // local-clock microseconds
                    int gen = s.flushes;
                    long waitUs = localTarget - (System.nanoTime() - t0) / 1000L;
                    if (waitUs <= 0) firstChunkLateUs = -waitUs;
                    while (waitUs > 0 && !s.stopped && gen == s.flushes) {
                        long slice = Math.min(waitUs, 50000L);   // in slices so a clear or end is seen
                        Thread.sleep(slice / 1000L, (int) ((slice % 1000L) * 1000L));
                        waitUs = localTarget - (System.nanoTime() - t0) / 1000L;
                    }
                    if (s.stopped) break;
                    if (gen != s.flushes) continue;   // cleared while waiting: this chunk is stale
                    track.play();
                    started = true;
                    playStartMs = System.currentTimeMillis();
                    log.log("audio: play() state=" + track.getPlayState() + " session buffer=" + Math.max(min * 4, 16384) + " bytes");
                }
                float g = gain;
                if (g != applied) { track.setStereoVolume(g, g); applied = g; }
                int o = 0;
                while (o < c.pcm.length && !s.stopped) {
                    int n = track.write(c.pcm, o, c.pcm.length - o);
                    if (n <= 0) break;
                    o += n;
                }
                written += c.pcm.length / (channels * bits / 8);
            }
            long headBeforeStop = track.getPlaybackHeadPosition();
            long wallMs = started ? System.currentTimeMillis() - playStartMs : 0;
            log.log("audio: wrote " + written + " frames in " + wallMs + " ms wall, head before stop " + headBeforeStop);
            log.log("audio: first chunk late by " + firstChunkLateUs + " us, dropped chunks " + underruns);
        } catch (InterruptedException ignored) {
        } catch (RuntimeException e) {
            log.log("audio: writer failed: " + e);   // e.g. play() on a track AudioFlinger refused; must not kill the app
        } finally {
            try {
                if (started) { track.pause(); track.flush(); }   // drop what is buffered so the stop is immediate
                track.stop();
            } catch (RuntimeException ignored) { }
            track.release();
        }
    }
}
