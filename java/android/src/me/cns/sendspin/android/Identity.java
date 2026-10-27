package me.cns.sendspin.android;

/**
 * Who the player belongs to, set from Home Assistant: the owner's first name, the device's name, the "if found"
 * contact on the lock screen, the home Wi-Fi networks and the speakers Home Assistant already counts towards the
 * bedtime budget. Each comes from an input_text helper (entity ids in ha.json, generic y1_* defaults), is read on every
 * Home Assistant sync and kept in the store, so it holds offline and after a restart. Nothing personal is in the code.
 */
public final class Identity {
    private final Store store;

    public Identity(Store s) { store = s; }

    /** The owner's first name ("" = none set: messages leave it out). */
    public String owner() { return store.str("idOwner", "").trim(); }

    /** The device's own name: the lock screen, the "Listen on" row for itself and its Music Assistant speaker. */
    public String device() {
        String d = store.str("idDevice", "").trim();
        if (d.length() > 0) return d;
        return owner().length() > 0 ? owner() + "’s Y1" : "Y1";
    }

    /** "Sam's Music & Audiobooks", or "Music & Audiobooks" with no owner set. */
    public String title() { return possessive("Music & Audiobooks"); }

    /** "Sam's Favourites": the Music Assistant playlist the favourites are mirrored to. */
    public String favouritesPlaylist() { return possessive("Favourites"); }

    /** The Music Assistant playlist of songs taken off the device (music-sync leaves them out). */
    public String removedPlaylist() { return device() + ": removed"; }

    /** ", Sam" for messages, or "" with no owner set. */
    public String commaName() { return owner().length() > 0 ? ", " + owner() : ""; }

    /** The lock screen's "if found" lines (separated by "|" or new lines in the helper). */
    public String[] lostContact() {
        String c = store.str("idContact", "").trim();
        return c.length() == 0 ? new String[0] : c.split("\\s*[|\\n]\\s*");
    }

    /** Home Wi-Fi network names (on one of these the device is "home"). */
    public String[] homeWifi() { return list("idHomeWifi"); }

    /** Speakers Home Assistant already counts towards the bedtime budget (matched by name, case-insensitive). */
    public String[] countedByHa() { return list("idCountedSpeakers"); }

    private String possessive(String what) { return owner().length() > 0 ? owner() + "’s " + what : what; }

    private String[] list(String key) {
        String v = store.str(key, "").trim();
        return v.length() == 0 ? new String[0] : v.split("\\s*,\\s*");
    }

    /** Setup's first-boot values (ha.json "identity"): fill only what Home Assistant has not set yet. */
    public void seed(java.util.Map<String, Object> m, String[][] keys) {
        for (String[] k : keys) if (store.str(k[1], "").length() == 0 && m.get(k[0]) != null) update(k[1], m.get(k[0]));
    }

    /** A value read from Home Assistant: kept unless empty or unknown (a helper not created yet). */
    public boolean update(String key, Object value) {
        String v = value == null ? "" : String.valueOf(value).trim();
        if (v.length() == 0 || v.equals("unknown") || v.equals("unavailable") || v.equals("None")) return false;
        if (v.equals(store.str(key, ""))) return false;
        store.put(key, v);
        return true;
    }
}
