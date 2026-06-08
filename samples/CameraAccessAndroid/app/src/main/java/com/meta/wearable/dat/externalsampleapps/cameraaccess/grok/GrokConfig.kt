package com.meta.wearable.dat.externalsampleapps.cameraaccess.grok

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object GrokConfig {
    const val WEBSOCKET_BASE_URL = "wss://api.x.ai/v1/realtime"
    const val MODEL = "grok-voice-think-fast-1.0"
    const val VISION_MODEL = "grok-4.3"
    const val CHAT_COMPLETIONS_URL = "https://api.x.ai/v1/chat/completions"

    const val INPUT_AUDIO_SAMPLE_RATE = 16000
    const val OUTPUT_AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16

    const val VIDEO_FRAME_INTERVAL_MS = 3000L
    const val VIDEO_JPEG_QUALITY = 45

    val systemInstruction: String
        get() {
            val soul = SettingsManager.grokSoulPrompt.trim()
            return if (soul.isEmpty()) {
                SettingsManager.grokSystemPrompt
            } else {
                "${SettingsManager.grokSystemPrompt}\n\nUser-provided soul/personality context:\n$soul"
            }
        }

    val voice: String
        get() = SettingsManager.grokVoice

    val authBrokerURL: String
        get() = SettingsManager.grokAuthBrokerURL

    val authBrokerToken: String
        get() {
            val token = SettingsManager.grokAuthBrokerToken.trim()
            return if (token.isNotEmpty() && token != "YOUR_GROK_AUTH_BROKER_TOKEN") {
                token
            } else {
                SettingsManager.openClawGatewayToken
            }
        }

    val apiKey: String
        get() = SettingsManager.grokAPIKey

    val openClawHost: String
        get() = SettingsManager.openClawHost

    val openClawPort: Int
        get() = SettingsManager.openClawPort

    val openClawHookToken: String
        get() = SettingsManager.openClawHookToken

    val openClawGatewayToken: String
        get() = SettingsManager.openClawGatewayToken

    fun websocketURL(): String? {
        if (!isConfigured) return null
        return "$WEBSOCKET_BASE_URL?model=$MODEL"
    }

    val isConfigured: Boolean
        get() = hasAuthBroker || hasDirectAPIKey

    val hasDirectAPIKey: Boolean
        get() {
            val trimmed = apiKey.trim()
            return trimmed != "YOUR_GROK_API_KEY" && trimmed.isNotEmpty()
        }

    val hasAuthBroker: Boolean
        get() {
            val trimmed = authBrokerURL.trim()
            return trimmed.isNotEmpty() && trimmed != "https://YOUR_HOST/api/grok/token"
        }

    val isOpenClawConfigured: Boolean
        get() = openClawGatewayToken != "YOUR_OPENCLAW_GATEWAY_TOKEN"
                && openClawGatewayToken.isNotEmpty()
                && openClawHost != "http://YOUR_MAC_HOSTNAME.local"
}
