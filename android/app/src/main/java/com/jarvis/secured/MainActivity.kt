package com.jarvis.secured

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.location.LocationManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val preferred = "${original.url.scheme}://${original.url.host}:${original.url.port}"
            var lastError: IOException? = null
            for (baseUrl in routeCandidates(preferred)) {
                val base = baseUrl.toHttpUrl()
                val routedUrl = base.newBuilder()
                    .encodedPath(original.url.encodedPath)
                    .encodedQuery(original.url.encodedQuery)
                    .build()
                try {
                    val response = chain.proceed(original.newBuilder().url(routedUrl).build())
                    rememberActiveRoute(baseUrl)
                    return@addInterceptor response
                } catch (error: IOException) {
                    lastError = error
                }
            }
            throw lastError ?: IOException("No saved JARVIS connection routes")
        }
        .build()
    private val jsonType = "application/json".toMediaType()
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var spokenName: EditText
    private lateinit var permissionStatus: TextView
    private lateinit var voiceActivationStatus: TextView
    private lateinit var awarenessStatus: TextView
    private lateinit var enableListeningButton: Button
    private lateinit var stopListeningButton: Button
    private lateinit var defaultAssistantButton: Button
    private lateinit var scopesStatus: TextView
    private lateinit var assistantInput: EditText
    private lateinit var assistantReply: TextView
    private lateinit var inboxInput: EditText
    private lateinit var inboxMessages: TextView
    private var sessionToken: String? = null
    private var tts: TextToSpeech? = null
    private val messageHandler = Handler(Looper.getMainLooper())
    private val updateHandler = Handler(Looper.getMainLooper())
    private var messagePolling = false
    private var updatePolling = false
    @Volatile private var updateCheckInProgress = false
    @Volatile private var reconnectInProgress = false
    private var enableListeningAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
        refreshPermissions()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        host.setText(prefs.getString("active_host", prefs.getString("host", "http://192.168.1.100:8765")))
        spokenName.setText(prefs.getString("spoken_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim()))
        val savedDevice = prefs.getString("device_id", null)
        if (savedDevice != null) refreshSession(savedDevice)
        if (!prefs.getBoolean("permissions_onboarded", false)) {
            prefs.edit().putBoolean("permissions_onboarded", true).apply()
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) refreshPermissions()
        if (::voiceActivationStatus.isInitialized) refreshVoiceActivationStatus()
        if (::awarenessStatus.isInitialized) refreshAwarenessStatus()
    }

    private fun buildUi() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val textPrimary = Color.parseColor("#F4F7FB")
        val textMuted = Color.parseColor("#91A4BA")
        val accent = Color.parseColor("#3979EF")
        fun styleInput(input: EditText) = input.apply {
            setTextColor(textPrimary)
            setHintTextColor(Color.parseColor("#647892"))
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#49617E"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun styleButton(button: Button, secondary: Boolean = false) = button.apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(if (secondary) Color.parseColor("#26364D") else accent)
            minHeight = dp(48)
        }
        fun actionRow(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
        }
        fun card(titleText: String, helpText: String? = null, vararg content: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#101826"))
                setStroke(dp(1), Color.parseColor("#24334A"))
                cornerRadius = dp(20).toFloat()
            }
            addView(TextView(this@MainActivity).apply {
                text = titleText
                textSize = 20f
                setTextColor(textPrimary)
                setTypeface(typeface, Typeface.BOLD)
            })
            if (helpText != null) addView(TextView(this@MainActivity).apply {
                text = helpText
                textSize = 14f
                setTextColor(textMuted)
                setPadding(0, dp(5), 0, dp(10))
            })
            content.forEach { view ->
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(28))
            setBackgroundColor(Color.parseColor("#070B12"))
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 30f
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        val subtitle = TextView(this).apply {
            text = "Your secure home companion"
            textSize = 15f
            setTextColor(textMuted)
            setPadding(0, dp(2), 0, dp(14))
        }
        permissionStatus = TextView(this).apply { textSize = 14f; setTextColor(textMuted) }
        voiceActivationStatus = TextView(this).apply { textSize = 14f; setTextColor(textMuted) }
        awarenessStatus = TextView(this).apply { textSize = 13f; setTextColor(textMuted) }
        val awarenessControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        AwarenessManager.categories.forEach { (key, label) ->
            awarenessControls.addView(Switch(this).apply {
                text = label; textSize = 14f; setTextColor(textPrimary); isChecked = AwarenessManager.enabled(this@MainActivity, key)
                setOnCheckedChangeListener { _, checked ->
                    AwarenessManager.setEnabled(this@MainActivity, key, checked)
                    if (checked && key == AwarenessManager.SCREEN) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    if (checked && key == AwarenessManager.LOCATION && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST)
                    if (checked && key == AwarenessManager.PERSONAL && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), PERMISSION_REQUEST)
                    refreshAwarenessStatus()
                }
            })
        }
        val request = styleButton(Button(this).apply {
            text = "Complete permission setup"
            setOnClickListener { requestAllPermissions() }
        })
        val settings = styleButton(Button(this).apply {
            text = "Android settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }, true)
        host = styleInput(EditText(this).apply {
            hint = "Host URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        })
        code = styleInput(EditText(this).apply {
            hint = "8-digit one-time pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        })
        val pair = styleButton(Button(this).apply {
            text = "Pair with JARVIS"
            setOnClickListener { pairDevice() }
        })
        status = TextView(this).apply { text = "Not paired"; textSize = 16f; setTextColor(Color.parseColor("#8FC2FF")) }
        spokenName = styleInput(EditText(this).apply { hint = "Name spoken by Windows"; maxLines = 1 })
        val saveSpokenName = styleButton(Button(this).apply {
            text = "Save spoken name"
            setOnClickListener { saveSpokenDeviceName() }
        })
        scopesStatus = TextView(this).apply { textSize = 13f; setTextColor(textMuted) }
        assistantInput = styleInput(EditText(this).apply { hint = "Ask JARVIS anything"; maxLines = 3 })
        val ask = styleButton(Button(this).apply { text = "Ask JARVIS"; setOnClickListener { askJarvis() } })
        val speak = styleButton(Button(this).apply { text = "🎙 Speak"; setOnClickListener { startVoiceInput() } }, true)
        val update = styleButton(Button(this).apply { text = "Check private update"; setOnClickListener { installPrivateUpdate(false) } })
        enableListeningButton = styleButton(Button(this).apply {
            text = "Enable always listening"
            setOnClickListener { enableAlwaysListening() }
        })
        stopListeningButton = styleButton(Button(this).apply {
            text = "Stop listening"
            setOnClickListener { AlwaysListeningService.stop(this@MainActivity); refreshVoiceActivationStatus() }
        }, true)
        defaultAssistantButton = styleButton(Button(this).apply {
            text = "Set as default assistant"
            setOnClickListener { requestAssistantRole() }
        })
        val batterySettings = styleButton(Button(this).apply {
            text = "Allow background battery use"
            setOnClickListener { requestBatteryExemption() }
        }, true)
        assistantReply = TextView(this).apply {
            text = "Pair with the Windows host to begin."
            textSize = 16f
            setTextColor(textPrimary)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#0A111B")); cornerRadius = dp(12).toFloat() }
        }
        inboxInput = styleInput(EditText(this).apply {
            hint = "Send a message to the Windows client"
            maxLines = 3
        })
        val sendInbox = styleButton(Button(this).apply {
            text = "Send to Windows"
            setOnClickListener { sendInboxMessage() }
        })
        inboxMessages = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimary)
            setPadding(dp(4), dp(8), dp(4), dp(4))
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        renderInbox()

        root.addView(title)
        root.addView(subtitle)
        listOf(
            card("Connection", "Pair once, then JARVIS reconnects automatically.", status, host, code, pair, spokenName, saveSpokenName, scopesStatus),
            card("Voice activation", "Automatic when JARVIS is your default assistant. Otherwise, you can enable wake listening manually. Say “Jarvis” followed by a command.", voiceActivationStatus, actionRow(enableListeningButton, stopListeningButton), actionRow(defaultAssistantButton, batterySettings)),
            card("Awareness", "Each category is private, separately controlled, and handled on this phone. Sensitive context is not silently sent to the host.", awarenessStatus, awarenessControls),
            card("App permissions", "Only access required by JARVIS features is requested. The setup button advances through any missing Android grants.", permissionStatus, actionRow(request, settings), update)
        ).forEach { section ->
            root.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            })
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        refreshVoiceActivationStatus()
        refreshAwarenessStatus()
    }

    private fun permissionRequests(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        return permissions.toTypedArray()
    }

    private fun requestAllPermissions() {
        val pending = permissionRequests().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pending.isNotEmpty()) {
            requestPermissions(pending.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val power = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && !power.isIgnoringBatteryOptimizations(packageName)) {
            requestBatteryExemption()
            return
        }
        refreshPermissions()
        assistantReply.text = "JARVIS has all required Android permissions and special access."
    }

    private fun permissionLabel(permission: String, label: String): String {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        return "$label: ${if (granted) "GRANTED" else "NOT GRANTED"}"
    }

    private fun refreshPermissions() {
        if (!::permissionStatus.isInitialized) return
        val lines = mutableListOf(
            permissionLabel(Manifest.permission.RECORD_AUDIO, "Microphone"),
            permissionLabel(Manifest.permission.ACCESS_FINE_LOCATION, "Location awareness (optional)")
        )
        lines.add(permissionLabel(Manifest.permission.READ_CALENDAR, "Calendar awareness (optional)"))
        if (Build.VERSION.SDK_INT >= 33) lines.add(permissionLabel(Manifest.permission.POST_NOTIFICATIONS, "Notifications"))
        val installs = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()
        val voiceBackgroundEnabled = AlwaysListeningService.isDefaultAssistant(this) || AlwaysListeningService.isManuallyEnabled(this)
        val battery = !voiceBackgroundEnabled || Build.VERSION.SDK_INT < 23 || getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        lines.add("Private update installation: ${if (installs) "ALLOWED" else "ACTION NEEDED"}")
        lines.add("Background battery use: ${if (!voiceBackgroundEnabled) "NOT NEEDED" else if (battery) "UNRESTRICTED" else "ACTION NEEDED"}")
        lines.add("Default assistant: ${if (AlwaysListeningService.isDefaultAssistant(this)) "JARVIS" else "OPTIONAL / NOT SELECTED"}")
        val runtimeReady = permissionRequests().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        permissionStatus.text = "Permission readiness: ${if (runtimeReady && installs && battery) "COMPLETE" else "ACTION NEEDED"}\n" + lines.joinToString("\n")
    }

    private fun refreshAwarenessStatus() {
        if (!::awarenessStatus.isInitialized) return
        val enabled = AwarenessManager.categories.filter { AwarenessManager.enabled(this, it.first) }.map { it.second }
        awarenessStatus.text = "Enabled: ${if (enabled.isEmpty()) "none" else enabled.joinToString(", ")}\nSay “what do you know about my context?” for a private summary."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            refreshPermissions()
            if (enableListeningAfterPermission) {
                enableListeningAfterPermission = false
                enableAlwaysListening(manual = !AlwaysListeningService.isDefaultAssistant(this))
            }
        }
    }

    private fun enableAlwaysListening(manual: Boolean = true) {
        if (getSharedPreferences("jarvis", MODE_PRIVATE).getString("device_id", null).isNullOrBlank()) {
            voiceActivationStatus.text = "Pair this phone with the Windows host first."
            return
        }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) {
            enableListeningAfterPermission = true
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        AlwaysListeningService.start(this, manual)
            .onSuccess { voiceActivationStatus.text = "Always listening: ON${if (!manual) " · managed by default assistant" else ""}\nSay “Jarvis” followed by your command." }
            .onFailure { voiceActivationStatus.text = "Could not start listening: ${it.message}" }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    enableAlwaysListening(manual = false)
                } else startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), ASSISTANT_ROLE_REQUEST)
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { voiceActivationStatus.text = "This phone does not expose a default-assistant picker." }
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(packageName)) {
            voiceActivationStatus.text = "Background battery use: allowed"
            return
        }
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun refreshVoiceActivationStatus() {
        if (!::voiceActivationStatus.isInitialized) return
        val manualEnabled = AlwaysListeningService.isManuallyEnabled(this)
        val assistant = if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else false
        val battery = if (Build.VERSION.SDK_INT >= 23) getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) else true
        if (::enableListeningButton.isInitialized) {
            enableListeningButton.visibility = if (assistant) View.GONE else View.VISIBLE
            stopListeningButton.visibility = if (assistant) View.GONE else View.VISIBLE
            defaultAssistantButton.text = if (assistant) "JARVIS is the default assistant" else "Set as default assistant"
            defaultAssistantButton.isEnabled = !assistant
        }
        if (assistant && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AlwaysListeningService.start(this, manual = false)
        }
        val lastError = AlwaysListeningService.lastError(this)
        voiceActivationStatus.text = if (assistant) {
            "Always listening: ON · managed by Android assistant role\nMicrophone: ${if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "READY" else "GRANT WHILE USING APP"}\nBackground battery use: ${if (battery) "allowed" else "system managed"}"
        } else {
            "Always listening: ${if (manualEnabled) "ON" else "OFF"} · manual\nDefault assistant: not selected\nBackground battery use: ${if (battery) "allowed" else "system managed"}"
        }
        if (lastError.isNotBlank()) voiceActivationStatus.append("\nLast listener error: ${lastError.take(160)}")
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun publicKeyPem(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS) ?: error("Device key missing")
        val b64 = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    private fun sign(challenge: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = (ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun normalizedUrl(value: String?): String = value.orEmpty().trim().trimEnd('/')

    private fun rememberRoutes(routes: JSONObject?) {
        if (routes == null) return
        val editor = getSharedPreferences("jarvis", MODE_PRIVATE).edit()
        normalizedUrl(routes.optString("lan")).takeIf { it.startsWith("http") }?.let { editor.putString("lan_host", it) }
        normalizedUrl(routes.optString("remote")).takeIf { it.startsWith("http") }?.let { editor.putString("remote_host", it) }
        editor.apply()
    }

    private fun routeCandidates(preferred: String? = null): List<String> {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        return listOf(
            normalizedUrl(preferred),
            normalizedUrl(prefs.getString("active_host", null)),
            normalizedUrl(prefs.getString("lan_host", null)),
            normalizedUrl(prefs.getString("remote_host", null)),
            normalizedUrl(prefs.getString("host", null))
        ).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }

    private fun rememberActiveRoute(baseUrl: String) {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("active_host", baseUrl).apply()
        runOnUiThread {
            if (::host.isInitialized) host.setText(baseUrl)
            if (::status.isInitialized && sessionToken != null) {
                status.text = "✓ Connected automatically via ${if (baseUrl.startsWith("https://")) "remote" else "local network"}"
            }
        }
    }

    private fun <T> withRoute(preferred: String? = null, action: (String) -> T): T {
        var lastError: Exception? = null
        for (baseUrl in routeCandidates(preferred)) {
            try {
                val result = action(baseUrl)
                rememberActiveRoute(baseUrl)
                return result
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IOException("No saved JARVIS connection routes")
    }

    private fun pairDevice() {
        val baseUrl = host.text.toString().trim().trimEnd('/')
        val pairingCode = code.text.toString().trim()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            status.text = "Host must start with http:// or https://"
            return
        }
        if (!pairingCode.matches(Regex("\\d{8}"))) {
            status.text = "Enter the 8-digit pairing code"
            return
        }
        status.text = "Connecting..."
        Thread {
            try {
                ensureKeyPair()
                val deviceName = spokenName.text.toString().trim().ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }
                val payload = JSONObject().apply {
                    put("code", pairingCode)
                    put("device_name", deviceName)
                    put("public_key_pem", publicKeyPem())
                }
                withRoute(baseUrl) { route ->
                    val request = Request.Builder().url("$route/pair")
                        .post(payload.toString().toRequestBody(jsonType)).build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: "{}"
                        if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Pairing failed"))
                        val obj = JSONObject(body)
                        val deviceId = obj.getString("device_id")
                        val challenge = obj.getString("challenge")
                        val scopes = obj.optJSONArray("scopes")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList()
                        rememberRoutes(obj.optJSONObject("routes"))
                        getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                            .putString("host", route).putString("device_id", deviceId)
                            .putString("spoken_name", deviceName)
                            .putStringSet("scopes", scopes.toSet()).commit()
                        authenticate(route, deviceId, challenge)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Pairing failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun authenticate(baseUrl: String, deviceId: String, challenge: String) {
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("signature_b64", sign(challenge))
        }
        withRoute(baseUrl) { route ->
          val request = Request.Builder().url("$route/auth/verify")
              .post(payload.toString().toRequestBody(jsonType)).build()
          http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            val obj = JSONObject(body)
            val ok = response.isSuccessful && obj.optBoolean("authenticated", false)
            if (!ok) error(obj.optString("detail", "Authentication failed"))
            if (ok) sessionToken = obj.optString("session_token").takeIf { it.isNotBlank() }
            rememberRoutes(obj.optJSONObject("routes"))
            val scopes = obj.optJSONArray("scopes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: getSharedPreferences("jarvis", MODE_PRIVATE).getStringSet("scopes", emptySet())!!.toList()
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putStringSet("scopes", scopes.toSet()).apply()
            runOnUiThread {
                status.text = if (ok) "✓ JARVIS paired and authenticated\nDevice: $deviceId" else "Authentication failed: $body"
                scopesStatus.text = "JARVIS capabilities\n" + (if (scopes.isEmpty()) "None" else scopes.joinToString("\n") { "• $it" })
                if (ok) assistantReply.text = "Ready. Ask me about Gmail, Calendar, Maps, or alarms."
                // The visible AssistantHomeActivity owns message polling. Keep the
                // legacy settings-screen inbox code dormant to avoid duplicate TTS.
                if (ok) startAutomaticUpdatePolling()
            }
          }
        }
    }

    private fun refreshSession(deviceId: String) {
        if (reconnectInProgress) return
        reconnectInProgress = true
        status.text = "Reconnecting to JARVIS..."
        Thread {
            try {
                ensureKeyPair()
                withRoute { route ->
                    val request = Request.Builder().url("$route/auth/challenge?device_id=$deviceId").post("".toRequestBody(null)).build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: "{}"
                        if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Authentication failed"))
                        authenticate(route, deviceId, JSONObject(body).getString("challenge"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Reconnect failed: ${e.message ?: "connection error"}" }
            } finally {
                reconnectInProgress = false
            }
        }.start()
    }

    private fun saveSpokenDeviceName() {
        val name = spokenName.text.toString().trim()
        if (name.isEmpty() || name.length > 50) {
            assistantReply.text = "Choose a spoken name between 1 and 50 characters."
            return
        }
        val token = sessionToken
        if (token == null) {
            assistantReply.text = "Pair or reconnect to the Windows host first."
            return
        }
        val baseUrl = host.text.toString().trim().trimEnd('/')
        Thread {
            try {
                val payload = JSONObject().put("name", name)
                val request = Request.Builder().url("$baseUrl/device/name")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        error(JSONObject(body).optString("detail", "Rename failed"))
                    }
                }
                getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("spoken_name", name).apply()
                runOnUiThread { assistantReply.text = "Windows will now announce messages from $name." }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Rename failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun askJarvis() {
        val message = assistantInput.text.toString().trim()
        if (message.isEmpty()) return
        AwarenessManager.handleLocalCommand(this, message)?.let { assistantReply.text = it; return }
        val token = sessionToken
        if (token == null) {
            OfflineCapabilities.launch(this, message)?.let { assistantReply.text = "Limited mode · $it"; return }
            assistantReply.text = OfflineCapabilities.status(this)
            Thread {
                val reply = DirectSerpApiSearch.search(this, message)
                runOnUiThread { assistantReply.text = reply ?: "Limited mode could not complete that request." }
            }.start()
            return
        }
        val baseUrl = host.text.toString().trim().trimEnd('/')
        assistantReply.text = "Thinking..."
        Thread {
            try {
                val payload = JSONObject().put("message", message)
                val request = Request.Builder().url("$baseUrl/assistant")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    val obj = JSONObject(body)
                    if (!response.isSuccessful) error(obj.optString("detail", "Assistant request failed"))
                    val reply = obj.optString("reply", "I don't have a reply yet.")
                    val action = obj.optJSONObject("action")
                    runOnUiThread {
                        assistantReply.text = reply
                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
                        if (action?.optString("type") == "open_url") {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.getString("url"))))
                        }
                        if (action?.optString("type") == "share_location") {
                            shareCurrentLocation(baseUrl, token, action.optString("message", "I'm here"))
                        }
                        if (action?.optString("type") == "send_message") {
                            sendMessageToHome(baseUrl, token, action.optString("message"))
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    OfflineCapabilities.launch(this, message)?.let { assistantReply.text = "Limited mode · $it"; return@runOnUiThread }
                    assistantReply.text = OfflineCapabilities.status(this)
                    Thread {
                        val reply = DirectSerpApiSearch.search(this, message)
                        runOnUiThread { assistantReply.text = reply ?: "Limited mode could not complete that request." }
                    }.start()
                }
            }
        }.start()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
        }
        try {
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (_: Exception) {
            assistantReply.text = "Voice recognition is not available on this phone."
        }
    }

    private fun shareCurrentLocation(baseUrl: String, token: String, prefix: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST)
            assistantReply.text = "Grant location permission, then ask me to share your location again."
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (location == null) {
            assistantReply.text = "I couldn't get a recent location. Turn on Location and try again."
            return
        }
        val mapsUrl = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
        Thread {
            try {
                val payload = JSONObject().put("body", "$prefix: $mapsUrl")
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Message failed"))
                }
                runOnUiThread { assistantReply.text = "Your location was sent to the home client." }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Location message failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun sendMessageToHome(baseUrl: String, token: String, message: String) {
        if (message.isBlank()) return
        Thread {
            try {
                val payload = JSONObject().put("body", message)
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(jsonType)).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (!response.isSuccessful) error(JSONObject(body).optString("detail", "Message failed"))
                }
                runOnUiThread {
                    appendInboxMessage("You", message)
                    if (::inboxInput.isInitialized && inboxInput.text.toString().trim() == message) inboxInput.text.clear()
                    val confirmation = "Message sent to the Windows home client."
                    assistantReply.text = confirmation
                    tts?.speak(confirmation, TextToSpeech.QUEUE_FLUSH, null, "jarvis-message-sent")
                }
            } catch (e: Exception) {
                runOnUiThread { assistantReply.text = "Message failed: ${e.message ?: "connection error"}" }
            }
        }.start()
    }

    private fun sendInboxMessage() {
        val message = inboxInput.text.toString().trim()
        if (message.isEmpty()) return
        val token = sessionToken
        if (token == null) {
            assistantReply.text = "Pair or reconnect to the Windows host first."
            return
        }
        sendMessageToHome(host.text.toString().trim().trimEnd('/'), token, message)
    }

    private fun appendInboxMessage(sender: String, body: String) {
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val history = runCatching {
            val saved = org.json.JSONArray(prefs.getString("message_history", "[]"))
            MutableList(saved.length()) { index -> saved.getString(index) }
        }.getOrDefault(mutableListOf())
        history.add("$sender: $body")
        val recent = history.takeLast(50)
        prefs.edit().putString("message_history", org.json.JSONArray(recent).toString()).apply()
        renderInbox(recent)
    }

    private fun renderInbox(messages: List<String>? = null) {
        if (!::inboxMessages.isInitialized) return
        val history = messages ?: runCatching {
            val saved = org.json.JSONArray(getSharedPreferences("jarvis", MODE_PRIVATE).getString("message_history", "[]"))
            List(saved.length()) { index -> saved.getString(index) }
        }.getOrDefault(emptyList())
        inboxMessages.text = if (history.isEmpty()) "No messages yet." else history.asReversed().joinToString("\n\n")
        Linkify.addLinks(inboxMessages, Linkify.WEB_URLS)
    }

    private fun startMessagePolling() {
        if (messagePolling) return
        messagePolling = true
        messageHandler.post(object : Runnable {
            override fun run() {
                pollMessages()
                if (messagePolling) messageHandler.postDelayed(this, 5000)
            }
        })
    }

    private fun pollMessages() {
        val token = sessionToken ?: return
        val baseUrl = host.text.toString().trim().trimEnd('/')
        Thread {
            try {
                val routesRequest = Request.Builder().url("$baseUrl/connection/routes")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(routesRequest).execute().use { response ->
                    if (response.code == 401) {
                        sessionToken = null
                        getSharedPreferences("jarvis", MODE_PRIVATE).getString("device_id", null)?.let { refreshSession(it) }
                        return@Thread
                    }
                    if (response.isSuccessful) rememberRoutes(JSONObject(response.body?.string() ?: "{}"))
                }
                val request = Request.Builder().url("$baseUrl/messages")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val items = org.json.JSONArray(response.body?.string() ?: "[]")
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val message = "${item.optString("sender_name", "JARVIS")}: ${item.getString("body")}"
                        runOnUiThread {
                            appendInboxMessage(item.optString("sender_name", "Home JARVIS"), item.getString("body"))
                            assistantReply.text = message
                            tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "jarvis-message-$index")
                        }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun startAutomaticUpdatePolling() {
        if (updatePolling) return
        updatePolling = true
        updateHandler.postDelayed(object : Runnable {
            override fun run() {
                checkAutomaticUpdate()
                if (updatePolling) updateHandler.postDelayed(this, 300_000)
            }
        }, 15_000)
    }

    private fun checkAutomaticUpdate() {
        if (updateCheckInProgress) return
        val token = sessionToken ?: return
        val baseUrl = host.text.toString().trim().trimEnd('/')
        Thread {
            try {
                val request = Request.Builder().url("$baseUrl/updates/android/status")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    if (JSONObject(response.body?.string() ?: "{}").optBoolean("available", false)) {
                        runOnUiThread { installPrivateUpdate(true) }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(path: String? = null): Long? {
        val info = if (path == null) packageManager.getPackageInfo(packageName, 0)
        else packageManager.getPackageArchiveInfo(path, 0)
        info ?: return null
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun installPrivateUpdate(automatic: Boolean) {
        if (updateCheckInProgress) return
        val token = sessionToken
        if (token == null) {
            assistantReply.text = "Reconnect to the Windows host first."
            return
        }
        updateCheckInProgress = true
        val baseUrl = host.text.toString().trim().trimEnd('/')
        if (!automatic) assistantReply.text = "Checking the private update staged on Windows..."
        Thread {
            try {
                val request = Request.Builder().url("$baseUrl/updates/android")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        error(JSONObject(body).optString("detail", "No update is ready"))
                    }
                    val directory = File(cacheDir, "updates").apply { mkdirs() }
                    val apk = File(directory, "JARVIS-update.apk")
                    response.body?.byteStream()?.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                    val candidateVersion = packageVersionCode(apk.absolutePath) ?: error("The downloaded APK is invalid")
                    val installedVersion = packageVersionCode() ?: 0L
                    if (candidateVersion <= installedVersion) {
                        apk.delete()
                        if (!automatic) runOnUiThread { assistantReply.text = "Android JARVIS is already up to date." }
                        return@use
                    }
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
                    runOnUiThread {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        assistantReply.text = "Android is ready to confirm the private JARVIS update."
                    }
                }
            } catch (e: Exception) {
                if (!automatic) runOnUiThread { assistantReply.text = "Update unavailable: ${e.message ?: "connection error"}" }
            } finally {
                updateCheckInProgress = false
            }
        }.start()
    }

    @Deprecated("Deprecated in Android, retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                assistantInput.setText(text)
                askJarvis()
            }
        }
        if (requestCode == ASSISTANT_ROLE_REQUEST) {
            if (AlwaysListeningService.isDefaultAssistant(this)) enableAlwaysListening(manual = false)
            refreshVoiceActivationStatus()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onDestroy() {
        messagePolling = false
        messageHandler.removeCallbacksAndMessages(null)
        updatePolling = false
        updateHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_ALIAS = "jarvis-device-key"
        private const val PERMISSION_REQUEST = 1001
        private const val VOICE_REQUEST = 1002
        private const val ASSISTANT_ROLE_REQUEST = 1003
    }
}
