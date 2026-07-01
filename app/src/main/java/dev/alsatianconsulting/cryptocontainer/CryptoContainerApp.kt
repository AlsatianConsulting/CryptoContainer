package dev.alsatianconsulting.cryptocontainer

import android.app.Application
import android.content.Context
import dev.alsatianconsulting.cryptocontainer.service.MountNotificationChannel

class CryptoContainerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        MountNotificationChannel.ensureCreated(this)
    }

    companion object {
        /**
         * Application context for components that have no Context of their own
         * (the mount manager, the inactivity timer). Set in onCreate, which runs
         * before any user-driven mount/unmount, so it is non-null at use time.
         */
        @Volatile
        var appContext: Context? = null
            private set
    }
}
