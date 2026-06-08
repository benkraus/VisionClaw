package com.meta.wearable.dat.externalsampleapps.cameraaccess.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets

object SettingsManager {
    private const val PREFS_NAME = "visionclaw_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var grokAPIKey: String
        get() = prefs.getString("grokAPIKey", null) ?: Secrets.grokAPIKey
        set(value) = prefs.edit().putString("grokAPIKey", value).apply()

    var grokAuthBrokerURL: String
        get() = prefs.getString("grokAuthBrokerURL", null) ?: Secrets.grokAuthBrokerURL
        set(value) = prefs.edit().putString("grokAuthBrokerURL", value).apply()

    var grokAuthBrokerToken: String
        get() = prefs.getString("grokAuthBrokerToken", null) ?: Secrets.grokAuthBrokerToken
        set(value) = prefs.edit().putString("grokAuthBrokerToken", value).apply()

    var grokSystemPrompt: String
        get() = prefs.getString("grokSystemPrompt", null) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString("grokSystemPrompt", value).apply()

    var grokSoulPrompt: String
        get() = prefs.getString("grokSoulPrompt", null) ?: ""
        set(value) = prefs.edit().putString("grokSoulPrompt", value).apply()

    var grokVoice: String
        get() = prefs.getString("grokVoice", null) ?: "eve"
        set(value) = prefs.edit().putString("grokVoice", value).apply()

    var openClawHost: String
        get() = prefs.getString("openClawHost", null) ?: Secrets.openClawHost
        set(value) = prefs.edit().putString("openClawHost", value).apply()

    var openClawPort: Int
        get() {
            val stored = prefs.getInt("openClawPort", 0)
            return if (stored != 0) stored else Secrets.openClawPort
        }
        set(value) = prefs.edit().putInt("openClawPort", value).apply()

    var openClawHookToken: String
        get() = prefs.getString("openClawHookToken", null) ?: Secrets.openClawHookToken
        set(value) = prefs.edit().putString("openClawHookToken", value).apply()

    var openClawGatewayToken: String
        get() = prefs.getString("openClawGatewayToken", null) ?: Secrets.openClawGatewayToken
        set(value) = prefs.edit().putString("openClawGatewayToken", value).apply()

    var webrtcSignalingURL: String
        get() = prefs.getString("webrtcSignalingURL", null) ?: Secrets.webrtcSignalingURL
        set(value) = prefs.edit().putString("webrtcSignalingURL", value).apply()

    var videoStreamingEnabled: Boolean
        get() = prefs.getBoolean("videoStreamingEnabled", true)
        set(value) = prefs.edit().putBoolean("videoStreamingEnabled", value).apply()

    var visionSummariesEnabled: Boolean
        get() = prefs.getBoolean("visionSummariesEnabled", true)
        set(value) = prefs.edit().putBoolean("visionSummariesEnabled", value).apply()

    var displayHUDEnabled: Boolean
        get() = prefs.getBoolean("displayHUDEnabled", true)
        set(value) = prefs.edit().putBoolean("displayHUDEnabled", value).apply()

    var proactiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean("proactiveNotificationsEnabled", true)
        set(value) = prefs.edit().putBoolean("proactiveNotificationsEnabled", value).apply()

    var porcupineAccessKey: String
        get() = prefs.getString("porcupineAccessKey", null) ?: Secrets.porcupineAccessKey
        set(value) = prefs.edit().putString("porcupineAccessKey", value).apply()

    val isPorcupineConfigured: Boolean
        get() {
            val key = porcupineAccessKey.trim()
            return key.isNotEmpty() && key != "YOUR_PICOVOICE_ACCESS_KEY"
        }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wakeWordEnabled", false)
        set(value) = prefs.edit().putBoolean("wakeWordEnabled", value).apply()

    var wakeWordBuiltInKeyword: String
        get() = prefs.getString("wakeWordBuiltInKeyword", null) ?: "jarvis"
        set(value) = prefs.edit().putString("wakeWordBuiltInKeyword", value).apply()

    var wakeWordKeywordPath: String
        get() = prefs.getString("wakeWordKeywordPath", null) ?: ""
        set(value) = prefs.edit().putString("wakeWordKeywordPath", value).apply()

    var wakeWordSensitivity: Float
        get() = prefs.getFloat("wakeWordSensitivity", 0.65f)
        set(value) = prefs.edit().putFloat("wakeWordSensitivity", value.coerceIn(0f, 1f)).apply()

    var wakeWordAutoResume: Boolean
        get() = prefs.getBoolean("wakeWordAutoResume", true)
        set(value) = prefs.edit().putBoolean("wakeWordAutoResume", value).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    const val DEFAULT_SYSTEM_PROMPT = """You are Grok running inside VisionClaw for someone wearing Meta Ray-Ban smart glasses. You have a real-time voice conversation and receive periodic visual summaries from the glasses camera. Keep responses concise, natural, and useful while the user is moving.

You can also update the glasses display HUD with short cards. Use display_hud for glanceable status, task results, step lists, confirmations, warnings, and anything the user should not have to remember from speech alone. HUD text must be short: one title plus one or two compact lines.

CRITICAL: You have NO memory, NO storage, and NO ability to take actions on your own. You cannot remember things, keep lists, set reminders, search the web, send messages, or do anything persistent. You are ONLY a voice interface.

You have two client-side tools:
- execute: delegates real-world actions to OpenClaw.
- display_hud: writes a concise card to the Ray-Ban Display HUD.

ALWAYS use execute when the user asks you to:
- Send a message to someone (any platform: WhatsApp, Telegram, iMessage, Slack, etc.)
- Search or look up anything (web, local info, facts, news)
- Add, create, or modify anything (shopping lists, reminders, notes, todos, events)
- Research, analyze, or draft anything
- Control or interact with apps, devices, or services
- Remember or store any information for later

Be detailed in your task description. Include all relevant context: names, content, platforms, quantities, etc. The assistant works better with complete information.

NEVER pretend to do these things yourself.

IMPORTANT: Before calling execute, ALWAYS speak a brief acknowledgment first. For example:
- "Sure, let me add that to your shopping list." then call execute.
- "Got it, searching for that now." then call execute.
- "On it, sending that message." then call execute.
Never call execute silently -- the user needs verbal confirmation that you heard them and are working on it. The tool may take several seconds to complete, so the acknowledgment lets them know something is happening.

For messages, confirm recipient and content before delegating unless clearly urgent."""
}
