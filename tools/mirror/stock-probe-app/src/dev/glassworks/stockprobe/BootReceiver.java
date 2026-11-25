package dev.glassworks.stockprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the read-only HTTP probe server automatically when Android finishes booting. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GlassworksStockProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        ProbeLog.i(TAG, "received " + (intent != null ? intent.getAction() : "null") + "; starting ProbeServerService");
        context.startService(new Intent(context, ProbeServerService.class));
    }
}
