import Foundation
import Porcupine

enum WakeWordState: Equatable {
  case disabled
  case notConfigured
  case idle
  case listening
  case detected
  case error(String)

  var displayText: String {
    switch self {
    case .disabled: return "Wake Off"
    case .notConfigured: return "Wake Setup"
    case .idle: return "Wake"
    case .listening: return "Listening"
    case .detected: return "Wake Detected"
    case .error: return "Wake Error"
    }
  }
}

@MainActor
final class WakeWordManager {
  private var porcupineManager: PorcupineManager?

  private(set) var state: WakeWordState = .disabled
  var onStateChanged: ((WakeWordState) -> Void)?
  var onDetected: (() -> Void)?

  func startListening() {
    guard SettingsManager.shared.wakeWordEnabled else {
      updateState(.disabled)
      return
    }

    guard SettingsManager.shared.isPorcupineConfigured else {
      updateState(.notConfigured)
      return
    }

    stopListening(updateIdleState: false)

    do {
      let accessKey = SettingsManager.shared.porcupineAccessKey
      let sensitivity = Float32(SettingsManager.shared.wakeWordSensitivity)
      let detection: (Int32) -> Void = { [weak self] _ in
        Task { @MainActor in
          guard let self else { return }
          self.updateState(.detected)
          self.onDetected?()
        }
      }
      let errorCallback: (Error) -> Void = { [weak self] error in
        Task { @MainActor in
          self?.updateState(.error(error.localizedDescription))
        }
      }

      if let keywordPath = resolvedCustomKeywordPath() {
        porcupineManager = try PorcupineManager(
          accessKey: accessKey,
          keywordPath: keywordPath,
          sensitivity: sensitivity,
          onDetection: detection,
          errorCallback: errorCallback
        )
      } else {
        porcupineManager = try PorcupineManager(
          accessKey: accessKey,
          keyword: builtInKeyword(named: SettingsManager.shared.wakeWordBuiltInKeyword),
          sensitivity: sensitivity,
          onDetection: detection,
          errorCallback: errorCallback
        )
      }

      try porcupineManager?.start()
      updateState(.listening)
    } catch {
      updateState(.error(error.localizedDescription))
      tearDownManager()
    }
  }

  func stopListening(updateIdleState: Bool = true) {
    tearDownManager()
    if updateIdleState {
      updateState(SettingsManager.shared.wakeWordEnabled ? .idle : .disabled)
    }
  }

  private func tearDownManager() {
    if let manager = porcupineManager {
      try? manager.stop()
      try? manager.delete()
    }
    porcupineManager = nil
  }

  private func resolvedCustomKeywordPath() -> String? {
    let rawPath = SettingsManager.shared.wakeWordKeywordPath.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !rawPath.isEmpty else { return nil }

    if FileManager.default.fileExists(atPath: rawPath) {
      return rawPath
    }

    let url = URL(fileURLWithPath: rawPath)
    let baseName = url.deletingPathExtension().lastPathComponent
    let ext = url.pathExtension.isEmpty ? "ppn" : url.pathExtension
    return Bundle.main.path(forResource: baseName, ofType: ext)
  }

  private func builtInKeyword(named name: String) -> Porcupine.BuiltInKeyword {
    switch name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "alexa": return .alexa
    case "americano": return .americano
    case "blueberry": return .blueberry
    case "bumblebee": return .bumblebee
    case "computer": return .computer
    case "grapefruit": return .grapefruit
    case "grasshopper": return .grasshopper
    case "hey google": return .heyGoogle
    case "hey siri": return .heySiri
    case "ok google": return .okGoogle
    case "picovoice": return .picovoice
    case "porcupine": return .porcupine
    case "terminator": return .terminator
    case "jarvis": fallthrough
    default: return .jarvis
    }
  }

  private func updateState(_ newState: WakeWordState) {
    state = newState
    onStateChanged?(newState)
  }
}
