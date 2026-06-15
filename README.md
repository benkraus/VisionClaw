# VisionClaw

![VisionClaw](assets/teaserimage.png)

A real-time AI assistant for Meta Ray-Ban smart glasses. See what you see, hear what you say, and take actions on your behalf -- all through voice.

![Cover](assets/cover.png)

Built on [Meta Wearables DAT SDK](https://github.com/facebook/meta-wearables-dat-ios) (iOS) / [DAT Android SDK](https://github.com/facebook/meta-wearables-dat-android) (Android) + [xAI Voice Agent API](https://docs.x.ai/developers/model-capabilities/audio/voice-agent) + [OpenClaw](https://github.com/nichochar/openclaw) (optional).

**Supported platforms:** iOS (iPhone) and Android (Pixel, Samsung, etc.)

## What It Does

Put on your glasses, tap the AI button, and talk:

- **"What am I looking at?"** -- Grok sees through your glasses camera and describes the scene
- **"Add milk to my shopping list"** -- delegates to OpenClaw, which adds it via your connected apps
- **"Send a message to John saying I'll be late"** -- routes through OpenClaw to WhatsApp/Telegram/iMessage
- **"Search for the best coffee shops nearby"** -- web search via OpenClaw, results spoken back

Audio flows bidirectionally through Grok's realtime voice socket. Camera frames are sampled, summarized through Grok image understanding, and injected into the voice session as compact visual context. On display-capable Ray-Ban glasses, the app can also push concise HUD cards for transcripts, visual context, tool progress, and model-authored status.

## How It Works

![How It Works](assets/how.png)

```
Meta Ray-Ban Glasses (or phone camera)
       |
       | video frames + mic audio
       v
iOS / Android App (this project)
       |
       | PCM audio (16kHz)
       v
Grok Voice Agent API (WebSocket)
       |
       |-- Audio response (PCM 24kHz) --> App --> Speaker
       |-- Tool calls (execute) -------> App --> OpenClaw Gateway
       |-- Tool calls (display_hud) ---> App --> Ray-Ban Display HUD
       |                                              |
       |                                              v
       |                                      56+ skills: web search,
       |                                      messaging, smart home,
       |                                      notes, reminders, etc.
       |                                              |
       |<---- Tool response (text) <----- App <-------+
       |
       v
  Grok speaks the result

JPEG frames are sent separately to Grok image understanding every few seconds, then the resulting visual summary is added to the realtime conversation.
```

**Key pieces:**
- **Grok Voice Agent** -- real-time speech-to-speech over WebSocket (native audio, not STT-first)
- **Grok image understanding** -- sampled camera frames become compact visual summaries for the voice session
- **Wake word** -- optional Picovoice Porcupine listener starts Grok hands-free
- **Display HUD** -- Ray-Ban Display cards for status, transcripts, visual context, and tool results
- **OpenClaw** (optional) -- local gateway that gives Grok access to 56+ tools and all your connected apps
- **Phone mode** -- test the full pipeline using your phone camera instead of glasses
- **WebRTC streaming** -- share your glasses POV live to a browser viewer

---

## Quick Start (iOS)

### 1. Clone and open

```bash
git clone git@github.com:benkraus/VisionClaw.git
cd VisionClaw/samples/CameraAccess
open CameraAccess.xcodeproj
```

### 2. Add your secrets

Copy the example file and fill in your values:

```bash
cp CameraAccess/Secrets.swift.example CameraAccess/Secrets.swift
```

Edit `Secrets.swift` with either your [Grok API key](https://console.x.ai) or a Grok auth broker URL, plus optional OpenClaw/WebRTC/Picovoice config.

### 3. Build and run

Select your iPhone as the target device and hit Run (Cmd+R).

### 4. Try it out

**Without glasses (iPhone mode):**
1. Tap **"Start on iPhone"** -- uses your iPhone's back camera
2. Tap the **AI button** to start a Grok voice session
3. Talk to the AI -- it can see through your iPhone camera

If Wake Word is enabled in Settings, the stream starts a local Porcupine listener automatically. Say the configured keyword (default: `jarvis`) to start Grok.

**With Meta Ray-Ban glasses:**

First, enable Developer Mode in the Meta AI app:

1. Open the **Meta AI** app on your iPhone
2. Go to **Settings** (gear icon, bottom left)
3. Tap **App Info**
4. Tap the **App version** number **5 times** -- this unlocks Developer Mode
5. Go back to Settings -- you'll now see a **Developer Mode** toggle. Turn it on.

![How to enable Developer Mode](assets/dev_mode.png)

Then in VisionClaw:
1. Tap **"Start Streaming"** in the app
2. Tap the **AI button** for voice + vision conversation

If Wake Word is enabled in Settings, the app listens locally for the configured keyword while streaming and auto-resumes listening after Grok stops.

---

## Quick Start (Android)

### 1. Clone and open

```bash
git clone git@github.com:benkraus/VisionClaw.git
```

Open `samples/CameraAccessAndroid/` in Android Studio.

### 2. Configure GitHub Packages (DAT SDK)

The Meta DAT Android SDK is distributed via GitHub Packages. You need a GitHub Personal Access Token with `read:packages` scope.

1. Go to [GitHub > Settings > Developer Settings > Personal Access Tokens](https://github.com/settings/tokens) and create a **classic** token with `read:packages` scope
2. In `samples/CameraAccessAndroid/local.properties`, add:

```properties
github_token=YOUR_GITHUB_TOKEN
```

> **Tip:** If you have the `gh` CLI installed, you can run `gh auth token` to get a valid token. Make sure it has `read:packages` scope -- if not, run `gh auth refresh -s read:packages`.
>
> **Note:** GitHub Packages requires authentication even for public repositories. The 401 error means your token is missing or invalid.

### 3. Add your secrets

```bash
cd samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/
cp Secrets.kt.example Secrets.kt
```

Edit `Secrets.kt` with either your [Grok API key](https://console.x.ai) or a Grok auth broker URL, plus optional OpenClaw/WebRTC/Picovoice config.

### 4. Build and run

1. Let Gradle sync in Android Studio (it will download the DAT SDK from GitHub Packages)
2. Select your Android phone as the target device
3. Click Run (Shift+F10)

> **Wireless debugging:** You can also install via ADB wirelessly. Enable **Wireless debugging** in your phone's Developer Options, then pair with `adb pair <ip>:<port>`.

### 5. Try it out

**Without glasses (Phone mode):**
1. Tap **"Start on Phone"** -- uses your phone's back camera
2. Tap the **AI button** (sparkle icon) to start a Grok voice session
3. Talk to the AI -- it can see through your phone camera

If Wake Word is enabled in Settings, the stream starts a local Porcupine listener automatically. Say the configured keyword (default: `jarvis`) to start Grok.

**With Meta Ray-Ban glasses:**

Enable Developer Mode in the Meta AI app (same steps as iOS above), then:
1. Tap **"Start Streaming"** in the app
2. Tap the **AI button** for voice + vision conversation

If Wake Word is enabled in Settings, the app listens locally for the configured keyword while streaming and auto-resumes listening after Grok stops.

---

## Setup: Wake Word (Optional)

VisionClaw can use [Picovoice Porcupine](https://picovoice.ai/platform/porcupine/) for on-device wake-word detection. The feature is disabled by default.

1. Create a Picovoice AccessKey in [Picovoice Console](https://console.picovoice.ai/)
2. Add it to `Secrets.swift` / `Secrets.kt`, or paste it into the in-app Settings screen
3. Enable **Wake Word** in Settings
4. Use the built-in keyword field for bundled keywords such as `jarvis`, `porcupine`, `computer`, `hey google`, or `picovoice`
5. For a custom keyword, generate a platform-specific `.ppn` in Picovoice Console and set **Custom Keyword Path** to a bundled resource name or absolute file path

Wake listening stops before Grok opens the realtime mic and can auto-resume when the Grok session ends.

---

## Setup: OpenClaw (Optional)

OpenClaw gives Grok the ability to take real-world actions: send messages, search the web, manage lists, control smart home devices, and more. Without it, Grok is voice + vision only.

### 1. Install and configure OpenClaw

Follow the [OpenClaw setup guide](https://github.com/nichochar/openclaw). Make sure the gateway is enabled:

In `~/.openclaw/openclaw.json`:

```json
{
  "gateway": {
    "port": 18789,
    "bind": "lan",
    "auth": {
      "mode": "token",
      "token": "your-gateway-token-here"
    },
    "http": {
      "endpoints": {
        "chatCompletions": { "enabled": true }
      }
    }
  }
}
```

Key settings:
- `bind: "lan"` -- exposes the gateway on your local network so your phone can reach it
- `chatCompletions.enabled: true` -- enables the `/v1/chat/completions` endpoint (off by default)
- `auth.token` -- the token your app will use to authenticate

### 2. Configure the app

**iOS** -- In `Secrets.swift`:
```swift
static let openClawHost = "http://Your-Mac.local"
static let openClawPort = 18789
static let openClawGatewayToken = "your-gateway-token-here"
```

**Android** -- In `Secrets.kt`:
```kotlin
const val openClawHost = "http://Your-Mac.local"
const val openClawPort = 18789
const val openClawGatewayToken = "your-gateway-token-here"
```

To find your Mac's Bonjour hostname: **System Settings > General > Sharing** -- it's shown at the top (e.g., `Johns-MacBook-Pro.local`).

> Both iOS and Android also have an in-app Settings screen where you can change these values at runtime without editing source code.

### 3. Start the gateway

```bash
openclaw gateway restart
```

Verify it's running:

```bash
curl http://localhost:18789/health
```

Now when you talk to the AI, it can execute tasks through OpenClaw.

---

## Setup: Grok OAuth Broker (Optional)

For a personal build, you can keep xAI/Grok OAuth on your remote host instead of storing an xAI API key in the mobile app. The sample WebRTC/signaling server exposes an authenticated token endpoint at:

```text
GET /api/grok/token
```

Configure the server with one of these token sources:

```bash
# Required for the phone to call /api/grok/token
export VISIONCLAW_AUTH_TOKEN="your-private-token"

# Option A: refresh an xAI OAuth token on the host
export XAI_OAUTH_REFRESH_TOKEN="..."
export XAI_OAUTH_CLIENT_ID="..."
# Optional, if your OAuth client requires it:
export XAI_OAUTH_CLIENT_SECRET="..."
# Optional fallback if the refresh request returns a transient 400/5xx:
export XAI_OAUTH_ACCESS_TOKEN="..."
export XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT="..."
# Optional OpenClaw auth-profile path to update after successful refresh:
export XAI_AUTH_STORE="$HOME/.openclaw-winston/agents/main/agent/auth-profiles.json"
export XAI_AUTH_PROFILE_ID="xai:you@example.com"

# Option B: use a host command that prints either a raw token or JSON
export XAI_OAUTH_TOKEN_COMMAND="your-command-that-prints-token"

# Option C: static bearer token, useful for quick testing
export XAI_OAUTH_ACCESS_TOKEN="..."
```

When both `XAI_OAUTH_REFRESH_TOKEN` and `XAI_OAUTH_ACCESS_TOKEN` are configured,
the broker refreshes first and only uses the access token as a short-lived
fallback if refresh fails. If `XAI_AUTH_STORE` is configured, successful
refreshes are written back to that auth store so restarts keep a current
fallback.

Then in the app Settings:

- Set **Auth Broker URL** to `https://your-host.example.com/api/grok/token`
- Set **Auth Broker Token** to `VISIONCLAW_AUTH_TOKEN`
- Leave **API Key** empty or as the placeholder

When the broker URL is configured, VisionClaw asks your host for a bearer token before opening the xAI realtime WebSocket and before summarizing camera frames. OpenClaw is still used separately for tool/action delegation.

If `VISIONCLAW_AUTH_TOKEN` is not set, the server also accepts `GROK_AUTH_BROKER_TOKEN` or `OPENCLAW_GATEWAY_TOKEN` as the broker auth token.

### Intended Host Exposure: Tailscale Serve

The intended personal deployment is to expose the broker privately through Tailscale Serve, not through a public tunnel. This keeps `/api/grok/token` reachable only by devices in your tailnet while still giving the iPhone app a normal HTTPS URL with a valid certificate.

On the host running the VisionClaw server:

```bash
cd samples/CameraAccess/server
npm install

export PORT=8080
export HOST=127.0.0.1
export VISIONCLAW_AUTH_TOKEN="your-private-token"
export XAI_OAUTH_REFRESH_TOKEN="..."
export XAI_OAUTH_CLIENT_ID="..."

npm start
```

In another shell on the same host:

```bash
tailscale serve --bg --https=443 127.0.0.1:8080
tailscale serve status
```

Use the HTTPS URL shown by `tailscale serve status` as the app's **Auth Broker URL**:

```text
https://<host>.<tailnet>.ts.net/api/grok/token
```

For example:

```text
https://winstons-mac-mini.tail5311f9.ts.net/api/grok/token
```

The iPhone must be connected to the same tailnet through the Tailscale app. Leave Tailscale Funnel disabled unless you intentionally want public internet access. If you use Cloudflare Tunnel instead, put Cloudflare Access or equivalent authentication in front of the endpoint; the broker returns a bearer token that can be used against xAI.

---

## Architecture

### Key Files (iOS)

All source code is in `samples/CameraAccess/CameraAccess/`:

| File | Purpose |
|------|---------|
| `Grok/GrokConfig.swift` | Grok auth, model config, system prompt |
| `Grok/GrokLiveService.swift` | WebSocket client for the xAI Voice Agent API |
| `Grok/AudioManager.swift` | Mic capture (PCM 16kHz) + audio playback (PCM 24kHz) |
| `Grok/GrokSessionViewModel.swift` | Session lifecycle, tool call wiring, transcript state |
| `Grok/DisplayHUDManager.swift` | Ray-Ban Display session and HUD card rendering |
| `Grok/WakeWordManager.swift` | Picovoice Porcupine wake-word listener |
| `OpenClaw/ToolCallModels.swift` | Tool declarations, data types |
| `OpenClaw/OpenClawBridge.swift` | HTTP client for OpenClaw gateway |
| `OpenClaw/ToolCallRouter.swift` | Routes Grok tool calls to OpenClaw |
| `iPhone/IPhoneCameraManager.swift` | AVCaptureSession wrapper for iPhone camera mode |
| `WebRTC/WebRTCClient.swift` | WebRTC peer connection + SDP negotiation |
| `WebRTC/SignalingClient.swift` | WebSocket signaling for WebRTC rooms |

### Key Files (Android)

All source code is in `samples/CameraAccessAndroid/app/src/main/java/.../cameraaccess/`:

| File | Purpose |
|------|---------|
| `grok/GrokConfig.kt` | Grok auth, model config, system prompt |
| `grok/GrokLiveService.kt` | OkHttp WebSocket client for the xAI Voice Agent API |
| `grok/AudioManager.kt` | AudioRecord (16kHz) + AudioTrack (24kHz) |
| `grok/GrokSessionViewModel.kt` | Session lifecycle, tool call wiring, UI state |
| `grok/DisplayHudManager.kt` | Ray-Ban Display session and HUD card rendering |
| `grok/WakeWordManager.kt` | Picovoice Porcupine wake-word listener |
| `openclaw/ToolCallModels.kt` | Tool declarations, data classes |
| `openclaw/OpenClawBridge.kt` | OkHttp HTTP client for OpenClaw gateway |
| `openclaw/ToolCallRouter.kt` | Routes Grok tool calls to OpenClaw |
| `phone/PhoneCameraManager.kt` | CameraX wrapper for phone camera mode |
| `webrtc/WebRTCClient.kt` | WebRTC peer connection (stream-webrtc-android) |
| `webrtc/SignalingClient.kt` | OkHttp WebSocket signaling for WebRTC rooms |
| `settings/SettingsManager.kt` | SharedPreferences with Secrets.kt fallback |

### Audio Pipeline

- **Input**: Phone mic -> AudioManager (PCM Int16, 16kHz mono, 100ms chunks) -> Grok WebSocket
- **Wake word**: Porcupine listens locally before a Grok session starts; the Grok mic takes over only after detection
- **Output**: Grok WebSocket -> AudioManager playback queue -> Phone speaker
- **iOS iPhone mode**: Uses `.voiceChat` audio session for echo cancellation + mic gating during AI speech
- **iOS Glasses mode**: Uses `.videoChat` audio session (mic is on glasses, speaker is on phone -- no echo)
- **Android**: Uses `VOICE_COMMUNICATION` audio source for built-in acoustic echo cancellation

### Video Pipeline

- **Glasses**: DAT SDK video stream (24fps) -> throttle to every ~3 seconds -> JPEG -> Grok image understanding -> visual context item in the voice session
- **Phone**: Camera capture (30fps) -> throttle to every ~3 seconds -> JPEG -> Grok image understanding -> visual context item in the voice session

### Tool Calling

Grok Voice Agent supports function calling. Both apps declare two client-side tools:

- `execute`: routes real-world tasks through OpenClaw.
- `display_hud`: writes a concise card to the Ray-Ban Display HUD.

1. User says "Add eggs to my shopping list"
2. Grok speaks "Sure, adding that now" (verbal acknowledgment before tool call)
3. Grok sends `response.function_call_arguments.done` for `execute(task: "Add eggs to the shopping list")`
4. `ToolCallRouter` sends HTTP POST to OpenClaw gateway
5. OpenClaw executes the task using its 56+ connected skills
6. Result returns to Grok as a `function_call_output` conversation item
7. Grok speaks the confirmation

### WebRTC Live Streaming

Share your glasses POV in real-time to a browser viewer with bidirectional audio and video.

1. Tap the **Live** button in the app
2. The app connects to a signaling server and gets a 6-character room code
3. Share the code -- the viewer opens the server URL in a browser and enters it
4. WebRTC peer connection is established (SDP + ICE via the signaling server)
5. Media flows peer-to-peer: glasses video to browser, browser camera back to iOS PiP

**Key details:**
- **Signaling server**: Node.js + WebSocket, located at `samples/CameraAccess/server/` -- serves the browser viewer and relays SDP/ICE
- **NAT traversal**: Google STUN servers + ExpressTURN relay (fetched from `/api/turn`)
- **Video**: 24 fps, 2.5 Mbps max bitrate
- **Background handling**: 60-second grace period for iOS app backgrounding -- room stays alive for reconnection
- **Constraint**: Cannot run simultaneously with a Grok voice session (audio device conflict)

For full details, see [`samples/CameraAccess/CameraAccess/WebRTC/README.md`](samples/CameraAccess/CameraAccess/WebRTC/README.md).

---

## Requirements

### iOS
- iOS 17.0+
- Xcode 15.0+
- Grok API key or Grok auth broker
- Picovoice AccessKey (optional -- for wake word)
- Meta Ray-Ban glasses (optional -- use iPhone mode for testing)
- Meta Ray-Ban Display glasses (optional -- for HUD support)
- OpenClaw on your Mac (optional -- for agentic actions)

### Android
- Android 14+ (API 34+)
- Android Studio Ladybug or newer
- GitHub account with `read:packages` token (for DAT SDK)
- Grok API key or Grok auth broker
- Picovoice AccessKey (optional -- for wake word)
- Meta Ray-Ban glasses (optional -- use Phone mode for testing)
- Meta Ray-Ban Display glasses (optional -- for HUD support)
- OpenClaw on your Mac (optional -- for agentic actions)

---

## Troubleshooting

### General

**Grok doesn't hear me** -- Check that microphone permission is granted. The app uses aggressive voice activity detection -- speak clearly and at normal volume.

**Wake word does not start** -- Make sure Wake Word is enabled, the Picovoice AccessKey is set, microphone permission is granted, and any custom `.ppn` matches the platform you are running on.

**OpenClaw connection timeout** -- Make sure your phone and Mac are on the same Wi-Fi network, the gateway is running (`openclaw gateway restart`), and the hostname matches your Mac's Bonjour name.

**OpenClaw opens duplicate browser tabs** -- This is a known upstream issue in OpenClaw's CDP (Chrome DevTools Protocol) connection management ([#13851](https://github.com/nichochar/openclaw/issues/13851), [#12317](https://github.com/nichochar/openclaw/issues/12317)). Using `profile: "openclaw"` (managed Chrome) instead of the default extension relay may improve stability.

### iOS-specific

**"Grok auth not configured"** -- Add a Grok API key or Grok auth broker URL in Secrets.swift / Secrets.kt or in the in-app Settings.

**Echo/feedback in iPhone mode** -- The app mutes the mic while the AI is speaking. If you still hear echo, try turning down the volume.

### Android-specific

**Gradle sync fails with 401 Unauthorized** -- Your GitHub token is missing or doesn't have `read:packages` scope. Set `GITHUB_TOKEN` in your shell or add `github_token=...` to `samples/CameraAccessAndroid/local.properties`. Generate a new token at [github.com/settings/tokens](https://github.com/settings/tokens).

**Grok WebSocket times out** -- Check that your xAI bearer source is valid and that your network allows WebSocket connections to `wss://api.x.ai/v1/realtime`.

**Audio not working** -- Ensure `RECORD_AUDIO` permission is granted. On Android 13+, you may need to grant this permission manually in Settings > Apps.

**Phone camera not starting** -- Ensure `CAMERA` permission is granted. CameraX requires both the permission and a valid lifecycle.

For DAT SDK issues, see the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or the [discussions forum](https://github.com/facebook/meta-wearables-dat-ios/discussions).

## Citation

If you use VisionClaw in your research, please cite our paper:

```bibtex
@article{liu2026visionclaw,
  title={VisionClaw: Always-On AI Agents through Smart Glasses},
  author={Liu, Xiaoan and Lee, DaeHo and Gonzalez, Eric J and Gonzalez-Franco, Mar and Suzuki, Ryo},
  journal={arXiv preprint arXiv:2604.03486},
  year={2026}
}
```

## License

This source code is licensed under the license found in the [LICENSE](LICENSE) file in the root directory of this source tree.
