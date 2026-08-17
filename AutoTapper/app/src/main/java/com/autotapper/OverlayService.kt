package com.autotapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that owns the floating overlay (bubble, markers, toolbar).
 * All the interesting logic lives in OverlayController.
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set
        private const val CHANNEL_ID = "autotapper_overlay"
        private const val NOTIFICATION_ID = 1
    }

    private var controller: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        controller = OverlayController(this).also { it.show() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: markers reposition from their normalized coordinates.
        controller?.onScreenChanged()
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Floating controls", NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("AutoTapper is floating")
            .setContentText("Tap to open setup")
            .setContentIntent(contentIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
