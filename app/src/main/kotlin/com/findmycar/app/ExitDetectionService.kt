package com.findmycar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service for car exit detection.
 *
 * Runs the CarPresenceStateMachine using:
 * - Motion model (TFLite) for car state detection
 * - Step counter for walking detection
 * - GPS for location tracking
 * - RoNIN model for dead reckoning during GPS gaps
 *
 * TODO: Full implementation to be ported from FindMyCar-trainer
 */
class ExitDetectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "exit_detection"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ExitDetectionService::class.java)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for drive..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FindMyCar",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Car presence monitoring" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMyCar")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }
}
