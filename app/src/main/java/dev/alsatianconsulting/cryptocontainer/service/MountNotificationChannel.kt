package dev.alsatianconsulting.cryptocontainer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object MountNotificationChannel {
    const val CHANNEL_ID = "crypto_container_mounts"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Container Activity",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mounts and file operations" }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
