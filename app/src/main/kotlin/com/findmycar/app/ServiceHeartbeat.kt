package com.findmycar.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Heartbeat that checks if ExitDetectionService is alive every 5 minutes.
 * If the service was killed, restarts it.
 *
 * Uses AlarmManager (survives Doze mode with setExactAndAllowWhileIdle).
 */
class ServiceHeartbeat : BroadcastReceiver() {

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val REQUEST_CODE = 9001

        /** Schedule the recurring heartbeat alarm. Call once on app start. */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, ServiceHeartbeat::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                // On Android 12+ (API 31), check if exact alarms are allowed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                            pendingIntent
                        )
                    } else {
                        // Fallback: inexact alarm (still works, just less precise)
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                // Permission not granted — fall back to inexact alarm
                try {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                        pendingIntent
                    )
                } catch (_: Exception) {
                    // If even this fails, silently skip — service will rely on other restart mechanisms
                }
            }
        }

        /** Cancel the heartbeat. */
        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, ServiceHeartbeat::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Restart service if not running
        ExitDetectionService.start(context)

        // Reschedule next heartbeat
        schedule(context)
    }
}
