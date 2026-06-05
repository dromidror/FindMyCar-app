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

            // setExactAndAllowWhileIdle works in Doze mode
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
                pendingIntent
            )
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
