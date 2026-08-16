package com.jarvis.secured

import android.app.Activity
import android.app.Application
import android.os.Bundle

class JarvisApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var pushStarted = false

    override fun onCreate() {
        super.onCreate()
        RouteUpdateWorker.schedule(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!pushStarted) {
            pushStarted = true
            runCatching { RoutePushService.start(this) }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
