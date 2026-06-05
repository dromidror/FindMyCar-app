package com.findmycar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the ExitDetectionService and heartbeat on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ExitDetectionService.start(context)
            ServiceHeartbeat.schedule(context)
        }
    }
}
