package com.findmycar.app.providers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.findmycar.shared.NotificationProvider

class AndroidNotificationProvider(
    private val context: Context,
    private val notificationId: Int = 2001
) : NotificationProvider {

    private val channelId = "findmycar_service"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FindMyCar", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Car presence monitoring" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun show(text: String) {
        update(text)
    }

    override fun update(text: String) {
        val notification = buildNotification(text)
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    override fun dismiss() {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("FindMyCar")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    fun getChannelId(): String = channelId
}
