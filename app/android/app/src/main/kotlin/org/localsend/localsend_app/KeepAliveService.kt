package org.localsend.localsend_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the app process (and thus the LocalSend receive server
 * running in the Dart engine) alive while the activity is in the background.
 *
 * This is mainly for Android TV, where the App should receive files without being opened
 * explicitly: after boot, [BootReceiver] relaunches the activity as a background task and
 * this service pins the process so OEM task killers leave it alone.
 *
 * When the activity comes to the foreground again the service is stopped; it is restarted
 * when the activity moves to the background ([MainActivity.onStop]).
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText("LocalSend")
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText("LocalSend")
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // dataSync cannot be started from BOOT_COMPLETED on Android 15+; specialUse can.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "localsend_keep_alive"
        const val CHANNEL_NAME = "LocalSend 常驻服务"
        const val NOTIFICATION_TITLE = "LocalSend 常驻服务"
        const val NOTIFICATION_ID = 4712
        private const val PREF_NAME = "FlutterSharedPreferences"
        private const val PREF_KEY = "flutter.ls_keep_alive"

        /// Starts the service only when the user did not disable keep-alive in the app settings.
        /// The preference is stored by the shared_preferences plugin; reading it natively keeps
        /// the native lifecycle hooks in sync with the Dart-side toggle.
        fun maybeStart(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(PREF_KEY, true)
            if (!enabled) {
                return
            }
            start(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Starting from the background is restricted; if this fails the app can still
                // run while the user keeps it open.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
