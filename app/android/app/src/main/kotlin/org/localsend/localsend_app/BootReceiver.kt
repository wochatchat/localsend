package org.localsend.localsend_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relaunches LocalSend after the device booted so that the TV can receive files
 * without the App being opened explicitly.
 *
 * Starting activities from BOOT_COMPLETED is restricted on phones since Android 10,
 * but is usually allowed on Android TV (where there is no overlay permission model).
 * If the activity cannot be started, we still start the keep-alive service so that the
 * process is at least pinned (the server itself starts once the user opens the app).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON) {
            return
        }

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // Ignore; fall back to the service below, which retries the activity
            // start once the system has settled (TV ROMs may drop early attempts).
        }

        KeepAliveService.maybeStart(context, fromBoot = true)
    }

    companion object {
        // Some TV ROMs (e.g. with quick boot / hybrid sleep) broadcast this instead.
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
