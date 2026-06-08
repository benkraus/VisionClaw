import Foundation
import MWDATCore
import SwiftUI

@MainActor
class GrokSessionViewModel: ObservableObject {
  @Published var isGrokActive: Bool = false
  @Published var connectionState: GrokConnectionState = .disconnected
  @Published var isModelSpeaking: Bool = false
  @Published var errorMessage: String?
  @Published var userTranscript: String = ""
  @Published var aiTranscript: String = ""
  @Published var toolCallStatus: ToolCallStatus = .idle
  @Published var openClawConnectionState: OpenClawConnectionState = .notConfigured
  @Published var hudConnectionState: DisplayHUDConnectionState = .disconnected
  @Published var lastVisionSummary: String = ""
  @Published var wakeWordState: WakeWordState = .disabled
  @Published var isWakeWordListening: Bool = false
  private let grokService = GrokLiveService()
  private let hudManager = DisplayHUDManager(wearables: Wearables.shared)
  private let wakeWordManager = WakeWordManager()
  private let openClawBridge = OpenClawBridge()
  private var toolCallRouter: ToolCallRouter?
  private let audioManager = AudioManager()
  private let eventClient = OpenClawEventClient()
  private var lastVideoFrameTime: Date = .distantPast
  private var stateObservation: Task<Void, Never>?
  private var lastHUDToolStatus: ToolCallStatus = .idle

  var streamingMode: StreamingMode = .glasses

  init() {
    wakeWordManager.onStateChanged = { [weak self] state in
      guard let self else { return }
      self.wakeWordState = state
      self.isWakeWordListening = state == .listening
    }

    wakeWordManager.onDetected = { [weak self] in
      guard let self else { return }
      Task { @MainActor in
        await self.handleWakeWordDetected()
      }
    }
  }

  func useDisplayDeviceSession(_ session: DeviceSession?) {
    hudManager.useSharedDeviceSession(session)
  }

  func startWakeWordListening() {
    guard !isGrokActive else { return }

    wakeWordManager.startListening()
    wakeWordState = wakeWordManager.state
    isWakeWordListening = wakeWordManager.state == .listening

    switch wakeWordManager.state {
    case .listening:
      errorMessage = nil
      Task { @MainActor in
        await hudManager.showStatus(
          title: "Wake word",
          body: "Say \(SettingsManager.shared.wakeWordBuiltInKeyword)"
        )
      }
    case .notConfigured:
      errorMessage = "Picovoice AccessKey not configured. Add it in Settings or Secrets.swift."
    case .error(let message):
      errorMessage = message
    default:
      break
    }
  }

  func stopWakeWordListening() {
    wakeWordManager.stopListening()
    wakeWordState = wakeWordManager.state
    isWakeWordListening = false
  }

  func startSession() async {
    guard !isGrokActive else { return }

    guard GrokConfig.isConfigured else {
      errorMessage = "Grok auth not configured. Open Settings and add either a Grok API key or auth broker URL."
      maybeResumeWakeWord()
      return
    }

    stopWakeWordListening()
    isGrokActive = true
    await hudManager.showStatus(title: "Grok", body: "Connecting voice session")

    // Wire audio callbacks
    audioManager.onAudioCaptured = { [weak self] data in
      guard let self else { return }
      Task { @MainActor in
        // Mute mic while model speaks when speaker is on the phone
        // (loudspeaker + co-located mic overwhelms iOS echo cancellation)
        let speakerOnPhone = self.streamingMode == .iPhone || SettingsManager.shared.speakerOutputEnabled
        if speakerOnPhone && self.grokService.isModelSpeaking { return }
        self.grokService.sendAudio(data: data)
      }
    }

    grokService.onAudioReceived = { [weak self] data in
      self?.audioManager.playAudio(data: data)
    }

    grokService.onInterrupted = { [weak self] in
      self?.audioManager.stopPlayback()
    }

    grokService.onTurnComplete = { [weak self] in
      guard let self else { return }
      Task { @MainActor in
        // Clear user transcript when AI finishes responding
        self.userTranscript = ""
        if !self.aiTranscript.isEmpty {
          await self.hudManager.showTranscript(user: "", assistant: self.aiTranscript)
        }
      }
    }

    grokService.onInputTranscription = { [weak self] text in
      guard let self else { return }
      Task { @MainActor in
        self.userTranscript += text
        self.aiTranscript = ""
        await self.hudManager.showTranscript(user: self.userTranscript, assistant: "")
      }
    }

    grokService.onOutputTranscription = { [weak self] text in
      guard let self else { return }
      Task { @MainActor in
        self.aiTranscript += text
        await self.hudManager.showTranscript(user: self.userTranscript, assistant: self.aiTranscript)
      }
    }

    grokService.onVisionSummary = { [weak self] summary in
      guard let self else { return }
      Task { @MainActor in
        self.lastVisionSummary = summary
        await self.hudManager.showVisionSummary(summary)
      }
    }

    // Handle unexpected disconnection
    grokService.onDisconnected = { [weak self] reason in
      guard let self else { return }
      Task { @MainActor in
        guard self.isGrokActive else { return }
        self.stopSession()
        self.errorMessage = "Connection lost: \(reason ?? "Unknown error")"
        await self.hudManager.showError(self.errorMessage ?? "Grok disconnected")
      }
    }

    // Check OpenClaw connectivity and start fresh session
    await openClawBridge.checkConnection()
    openClawBridge.resetSession()

    // Wire tool call handling
    toolCallRouter = ToolCallRouter(bridge: openClawBridge)

    grokService.onToolCall = { [weak self] toolCall in
      guard let self else { return }
      Task { @MainActor in
        for call in toolCall.functionCalls {
          if call.name == "display_hud" {
            let title = call.args["title"] as? String ?? "VisionClaw"
            let body = call.args["body"] as? String ?? ""
            let kind = call.args["kind"] as? String
            await self.hudManager.showModelCard(title: title, body: body, kind: kind)
            let response = ToolCallRouter.buildToolResponse(
              callId: call.id,
              result: .success("HUD updated")
            )
            self.grokService.sendToolResponse(response)
            continue
          }

          await self.hudManager.showToolStatus(.executing(call.name))
          self.toolCallRouter?.handleToolCall(call) { [weak self] response in
            self?.grokService.sendToolResponse(response)
          }
        }
      }
    }

    grokService.onToolCallCancellation = { [weak self] cancellation in
      guard let self else { return }
      Task { @MainActor in
        self.toolCallRouter?.cancelToolCalls(ids: cancellation.ids)
      }
    }

    // Observe service state
    stateObservation = Task { [weak self] in
      guard let self else { return }
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 100_000_000) // 100ms
        guard !Task.isCancelled else { break }
        self.connectionState = self.grokService.connectionState
        self.isModelSpeaking = self.grokService.isModelSpeaking
        self.toolCallStatus = self.openClawBridge.lastToolCallStatus
        self.openClawConnectionState = self.openClawBridge.connectionState
        self.hudConnectionState = self.hudManager.connectionState
        if self.toolCallStatus != self.lastHUDToolStatus {
          self.lastHUDToolStatus = self.toolCallStatus
          await self.hudManager.showToolStatus(self.toolCallStatus)
        }
      }
    }

    // Setup audio
    do {
      try audioManager.setupAudioSession(useIPhoneMode: streamingMode == .iPhone)
    } catch {
      errorMessage = "Audio setup failed: \(error.localizedDescription)"
      stateObservation?.cancel()
      stateObservation = nil
      isGrokActive = false
      await hudManager.showError(errorMessage ?? "Audio setup failed")
      maybeResumeWakeWord()
      return
    }

    // Connect to Grok and wait for setupComplete
    let setupOk = await grokService.connect()

    if !setupOk {
      let msg: String
      if case .error(let err) = grokService.connectionState {
        msg = err
      } else {
        msg = "Failed to connect to Grok"
      }
      errorMessage = msg
      grokService.disconnect()
      stateObservation?.cancel()
      stateObservation = nil
      isGrokActive = false
      connectionState = .disconnected
      await hudManager.showError(msg)
      maybeResumeWakeWord()
      return
    }

    // Start mic capture
    do {
      try audioManager.startCapture()
    } catch {
      errorMessage = "Mic capture failed: \(error.localizedDescription)"
      grokService.disconnect()
      stateObservation?.cancel()
      stateObservation = nil
      isGrokActive = false
      connectionState = .disconnected
      await hudManager.showError(errorMessage ?? "Mic capture failed")
      maybeResumeWakeWord()
      return
    }

    await hudManager.showStatus(title: "Grok ready", body: "Listening")

    // Connect to OpenClaw event stream for proactive notifications
    if SettingsManager.shared.proactiveNotificationsEnabled {
      eventClient.onNotification = { [weak self] text in
        guard let self else { return }
        Task { @MainActor in
          guard self.isGrokActive, self.connectionState == .ready else { return }
          self.grokService.sendTextMessage(text)
        }
      }
      eventClient.connect()
    }
  }

  func stopSession(resumeWakeWord: Bool = true) {
    eventClient.disconnect()
    toolCallRouter?.cancelAll()
    toolCallRouter = nil
    audioManager.stopCapture()
    grokService.disconnect()
    stateObservation?.cancel()
    stateObservation = nil
    isGrokActive = false
    connectionState = .disconnected
    isModelSpeaking = false
    userTranscript = ""
    aiTranscript = ""
    toolCallStatus = .idle
    hudConnectionState = hudManager.connectionState
    lastHUDToolStatus = .idle
    let shouldResumeWakeWord = resumeWakeWord
      && SettingsManager.shared.wakeWordEnabled
      && SettingsManager.shared.wakeWordAutoResume
    Task { @MainActor in
      await hudManager.showStatus(title: "Grok stopped", body: "Voice session ended")
      await hudManager.detachFromDisplay()
      if shouldResumeWakeWord {
        self.startWakeWordListening()
      }
    }
  }

  func sendVideoFrameIfThrottled(image: UIImage) {
    guard SettingsManager.shared.videoStreamingEnabled else { return }
    guard isGrokActive, connectionState == .ready else { return }
    let now = Date()
    guard now.timeIntervalSince(lastVideoFrameTime) >= GrokConfig.videoFrameInterval else { return }
    lastVideoFrameTime = now
    grokService.sendVideoFrame(image: image)
  }

  private func handleWakeWordDetected() async {
    stopWakeWordListening()
    await hudManager.showStatus(title: "Wake word", body: "Starting Grok")
    await startSession()
  }

  private func maybeResumeWakeWord() {
    guard SettingsManager.shared.wakeWordEnabled, SettingsManager.shared.wakeWordAutoResume else { return }
    startWakeWordListening()
  }

}
