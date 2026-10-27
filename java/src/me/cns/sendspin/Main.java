package me.cns.sendspin;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Desktop test harness: connects, records the PCM stream to a WAV file. Usage: Main host port out.wav seconds keyfile */
public final class Main {
    static final class WavSink implements SendspinClient.AudioSink {
        private final File f;
        private RandomAccessFile raf;
        private int rate, ch, bits;
        private long data;

        WavSink(File f) { this.f = f; }

        public void format(int sampleRate, int channels, int bitDepth) {
            rate = sampleRate; ch = channels; bits = bitDepth;
            try {
                raf = new RandomAccessFile(f, "rw");
                raf.setLength(0);
                raf.write(new byte[44]);
                data = 0;
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        public void write(long ts, byte[] pcm, int off, int len) {
            try { if (raf != null) { raf.write(pcm, off, len); data += len; } } catch (IOException e) { throw new RuntimeException(e); }
        }

        public void end() {
            if (raf == null) return;
            try {
                raf.seek(0);
                raf.write(header());
                raf.close();
            } catch (IOException e) { throw new RuntimeException(e); }
            raf = null;
        }

        private byte[] header() {
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, 36 + data); put(h, 8, "WAVEfmt "); le32(h, 16, 16);
            le16(h, 20, 1); le16(h, 22, ch); le32(h, 24, rate); le32(h, 28, (long) rate * ch * bits / 8);
            le16(h, 32, ch * bits / 8); le16(h, 34, bits); put(h, 36, "data"); le32(h, 40, data);
            return h;
        }

        private static void put(byte[] h, int o, String s) { for (int i = 0; i < s.length(); i++) h[o + i] = (byte) s.charAt(i); }
        private static void le16(byte[] h, int o, int v) { h[o] = (byte) v; h[o + 1] = (byte) (v >> 8); }
        private static void le32(byte[] h, int o, long v) { for (int i = 0; i < 4; i++) h[o + i] = (byte) (v >> (8 * i)); }
    }

    public static void main(String[] a) throws Exception {
        SendspinClient c = new SendspinClient(a[0], Integer.parseInt(a[1]), "Y1 java prototype",
            new File(a[4]), new WavSink(new File(a[2])), new SendspinClient.Log() {
                public void log(String s) { System.out.println(s); System.out.flush(); }
            });
        System.out.println("client_id " + c.clientId());
        c.run(Integer.parseInt(a[3]));
    }
}
