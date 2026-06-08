import Foundation

final class SettingsManager {
  static let shared = SettingsManager()

  private let defaults = UserDefaults.standard

  private enum Key: String {
    case grokAPIKey
    case grokAuthBrokerURL
    case grokAuthBrokerToken
    case openClawHost
    case openClawPort
    case openClawHookToken
    case openClawGatewayToken
    case grokSystemPrompt
    case grokSoulPrompt
    case grokVoice
    case webrtcSignalingURL
    case speakerOutputEnabled
    case videoStreamingEnabled
    case visionSummariesEnabled
    case displayHUDEnabled
    case proactiveNotificationsEnabled
    case porcupineAccessKey
    case wakeWordEnabled
    case wakeWordBuiltInKeyword
    case wakeWordKeywordPath
    case wakeWordSensitivity
    case wakeWordAutoResume
  }

  private init() {}

  // MARK: - Grok

  var grokAPIKey: String {
    get { defaults.string(forKey: Key.grokAPIKey.rawValue) ?? Secrets.grokAPIKey }
    set { defaults.set(newValue, forKey: Key.grokAPIKey.rawValue) }
  }

  var grokAuthBrokerURL: String {
    get { defaults.string(forKey: Key.grokAuthBrokerURL.rawValue) ?? Secrets.grokAuthBrokerURL }
    set { defaults.set(newValue, forKey: Key.grokAuthBrokerURL.rawValue) }
  }

  var grokAuthBrokerToken: String {
    get { defaults.string(forKey: Key.grokAuthBrokerToken.rawValue) ?? Secrets.grokAuthBrokerToken }
    set { defaults.set(newValue, forKey: Key.grokAuthBrokerToken.rawValue) }
  }

  var grokSystemPrompt: String {
    get { defaults.string(forKey: Key.grokSystemPrompt.rawValue) ?? GrokConfig.defaultSystemInstruction }
    set { defaults.set(newValue, forKey: Key.grokSystemPrompt.rawValue) }
  }

  var grokSoulPrompt: String {
    get { defaults.string(forKey: Key.grokSoulPrompt.rawValue) ?? "" }
    set { defaults.set(newValue, forKey: Key.grokSoulPrompt.rawValue) }
  }

  var grokVoice: String {
    get { defaults.string(forKey: Key.grokVoice.rawValue) ?? "eve" }
    set { defaults.set(newValue, forKey: Key.grokVoice.rawValue) }
  }

  // MARK: - OpenClaw

  var openClawHost: String {
    get { defaults.string(forKey: Key.openClawHost.rawValue) ?? Secrets.openClawHost }
    set { defaults.set(newValue, forKey: Key.openClawHost.rawValue) }
  }

  var openClawPort: Int {
    get {
      let stored = defaults.integer(forKey: Key.openClawPort.rawValue)
      return stored != 0 ? stored : Secrets.openClawPort
    }
    set { defaults.set(newValue, forKey: Key.openClawPort.rawValue) }
  }

  var openClawHookToken: String {
    get { defaults.string(forKey: Key.openClawHookToken.rawValue) ?? Secrets.openClawHookToken }
    set { defaults.set(newValue, forKey: Key.openClawHookToken.rawValue) }
  }

  var openClawGatewayToken: String {
    get { defaults.string(forKey: Key.openClawGatewayToken.rawValue) ?? Secrets.openClawGatewayToken }
    set { defaults.set(newValue, forKey: Key.openClawGatewayToken.rawValue) }
  }

  // MARK: - WebRTC

  var webrtcSignalingURL: String {
    get { defaults.string(forKey: Key.webrtcSignalingURL.rawValue) ?? Secrets.webrtcSignalingURL }
    set { defaults.set(newValue, forKey: Key.webrtcSignalingURL.rawValue) }
  }

  // MARK: - Audio

  var speakerOutputEnabled: Bool {
    get { defaults.bool(forKey: Key.speakerOutputEnabled.rawValue) }
    set { defaults.set(newValue, forKey: Key.speakerOutputEnabled.rawValue) }
  }

  // MARK: - Video

  var videoStreamingEnabled: Bool {
    get { defaults.object(forKey: Key.videoStreamingEnabled.rawValue) as? Bool ?? true }
    set { defaults.set(newValue, forKey: Key.videoStreamingEnabled.rawValue) }
  }

  var visionSummariesEnabled: Bool {
    get { defaults.object(forKey: Key.visionSummariesEnabled.rawValue) as? Bool ?? true }
    set { defaults.set(newValue, forKey: Key.visionSummariesEnabled.rawValue) }
  }

  // MARK: - Display HUD

  var displayHUDEnabled: Bool {
    get { defaults.object(forKey: Key.displayHUDEnabled.rawValue) as? Bool ?? true }
    set { defaults.set(newValue, forKey: Key.displayHUDEnabled.rawValue) }
  }

  // MARK: - Notifications

  var proactiveNotificationsEnabled: Bool {
    get { defaults.object(forKey: Key.proactiveNotificationsEnabled.rawValue) as? Bool ?? true }
    set { defaults.set(newValue, forKey: Key.proactiveNotificationsEnabled.rawValue) }
  }

  // MARK: - Wake Word

  var porcupineAccessKey: String {
    get { defaults.string(forKey: Key.porcupineAccessKey.rawValue) ?? Secrets.porcupineAccessKey }
    set { defaults.set(newValue, forKey: Key.porcupineAccessKey.rawValue) }
  }

  var isPorcupineConfigured: Bool {
    let key = porcupineAccessKey.trimmingCharacters(in: .whitespacesAndNewlines)
    return !key.isEmpty && key != "YOUR_PICOVOICE_ACCESS_KEY"
  }

  var wakeWordEnabled: Bool {
    get { defaults.object(forKey: Key.wakeWordEnabled.rawValue) as? Bool ?? false }
    set { defaults.set(newValue, forKey: Key.wakeWordEnabled.rawValue) }
  }

  var wakeWordBuiltInKeyword: String {
    get { defaults.string(forKey: Key.wakeWordBuiltInKeyword.rawValue) ?? "jarvis" }
    set { defaults.set(newValue, forKey: Key.wakeWordBuiltInKeyword.rawValue) }
  }

  var wakeWordKeywordPath: String {
    get { defaults.string(forKey: Key.wakeWordKeywordPath.rawValue) ?? "" }
    set { defaults.set(newValue, forKey: Key.wakeWordKeywordPath.rawValue) }
  }

  var wakeWordSensitivity: Double {
    get { defaults.object(forKey: Key.wakeWordSensitivity.rawValue) as? Double ?? 0.65 }
    set { defaults.set(min(max(newValue, 0.0), 1.0), forKey: Key.wakeWordSensitivity.rawValue) }
  }

  var wakeWordAutoResume: Bool {
    get { defaults.object(forKey: Key.wakeWordAutoResume.rawValue) as? Bool ?? true }
    set { defaults.set(newValue, forKey: Key.wakeWordAutoResume.rawValue) }
  }

  // MARK: - Reset

  func resetAll() {
    for key in [Key.grokAPIKey, .grokAuthBrokerURL, .grokAuthBrokerToken,
                .grokSystemPrompt, .grokSoulPrompt, .grokVoice,
                .openClawHost, .openClawPort,
                .openClawHookToken, .openClawGatewayToken, .webrtcSignalingURL,
                .speakerOutputEnabled, .videoStreamingEnabled, .visionSummariesEnabled,
                .displayHUDEnabled,
                .proactiveNotificationsEnabled,
                .porcupineAccessKey, .wakeWordEnabled, .wakeWordBuiltInKeyword,
                .wakeWordKeywordPath, .wakeWordSensitivity, .wakeWordAutoResume] {
      defaults.removeObject(forKey: key.rawValue)
    }
  }
}
