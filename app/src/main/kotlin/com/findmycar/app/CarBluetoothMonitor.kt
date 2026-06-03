package com.findmycar.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Monitors Bluetooth connection/disconnection of the user's configured car device.
 *
 * Used by ExitDetectionService:
 * - On car BT connect (while EXITED) → transition to IN_CAR
 * - On car BT disconnect (while IN_CAR) → supporting signal for exit detection
 */
class CarBluetoothMonitor(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var registered = false
    private var targetMac: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mac = targetMac ?: return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            if (!device.address.equals(mac, ignoreCase = true)) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected()
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected()
            }
        }
    }

    /**
     * Start monitoring. Call with the configured car Bluetooth MAC address.
     * If mac is null or blank, monitoring is not started.
     */
    fun start(mac: String?) {
        stop()
        if (mac.isNullOrBlank()) return
        targetMac = mac

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        registered = true
    }

    /**
     * Stop monitoring and unregister the receiver.
     */
    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        registered = false
        targetMac = null
    }

    companion object {
        private const val PREFS_NAME = "car_bluetooth"
        private const val KEY_MAC = "car_bt_mac"
        private const val KEY_NAME = "car_bt_name"

        /** Save the configured car Bluetooth device. */
        fun saveCarDevice(context: Context, mac: String, name: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_MAC, mac)
                .putString(KEY_NAME, name)
                .apply()
        }

        /** Get the configured car Bluetooth MAC, or null if not configured. */
        fun getCarMac(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MAC, null)
        }

        /** Get the configured car Bluetooth name, or null. */
        fun getCarName(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NAME, null)
        }

        /** Clear the configured car Bluetooth device. */
        fun clearCarDevice(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear().apply()
        }
    }
}
