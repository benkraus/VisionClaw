package com.meta.wearable.dat.externalsampleapps.cameraaccess.grok

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class WakeWordState(val displayText: String) {
    data object Disabled : WakeWordState("Wake Off")
    data object NotConfigured : WakeWordState("Wake Setup")
    data object Idle : WakeWordState("Wake")
    data object Listening : WakeWordState("Listening")
    data object Detected : WakeWordState("Wake Detected")
    data class Error(val message: String) : WakeWordState("Wake Error")
}

class WakeWordManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDetected: () -> Unit,
) {
    companion object {
        private const val TAG = "WakeWordManager"
    }

    private var porcupineManager: PorcupineManager? = null
    private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Disabled)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    fun startListening() {
        if (!SettingsManager.wakeWordEnabled) {
            _state.value = WakeWordState.Disabled
            return
        }

        if (!SettingsManager.isPorcupineConfigured) {
            _state.value = WakeWordState.NotConfigured
            return
        }

        stopListening(updateIdleState = false)

        try {
            var builder = PorcupineManager.Builder()
                .setAccessKey(SettingsManager.porcupineAccessKey)
                .setSensitivity(SettingsManager.wakeWordSensitivity)
                .setErrorCallback { error ->
                    scope.launch {
                        _state.value = WakeWordState.Error(error.message ?: error.toString())
                    }
                }

            val customKeywordPath = SettingsManager.wakeWordKeywordPath.trim()
            builder = if (customKeywordPath.isNotEmpty()) {
                builder.setKeywordPath(customKeywordPath)
            } else {
                builder.setKeyword(builtInKeyword(SettingsManager.wakeWordBuiltInKeyword))
            }

            porcupineManager = builder.build(context.applicationContext) { _ ->
                scope.launch {
                    _state.value = WakeWordState.Detected
                    onDetected()
                }
            }
            porcupineManager?.start()
            _state.value = WakeWordState.Listening
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start wake word listener", error)
            _state.value = WakeWordState.Error(error.message ?: error.toString())
            tearDownManager()
        }
    }

    fun stopListening(updateIdleState: Boolean = true) {
        tearDownManager()
        if (updateIdleState) {
            _state.value = if (SettingsManager.wakeWordEnabled) WakeWordState.Idle else WakeWordState.Disabled
        }
    }

    private fun tearDownManager() {
        porcupineManager?.let { manager ->
            try {
                manager.stop()
            } catch (error: Exception) {
                Log.d(TAG, "Wake word listener was not active", error)
            }
            manager.delete()
        }
        porcupineManager = null
    }

    private fun builtInKeyword(name: String): Porcupine.BuiltInKeyword =
        when (name.trim().lowercase().replace(" ", "_").replace("-", "_")) {
            "alexa" -> Porcupine.BuiltInKeyword.ALEXA
            "americano" -> Porcupine.BuiltInKeyword.AMERICANO
            "blueberry" -> Porcupine.BuiltInKeyword.BLUEBERRY
            "bumblebee" -> Porcupine.BuiltInKeyword.BUMBLEBEE
            "computer" -> Porcupine.BuiltInKeyword.COMPUTER
            "grapefruit" -> Porcupine.BuiltInKeyword.GRAPEFRUIT
            "grasshopper" -> Porcupine.BuiltInKeyword.GRASSHOPPER
            "hey_google" -> Porcupine.BuiltInKeyword.HEY_GOOGLE
            "hey_siri" -> Porcupine.BuiltInKeyword.HEY_SIRI
            "ok_google" -> Porcupine.BuiltInKeyword.OK_GOOGLE
            "picovoice" -> Porcupine.BuiltInKeyword.PICOVOICE
            "porcupine" -> Porcupine.BuiltInKeyword.PORCUPINE
            "terminator" -> Porcupine.BuiltInKeyword.TERMINATOR
            else -> Porcupine.BuiltInKeyword.JARVIS
        }
}
