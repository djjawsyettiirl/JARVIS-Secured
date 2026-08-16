package com.jarvis.secured

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RouteUpdateWorker.schedule(this)
    }
}
