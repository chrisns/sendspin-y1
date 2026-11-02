package me.cns.sendspin.android;

/**
 * When to switch Android's Bluetooth, so the Find My beacon can rotate its keys while nobody needs the headphones.
 *
 * The beacon rotates through the item's real keys only while Bluetooth is off (a rotating key means a rotating Bluetooth
 * address, and headphones are paired to a fixed one). With Bluetooth on it advertises one fixed key instead. So: when the iPod
 * has been quiet for a while and nothing is connected, Bluetooth goes off and the keys rotate; when it wakes and headphones
 * have been paired before, Bluetooth comes on and the fixed key takes over.
 */
final class BtPolicy {
    static final int NONE = 0, ON = 1, OFF = 2;
    static final long IDLE_MS = 10 * 60000L;        // quiet this long, with nothing connected, before Bluetooth goes off
    static final long MIN_GAP_MS = 5 * 60000L;      // never switch twice within this: the chip does not like being cycled

    private BtPolicy() { }

    /**
     * @param btOn       Android's Bluetooth is on now
     * @param headphones Bluetooth audio is connected
     * @param playing    something is playing
     * @param screenOn   the screen is on
     * @param paired     headphones have been paired at some point (so there is something to switch Bluetooth on for)
     * @param idleMs     how long it has been since the screen was on, something played, or headphones were connected
     * @param sinceMs    how long since Bluetooth was last switched by this rule
     */
    static int decide(boolean btOn, boolean headphones, boolean playing, boolean screenOn, boolean paired, long idleMs, long sinceMs) {
        if (sinceMs < MIN_GAP_MS) return NONE;
        boolean active = screenOn || playing || headphones;
        if (btOn) return !active && idleMs >= IDLE_MS ? OFF : NONE;
        return screenOn && paired ? ON : NONE;          // on a real wake only: never while the screen is off
    }
}
