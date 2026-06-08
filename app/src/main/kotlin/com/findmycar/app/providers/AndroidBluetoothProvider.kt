package com.findmycar.app.providers

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.findmycar.shared.BluetoothProvider

class AndroidBluetoothProvider(
    private val context: Context
) : BluetoothProvider {

    private var targetMac: String? = null
    private var connected = false
    private var registered = false
    private var onConnect: (() -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null

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
                BluetoothDevice.ACTION_ACL_CONNECTED -> { connected = true; onConnect?.invoke() }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> { connected = false; onDisconnect?.invoke() }
            }
        }
    }

    override fun isCarConnected(): Boolean = connected

    override fun startMonitoring(deviceId: String?) {
        stopMonitoring()
        if (deviceId.isNullOrBlank()) return
        targetMac = deviceId
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    override fun stopMonitoring() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    override fun onCarConnected(listener: () -> Unit) { onConnect = listener }
    override fun onCarDisconnected(listener: () -> Unit) { onDisconnect = listener }
}
