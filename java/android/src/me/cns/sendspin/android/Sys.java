package me.cns.sendspin.android;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The device settings Solar used to provide: battery, brightness, screen timeout, Wi-Fi and Bluetooth. */
public final class Sys {
    private final Context app;

    public Sys(Context c) { app = c.getApplicationContext(); }

    // ------------------------------------------------------------------ battery

    /** {level 0-100, charging 0/1} */
    public int[] battery() {
        Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return new int[] { -1, 0 };
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        return new int[] { level < 0 ? -1 : level * 100 / Math.max(1, scale), charging ? 1 : 0 };
    }

    // ------------------------------------------------------------------ screen

    public int brightnessPercent() {
        try {
            return Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) * 100 / 255;
        } catch (Exception e) { return 50; }
    }

    public void setBrightnessPercent(int p) {
        int v = Math.max(10, Math.min(255, p * 255 / 100));
        Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
    }

    public int screenTimeoutSec() {
        return Settings.System.getInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000;
    }

    public void setScreenTimeoutSec(int s) {
        Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, s * 1000);
    }

    // ------------------------------------------------------------------ Wi-Fi

    public static final class Net {
        public String ssid;
        public int level;        // 0-4 bars
        public boolean secure, saved, connected;
    }

    private WifiManager wifi() { return (WifiManager) app.getSystemService(Context.WIFI_SERVICE); }

    public boolean wifiOn() { return wifi().isWifiEnabled(); }

    public void setWifiOn(boolean on) { wifi().setWifiEnabled(on); }

    public void scan() { wifi().startScan(); }

    public String connectedSsid() {
        WifiInfo i = wifi().getConnectionInfo();
        if (i == null || i.getSSID() == null || i.getNetworkId() < 0) return null;
        return unquote(i.getSSID());
    }

    private static String unquote(String s) {
        return s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** Networks in range, strongest first, one entry per name. */
    public List<Net> networks() {
        Map<String, Net> by = new HashMap<String, Net>();
        String cur = connectedSsid();
        List<WifiConfiguration> conf = wifi().getConfiguredNetworks();
        List<ScanResult> res = wifi().getScanResults();
        if (res != null) for (ScanResult r : res) {
            if (r.SSID == null || r.SSID.length() == 0) continue;
            Net n = by.get(r.SSID);
            int lvl = WifiManager.calculateSignalLevel(r.level, 5);
            if (n == null) { n = new Net(); n.ssid = r.SSID; by.put(r.SSID, n); }
            n.level = Math.max(n.level, lvl);
            n.secure = r.capabilities.contains("WPA") || r.capabilities.contains("WEP");
            n.connected = r.SSID.equals(cur);
        }
        if (conf != null) for (WifiConfiguration c : conf) {
            Net n = by.get(unquote(c.SSID));
            if (n != null) n.saved = true;
        }
        List<Net> out = new ArrayList<Net>(by.values());
        Collections.sort(out, new Comparator<Net>() {
            public int compare(Net a, Net b) {
                if (a.connected != b.connected) return a.connected ? -1 : 1;
                return b.level - a.level;
            }
        });
        return out;
    }

    /** Join a network; password null for an open or already-saved one. */
    public boolean join(String ssid, String password) {
        WifiManager w = wifi();
        List<WifiConfiguration> conf = w.getConfiguredNetworks();
        if (password == null && conf != null) {
            for (WifiConfiguration c : conf) {
                if (ssid.equals(unquote(c.SSID))) return w.enableNetwork(c.networkId, true);
            }
        }
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        if (password == null || password.length() == 0) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            c.preSharedKey = "\"" + password + "\"";
        }
        int id = w.addNetwork(c);
        if (id < 0) return false;
        boolean ok = w.enableNetwork(id, true);
        w.saveConfiguration();
        return ok;
    }

    public void forget(String ssid) {
        WifiManager w = wifi();
        List<WifiConfiguration> conf = w.getConfiguredNetworks();
        if (conf == null) return;
        for (WifiConfiguration c : conf) if (ssid.equals(unquote(c.SSID))) w.removeNetwork(c.networkId);
        w.saveConfiguration();
    }

    // ------------------------------------------------------------------ Bluetooth

    public static final class Dev {
        public String name, address;
        public boolean bonded, connected;
        public BluetoothDevice device;
    }

    private Object a2dp;   // BluetoothA2dp proxy once bound

    public BluetoothAdapter bt() { return BluetoothAdapter.getDefaultAdapter(); }

    public boolean btOn() { return bt() != null && bt().isEnabled(); }

    public void setBtOn(boolean on) {
        if (bt() == null) return;
        if (on) { bt().enable(); bindA2dp(); } else bt().disable();
    }

    public void bindA2dp() {
        if (bt() == null || a2dp != null) return;
        bt().getProfileProxy(app, new BluetoothProfile.ServiceListener() {
            public void onServiceConnected(int profile, BluetoothProfile proxy) { a2dp = proxy; }
            public void onServiceDisconnected(int profile) { a2dp = null; }
        }, BluetoothProfile.A2DP);
    }

    /** Headphones or a speaker connected over Bluetooth audio now. */
    public boolean a2dpConnected() {
        try { return a2dp != null && !((BluetoothProfile) a2dp).getConnectedDevices().isEmpty(); } catch (RuntimeException e) { return false; }
    }

    public void discover() { if (btOn()) { bt().cancelDiscovery(); bt().startDiscovery(); } }

    public List<Dev> devices(List<BluetoothDevice> found) {
        List<Dev> out = new ArrayList<Dev>();
        if (!btOn()) return out;
        List<BluetoothDevice> connected = new ArrayList<BluetoothDevice>();
        if (a2dp != null) connected.addAll(((BluetoothProfile) a2dp).getConnectedDevices());
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (BluetoothDevice d : bt().getBondedDevices()) {
            Dev x = new Dev();
            x.device = d; x.name = d.getName(); x.address = d.getAddress(); x.bonded = true;
            x.connected = connected.contains(d);
            out.add(x); seen.add(d.getAddress());
        }
        for (BluetoothDevice d : found) {
            if (seen.contains(d.getAddress())) continue;
            Dev x = new Dev();
            x.device = d; x.name = d.getName() != null ? d.getName() : d.getAddress(); x.address = d.getAddress();
            out.add(x); seen.add(d.getAddress());
        }
        return out;
    }

    /**
     * Pair (if needed) and connect for audio, off the UI thread. Both calls are hidden API on Android 4.2, hence reflection.
     * createBond throws a SecurityException from the system process (it cannot announce the bond change across users)
     * even though the pairing goes ahead, so that is ignored: wait for the bond, then ask A2DP to connect (as root), a few times,
     * since the headphones are often still finishing their own side of pairing. Never while they are connected or connecting.
     */
    public void connect(final BluetoothDevice d) {
        new Thread(new Runnable() { public void run() { connectNow(d); } }).start();
    }

    private void connectNow(BluetoothDevice d) {
        try {
            if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
                try { d.getClass().getMethod("createBond").invoke(d); }
                catch (Exception e) { android.util.Log.i("ab", "bt createBond: " + e + " (carrying on)"); }
                for (int i = 0; i < 60 && d.getBondState() != BluetoothDevice.BOND_BONDED; i++) Thread.sleep(500);
                if (d.getBondState() != BluetoothDevice.BOND_BONDED) { android.util.Log.i("ab", "bt connect: not paired"); return; }
            }
            for (int i = 0; i < 20 && a2dp == null; i++) { bindA2dp(); Thread.sleep(500); }
            for (int attempt = 0; attempt < 4 && a2dp != null; attempt++) {
                Thread.sleep(attempt == 0 ? 1500 : 4000);
                // connect on a sink that is already connected disconnects it (rom/bt/NOTES.md), and the headphones often
                // finish connecting by themselves during the wait: look first, and leave one that is still connecting alone
                BluetoothProfile p = (BluetoothProfile) a2dp;
                if (p.getConnectedDevices().contains(d)) { android.util.Log.i("ab", "bt connected: " + d.getName()); return; }
                if (p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTING) android.util.Log.i("ab", "bt a2dp: still connecting, waiting");
                // as root: called from this app, the A2DP service trips over a missing clearCallingIdentity (a
                // SecurityException while it announces the new priority) and never connects
                else if (!Root.run("service call bluetooth_a2dp 1 i32 1 s16 " + d.getAddress())) android.util.Log.i("ab", "bt a2dp connect: root call failed");
                for (int i = 0; i < 16; i++) {
                    Thread.sleep(500);
                    if (((BluetoothProfile) a2dp).getConnectedDevices().contains(d)) { android.util.Log.i("ab", "bt connected: " + d.getName()); return; }
                }
            }
            android.util.Log.i("ab", "bt connect: gave up" + (a2dp == null ? " (no A2DP service)" : ""));
        } catch (Exception e) {
            android.util.Log.i("ab", "bt connect: " + e);
        }
    }

    public void disconnect(BluetoothDevice d) {
        try {
            Root.run("service call bluetooth_a2dp 2 i32 1 s16 " + d.getAddress());   // as root, see connectNow
        } catch (Exception ignored) { }
    }
}
