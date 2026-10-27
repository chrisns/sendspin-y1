package me.cns.sendspin.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After boot, start the always-on service; it brings the player to the front once the home screen has settled. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        c.getApplicationContext().startService(new Intent(c.getApplicationContext(), PlayService.class));
    }
}
