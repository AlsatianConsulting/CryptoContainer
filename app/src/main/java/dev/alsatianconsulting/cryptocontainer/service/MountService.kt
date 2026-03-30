package dev.alsatianconsulting.cryptocontainer.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.alsatianconsulting.cryptocontainer.MainActivity
import dev.alsatianconsulting.cryptocontainer.R
import dev.alsatianconsulting.cryptocontainer.MountController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MountService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var stateJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
        stateJob?.cancel()
        stateJob = scope.launch {
            MountController.vera.volumeState.collectLatest { state ->
                val status = when {
                    state == null -> "Idle"
                    state.readOnly -> "Mounted (ro)"
                    else -> "Mounted"
                }
                val note = buildNotification(status)
                startForeground(NOTIFICATION_ID, note)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MountNotificationChannel.CHANNEL_ID)
            .setContentTitle("CryptoContainer")
            .setContentText("Volumes: $status")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MountController.unmountAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MountController.unmountAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stateJob?.cancel()
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
