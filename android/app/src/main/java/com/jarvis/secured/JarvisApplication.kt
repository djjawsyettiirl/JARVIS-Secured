package com.jarvis.secured

import android.app.Activity
import android.app.Application
import android.os.Bundle

class JarvisApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var pushStarted = false

    override fun onCreate() {
        super.onCreate()
        RouteUpdateWorker.schedule(this)
        AwarenessWorker.schedule(this, AwarenessManager.enabled(this, AwarenessManager.PROACTIVE))
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val pushReady = !prefs.getString("device_id", null).isNullOrBlank() &&
            !prefs.getString("route_broker", null).isNullOrBlank() &&
            !prefs.getString("route_topic", null).isNullOrBlank() &&
            !prefs.getString("route_secret", null).isNullOrBlank()
        if (!pushStarted && pushReady) {
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
