package me.cns.sendspin.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Always running (started at boot and whenever the app starts). Keeps the process in the foreground so playback
 * and sync survive the screen being off, and is the safety net that makes the app impossible to lose: if the
 * activity is not alive for any reason (closed, crashed, killed) it is brought straight back.
 */
public final class PlayService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (!MainActivity.alive) {
                Intent s = new Intent(PlayService.this, MainActivity.class);
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(s); } catch (RuntimeException ignored) { }
            }
            handler.postDelayed(this, 4000);
        }
    };
    private boolean started;

    /** Waking the iPod always lands in the player, even if Solar was left in front when it went to sleep. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            Intent s = new Intent(c, MainActivity.class);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { c.startActivity(s); } catch (RuntimeException ignored) { }
        }
    };

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        Notification n = new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(Engine.get(this).id.title())
            .setContentText("Running")
            .setContentIntent(pi)
            .build();
        startForeground(1, n);
        if (!started) {
            started = true;
            Engine.get(this);                       // a restarted process gets its sync and state back
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            handler.postDelayed(watchdog, 12000);   // after boot, let the home screen settle first
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
