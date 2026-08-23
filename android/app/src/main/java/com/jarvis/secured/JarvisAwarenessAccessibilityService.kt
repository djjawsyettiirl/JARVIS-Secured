package com.jarvis.secured

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.time.Instant

class JarvisAwarenessAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(!AwarenessManager.enabled(this,AwarenessManager.SCREEN))return
        val selected=if(event?.eventType==AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) event.text?.joinToString(" ").orEmpty().take(500) else ""
        getSharedPreferences("jarvis",MODE_PRIVATE).edit()
            .putString("awareness_screen_package",event?.packageName?.toString().orEmpty())
            .putString("awareness_screen_updated",Instant.now().toString())
            .apply { if(selected.isNotBlank())putString("awareness_selected_text",selected) }.apply()
    }
    override fun onInterrupt()=Unit
}
