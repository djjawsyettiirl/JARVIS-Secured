package com.jarvis.secured

import android.service.voice.VoiceInteractionService

class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AlwaysListeningService.start(this, manual = false)
    }

    override fun onShutdown() {
        if (!AlwaysListeningService.isManuallyEnabled(this)) {
            AlwaysListeningService.stop(this, clearManualChoice = false)
        }
        super.onShutdown()
    }
}
