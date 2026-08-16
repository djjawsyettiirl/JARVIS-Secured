package com.jarvis.secured

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ConnectionManagerActivity : AppCompatActivity() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    private lateinit var routesContainer: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshConnections()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun normalized(value: String?): String = value.orEmpty().trim().trimEnd('/')

    private fun savedRoutes(): List<Pair<String, String>> {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        return listOf(
            "Active" to normalized(prefs.getString("active_host", null)),
            "LAN" to normalized(prefs.getString("lan_host", null)),
            "Remote" to normalized(prefs.getString("remote_host", null)),
            "Original" to normalized(prefs.getString("host", null))
        ).filter { (_, url) -> url.startsWith("http://") || url.startsWith("https://") }
            .distinctBy { it.second }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(28))
            setBackgroundColor(Color.parseColor("#070B12"))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS Connections"
            textSize = 28f
            setTextColor(Color.parseColor("#F4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "See which saved routes work now and remove dead ones. The active route is protected from deletion."
            textSize = 14f
            setTextColor(Color.parseColor("#91A4BA"))
            setPadding(0, dp(4), 0, dp(14))
        })
        summary = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#8FC2FF"))
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(summary)
        val refresh = Button(this).apply {
            text = "Refresh connection status"
            isAllCaps = false
            setOnClickListener { refreshConnections() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        routesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(routesContainer)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refreshConnections() {
        val routes = savedRoutes()
        routesContainer.removeAllViews()
        summary.text = if (routes.isEmpty()) "No saved JARVIS routes." else "Checking ${routes.size} saved route${if (routes.size == 1) "" else "s"}..."
        if (routes.isEmpty()) return
        Thread {
            val statuses = routes.map { (kind, url) -> Triple(kind, url, probe(url)) }
            runOnUiThread {
                routesContainer.removeAllViews()
                val active = normalized(getSharedPreferences("jarvis", MODE_PRIVATE).getString("active_host", null))
                statuses.forEach { (kind, url, reachable) -> addRouteCard(kind, url, reachable, url == active) }
                val up = statuses.count { it.third }
                summary.text = "$up active/reachable • ${statuses.size - up} unavailable"
            }
        }.start()
    }

    private fun probe(url: String): Boolean {
        return try {
            val request = Request.Builder().url("$url/health").get().build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun addRouteCard(kind: String, url: String, reachable: Boolean, isActive: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#101826"))
                setStroke(dp(2), Color.parseColor(if (isActive) "#3979EF" else if (reachable) "#2F8F5B" else "#71363B"))
                cornerRadius = dp(16).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            text = when {
                isActive && reachable -> "● ACTIVE NOW — $kind"
                isActive -> "● SELECTED, CURRENTLY UNREACHABLE — $kind"
                reachable -> "● AVAILABLE FAILOVER — $kind"
                else -> "○ UNAVAILABLE — $kind"
            }
            textSize = 15f
            setTextColor(Color.parseColor(if (reachable) "#9CE0B9" else "#F2A5A9"))
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = url
            textSize = 13f
            setTextColor(Color.parseColor("#D4DEEA"))
            setPadding(0, dp(5), 0, dp(8))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val use = Button(this).apply {
            text = if (isActive) "Active" else "Use this route"
            isAllCaps = false
            isEnabled = reachable && !isActive
            setOnClickListener {
                getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("active_host", url).apply()
                refreshConnections()
            }
        }
        actions.addView(use, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val remove = Button(this).apply {
            text = "Delete"
            isAllCaps = false
            isEnabled = !isActive
            setOnClickListener { deleteRoute(url) }
        }
        actions.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        card.addView(actions)
        routesContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun deleteRoute(url: String) {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val active = normalized(prefs.getString("active_host", null))
        if (url == active) return
        val editor = prefs.edit()
        listOf("lan_host", "remote_host", "host").forEach { key ->
            if (normalized(prefs.getString(key, null)) == url) editor.remove(key)
        }
        editor.apply()
        refreshConnections()
    }
}
