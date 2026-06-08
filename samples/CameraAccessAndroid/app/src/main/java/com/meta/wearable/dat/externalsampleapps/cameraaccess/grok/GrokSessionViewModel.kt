package com.meta.wearable.dat.externalsampleapps.cameraaccess.grok

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawEventClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GrokUiState(
    val isGrokActive: Boolean = false,
    val connectionState: GrokConnectionState = GrokConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val toolCallStatus: ToolCallStatus = ToolCallStatus.Idle,
    val openClawConnectionState: OpenClawConnectionState = OpenClawConnectionState.NotConfigured,
    val hudConnectionState: DisplayHudConnectionState = DisplayHudConnectionState.DISCONNECTED,
    val lastVisionSummary: String = "",
    val wakeWordState: WakeWordState = WakeWordState.Disabled,
    val isWakeWordListening: Boolean = false,
)

class GrokSessionViewModel : ViewModel() {
    companion object {
        private const val TAG = "GrokSessionVM"
    }

    private val _uiState = MutableStateFlow(GrokUiState())
    val uiState: StateFlow<GrokUiState> = _uiState.asStateFlow()

    private val grokService = GrokLiveService()
    private val displayHudManager = DisplayHudManager(viewModelScope)
    private var wakeWordManager: WakeWordManager? = null
    private val openClawBridge = OpenClawBridge()
    private var toolCallRouter: ToolCallRouter? = null
    private val audioManager = AudioManager()
    private val eventClient = OpenClawEventClient()
    private var lastVideoFrameTime: Long = 0
    private var stateObservationJob: Job? = null
    private var wakeWordObservationJob: Job? = null
    private var lastHudToolStatus: ToolCallStatus = ToolCallStatus.Idle

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    fun setDisplayDeviceSession(session: DeviceSession?) {
        displayHudManager.setSharedSession(session)
    }

    fun startWakeWordListening(context: Context) {
        if (_uiState.value.isGrokActive) return

        val manager = ensureWakeWordManager(context.applicationContext)
        manager.startListening()
        applyWakeWordState(manager.state.value)

        when (val state = manager.state.value) {
            WakeWordState.Listening -> {
                _uiState.value = _uiState.value.copy(errorMessage = null)
                displayHudManager.showStatus("Wake word", "Say ${SettingsManager.wakeWordBuiltInKeyword}")
            }
            WakeWordState.NotConfigured -> {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Picovoice AccessKey not configured. Add it in Settings or Secrets.kt",
                )
            }
            is WakeWordState.Error -> {
                _uiState.value = _uiState.value.copy(errorMessage = state.message)
            }
            else -> Unit
        }
    }

    fun stopWakeWordListening() {
        wakeWordManager?.stopListening()
        val state = wakeWordManager?.state?.value
            ?: if (SettingsManager.wakeWordEnabled) WakeWordState.Idle else WakeWordState.Disabled
        applyWakeWordState(state)
    }

    fun startSession() {
        if (_uiState.value.isGrokActive) return

        if (!GrokConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Grok auth not configured. Open Settings and add either a Grok API key or auth broker URL."
            )
            resumeWakeWordIfNeeded()
            return
        }

        stopWakeWordListening()
        _uiState.value = _uiState.value.copy(isGrokActive = true)
        displayHudManager.showStatus("Grok", "Connecting voice session")

        // Wire audio callbacks
        audioManager.onAudioCaptured = lambda@{ data ->
            // Phone mode: mute mic while model speaks to prevent echo
            if (streamingMode == StreamingMode.PHONE && grokService.isModelSpeaking.value) return@lambda
            grokService.sendAudio(data)
        }

        grokService.onAudioReceived = { data ->
            audioManager.playAudio(data)
        }

        grokService.onInterrupted = {
            audioManager.stopPlayback()
        }

        grokService.onTurnComplete = {
            if (_uiState.value.aiTranscript.isNotEmpty()) {
                displayHudManager.showTranscript("", _uiState.value.aiTranscript)
            }
            _uiState.value = _uiState.value.copy(userTranscript = "")
        }

        grokService.onInputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
            displayHudManager.showTranscript(_uiState.value.userTranscript, "")
        }

        grokService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
            displayHudManager.showTranscript(_uiState.value.userTranscript, _uiState.value.aiTranscript)
        }

        grokService.onVisionSummary = { summary ->
            _uiState.value = _uiState.value.copy(lastVisionSummary = summary)
            displayHudManager.showVisionSummary(summary)
        }

        grokService.onDisconnected = { reason ->
            if (_uiState.value.isGrokActive) {
                stopSession()
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Connection lost: ${reason ?: "Unknown error"}"
                )
                displayHudManager.showError(_uiState.value.errorMessage ?: "Grok disconnected")
            }
        }

        // Check OpenClaw and start session
        viewModelScope.launch {
            openClawBridge.checkConnection()
            openClawBridge.resetSession()

            // Wire tool call handling
            toolCallRouter = ToolCallRouter(openClawBridge, viewModelScope)

            grokService.onToolCall = { toolCall ->
                for (call in toolCall.functionCalls) {
                    if (call.name == "display_hud") {
                        val title = call.args["title"]?.toString() ?: "VisionClaw"
                        val body = call.args["body"]?.toString() ?: ""
                        val kind = call.args["kind"]?.toString()
                        displayHudManager.showModelCard(title, body, kind)
                        grokService.sendToolResponse(
                            ToolCallRouter.buildToolResponse(call.id, ToolResult.Success("HUD updated"))
                        )
                        continue
                    }
                    displayHudManager.showToolStatus(ToolCallStatus.Executing(call.name))
                    toolCallRouter?.handleToolCall(call) { response ->
                        grokService.sendToolResponse(response)
                    }
                }
            }

            grokService.onToolCallCancellation = { cancellation ->
                toolCallRouter?.cancelToolCalls(cancellation.ids)
            }

            // Observe service state
            stateObservationJob = viewModelScope.launch {
                while (isActive) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(
                        connectionState = grokService.connectionState.value,
                        isModelSpeaking = grokService.isModelSpeaking.value,
                        toolCallStatus = openClawBridge.lastToolCallStatus.value,
                        openClawConnectionState = openClawBridge.connectionState.value,
                        hudConnectionState = displayHudManager.connectionState.value,
                    )
                    val status = _uiState.value.toolCallStatus
                    if (status != lastHudToolStatus) {
                        lastHudToolStatus = status
                        displayHudManager.showToolStatus(status)
                    }
                }
            }

            // Connect to Grok
            grokService.connect { setupOk ->
                if (!setupOk) {
                    val msg = when (val state = grokService.connectionState.value) {
                        is GrokConnectionState.Error -> state.message
                        else -> "Failed to connect to Grok"
                    }
                    _uiState.value = _uiState.value.copy(errorMessage = msg)
                    grokService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGrokActive = false,
                        connectionState = GrokConnectionState.Disconnected
                    )
                    displayHudManager.showError(msg)
                    resumeWakeWordIfNeeded()
                    return@connect
                }

                // Start mic capture
                try {
                    audioManager.startCapture()
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mic capture failed: ${e.message}"
                    )
                    grokService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGrokActive = false,
                        connectionState = GrokConnectionState.Disconnected
                    )
                    displayHudManager.showError(_uiState.value.errorMessage ?: "Mic capture failed")
                    resumeWakeWordIfNeeded()
                    return@connect
                }

                displayHudManager.showStatus("Grok ready", "Listening")

                // Connect to OpenClaw event stream for proactive notifications
                if (SettingsManager.proactiveNotificationsEnabled) {
                    eventClient.onNotification = { text ->
                        val state = _uiState.value
                        if (state.isGrokActive && state.connectionState == GrokConnectionState.Ready) {
                            grokService.sendTextMessage(text)
                        }
                    }
                    eventClient.connect()
                }
            }
        }
    }

    fun stopSession(resumeWakeWord: Boolean = true) {
        eventClient.disconnect()
        toolCallRouter?.cancelAll()
        toolCallRouter = null
        audioManager.stopCapture()
        grokService.disconnect()
        displayHudManager.showStatus("Grok stopped", "Voice session ended")
        displayHudManager.stop()
        stateObservationJob?.cancel()
        stateObservationJob = null
        lastHudToolStatus = ToolCallStatus.Idle
        _uiState.value = GrokUiState()
        if (resumeWakeWord) {
            resumeWakeWordIfNeeded()
        }
    }

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        if (!SettingsManager.videoStreamingEnabled) return
        if (!_uiState.value.isGrokActive) return
        if (_uiState.value.connectionState != GrokConnectionState.Ready) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < GrokConfig.VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        grokService.sendVideoFrame(bitmap)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopWakeWordListening()
        stopSession(resumeWakeWord = false)
    }

    private fun ensureWakeWordManager(context: Context): WakeWordManager {
        wakeWordManager?.let { return it }

        val manager = WakeWordManager(context, viewModelScope) {
            handleWakeWordDetected()
        }
        wakeWordManager = manager
        wakeWordObservationJob?.cancel()
        wakeWordObservationJob = viewModelScope.launch {
            manager.state.collect { state ->
                applyWakeWordState(state)
            }
        }
        return manager
    }

    private fun handleWakeWordDetected() {
        viewModelScope.launch {
            stopWakeWordListening()
            displayHudManager.showStatus("Wake word", "Starting Grok")
            startSession()
        }
    }

    private fun resumeWakeWordIfNeeded() {
        if (!SettingsManager.wakeWordEnabled || !SettingsManager.wakeWordAutoResume) return
        val manager = wakeWordManager ?: return
        manager.startListening()
        val state = manager.state.value
        applyWakeWordState(state)
        if (state == WakeWordState.Listening) {
            displayHudManager.showStatus("Wake word", "Say ${SettingsManager.wakeWordBuiltInKeyword}")
        }
    }

    private fun applyWakeWordState(state: WakeWordState) {
        _uiState.value = _uiState.value.copy(
            wakeWordState = state,
            isWakeWordListening = state == WakeWordState.Listening,
        )
    }
}
