package com.jarvis.secured

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CodingModeActivity : AppCompatActivity() {
    private lateinit var language: Spinner
    private lateinit var instruction: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad,pad,pad,pad); setBackgroundColor(Color.rgb(7,11,18)) }
        root.addView(TextView(this).apply { text="JARVIS Coding Mode"; textSize=26f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text="Runs directly from this phone. Generated code is never executed automatically."; textSize=13f; setTextColor(Color.LTGRAY) })
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@CodingModeActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto detect","Python","JavaScript","TypeScript","Kotlin","Java","C","C++","C#","Go","Rust","Swift","PHP","Ruby","SQL","HTML/CSS","Shell","PowerShell","R","Dart","Lua"))
        }
        instruction = EditText(this).apply { hint="Describe what to build or change"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); minLines=2 }
        code = EditText(this).apply { hint="Code appears here"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); typeface=android.graphics.Typeface.MONOSPACE; minLines=14; gravity=Gravity.TOP; setHorizontallyScrolling(true) }
        status = TextView(this).apply { text="Ready"; setTextColor(Color.LTGRAY); setPadding(0,pad/2,0,pad/2) }
        val generate = Button(this).apply { text="Generate / revise"; isAllCaps=false; setOnClickListener { generate() } }
        val share = Button(this).apply { text="Share code"; isAllCaps=false; setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,code.text.toString()) },"Share code"))
        } }
        root.addView(language); root.addView(instruction); root.addView(generate); root.addView(status)
        root.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f)); root.addView(share)
        setContentView(root)
    }

    private fun generate() {
        if (busy) return
        val request = instruction.text.toString().trim(); if (request.isBlank()) { status.text="Describe what you want first."; return }
        val existing = code.text.toString().trim()
        busy=true; status.text="JARVIS is coding…"
        Thread {
            val prompt = buildString {
                append("Act as a senior software engineer. Language: ${language.selectedItem}. ")
                append("Return complete usable code followed by a short explanation. Request: $request")
                if(existing.isNotBlank()) append("\nRevise this existing code:\n$existing")
            }
            val result=runCatching{StandaloneAiClient(this).ask(prompt, false)}
            runOnUiThread { busy=false; result.onSuccess { code.setText(stripFence(it)); status.text="Complete · review before running" }
                .onFailure { status.text="Coding request failed: ${it.message}" } }
        }.start()
    }

    private fun stripFence(value:String):String {
        val trimmed=value.trim(); if(!trimmed.startsWith("```"))return trimmed
        return trimmed.substringAfter('\n').substringBeforeLast("```").trim()
    }
}
