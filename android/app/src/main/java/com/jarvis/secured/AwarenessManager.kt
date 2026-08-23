package com.jarvis.secured

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object AwarenessManager {
    const val DEVICE = "awareness_device"
    const val LOCATION = "awareness_location"
    const val PERSONAL = "awareness_personal"
    const val SCREEN = "awareness_screen"
    const val HOME = "awareness_home"
    const val MEMORY = "awareness_memory"
    const val PROACTIVE = "awareness_proactive"
    val categories = listOf(
        DEVICE to "Device state", LOCATION to "Location and nearby context",
        PERSONAL to "Calendar, email, reminders, and messages", SCREEN to "Current app and selected text",
        HOME to "Home and paired devices", MEMORY to "Private preferences and routines",
        PROACTIVE to "Proactive alerts and suggestions"
    )

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun enabled(context: Context, key: String) = prefs(context).getBoolean(key, key == DEVICE)
    fun setEnabled(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        if (key == PROACTIVE) AwarenessWorker.schedule(context, value)
    }

    fun snapshot(context: Context): JSONObject {
        val result = JSONObject().put("captured_at", Instant.now().toString())
        if (enabled(context, DEVICE)) result.put("device", device(context))
        if (enabled(context, LOCATION)) location(context)?.let { result.put("location", it) }
        if (enabled(context, PERSONAL)) result.put("personal", personal(context))
        if (enabled(context, SCREEN)) result.put("screen", screen(context))
        if (enabled(context, HOME)) result.put("home", home(context))
        if (enabled(context, MEMORY)) result.put("memory", memories(context))
        result.put("proactive_enabled", enabled(context, PROACTIVE))
        return result
    }

    private fun device(context: Context): JSONObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val network = context.getSystemService(ConnectivityManager::class.java).activeNetworkInfo
        return JSONObject()
            .put("battery_percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", battery.isCharging)
            .put("network", network?.typeName ?: "offline")
            .put("connected", network?.isConnected == true)
            .put("ringer_mode", when(audio.ringerMode){AudioManager.RINGER_MODE_SILENT->"silent";AudioManager.RINGER_MODE_VIBRATE->"vibrate";else->"normal"})
            .put("local_time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now()))
    }

    private fun location(context: Context): JSONObject? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val fix = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude).put("accuracy_meters", fix.accuracy).put("observed_at", Instant.ofEpochMilli(fix.time).toString())
    }

    private fun personal(context: Context): JSONObject {
        val out = JSONObject().put("host_google_context_available", true)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return out.put("calendar_permission", false)
        val events = JSONArray()
        val now = System.currentTimeMillis(); val end = now + TimeUnit.DAYS.toMillis(2)
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        runCatching {
            CalendarContract.Instances.query(context.contentResolver, projection, now, end)?.use { cursor ->
                while(cursor.moveToNext() && events.length() < 5) events.put(JSONObject().put("title",cursor.getString(0)?:"Event").put("start",Instant.ofEpochMilli(cursor.getLong(1)).toString()).put("end",Instant.ofEpochMilli(cursor.getLong(2)).toString()))
            }
        }
        return out.put("calendar_permission", true).put("upcoming_events", events)
    }

    private fun screen(context: Context) = JSONObject()
        .put("package", prefs(context).getString("awareness_screen_package", "") ?: "")
        .put("selected_text", prefs(context).getString("awareness_selected_text", "") ?: "")
        .put("updated_at", prefs(context).getString("awareness_screen_updated", "") ?: "")

    private fun home(context: Context): JSONObject {
        val p=prefs(context); val queue=runCatching{JSONArray(p.getString("offline_queue","[]"))}.getOrDefault(JSONArray())
        return JSONObject().put("paired",!p.getString("device_id",null).isNullOrBlank()).put("active_host",p.getString("active_host","") ?: "").put("queued_messages",queue.length())
    }

    private fun memories(context: Context): JSONArray = runCatching { JSONArray(prefs(context).getString("awareness_memories", "[]")) }.getOrDefault(JSONArray())
    fun handleMemoryCommand(context: Context, command: String): String? {
        if (!enabled(context, MEMORY)) return null
        val clean=command.trim(); val lower=clean.lowercase()
        if(lower.startsWith("remember that ")) {
            val fact=clean.substring(14).trim(); if(fact.isBlank())return null
            val items=memories(context);items.put(JSONObject().put("fact",fact).put("saved_at",Instant.now().toString()))
            val trimmed=JSONArray();for(i in maxOf(0,items.length()-50) until items.length())trimmed.put(items.get(i))
            prefs(context).edit().putString("awareness_memories",trimmed.toString()).apply();return "I’ll remember that privately on this phone."
        }
        if(lower.contains("what do you remember") || lower.contains("what have you remembered")) {
            val items=memories(context);if(items.length()==0)return "I don’t have any saved memories yet."
            return (0 until items.length()).joinToString("; ","I remember: "){items.optJSONObject(it)?.optString("fact").orEmpty()}
        }
        return null
    }

    fun handleLocalCommand(context: Context, command: String): String? {
        handleMemoryCommand(context, command)?.let { return it }
        val lowered = command.lowercase()
        val data = snapshot(context)
        if (lowered.contains("what do you know about my context") || lowered.contains("awareness status")) {
            val parts = mutableListOf<String>()
            data.optJSONObject("device")?.let { parts += "Battery ${it.optInt("battery_percent")}%${if(it.optBoolean("charging")) " and charging" else ""}; network ${it.optString("network", "unknown")}; ringer ${it.optString("ringer_mode", "unknown")}." }
            data.optJSONObject("location")?.let { parts += "Location is available with about ${it.optInt("accuracy_meters")} meter accuracy." }
            data.optJSONObject("personal")?.optJSONArray("upcoming_events")?.let { parts += "I can see ${it.length()} upcoming calendar event${if(it.length()==1) "" else "s"}." }
            data.optJSONObject("screen")?.optString("package")?.takeIf { it.isNotBlank() }?.let { parts += "The current app is $it." }
            data.optJSONObject("home")?.let { parts += if(it.optBoolean("paired")) "A home host is paired." else "No home host is paired." }
            data.optJSONArray("memory")?.let { parts += "I have ${it.length()} private saved memor${if(it.length()==1) "y" else "ies"}." }
            return if(parts.isEmpty()) "Awareness is enabled, but no context is available yet." else parts.joinToString(" ")
        }
        if (enabled(context, DEVICE) && (lowered.contains("battery") || lowered.contains("device status"))) {
            val device = data.optJSONObject("device") ?: return null
            return "Your battery is ${device.optInt("battery_percent")}%${if(device.optBoolean("charging")) " and charging" else ""}. Network: ${device.optString("network", "unknown")}. Ringer: ${device.optString("ringer_mode", "unknown")}."
        }
        if (enabled(context, PERSONAL) && (lowered.contains("calendar") || lowered.contains("schedule"))) {
            val personal = data.optJSONObject("personal") ?: return null
            if(!personal.optBoolean("calendar_permission")) return "Calendar awareness is on, but Android calendar permission is still needed."
            val events = personal.optJSONArray("upcoming_events") ?: JSONArray()
            if(events.length()==0) return "There are no calendar events in the next two days."
            return (0 until events.length()).joinToString("; ", "Your upcoming events: ") { events.optJSONObject(it)?.let { event -> "${event.optString("title")} at ${event.optString("start")}" }.orEmpty() }
        }
        if (enabled(context, SCREEN) && (lowered.contains("current app") || lowered.contains("selected text") || lowered.contains("on my screen"))) {
            val screen = data.optJSONObject("screen") ?: return null
            val app = screen.optString("package").ifBlank { "unknown" }
            val selected = screen.optString("selected_text")
            return if(selected.isBlank()) "The current app is $app. No selected text is available." else "The current app is $app. Selected text: $selected"
        }
        if (enabled(context, HOME) && (lowered.contains("home status") || lowered.contains("paired device"))) {
            val home = data.optJSONObject("home") ?: return null
            return "Home host: ${if(home.optBoolean("paired")) "paired" else "not paired"}. Queued messages: ${home.optInt("queued_messages")}."
        }
        return null
    }
}

class AwarenessWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork(): Result {
        if(!AwarenessManager.enabled(applicationContext, AwarenessManager.PROACTIVE))return Result.success()
        val snapshot=AwarenessManager.snapshot(applicationContext);val battery=snapshot.optJSONObject("device")?.optInt("battery_percent",100)?:100
        if(battery<=20)notify("Battery is low", "Your phone is at $battery%. Consider charging it.")
        val events=snapshot.optJSONObject("personal")?.optJSONArray("upcoming_events")
        if(events!=null&&events.length()>0){val event=events.optJSONObject(0);val start=runCatching{Instant.parse(event.optString("start"))}.getOrNull();if(start!=null&&start.toEpochMilli()-System.currentTimeMillis() in 1..TimeUnit.MINUTES.toMillis(30))notify("Upcoming: ${event.optString("title")}","Starting within 30 minutes")}
        return Result.success()
    }
    private fun notify(title:String,text:String){val manager=applicationContext.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel("jarvis_awareness","JARVIS awareness",NotificationManager.IMPORTANCE_DEFAULT));val open=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,AssistantHomeActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);manager.notify(2210,NotificationCompat.Builder(applicationContext,"jarvis_awareness").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())}
    companion object { fun schedule(context:Context,enabled:Boolean){val work=WorkManager.getInstance(context);if(!enabled){work.cancelUniqueWork("jarvis-awareness");return};work.enqueueUniquePeriodicWork("jarvis-awareness",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<AwarenessWorker>(15,TimeUnit.MINUTES).build())} }
}
