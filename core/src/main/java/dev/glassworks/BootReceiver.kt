package dev.glassworks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Brings the kiosk up after boot / app update (in addition to being the HOME activity). */
open class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Glassworks", "BootReceiver: ${intent.action}")
        try {
            KioskService.start(context)
        } catch (e: Exception) {
            Log.w("Glassworks", "Could not start KioskService", e)
        }
        try {
            val launch = Intent(context, context.glassworks.components.mainActivity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launch)
        } catch (e: Exception) {
            // Background activity starts can be blocked on Android 10+ unless the app has
            // SYSTEM_ALERT_WINDOW (appops) or is the default HOME app.
            Log.w("Glassworks", "Could not start kiosk from boot", e)
        }
    }
}
