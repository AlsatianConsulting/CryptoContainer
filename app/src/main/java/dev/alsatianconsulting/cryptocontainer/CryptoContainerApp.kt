package dev.alsatianconsulting.cryptocontainer

import android.app.Application
import dev.alsatianconsulting.cryptocontainer.service.MountNotificationChannel

class CryptoContainerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MountNotificationChannel.ensureCreated(this)
    }
}
