package com.meta.wearable.dat.externalsampleapps.cameraaccess.grok

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GrokToolCall
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GrokToolCallCancellation
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolDeclarations
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

sealed class GrokConnectionState {
    data object Disconnected : GrokConnectionState()
    data object Connecting : GrokConnectionState()
    data object SettingUp : GrokConnectionState()
    data object Ready : GrokConnectionState()
    data class Error(val message: String) : GrokConnectionState()
}

class GrokLiveService {
    companion object {
        private const val TAG = "GrokLiveService"
    }

    private val _connectionState = MutableStateFlow<GrokConnectionState>(GrokConnectionState.Disconnected)
    val connectionState: StateFlow<GrokConnectionState> = _connectionState.asStateFlow()

    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onDisconnected: ((String?) -> Unit)? = null
    var onInputTranscription: ((String) -> Unit)? = null
    var onOutputTranscription: ((String) -> Unit)? = null
    var onToolCall: ((GrokToolCall) -> Unit)? = null
    var onToolCallCancellation: ((GrokToolCallCancellation) -> Unit)? = null
    var onVisionSummary: ((String) -> Unit)? = null

    private var lastUserSpeechEnd: Long = 0
    private var responseLatencyLogged = false
    @Volatile private var visionRequestInFlight = false
    private val pendingToolCallIds = mutableSetOf<String>()
    @Volatile private var isAwaitingToolContinuation = false
    @Volatile private var hasToolResponseDone = false
    @Volatile private var cachedBrokerAccessToken: String? = null
    @Volatile private var cachedBrokerExpiresAtMs: Long = 0

    private var webSocket: WebSocket? = null
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private var connectCallback: ((Boolean) -> Unit)? = null
    private var timeoutTimer: Timer? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val authClient = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect(callback: (Boolean) -> Unit) {
        val url = GrokConfig.websocketURL()
        if (url == null) {
            _connectionState.value = GrokConnectionState.Error("Grok auth not configured")
            callback(false)
            return
        }

        _connectionState.value = GrokConnectionState.Connecting
        resetToolContinuationState()
        connectCallback = callback

        sendExecutor.execute {
            val bearerToken = try {
                authorizationToken()
            } catch (e: Exception) {
                val msg = e.message ?: "Grok authorization failed"
                Log.e(TAG, msg)
                _connectionState.value = GrokConnectionState.Error(msg)
                resolveConnect(false)
                return@execute
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $bearerToken")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened")
                    _connectionState.value = GrokConnectionState.SettingUp
                    sendSetupMessage()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val msg = t.message ?: "Unknown error"
                    Log.e(TAG, "WebSocket failure: $msg")
                    _connectionState.value = GrokConnectionState.Error(msg)
                    _isModelSpeaking.value = false
                    resolveConnect(false)
                    onDisconnected?.invoke(msg)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code $reason")
                    _connectionState.value = GrokConnectionState.Disconnected
                    _isModelSpeaking.value = false
                    resolveConnect(false)
                    onDisconnected?.invoke("Connection closed (code $code: $reason)")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    _connectionState.value = GrokConnectionState.Disconnected
                    _isModelSpeaking.value = false
                }
            })

            timeoutTimer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        if (_connectionState.value == GrokConnectionState.Connecting
                            || _connectionState.value == GrokConnectionState.SettingUp) {
                            Log.e(TAG, "Connection timed out")
                            _connectionState.value = GrokConnectionState.Error("Connection timed out")
                            resolveConnect(false)
                        }
                    }
                }, 15000)
            }
        }
    }

    fun disconnect() {
        timeoutTimer?.cancel()
        timeoutTimer = null
        webSocket?.close(1000, null)
        webSocket = null
        onToolCall = null
        onToolCallCancellation = null
        resetToolContinuationState()
        _connectionState.value = GrokConnectionState.Disconnected
        _isModelSpeaking.value = false
        resolveConnect(false)
    }

    fun sendAudio(data: ByteArray) {
        if (_connectionState.value != GrokConnectionState.Ready) return
        sendExecutor.execute {
            webSocket?.send(JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", Base64.encodeToString(data, Base64.NO_WRAP))
            }.toString())
        }
    }

    fun sendVideoFrame(bitmap: Bitmap) {
        if (_connectionState.value != GrokConnectionState.Ready) return
        if (!SettingsManager.visionSummariesEnabled) return
        sendExecutor.execute {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, GrokConfig.VIDEO_JPEG_QUALITY, baos)
            summarizeFrame(baos.toByteArray())
        }
    }

    fun sendToolResponse(response: JSONObject) {
        val callId = response.optJSONObject("item")?.optString("call_id", "")
        sendExecutor.execute {
            webSocket?.send(response.toString())
        }
        if (!callId.isNullOrEmpty()) {
            synchronized(pendingToolCallIds) {
                pendingToolCallIds.remove(callId)
            }
        }
        sendResponseCreateIfToolCallsComplete()
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value != GrokConnectionState.Ready) return
        sendExecutor.execute {
            webSocket?.send(JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    }))
                })
            }.toString())
            webSocket?.send(JSONObject().put("type", "response.create").toString())
        }
    }

    private fun authorizationToken(): String {
        if (GrokConfig.hasAuthBroker) {
            return brokerAuthorizationToken()
        }
        if (GrokConfig.hasDirectAPIKey) {
            return GrokConfig.apiKey.trim()
        }
        throw IllegalStateException("Configure a Grok API key or Grok auth broker in Settings.")
    }

    private fun brokerAuthorizationToken(): String {
        val now = System.currentTimeMillis()
        val cached = cachedBrokerAccessToken
        if (!cached.isNullOrEmpty() && cachedBrokerExpiresAtMs - now > 60_000) {
            return cached
        }

        val brokerURL = GrokConfig.authBrokerURL.trim()
        if (brokerURL.isEmpty()) {
            throw IllegalStateException("Grok auth broker URL is empty.")
        }

        val requestBuilder = Request.Builder().url(brokerURL).get()
        val brokerToken = GrokConfig.authBrokerToken.trim()
        if (brokerToken.isNotEmpty() && brokerToken != "YOUR_OPENCLAW_GATEWAY_TOKEN") {
            requestBuilder.addHeader("Authorization", "Bearer $brokerToken")
        }

        authClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Grok auth broker returned HTTP ${response.code}: ${body.take(200)}"
                )
            }

            val json = JSONObject(body)
            val accessToken = json.optString("accessToken")
                .ifEmpty { json.optString("access_token") }
                .ifEmpty { json.optString("token") }
            if (accessToken.isEmpty()) {
                throw IllegalStateException("Grok auth broker did not return an access token.")
            }

            cachedBrokerAccessToken = accessToken
            cachedBrokerExpiresAtMs = parseExpiresAt(json)
            return accessToken
        }
    }

    private fun parseExpiresAt(json: JSONObject): Long {
        val expiresAt = json.opt("expiresAt") ?: json.opt("expires_at")
        if (expiresAt is Number) {
            return timestampToMillis(expiresAt.toLong())
        }
        if (expiresAt is String && expiresAt.isNotBlank()) {
            expiresAt.toLongOrNull()?.let { return timestampToMillis(it) }
            runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()?.let { return it }
        }

        val expiresIn = json.opt("expiresIn") ?: json.opt("expires_in")
        val expiresInSeconds = when (expiresIn) {
            is Number -> expiresIn.toLong()
            is String -> expiresIn.toLongOrNull()
            else -> null
        } ?: 300
        return System.currentTimeMillis() + expiresInSeconds * 1000
    }

    private fun timestampToMillis(timestamp: Long): Long {
        return if (timestamp < 4_000_000_000L) timestamp * 1000 else timestamp
    }

    private fun resolveConnect(success: Boolean) {
        val cb = connectCallback
        connectCallback = null
        timeoutTimer?.cancel()
        timeoutTimer = null
        cb?.invoke(success)
    }

    private fun sendSetupMessage() {
        val setup = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("model", GrokConfig.MODEL)
                put("instructions", GrokConfig.systemInstruction)
                put("voice", GrokConfig.voice)
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.7)
                    put("prefix_padding_ms", 250)
                    put("silence_duration_ms", 650)
                })
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            put("rate", GrokConfig.INPUT_AUDIO_SAMPLE_RATE)
                        })
                    })
                    put("output", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            put("rate", GrokConfig.OUTPUT_AUDIO_SAMPLE_RATE)
                        })
                    })
                })
                put("tools", ToolDeclarations.allDeclarationsJSON())
            })
        }
        webSocket?.send(setup.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "session.created",
                "conversation.created" -> Unit

                "session.updated" -> {
                    _connectionState.value = GrokConnectionState.Ready
                    resolveConnect(true)
                }

                "input_audio_buffer.speech_started" -> {
                    _isModelSpeaking.value = false
                    onInterrupted?.invoke()
                }

                "input_audio_buffer.speech_stopped" -> {
                    lastUserSpeechEnd = System.currentTimeMillis()
                    responseLatencyLogged = false
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotEmpty()) {
                        Log.d(TAG, "You: $transcript")
                        onInputTranscription?.invoke(transcript)
                    }
                }

                "response.output_audio.delta" -> {
                    val base64Data = json.optString("delta", "")
                    if (base64Data.isNotEmpty()) {
                        markModelSpeakingIfNeeded()
                        onAudioReceived?.invoke(Base64.decode(base64Data, Base64.DEFAULT))
                    }
                }

                "response.output_audio_transcript.delta",
                "response.text.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        Log.d(TAG, "AI: $delta")
                        onOutputTranscription?.invoke(delta)
                    }
                }

                "response.function_call_arguments.done" -> {
                    GrokToolCall.fromJSON(json)?.let { toolCall ->
                        if (!isAwaitingToolContinuation) {
                            isAwaitingToolContinuation = true
                            hasToolResponseDone = false
                        }
                        synchronized(pendingToolCallIds) {
                            pendingToolCallIds.addAll(toolCall.functionCalls.map { it.id })
                        }
                        Log.d(TAG, "Tool call received: ${toolCall.functionCalls.size} function(s)")
                        onToolCall?.invoke(toolCall)
                    }
                }

                "response.done" -> {
                    _isModelSpeaking.value = false
                    responseLatencyLogged = false
                    if (isAwaitingToolContinuation) {
                        hasToolResponseDone = true
                        sendResponseCreateIfToolCallsComplete()
                    } else {
                        onTurnComplete?.invoke()
                    }
                }

                "error" -> {
                    val message = json.optJSONObject("error")?.optString("message")
                        ?: "Unknown Grok realtime error"
                    _connectionState.value = GrokConnectionState.Error(message)
                    _isModelSpeaking.value = false
                    resolveConnect(false)
                    onDisconnected?.invoke(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun markModelSpeakingIfNeeded() {
        if (_isModelSpeaking.value) return
        _isModelSpeaking.value = true
        if (lastUserSpeechEnd > 0 && !responseLatencyLogged) {
            val latency = System.currentTimeMillis() - lastUserSpeechEnd
            Log.d(TAG, "[Latency] ${latency}ms (user speech end -> first audio)")
            responseLatencyLogged = true
        }
    }

    private fun sendResponseCreateIfToolCallsComplete() {
        val isComplete = synchronized(pendingToolCallIds) { pendingToolCallIds.isEmpty() }
        if (!isAwaitingToolContinuation || !hasToolResponseDone || !isComplete) return

        isAwaitingToolContinuation = false
        hasToolResponseDone = false
        sendExecutor.execute {
            webSocket?.send(JSONObject().put("type", "response.create").toString())
        }
    }

    private fun resetToolContinuationState() {
        synchronized(pendingToolCallIds) {
            pendingToolCallIds.clear()
        }
        isAwaitingToolContinuation = false
        hasToolResponseDone = false
    }

    private fun summarizeFrame(jpegData: ByteArray) {
        if (visionRequestInFlight) return
        visionRequestInFlight = true
        try {
            val imageUrl = "data:image/jpeg;base64,${Base64.encodeToString(jpegData, Base64.NO_WRAP)}"
            val body = JSONObject().apply {
                put("model", GrokConfig.VISION_MODEL)
                put("store", false)
                put("temperature", 0.1)
                put("max_tokens", 120)
                put("messages", JSONArray()
                    .put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You summarize live wearable-camera frames for a voice assistant. Return one compact, actionable sentence. Mention readable signs, people, hazards, screens, objects, or navigation cues. Do not invent certainty.")
                    })
                    .put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray()
                            .put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", imageUrl)
                                    put("detail", "low")
                                })
                            })
                            .put(JSONObject().apply {
                                put("type", "text")
                                put("text", "Summarize the current camera frame for the assistant.")
                            }))
                    }))
            }

            val request = Request.Builder()
                .url(GrokConfig.CHAT_COMPLETIONS_URL)
                .addHeader("Authorization", "Bearer ${authorizationToken()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val responseBody = response.body?.string() ?: return
                val summary = JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    .orEmpty()
                if (summary.isNotEmpty()) {
                    onVisionSummary?.invoke(summary)
                    injectVisionContext(summary)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision summary failed: ${e.message}")
        } finally {
            visionRequestInFlight = false
        }
    }

    private fun injectVisionContext(summary: String) {
        if (_connectionState.value != GrokConnectionState.Ready) return
        webSocket?.send(JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", "Current visual context from the glasses camera: $summary")
                }))
            })
        }.toString())
    }
}
