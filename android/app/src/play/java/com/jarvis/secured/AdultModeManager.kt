package com.jarvis.secured

import android.content.Context

object AdultModeManager {
    fun isConfigured(context: Context) = false
    fun isEnabled(context: Context) = false
    fun hasPin(context: Context) = false
    fun lock(context: Context) = Unit
    fun validateImagePrompt(prompt: String, adultMode: Boolean) {
        if (Regex("(?i)\\b(nude|naked|explicit|sex|porn)\\b").containsMatchIn(prompt))
            error("That image request is unavailable in this edition")
    }
    fun systemInstruction() = ""
}
