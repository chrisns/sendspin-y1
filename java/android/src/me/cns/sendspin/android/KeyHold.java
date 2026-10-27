package me.cns.sendspin.android;

/**
 * The iPod's hold switch, in software: after a quiet spell the buttons stop doing anything, and pressing the same
 * button three times in quick succession (1 s, or as fast as 0.5 s) brings them back. Wheel turns never count, so a
 * wheel rubbing in a pocket cannot unlock it. Times are SystemClock.uptimeMillis().
 *
 * Pure Java on purpose: it is unit-tested on the desktop (test/BedtimeTest.java).
 */
public final class KeyHold {
    public static final int PRESSES = 3;
    public static final int[] IDLE_CHOICES_S = { 0, 30, 60, 120, 300 };          // 0 = never
    public static final int[] WINDOW_CHOICES_MS = { 500, 750, 1000, 1500, 2000 };

    public int idleS = 60;            // quiet spell before hold engages (0 = never)
    public int windowMs = 1000;       // the three presses must fit in this
    private long lastActivity;
    private boolean held;
    private int pressCode = -1, count;
    private long firstAt;

    /** A button or the wheel was used normally: restart the quiet spell. */
    public void activity(long now) { if (!held) lastActivity = now; }

    /** True when the buttons are on hold, engaging it first if the quiet spell has passed. */
    public boolean held(long now) {
        if (!held && idleS > 0 && lastActivity != 0 && now - lastActivity >= idleS * 1000L) { held = true; count = 0; }
        if (lastActivity == 0) lastActivity = now;
        return held;
    }

    /** Put it on hold now (the power menu's "Hold", or a test). */
    public void engage() { held = true; count = 0; }

    /**
     * A button pressed while on hold (never a wheel turn). Returns how many more presses of that button unlock it,
     * 0 when this press unlocked it.
     */
    public int press(int code, long now) {
        if (!held) return 0;
        if (code != pressCode || count == 0 || now - firstAt > windowMs) { pressCode = code; count = 1; firstAt = now; }
        else count++;
        if (count >= PRESSES) { held = false; count = 0; pressCode = -1; lastActivity = now; return 0; }
        return PRESSES - count;
    }

    /** Presses still needed for the button being counted, or 0 if nothing is under way (or it timed out). */
    public int remaining(long now) {
        if (!held || count == 0 || now - firstAt > windowMs) return 0;
        return PRESSES - count;
    }

    /** The button being counted (a key code), or -1. */
    public int counting(long now) { return remaining(now) > 0 ? pressCode : -1; }
}
