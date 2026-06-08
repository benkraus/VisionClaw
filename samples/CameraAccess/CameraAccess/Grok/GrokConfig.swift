import Foundation

enum GrokConfig {
  static let websocketBaseURL = "wss://api.x.ai/v1/realtime"
  static let model = "grok-voice-think-fast-1.0"
  static let visionModel = "grok-4.3"
  static let chatCompletionsURL = URL(string: "https://api.x.ai/v1/chat/completions")!

  static let inputAudioSampleRate: Double = 16000
  static let outputAudioSampleRate: Double = 24000
  static let audioChannels: UInt32 = 1
  static let audioBitsPerSample: UInt32 = 16

  static let videoFrameInterval: TimeInterval = 3.0
  static let videoJPEGQuality: CGFloat = 0.45

  static var voice: String { SettingsManager.shared.grokVoice }
  static var authBrokerURL: String { SettingsManager.shared.grokAuthBrokerURL }
  static var authBrokerToken: String {
    let token = SettingsManager.shared.grokAuthBrokerToken.trimmingCharacters(in: .whitespacesAndNewlines)
    if !token.isEmpty && token != "YOUR_GROK_AUTH_BROKER_TOKEN" {
      return token
    }
    return SettingsManager.shared.openClawGatewayToken
  }

  static var systemInstruction: String {
    let soul = SettingsManager.shared.grokSoulPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !soul.isEmpty else { return SettingsManager.shared.grokSystemPrompt }
    return """
      \(SettingsManager.shared.grokSystemPrompt)

      User-provided soul/personality context:
      \(soul)
      """
  }

  static let defaultSystemInstruction = """
    You are Grok running inside VisionClaw for someone wearing Meta Ray-Ban smart glasses. You have a real-time voice conversation and receive periodic visual summaries from the glasses camera. Keep responses concise, natural, and useful while the user is moving.

    You can also update the glasses display HUD with short cards. Use display_hud for glanceable status, task results, step lists, confirmations, warnings, and anything the user should not have to remember from speech alone. HUD text must be short: one title plus one or two compact lines.

    CRITICAL: You have NO memory, NO storage, and NO ability to take actions on your own. You cannot remember things, keep lists, set reminders, search the web, send messages, or do anything persistent. You are ONLY a voice interface.

    You have two client-side tools:
    - execute: delegates real-world actions to OpenClaw.
    - display_hud: writes a concise card to the Ray-Ban Display HUD.

    ALWAYS use execute when the user asks you to:
    - Send a message to someone (any platform: WhatsApp, Telegram, iMessage, Slack, etc.)
    - Search or look up anything (web, local info, facts, news)
    - Add, create, or modify anything (shopping lists, reminders, notes, todos, events)
    - Research, analyze, or draft anything
    - Control or interact with apps, devices, or services
    - Remember or store any information for later

    Be detailed in your task description. Include all relevant context: names, content, platforms, quantities, etc. The assistant works better with complete information.

    NEVER pretend to do these things yourself.

    IMPORTANT: Before calling execute, ALWAYS speak a brief acknowledgment first. For example:
    - "Sure, let me add that to your shopping list." then call execute.
    - "Got it, searching for that now." then call execute.
    - "On it, sending that message." then call execute.
    Never call execute silently -- the user needs verbal confirmation that you heard them and are working on it. The tool may take several seconds to complete, so the acknowledgment lets them know something is happening.

    For messages, confirm recipient and content before delegating unless clearly urgent.
    """

  // User-configurable values (Settings screen overrides, falling back to Secrets.swift)
  static var apiKey: String { SettingsManager.shared.grokAPIKey }
  static var openClawHost: String { SettingsManager.shared.openClawHost }
  static var openClawPort: Int { SettingsManager.shared.openClawPort }
  static var openClawHookToken: String { SettingsManager.shared.openClawHookToken }
  static var openClawGatewayToken: String { SettingsManager.shared.openClawGatewayToken }

  static func websocketURL() -> URL? {
    var components = URLComponents(string: websocketBaseURL)
    components?.queryItems = [
      URLQueryItem(name: "model", value: model)
    ]
    return components?.url
  }

  static var isConfigured: Bool {
    return hasAuthBroker || hasDirectAPIKey
  }

  static var hasDirectAPIKey: Bool {
    let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed != "YOUR_GROK_API_KEY" && !trimmed.isEmpty
  }

  static var hasAuthBroker: Bool {
    let trimmed = authBrokerURL.trimmingCharacters(in: .whitespacesAndNewlines)
    return !trimmed.isEmpty
      && trimmed != "https://YOUR_HOST/api/grok/token"
      && URL(string: trimmed) != nil
  }

  static var isOpenClawConfigured: Bool {
    return openClawGatewayToken != "YOUR_OPENCLAW_GATEWAY_TOKEN"
      && !openClawGatewayToken.isEmpty
      && openClawHost != "http://YOUR_MAC_HOSTNAME.local"
  }
}

enum GrokAuthError: LocalizedError {
  case notConfigured
  case invalidBrokerURL
  case brokerHTTP(Int, String)
  case brokerMissingToken

  var errorDescription: String? {
    switch self {
    case .notConfigured:
      return "Configure a Grok API key or Grok auth broker in Settings."
    case .invalidBrokerURL:
      return "Grok auth broker URL is invalid."
    case .brokerHTTP(let code, let body):
      return "Grok auth broker returned HTTP \(code): \(body)"
    case .brokerMissingToken:
      return "Grok auth broker did not return an access token."
    }
  }
}

private struct GrokBrokerAuth {
  let accessToken: String
  let expiresAt: Date?

  var isFresh: Bool {
    guard let expiresAt else { return true }
    return expiresAt.timeIntervalSinceNow > 60
  }
}

@MainActor
final class GrokAuthProvider {
  private var cachedBrokerAuth: GrokBrokerAuth?

  func authorizationToken() async throws -> String {
    if GrokConfig.hasAuthBroker {
      return try await brokerAuthorizationToken()
    }
    if GrokConfig.hasDirectAPIKey {
      return GrokConfig.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    throw GrokAuthError.notConfigured
  }

  private func brokerAuthorizationToken() async throws -> String {
    if let cachedBrokerAuth, cachedBrokerAuth.isFresh {
      return cachedBrokerAuth.accessToken
    }

    guard let url = URL(string: GrokConfig.authBrokerURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
      throw GrokAuthError.invalidBrokerURL
    }

    var request = URLRequest(url: url)
    request.httpMethod = "GET"
    request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

    let brokerToken = GrokConfig.authBrokerToken.trimmingCharacters(in: .whitespacesAndNewlines)
    if !brokerToken.isEmpty && brokerToken != "YOUR_OPENCLAW_GATEWAY_TOKEN" {
      request.setValue("Bearer \(brokerToken)", forHTTPHeaderField: "Authorization")
    }

    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw GrokAuthError.brokerHTTP(0, "No HTTP response")
    }
    guard (200..<300).contains(http.statusCode) else {
      let body = String(data: data, encoding: .utf8) ?? ""
      throw GrokAuthError.brokerHTTP(http.statusCode, String(body.prefix(200)))
    }
    guard
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let token = object["accessToken"] as? String
        ?? object["access_token"] as? String
        ?? object["token"] as? String,
      !token.isEmpty
    else {
      throw GrokAuthError.brokerMissingToken
    }

    let expiresAt = parseExpiry(from: object)
    cachedBrokerAuth = GrokBrokerAuth(accessToken: token, expiresAt: expiresAt)
    return token
  }

  private func parseExpiry(from object: [String: Any]) -> Date? {
    if let expiresAt = object["expiresAt"] as? String ?? object["expires_at"] as? String {
      if let date = ISO8601DateFormatter().date(from: expiresAt) {
        return date
      }
      if let interval = TimeInterval(expiresAt) {
        return dateFromTimestamp(interval)
      }
    }
    if let expiresAt = object["expiresAt"] as? TimeInterval ?? object["expires_at"] as? TimeInterval {
      return dateFromTimestamp(expiresAt)
    }
    if let expiresIn = object["expiresIn"] as? TimeInterval ?? object["expires_in"] as? TimeInterval {
      return Date(timeIntervalSinceNow: expiresIn)
    }
    return Date(timeIntervalSinceNow: 300)
  }

  private func dateFromTimestamp(_ timestamp: TimeInterval) -> Date {
    if timestamp < 4_000_000_000 {
      return Date(timeIntervalSince1970: timestamp)
    }
    return Date(timeIntervalSince1970: timestamp / 1000)
  }
}
