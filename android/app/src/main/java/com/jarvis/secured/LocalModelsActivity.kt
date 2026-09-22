package com.jarvis.secured

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LocalModelsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var url: EditText
    private lateinit var checksum: EditText
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(7, 11, 18))
        }
        content.addView(TextView(this).apply {
            text = "Local AI model packs"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Install verified MediaPipe-compatible models. Downloads must use HTTPS and match the publisher's SHA-256 checksum. The chat model is a .task file. The image model is a ZIP containing the converted model directory."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply { setTextColor(Color.rgb(143, 194, 255)); textSize = 15f }
        url = EditText(this).apply {
            hint = "HTTPS model download URL"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        checksum = EditText(this).apply {
            hint = "Publisher SHA-256 (64 hexadecimal characters)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        content.addView(status)
        content.addView(url)
        content.addView(checksum)
        content.addView(Button(this).apply {
            text = "Install local chat & coding model"
            isAllCaps = false
            setOnClickListener { install(LocalModelStore.Kind.LLM) }
        })
        content.addView(Button(this).apply {
            text = "Install local image model"
            isAllCaps = false
            setOnClickListener { install(LocalModelStore.Kind.IMAGE) }
        })
        content.addView(Button(this).apply {
            text = "Remove local chat & coding model"
            isAllCaps = false
            setOnClickListener {
                LocalAiEngine.close()
                LocalModelStore.remove(this@LocalModelsActivity, LocalModelStore.Kind.LLM)
                refresh()
            }
        })
        content.addView(Button(this).apply {
            text = "Remove local image model"
            isAllCaps = false
            setOnClickListener {
                LocalModelStore.remove(this@LocalModelsActivity, LocalModelStore.Kind.IMAGE)
                refresh()
            }
        })
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refresh()
    }

    private fun refresh(extra: String? = null) {
        status.text = listOfNotNull(extra, LocalModelStore.status(this)).joinToString("\n")
    }

    private fun install(kind: LocalModelStore.Kind) {
        if (busy) return
        val source = url.text.toString().trim()
        val sha = checksum.text.toString().trim()
        busy = true
        refresh("Preparing verified download…")
        Thread {
            val result = runCatching {
                LocalModelStore.download(this, kind, source, sha) { copied, total ->
                    val detail = if (total > 0) "${copied * 100 / total}%" else "${copied / (1024 * 1024)} MB"
                    runOnUiThread { refresh("Downloading $detail") }
                }
            }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    url.text.clear()
                    checksum.text.clear()
                    refresh("Model installed and verified.")
                }.onFailure { refresh("Installation failed: ${it.message}") }
            }
        }.start()
    }
}
