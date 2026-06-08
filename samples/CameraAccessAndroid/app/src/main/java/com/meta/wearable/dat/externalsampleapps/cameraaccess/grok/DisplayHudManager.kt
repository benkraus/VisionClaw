package com.meta.wearable.dat.externalsampleapps.cameraaccess.grok

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DisplayHudConnectionState(val displayText: String) {
    DISABLED("HUD Off"),
    DISCONNECTED("HUD"),
    CONNECTING("HUD..."),
    READY("HUD"),
    ERROR("HUD Error"),
}

class DisplayHudManager(private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "DisplayHudManager"
    }

    private val lock = Any()
    private val _connectionState = MutableStateFlow(DisplayHudConnectionState.DISCONNECTED)
    val connectionState: StateFlow<DisplayHudConnectionState> = _connectionState.asStateFlow()

    private var session: DeviceSession? = null
    private var sharedSession: DeviceSession? = null
    private var ownsSession: Boolean = false
    private var display: Display? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var displayStateJob: Job? = null
    private var pendingContent: (ContentScope.() -> Unit)? = null

    fun showStatus(title: String, body: String) {
        sendContent { card("VisionClaw", title, body) }
    }

    fun showTranscript(user: String, assistant: String) {
        if (user.isBlank() && assistant.isBlank()) return
        sendContent {
            flexBox(direction = Direction.COLUMN, gap = 10, padding = 24, background = FlexBoxBackground.CARD) {
                if (user.isNotBlank()) {
                    text("You", style = TextStyle.META, color = TextColor.SECONDARY)
                    text(clamp(user, 96), style = TextStyle.BODY)
                }
                if (assistant.isNotBlank()) {
                    text("Grok", style = TextStyle.META, color = TextColor.SECONDARY)
                    text(clamp(assistant, 140), style = TextStyle.BODY)
                }
            }
        }
    }

    fun showToolStatus(status: ToolCallStatus) {
        if (status is ToolCallStatus.Idle) return
        sendContent { card("OpenClaw", toolTitle(status), clamp(status.displayText, 120)) }
    }

    fun showVisionSummary(summary: String) {
        sendContent { card("Camera", "Visual context", clamp(summary, 150)) }
    }

    fun showModelCard(title: String, body: String, kind: String?) {
        sendContent { card((kind ?: "HUD").replaceFirstChar { it.uppercase() }, clamp(title, 40), clamp(body, 160)) }
    }

    fun showError(message: String) {
        sendContent { card("VisionClaw", "Needs attention", clamp(message, 150)) }
    }

    fun setSharedSession(deviceSession: DeviceSession?) {
        synchronized(lock) { sharedSession = deviceSession }
    }

    fun stop() {
        synchronized(lock) { pendingContent = null }
        synchronized(lock) { session }?.removeDisplay()
        cleanupDisplay()
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        synchronized(lock) {
            if (ownsSession) {
                session?.stop()
            }
            session = null
            ownsSession = false
        }
        _connectionState.value =
            if (SettingsManager.displayHUDEnabled) DisplayHudConnectionState.DISCONNECTED
            else DisplayHudConnectionState.DISABLED
    }

    private fun sendContent(content: ContentScope.() -> Unit) {
        if (!SettingsManager.displayHUDEnabled) {
            _connectionState.value = DisplayHudConnectionState.DISABLED
            return
        }

        val currentDisplay = synchronized(lock) { display }
        if (currentDisplay != null && _connectionState.value == DisplayHudConnectionState.READY) {
            doSend(currentDisplay, content)
            return
        }

        synchronized(lock) { pendingContent = content }
        prepareDisplay()
    }

    private fun prepareDisplay() {
        val existingSession = synchronized(lock) { session }
        if (existingSession != null) {
            if (synchronized(lock) { display } == null) {
                if (existingSession.state.value == DeviceSessionState.STARTED) {
                    attachDisplay(existingSession)
                } else {
                    _connectionState.value = DisplayHudConnectionState.CONNECTING
                }
            }
            return
        }

        val currentSharedSession = synchronized(lock) { sharedSession }
        if (currentSharedSession != null) {
            synchronized(lock) {
                session = currentSharedSession
                ownsSession = false
            }
            observeSession(currentSharedSession, shouldStart = false)
            if (currentSharedSession.state.value == DeviceSessionState.STARTED) {
                attachDisplay(currentSharedSession)
            } else {
                _connectionState.value = DisplayHudConnectionState.CONNECTING
            }
            return
        }

        val deviceId = selectDisplayDevice()
        if (deviceId == null) {
            _connectionState.value = DisplayHudConnectionState.ERROR
            Log.w(TAG, "No display-capable wearable is available")
            return
        }

        _connectionState.value = DisplayHudConnectionState.CONNECTING
        Wearables.createSession(SpecificDeviceSelector(deviceId)).fold(
            onSuccess = { newSession ->
                synchronized(lock) {
                    session = newSession
                    ownsSession = true
                }
                observeSession(newSession, shouldStart = true)
            },
            onFailure = { error, _ ->
                Log.e(TAG, "Failed to create display session: ${error.description}")
                _connectionState.value = DisplayHudConnectionState.ERROR
            },
        )
    }

    private fun observeSession(currentSession: DeviceSession, shouldStart: Boolean) {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        sessionStateJob = scope.launch {
            currentSession.state.collect { state ->
                when (state) {
                    DeviceSessionState.STARTED -> attachDisplay(currentSession)
                    DeviceSessionState.STOPPED -> {
                        cleanupDisplay()
                        synchronized(lock) {
                            if (session == currentSession) {
                                session = null
                                ownsSession = false
                            }
                        }
                        _connectionState.value = DisplayHudConnectionState.DISCONNECTED
                    }
                    else -> Unit
                }
            }
        }
        sessionErrorJob = scope.launch {
            currentSession.errors.collect { error -> handleSessionError(error) }
        }
        if (shouldStart) {
            currentSession.start()
        }
    }

    private fun attachDisplay(currentSession: DeviceSession) {
        if (synchronized(lock) { display } != null) return

        currentSession.addDisplay().fold(
            onSuccess = { newDisplay ->
                synchronized(lock) { display = newDisplay }
                displayStateJob = scope.launch {
                    newDisplay.state.collect { state ->
                        when (state) {
                            DisplayState.STARTED -> {
                                _connectionState.value = DisplayHudConnectionState.READY
                                val pending = synchronized(lock) {
                                    val content = pendingContent
                                    pendingContent = null
                                    content
                                }
                                if (pending != null) {
                                    doSend(newDisplay, pending)
                                }
                            }
                            DisplayState.STARTING -> _connectionState.value = DisplayHudConnectionState.CONNECTING
                            DisplayState.STOPPED -> {
                                cleanupDisplay()
                                _connectionState.value = DisplayHudConnectionState.DISCONNECTED
                            }
                            DisplayState.STOPPING -> _connectionState.value = DisplayHudConnectionState.CONNECTING
                        }
                    }
                }
            },
            onFailure = { error, _ ->
                Log.e(TAG, "Failed to attach display: ${error.description}")
                _connectionState.value = DisplayHudConnectionState.ERROR
            },
        )
    }

    private fun doSend(currentDisplay: Display, content: ContentScope.() -> Unit) {
        scope.launch {
            currentDisplay.sendContent(content).fold(
                onSuccess = {},
                onFailure = { error, _ ->
                    Log.e(TAG, "Display send failed: ${error.description}")
                    _connectionState.value = DisplayHudConnectionState.ERROR
                },
            )
        }
    }

    private fun cleanupDisplay() {
        displayStateJob?.cancel()
        displayStateJob = null
        synchronized(lock) { display = null }
    }

    private fun handleSessionError(error: DeviceSessionError) {
        Log.e(TAG, "Display session error: ${error.description}")
        _connectionState.value = DisplayHudConnectionState.ERROR
    }

    private fun selectDisplayDevice(): DeviceIdentifier? {
        val devices = Wearables.devices.value
        return devices.firstOrNull { deviceId ->
            Wearables.devicesMetadata[deviceId]?.value?.isDisplayCapable() == true
        } ?: devices.firstOrNull()
    }

    private fun ContentScope.card(label: String, title: String, body: String) {
        flexBox(direction = Direction.COLUMN, gap = 10, padding = 24, background = FlexBoxBackground.CARD) {
            text(label, style = TextStyle.META, color = TextColor.SECONDARY)
            text(title, style = TextStyle.HEADING)
            text(body, style = TextStyle.BODY)
        }
    }

    private fun toolTitle(status: ToolCallStatus): String =
        when (status) {
            is ToolCallStatus.Executing -> "Working"
            is ToolCallStatus.Completed -> "Done"
            is ToolCallStatus.Failed -> "Failed"
            is ToolCallStatus.Cancelled -> "Cancelled"
            is ToolCallStatus.Idle -> "OpenClaw"
        }

    private fun clamp(text: String, limit: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= limit) trimmed else trimmed.take(limit) + "..."
    }
}
