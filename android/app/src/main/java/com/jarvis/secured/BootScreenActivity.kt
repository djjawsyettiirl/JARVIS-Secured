package com.jarvis.secured

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Short branded launch sequence for the launcher icon.
 * Voice-assistant launches continue to open AssistantHomeActivity directly.
 */
class BootScreenActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 18)
        window.navigationBarColor = Color.rgb(7, 11, 18)

        val muted = Color.rgb(145, 164, 186)
        val primary = Color.rgb(244, 247, 251)
        val accent = Color.rgb(57, 121, 239)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 11, 18))
        }

        val brandPage = TextView(this).apply {
            text = "Assistant Jarvis"
            textSize = 34f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0f
            contentDescription = "Assistant Jarvis"
        }

        val editionPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.INVISIBLE
        }
        val version = BuildConfig.VERSION_NAME
            .removeSuffix("-direct")
            .removeSuffix("-public")
        val distribution = when (BuildConfig.JARVIS_DISTRIBUTION) {
            "play" -> "Google Play Edition"
            "direct" -> "Direct Edition"
            else -> "Android Edition"
        }
        editionPage.addView(TextView(this).apply {
            text = "Version $version"
            textSize = 27f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        editionPage.addView(TextView(this).apply {
            text = distribution
            textSize = 17f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        editionPage.addView(View(this).apply {
            setBackgroundColor(accent)
        }, LinearLayout.LayoutParams(dp(64), dp(3)).apply {
            gravity = Gravity.CENTER
            topMargin = dp(18)
        })

        root.addView(brandPage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(editionPage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        brandPage.animate().alpha(1f).setDuration(900).start()
        handler.postDelayed({
            brandPage.animate().alpha(0f).setDuration(250).withEndAction {
                brandPage.visibility = View.INVISIBLE
                editionPage.visibility = View.VISIBLE
                editionPage.animate().alpha(1f).setDuration(350).start()
                // Keep the version and edition page visible for ten seconds.
                handler.postDelayed({ openAssistant() }, 10_000)
            }.start()
        }, 1_800)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openAssistant() {
        if (launched || isFinishing || isDestroyed) return
        launched = true
        startActivity(Intent(this, AssistantHomeActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
