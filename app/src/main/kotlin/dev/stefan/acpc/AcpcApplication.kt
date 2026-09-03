package dev.stefan.acpc

import android.app.Application

/** Application singleton: holds process-wide services (created lazily). */
class AcpcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AcpcApplication
            private set
    }
}
