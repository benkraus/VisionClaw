import Combine
import Foundation
import MWDATCore
import MWDATDisplay

enum DisplayHUDConnectionState: Equatable {
  case disabled
  case disconnected
  case connecting
  case ready
  case error(String)

  var displayText: String {
    switch self {
    case .disabled: return "HUD Off"
    case .disconnected: return "HUD"
    case .connecting: return "HUD..."
    case .ready: return "HUD"
    case .error: return "HUD Error"
    }
  }
}

@MainActor
final class DisplayHUDManager: ObservableObject {
  @Published private(set) var connectionState: DisplayHUDConnectionState = .disconnected
  @Published private(set) var lastError: String?

  private let wearables: WearablesInterface
  private var deviceSelector: AutoDeviceSelector
  private var sharedDeviceSession: DeviceSession?
  private var deviceSession: DeviceSession?
  private var ownsDeviceSession = false
  private var display: Display?
  private var stateListenerToken: AnyListenerToken?
  private var coreStateTask: Task<Void, Never>?
  private var sessionErrorTask: Task<Void, Never>?
  private var displayStateTask: Task<Void, Never>?
  private var displayStateContinuation: AsyncStream<DisplayState>.Continuation?
  private var pendingAction: (() async -> Void)?

  init(wearables: WearablesInterface) {
    self.wearables = wearables
    self.deviceSelector = AutoDeviceSelector(wearables: wearables, filter: { $0.supportsDisplay() })
  }

  deinit {
    stateListenerToken = nil
    coreStateTask?.cancel()
    sessionErrorTask?.cancel()
    displayStateTask?.cancel()
  }

  func showStatus(title: String, body: String) async {
    await send(DisplayHUDViews.status(title: title, body: body))
  }

  func showTranscript(user: String, assistant: String) async {
    guard !user.isEmpty || !assistant.isEmpty else { return }
    await send(DisplayHUDViews.transcript(user: user, assistant: assistant))
  }

  func showToolStatus(_ status: ToolCallStatus) async {
    guard status != .idle else { return }
    await send(DisplayHUDViews.toolStatus(status))
  }

  func showVisionSummary(_ summary: String) async {
    await send(DisplayHUDViews.vision(summary))
  }

  func showModelCard(title: String, body: String, kind: String?) async {
    await send(DisplayHUDViews.modelCard(title: title, body: body, kind: kind))
  }

  func showError(_ message: String) async {
    await send(DisplayHUDViews.error(message))
  }

  func useSharedDeviceSession(_ session: DeviceSession?) {
    sharedDeviceSession = session
  }

  func detachFromDisplay() async {
    stateListenerToken = nil
    displayStateContinuation?.finish()
    displayStateContinuation = nil
    displayStateTask?.cancel()
    displayStateTask = nil
    await display?.stop()
    display = nil
    coreStateTask?.cancel()
    coreStateTask = nil
    sessionErrorTask?.cancel()
    sessionErrorTask = nil
    if ownsDeviceSession {
      deviceSession?.stop()
    }
    deviceSession = nil
    ownsDeviceSession = false
    connectionState = SettingsManager.shared.displayHUDEnabled ? .disconnected : .disabled
  }

  private func send(_ view: some DisplayableView) async {
    guard SettingsManager.shared.displayHUDEnabled else {
      connectionState = .disabled
      return
    }

    if let display, connectionState == .ready {
      await doSend(view, on: display)
      return
    }

    let sendableView = view
    pendingAction = { [weak self] in
      guard let self, let display = self.display else { return }
      await self.doSend(sendableView, on: display)
    }

    if display == nil {
      await attachToDisplay()
    }
  }

  private func doSend(_ view: some DisplayableView, on capability: Display) async {
    do {
      try await capability.send(view)
    } catch {
      let message = (error as? DisplayError)?.description ?? error.localizedDescription
      lastError = message
      connectionState = .error(message)
    }
  }

  private func attachToDisplay() async {
    guard display == nil else { return }
    connectionState = .connecting
    lastError = nil

    if let sharedDeviceSession {
      deviceSession = sharedDeviceSession
      ownsDeviceSession = false
      observeDeviceSession(sharedDeviceSession)
      if sharedDeviceSession.state == .started {
        await setupDisplay(on: sharedDeviceSession)
      }
      return
    }

    do {
      let session = try wearables.createSession(deviceSelector: deviceSelector)
      deviceSession = session
      ownsDeviceSession = true
      observeDeviceSession(session)
      try session.start()
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      let message = DeviceSessionError.datAppOnTheGlassesUpdateRequired.localizedDescription
      lastError = message
      connectionState = .error(message)
    } catch {
      let message = "Failed to create display session: \(error.localizedDescription)"
      lastError = message
      connectionState = .error(message)
    }
  }

  private func observeDeviceSession(_ session: DeviceSession) {
    coreStateTask?.cancel()
    sessionErrorTask?.cancel()

    let stateStream = session.stateStream()
    let errorStream = session.errorStream()
    coreStateTask = Task { [weak self] in
      for await state in stateStream {
        guard let self, !Task.isCancelled else { return }
        switch state {
        case .started:
          await self.setupDisplay(on: session)
        case .stopping, .stopped:
          self.connectionState = .disconnected
          self.display = nil
          if self.ownsDeviceSession {
            self.deviceSession = nil
            self.ownsDeviceSession = false
          }
        case .idle, .starting, .paused:
          break
        @unknown default:
          break
        }
      }
    }
    sessionErrorTask = Task { [weak self] in
      for await error in errorStream {
        guard let self, !Task.isCancelled else { return }
        self.handleSessionError(error)
      }
    }
  }

  private func setupDisplay(on session: DeviceSession) async {
    guard display == nil else { return }

    do {
      let capability = try session.addDisplay()
      let (stateStream, continuation) = AsyncStream.makeStream(of: DisplayState.self)
      displayStateContinuation = continuation
      stateListenerToken = capability.statePublisher.listen { state in
        continuation.yield(state)
      }

      displayStateTask = Task { [weak self] in
        for await state in stateStream {
          guard let self, !Task.isCancelled else { return }
          switch state {
          case .starting:
            self.connectionState = .connecting
          case .started:
            self.connectionState = .ready
            if let action = self.pendingAction {
              self.pendingAction = nil
              await action()
            }
          case .stopping:
            self.connectionState = .connecting
          case .stopped:
            self.connectionState = .disconnected
            self.stateListenerToken = nil
            self.displayStateContinuation?.finish()
            self.displayStateContinuation = nil
            self.display = nil
          }
        }
      }

      await capability.start()
      display = capability
    } catch {
      let message = "Failed to start display: \(error.localizedDescription)"
      lastError = message
      connectionState = .error(message)
    }
  }

  private func handleSessionError(_ error: DeviceSessionError) {
    let message = error.localizedDescription
    lastError = message
    connectionState = .error(message)
  }
}

private enum DisplayHUDViews {
  static func status(title: String, body: String) -> FlexBox {
    base(label: "VisionClaw", title: title, body: body)
  }

  static func transcript(user: String, assistant: String) -> FlexBox {
    FlexBox(direction: .column, spacing: 10) {
      if !user.isEmpty {
        Text("You", style: .meta, color: .secondary)
        Text(clamp(user, limit: 96), style: .body)
      }
      if !assistant.isEmpty {
        Text("Grok", style: .meta, color: .secondary)
        Text(clamp(assistant, limit: 140), style: .body)
      }
    }
    .padding(24)
    .background(.card)
  }

  static func toolStatus(_ status: ToolCallStatus) -> FlexBox {
    base(label: "OpenClaw", title: toolTitle(status), body: clamp(status.displayText, limit: 120))
  }

  static func vision(_ summary: String) -> FlexBox {
    base(label: "Camera", title: "Visual context", body: clamp(summary, limit: 150))
  }

  static func modelCard(title: String, body: String, kind: String?) -> FlexBox {
    base(label: (kind ?? "HUD").capitalized, title: clamp(title, limit: 40), body: clamp(body, limit: 160))
  }

  static func error(_ message: String) -> FlexBox {
    base(label: "VisionClaw", title: "Needs attention", body: clamp(message, limit: 150))
  }

  private static func base(label: String, title: String, body: String) -> FlexBox {
    FlexBox(direction: .column, spacing: 10) {
      Text(label, style: .meta, color: .secondary)
      Text(title, style: .heading)
      Text(body, style: .body)
    }
    .padding(24)
    .background(.card)
  }

  private static func toolTitle(_ status: ToolCallStatus) -> String {
    switch status {
    case .executing: return "Working"
    case .completed: return "Done"
    case .failed: return "Failed"
    case .cancelled: return "Cancelled"
    case .idle: return "OpenClaw"
    }
  }

  private static func clamp(_ text: String, limit: Int) -> String {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.count > limit else { return trimmed }
    let index = trimmed.index(trimmed.startIndex, offsetBy: limit)
    return String(trimmed[..<index]) + "..."
  }
}
