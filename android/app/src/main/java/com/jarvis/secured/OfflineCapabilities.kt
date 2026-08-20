package com.jarvis.secured

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract

object OfflineCapabilities {
    data class Action(val intent: Intent, val confirmation: String)

    fun actionFor(text: String): Action? {
        val command = text.trim()
        val lower = command.lowercase()
        if (lower.startsWith("directions ") || lower.startsWith("navigate ") || lower.startsWith("map ") || lower.startsWith("maps ") || lower.startsWith("open map") || lower.contains(" near me")) {
            val destination = command.replace(Regex("(?i)^(open\\s+)?(directions|navigate|maps?)(\\s+to|\\s+for)?\\s*"), "").ifBlank { command }
            return Action(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")), "Opening Maps for $destination")
        }
        if (lower.startsWith("email") || lower.startsWith("compose email") || lower.startsWith("send email")) {
            val address = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).find(command)?.value.orEmpty()
            val uri = Uri.parse(if (address.isBlank()) "mailto:" else "mailto:${Uri.encode(address)}")
            return Action(Intent(Intent.ACTION_SENDTO, uri), if (address.isBlank()) "Opening email" else "Opening email to $address")
        }
        if (lower.contains("calendar") || lower.startsWith("add event") || lower.startsWith("schedule event")) {
            val title = command.replace(Regex("(?i)^(add|create|schedule|open)?\\s*(a\\s+)?(calendar\\s+)?event\\s*"), "").ifBlank { "JARVIS event" }
            return Action(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title), "Opening Calendar for $title")
        }
        return null
    }

    fun launch(context: Context, text: String): String? {
        val action = actionFor(text) ?: return null
        action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(action.intent); action.confirmation }
            .getOrElse { "No compatible app is installed for that offline action." }
    }

    fun status(context: Context): String = if (SecureSearchCredentials.serpApiKey(context) != null)
        "Limited mode · SerpAPI search, Maps, email, and Calendar available"
    else "Limited mode · Maps, email, and Calendar available"
}
