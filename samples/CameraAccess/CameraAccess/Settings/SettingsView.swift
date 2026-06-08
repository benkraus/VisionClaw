import SwiftUI

struct SettingsView: View {
  @Environment(\.dismiss) private var dismiss
  private let settings = SettingsManager.shared

  @State private var grokAPIKey: String = ""
  @State private var grokAuthBrokerURL: String = ""
  @State private var grokAuthBrokerToken: String = ""
  @State private var openClawHost: String = ""
  @State private var openClawPort: String = ""
  @State private var openClawHookToken: String = ""
  @State private var openClawGatewayToken: String = ""
  @State private var grokSystemPrompt: String = ""
  @State private var grokSoulPrompt: String = ""
  @State private var grokVoice: String = "eve"
  @State private var webrtcSignalingURL: String = ""
  @State private var speakerOutputEnabled: Bool = false
  @State private var videoStreamingEnabled: Bool = true
  @State private var visionSummariesEnabled: Bool = true
  @State private var displayHUDEnabled: Bool = true
  @State private var proactiveNotificationsEnabled: Bool = true
  @State private var porcupineAccessKey: String = ""
  @State private var wakeWordEnabled: Bool = false
  @State private var wakeWordBuiltInKeyword: String = "jarvis"
  @State private var wakeWordKeywordPath: String = ""
  @State private var wakeWordSensitivity: Double = 0.65
  @State private var wakeWordAutoResume: Bool = true
  @State private var showResetConfirmation = false

  var body: some View {
    NavigationView {
      Form {
        Section(header: Text("Grok API")) {
          VStack(alignment: .leading, spacing: 4) {
            Text("API Key")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("Enter Grok API key", text: $grokAPIKey)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Auth Broker URL")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("https://your-host.example.com/api/grok/token", text: $grokAuthBrokerURL)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .keyboardType(.URL)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Auth Broker Token")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("Broker auth token", text: $grokAuthBrokerToken)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          Picker("Voice", selection: $grokVoice) {
            Text("Eve").tag("eve")
            Text("Ara").tag("ara")
            Text("Rex").tag("rex")
            Text("Sal").tag("sal")
            Text("Leo").tag("leo")
          }
        }

        Section(header: Text("System Prompt"), footer: Text("Customize the AI assistant's behavior and personality. Changes take effect on the next Grok session.")) {
          TextEditor(text: $grokSystemPrompt)
            .font(.system(.body, design: .monospaced))
            .frame(minHeight: 200)
        }

        Section(header: Text("Soul Context"), footer: Text("Optional persistent personality/context block appended to Grok's session instructions.")) {
          TextEditor(text: $grokSoulPrompt)
            .font(.system(.body, design: .monospaced))
            .frame(minHeight: 120)
        }

        Section(header: Text("OpenClaw"), footer: Text("Connect to an OpenClaw gateway running on your Mac for agentic tool-calling.")) {
          VStack(alignment: .leading, spacing: 4) {
            Text("Host")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("http://your-mac.local", text: $openClawHost)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .keyboardType(.URL)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Port")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("18789", text: $openClawPort)
              .keyboardType(.numberPad)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Hook Token")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("Hook token", text: $openClawHookToken)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Gateway Token")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("Gateway auth token", text: $openClawGatewayToken)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }
        }

        Section(header: Text("WebRTC")) {
          VStack(alignment: .leading, spacing: 4) {
            Text("Signaling URL")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("wss://your-server.example.com", text: $webrtcSignalingURL)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .keyboardType(.URL)
              .font(.system(.body, design: .monospaced))
          }
        }

        Section(header: Text("Audio"), footer: Text("Route audio output to the iPhone speaker instead of glasses. Useful for demos where others need to hear.")) {
          Toggle("Speaker Output", isOn: $speakerOutputEnabled)
        }

        Section(header: Text("Video"), footer: Text("Grok realtime voice receives compact visual summaries from sampled frames; raw video is not streamed over the voice socket.")) {
          Toggle("Video Streaming", isOn: $videoStreamingEnabled)
          Toggle("Vision Summaries", isOn: $visionSummariesEnabled)
        }

        Section(header: Text("Display HUD"), footer: Text("Send glanceable Grok, OpenClaw, transcript, visual-context, and model-authored cards to Ray-Ban Display glasses.")) {
          Toggle("Display HUD", isOn: $displayHUDEnabled)
        }

        Section(header: Text("Notifications"), footer: Text("Receive proactive updates from OpenClaw (heartbeat, scheduled tasks) spoken through the glasses.")) {
          Toggle("Proactive Notifications", isOn: $proactiveNotificationsEnabled)
        }

        Section(header: Text("Wake Word"), footer: Text("Uses Picovoice Porcupine for on-device wake-word detection. Default built-in keyword is Jarvis; enter a bundled resource name or absolute .ppn path for a custom keyword.")) {
          Toggle("Wake Word", isOn: $wakeWordEnabled)

          VStack(alignment: .leading, spacing: 4) {
            Text("Picovoice AccessKey")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("Enter Picovoice AccessKey", text: $porcupineAccessKey)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Built-in Keyword")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("jarvis", text: $wakeWordBuiltInKeyword)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 4) {
            Text("Custom Keyword Path")
              .font(.caption)
              .foregroundColor(.secondary)
            TextField("optional custom .ppn path or resource name", text: $wakeWordKeywordPath)
              .autocapitalization(.none)
              .disableAutocorrection(true)
              .font(.system(.body, design: .monospaced))
          }

          VStack(alignment: .leading, spacing: 8) {
            HStack {
              Text("Sensitivity")
              Spacer()
              Text(String(format: "%.2f", wakeWordSensitivity))
                .foregroundColor(.secondary)
                .font(.system(.body, design: .monospaced))
            }
            Slider(value: $wakeWordSensitivity, in: 0.1...0.95, step: 0.05)
          }

          Toggle("Auto-resume after Grok stops", isOn: $wakeWordAutoResume)
        }

        Section {
          Button("Reset to Defaults") {
            showResetConfirmation = true
          }
          .foregroundColor(.red)
        }
      }
      .navigationTitle("Settings")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .navigationBarLeading) {
          Button("Cancel") {
            dismiss()
          }
        }
        ToolbarItem(placement: .navigationBarTrailing) {
          Button("Save") {
            save()
            dismiss()
          }
          .fontWeight(.semibold)
        }
      }
      .alert("Reset Settings", isPresented: $showResetConfirmation) {
        Button("Reset", role: .destructive) {
          settings.resetAll()
          loadCurrentValues()
        }
        Button("Cancel", role: .cancel) {}
      } message: {
        Text("This will reset all settings to the values built into the app.")
      }
      .onAppear {
        loadCurrentValues()
      }
    }
  }

  private func loadCurrentValues() {
    grokAPIKey = settings.grokAPIKey
    grokAuthBrokerURL = settings.grokAuthBrokerURL
    grokAuthBrokerToken = settings.grokAuthBrokerToken
    grokSystemPrompt = settings.grokSystemPrompt
    grokSoulPrompt = settings.grokSoulPrompt
    grokVoice = settings.grokVoice
    openClawHost = settings.openClawHost
    openClawPort = String(settings.openClawPort)
    openClawHookToken = settings.openClawHookToken
    openClawGatewayToken = settings.openClawGatewayToken
    webrtcSignalingURL = settings.webrtcSignalingURL
    speakerOutputEnabled = settings.speakerOutputEnabled
    videoStreamingEnabled = settings.videoStreamingEnabled
    visionSummariesEnabled = settings.visionSummariesEnabled
    displayHUDEnabled = settings.displayHUDEnabled
    proactiveNotificationsEnabled = settings.proactiveNotificationsEnabled
    porcupineAccessKey = settings.porcupineAccessKey
    wakeWordEnabled = settings.wakeWordEnabled
    wakeWordBuiltInKeyword = settings.wakeWordBuiltInKeyword
    wakeWordKeywordPath = settings.wakeWordKeywordPath
    wakeWordSensitivity = settings.wakeWordSensitivity
    wakeWordAutoResume = settings.wakeWordAutoResume
  }

  private func save() {
    settings.grokAPIKey = grokAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.grokAuthBrokerURL = grokAuthBrokerURL.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.grokAuthBrokerToken = grokAuthBrokerToken.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.grokSystemPrompt = grokSystemPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.grokSoulPrompt = grokSoulPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.grokVoice = grokVoice
    settings.openClawHost = openClawHost.trimmingCharacters(in: .whitespacesAndNewlines)
    if let port = Int(openClawPort.trimmingCharacters(in: .whitespacesAndNewlines)) {
      settings.openClawPort = port
    }
    settings.openClawHookToken = openClawHookToken.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.openClawGatewayToken = openClawGatewayToken.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.webrtcSignalingURL = webrtcSignalingURL.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.speakerOutputEnabled = speakerOutputEnabled
    settings.videoStreamingEnabled = videoStreamingEnabled
    settings.visionSummariesEnabled = visionSummariesEnabled
    settings.displayHUDEnabled = displayHUDEnabled
    settings.proactiveNotificationsEnabled = proactiveNotificationsEnabled
    settings.porcupineAccessKey = porcupineAccessKey.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.wakeWordEnabled = wakeWordEnabled
    let trimmedWakeWord = wakeWordBuiltInKeyword.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.wakeWordBuiltInKeyword = trimmedWakeWord.isEmpty ? "jarvis" : trimmedWakeWord
    settings.wakeWordKeywordPath = wakeWordKeywordPath.trimmingCharacters(in: .whitespacesAndNewlines)
    settings.wakeWordSensitivity = wakeWordSensitivity
    settings.wakeWordAutoResume = wakeWordAutoResume
  }
}
