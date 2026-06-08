import Foundation
import UIKit

enum GrokConnectionState: Equatable {
  case disconnected
  case connecting
  case settingUp
  case ready
  case error(String)
}

@MainActor
class GrokLiveService: ObservableObject {
  @Published var connectionState: GrokConnectionState = .disconnected
  @Published var isModelSpeaking: Bool = false

  var onAudioReceived: ((Data) -> Void)?
  var onTurnComplete: (() -> Void)?
  var onInterrupted: (() -> Void)?
  var onDisconnected: ((String?) -> Void)?
  var onInputTranscription: ((String) -> Void)?
  var onOutputTranscription: ((String) -> Void)?
  var onToolCall: ((GrokToolCall) -> Void)?
  var onToolCallCancellation: ((GrokToolCallCancellation) -> Void)?
  var onVisionSummary: ((String) -> Void)?

  private var lastUserSpeechEnd: Date?
  private var responseLatencyLogged = false
  private var isVisionRequestInFlight = false
  private var pendingToolCallIds = Set<String>()
  private var isAwaitingToolContinuation = false
  private var hasToolResponseDone = false

  private var webSocketTask: URLSessionWebSocketTask?
  private var receiveTask: Task<Void, Never>?
  private var connectContinuation: CheckedContinuation<Bool, Never>?
  private let delegate = WebSocketDelegate()
  private let authProvider = GrokAuthProvider()
  private var urlSession: URLSession!
  private let sendQueue = DispatchQueue(label: "grok.send", qos: .userInitiated)

  init() {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 30
    self.urlSession = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
  }

  func connect() async -> Bool {
    guard let url = GrokConfig.websocketURL() else {
      connectionState = .error("Invalid Grok WebSocket URL")
      return false
    }

    let bearerToken: String
    do {
      bearerToken = try await authProvider.authorizationToken()
    } catch {
      connectionState = .error(error.localizedDescription)
      return false
    }

    resetToolContinuationState()
    connectionState = .connecting

    let result = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
      self.connectContinuation = continuation

      self.delegate.onOpen = { [weak self] _ in
        guard let self else { return }
        Task { @MainActor in
          self.connectionState = .settingUp
          self.sendSetupMessage()
          self.startReceiving()
        }
      }

      self.delegate.onClose = { [weak self] code, reason in
        guard let self else { return }
        let reasonStr = reason.flatMap { String(data: $0, encoding: .utf8) } ?? "no reason"
        Task { @MainActor in
          self.resolveConnect(success: false)
          self.connectionState = .disconnected
          self.isModelSpeaking = false
          self.onDisconnected?("Connection closed (code \(code.rawValue): \(reasonStr))")
        }
      }

      self.delegate.onError = { [weak self] error in
        guard let self else { return }
        let msg = error?.localizedDescription ?? "Unknown error"
        Task { @MainActor in
          self.resolveConnect(success: false)
          self.connectionState = .error(msg)
          self.isModelSpeaking = false
          self.onDisconnected?(msg)
        }
      }

      var request = URLRequest(url: url)
      request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
      self.webSocketTask = self.urlSession.webSocketTask(with: request)
      self.webSocketTask?.resume()

      Task {
        try? await Task.sleep(nanoseconds: 15_000_000_000)
        await MainActor.run {
          self.resolveConnect(success: false)
          if self.connectionState == .connecting || self.connectionState == .settingUp {
            self.connectionState = .error("Connection timed out")
          }
        }
      }
    }

    return result
  }

  func disconnect() {
    receiveTask?.cancel()
    receiveTask = nil
    webSocketTask?.cancel(with: .normalClosure, reason: nil)
    webSocketTask = nil
    delegate.onOpen = nil
    delegate.onClose = nil
    delegate.onError = nil
    onToolCall = nil
    onToolCallCancellation = nil
    resetToolContinuationState()
    connectionState = .disconnected
    isModelSpeaking = false
    resolveConnect(success: false)
  }

  func sendAudio(data: Data) {
    guard connectionState == .ready else { return }
    enqueueJSON([
      "type": "input_audio_buffer.append",
      "audio": data.base64EncodedString()
    ])
  }

  func sendVideoFrame(image: UIImage) {
    guard connectionState == .ready else { return }
    guard SettingsManager.shared.visionSummariesEnabled else { return }
    guard let jpegData = image.jpegData(compressionQuality: GrokConfig.videoJPEGQuality) else { return }

    Task { [weak self] in
      await self?.summarizeFrame(jpegData: jpegData)
    }
  }

  func sendToolResponse(_ response: [String: Any]) {
    let callId = (response["item"] as? [String: Any])?["call_id"] as? String
    enqueueJSON(response)

    if let callId {
      pendingToolCallIds.remove(callId)
    }
    sendResponseCreateIfToolCallsComplete()
  }

  func sendTextMessage(_ text: String) {
    guard connectionState == .ready else { return }
    enqueueJSON([
      "type": "conversation.item.create",
      "item": [
        "type": "message",
        "role": "user",
        "content": [
          ["type": "input_text", "text": text]
        ]
      ]
    ])
    enqueueJSON(["type": "response.create"])
  }

  private func resolveConnect(success: Bool) {
    if let cont = connectContinuation {
      connectContinuation = nil
      cont.resume(returning: success)
    }
  }

  private func sendSetupMessage() {
    let setup: [String: Any] = [
      "type": "session.update",
      "session": [
        "model": GrokConfig.model,
        "instructions": GrokConfig.systemInstruction,
        "voice": GrokConfig.voice,
        "turn_detection": [
          "type": "server_vad",
          "threshold": 0.7,
          "prefix_padding_ms": 250,
          "silence_duration_ms": 650
        ],
        "audio": [
          "input": [
            "format": [
              "type": "audio/pcm",
              "rate": Int(GrokConfig.inputAudioSampleRate)
            ]
          ],
          "output": [
            "format": [
              "type": "audio/pcm",
              "rate": Int(GrokConfig.outputAudioSampleRate)
            ]
          ]
        ],
        "tools": ToolDeclarations.allDeclarations()
      ]
    ]
    enqueueJSON(setup)
  }

  private func enqueueJSON(_ json: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: json),
          let string = String(data: data, encoding: .utf8) else {
      return
    }
    let task = webSocketTask
    sendQueue.async {
      task?.send(.string(string)) { _ in }
    }
  }

  private func startReceiving() {
    receiveTask = Task { [weak self] in
      guard let self else { return }
      while !Task.isCancelled {
        guard let task = self.webSocketTask else { break }
        do {
          let message = try await task.receive()
          switch message {
          case .string(let text):
            await self.handleMessage(text)
          case .data(let data):
            if let text = String(data: data, encoding: .utf8) {
              await self.handleMessage(text)
            }
          @unknown default:
            break
          }
        } catch {
          if !Task.isCancelled {
            let reason = error.localizedDescription
            await MainActor.run {
              self.resolveConnect(success: false)
              self.connectionState = .disconnected
              self.isModelSpeaking = false
              self.onDisconnected?(reason)
            }
          }
          break
        }
      }
    }
  }

  private func handleMessage(_ text: String) async {
    guard let data = text.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let type = json["type"] as? String else {
      return
    }

    switch type {
    case "session.created", "conversation.created":
      return

    case "session.updated":
      connectionState = .ready
      resolveConnect(success: true)

    case "input_audio_buffer.speech_started":
      isModelSpeaking = false
      onInterrupted?()

    case "input_audio_buffer.speech_stopped":
      lastUserSpeechEnd = Date()
      responseLatencyLogged = false

    case "conversation.item.input_audio_transcription.completed":
      if let transcript = json["transcript"] as? String, !transcript.isEmpty {
        NSLog("[Grok] You: %@", transcript)
        onInputTranscription?(transcript)
      }

    case "response.output_audio.delta":
      if let base64Data = json["delta"] as? String,
         let audioData = Data(base64Encoded: base64Data) {
        markModelSpeakingIfNeeded()
        onAudioReceived?(audioData)
      }

    case "response.output_audio_transcript.delta", "response.text.delta":
      if let delta = json["delta"] as? String, !delta.isEmpty {
        NSLog("[Grok] AI: %@", delta)
        onOutputTranscription?(delta)
      }

    case "response.output_audio_transcript.done", "response.text.done":
      return

    case "response.function_call_arguments.done":
      if let toolCall = GrokToolCall(json: json) {
        if !isAwaitingToolContinuation {
          isAwaitingToolContinuation = true
          hasToolResponseDone = false
        }
        for call in toolCall.functionCalls {
          pendingToolCallIds.insert(call.id)
        }
        NSLog("[Grok] Tool call received: %d function(s)", toolCall.functionCalls.count)
        onToolCall?(toolCall)
      }

    case "response.done":
      isModelSpeaking = false
      responseLatencyLogged = false
      if isAwaitingToolContinuation {
        hasToolResponseDone = true
        sendResponseCreateIfToolCallsComplete()
      } else {
        onTurnComplete?()
      }

    case "error":
      let error = json["error"] as? [String: Any]
      let message = error?["message"] as? String ?? "Unknown Grok realtime error"
      connectionState = .error(message)
      isModelSpeaking = false
      resolveConnect(success: false)
      onDisconnected?(message)

    default:
      return
    }
  }

  private func markModelSpeakingIfNeeded() {
    guard !isModelSpeaking else { return }
    isModelSpeaking = true
    if let speechEnd = lastUserSpeechEnd, !responseLatencyLogged {
      let latency = Date().timeIntervalSince(speechEnd)
      NSLog("[Latency] %.0fms (user speech end -> first audio)", latency * 1000)
      responseLatencyLogged = true
    }
  }

  private func sendResponseCreateIfToolCallsComplete() {
    guard isAwaitingToolContinuation,
          hasToolResponseDone,
          pendingToolCallIds.isEmpty else {
      return
    }

    isAwaitingToolContinuation = false
    hasToolResponseDone = false
    enqueueJSON(["type": "response.create"])
  }

  private func resetToolContinuationState() {
    pendingToolCallIds.removeAll()
    isAwaitingToolContinuation = false
    hasToolResponseDone = false
  }

  private func summarizeFrame(jpegData: Data) async {
    guard !isVisionRequestInFlight else { return }
    isVisionRequestInFlight = true
    defer { isVisionRequestInFlight = false }

    let bearerToken: String
    do {
      bearerToken = try await authProvider.authorizationToken()
    } catch {
      NSLog("[Grok] Vision auth failed: %@", error.localizedDescription)
      return
    }

    var request = URLRequest(url: GrokConfig.chatCompletionsURL)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")

    let imageURL = "data:image/jpeg;base64,\(jpegData.base64EncodedString())"
    let body: [String: Any] = [
      "model": GrokConfig.visionModel,
      "store": false,
      "temperature": 0.1,
      "max_tokens": 120,
      "messages": [
        [
          "role": "system",
          "content": "You summarize live wearable-camera frames for a voice assistant. Return one compact, actionable sentence. Mention readable signs, people, hazards, screens, objects, or navigation cues. Do not invent certainty."
        ],
        [
          "role": "user",
          "content": [
            [
              "type": "image_url",
              "image_url": [
                "url": imageURL,
                "detail": "low"
              ]
            ],
            [
              "type": "text",
              "text": "Summarize the current camera frame for the assistant."
            ]
          ]
        ]
      ]
    ]

    guard let httpBody = try? JSONSerialization.data(withJSONObject: body) else { return }
    request.httpBody = httpBody

    do {
      let (data, response) = try await URLSession.shared.data(for: request)
      guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
        return
      }
      guard
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
        let choices = object["choices"] as? [[String: Any]],
        let first = choices.first,
        let message = first["message"] as? [String: Any],
        let content = message["content"] as? String
      else {
        return
      }

      let summary = content.trimmingCharacters(in: .whitespacesAndNewlines)
      guard !summary.isEmpty else { return }
      onVisionSummary?(summary)
      injectVisionContext(summary)
    } catch {
      NSLog("[Grok] Vision summary failed: %@", error.localizedDescription)
    }
  }

  private func injectVisionContext(_ summary: String) {
    guard connectionState == .ready else { return }
    enqueueJSON([
      "type": "conversation.item.create",
      "item": [
        "type": "message",
        "role": "user",
        "content": [
          [
            "type": "input_text",
            "text": "Current visual context from the glasses camera: \(summary)"
          ]
        ]
      ]
    ])
  }
}

private class WebSocketDelegate: NSObject, URLSessionWebSocketDelegate {
  var onOpen: ((String?) -> Void)?
  var onClose: ((URLSessionWebSocketTask.CloseCode, Data?) -> Void)?
  var onError: ((Error?) -> Void)?

  func urlSession(
    _ session: URLSession,
    webSocketTask: URLSessionWebSocketTask,
    didOpenWithProtocol protocol: String?
  ) {
    onOpen?(`protocol`)
  }

  func urlSession(
    _ session: URLSession,
    webSocketTask: URLSessionWebSocketTask,
    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
    reason: Data?
  ) {
    onClose?(closeCode, reason)
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didCompleteWithError error: Error?
  ) {
    if let error {
      onError?(error)
    }
  }
}
