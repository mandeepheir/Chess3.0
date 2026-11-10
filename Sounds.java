import javax.sound.sampled.*;
import java.util.Random;

/** Tiny synthesized sound effects (no audio files needed). Fails silently if the machine has no audio output. */
public final class Sounds {

    public enum Effect { MOVE, CAPTURE, CHECK, END }

    private static final float RATE = 22050f;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, true);
    private static final byte[] MOVE = click(0.07, 420, 55, 0.45);
    private static final byte[] CAPTURE = click(0.12, 300, 35, 0.85);
    private static final byte[] CHECK = join(tone(0.09, 880, 30, 0.5), tone(0.12, 1175, 22, 0.5));
    private static final byte[] END = join(tone(0.16, 523, 12, 0.5), tone(0.16, 392, 12, 0.5), tone(0.30, 262, 8, 0.5));

    private Sounds() { }

    public static void play(Effect effect) {
        final byte[] data;
        switch (effect) {
            case CAPTURE: data = CAPTURE; break;
            case CHECK:   data = CHECK; break;
            case END:     data = END; break;
            default:      data = MOVE;
        }
        Thread t = new Thread(() -> {                               // opening an audio line can block briefly
            try {
                Clip clip = AudioSystem.getClip();
                clip.open(FORMAT, data, 0, data.length);
                clip.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) clip.close(); });
                clip.start();
            } catch (Exception | Error ignored) { /* no sound device: that is fine */ }
        }, "sound");
        t.setDaemon(true);
        t.start();
    }

    /** A short wooden "tock": a low sine plus a burst of noise, both fading out quickly. */
    private static byte[] click(double seconds, double freq, double decay, double volume) {
        int n = (int) (seconds * RATE);
        byte[] out = new byte[n * 2];
        Random rnd = new Random(7);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE, env = Math.exp(-t * decay);
            double v = (Math.sin(2 * Math.PI * freq * t) * 0.6 + (rnd.nextDouble() * 2 - 1) * 0.4) * env * volume;
            put(out, i, v);
        }
        return out;
    }

    private static byte[] tone(double seconds, double freq, double decay, double volume) {
        int n = (int) (seconds * RATE);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            put(out, i, Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * decay) * volume);
        }
        return out;
    }

    private static void put(byte[] out, int i, double v) {
        short s = (short) Math.max(-32767, Math.min(32767, (int) (v * 32767)));
        out[2 * i] = (byte) (s >> 8);                                // 16-bit big-endian
        out[2 * i + 1] = (byte) s;
    }

    private static byte[] join(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, pos, p.length); pos += p.length; }
        return out;
    }
}