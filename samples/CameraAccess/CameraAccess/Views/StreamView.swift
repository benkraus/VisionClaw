/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// StreamView.swift
//
// Main UI for video streaming from Meta wearable devices using the DAT SDK.
// This view demonstrates the complete streaming API: video streaming with real-time display, photo capture,
// and error handling. Extended with Grok voice assistant and WebRTC live streaming integration.
//

import MWDATCore
import SwiftUI

struct StreamView: View {
  @ObservedObject var viewModel: StreamSessionViewModel
  @ObservedObject var wearablesVM: WearablesViewModel
  @ObservedObject var grokVM: GrokSessionViewModel
  @ObservedObject var webrtcVM: WebRTCSessionViewModel

  var body: some View {
    ZStack {
      // Black background for letterboxing/pillarboxing
      Color.black
        .edgesIgnoringSafeArea(.all)

      // Video backdrop: PiP when WebRTC connected, otherwise single local feed
      if webrtcVM.isActive && webrtcVM.connectionState == .connected {
        PiPVideoView(
          localFrame: viewModel.currentVideoFrame,
          remoteVideoTrack: webrtcVM.remoteVideoTrack,
          hasRemoteVideo: webrtcVM.hasRemoteVideo
        )
      } else if let videoFrame = viewModel.currentVideoFrame, viewModel.hasReceivedFirstFrame {
        GeometryReader { geometry in
          Image(uiImage: videoFrame)
            .resizable()
            .aspectRatio(contentMode: .fill)
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .edgesIgnoringSafeArea(.all)
      } else {
        ProgressView()
          .scaleEffect(1.5)
          .foregroundColor(.white)
      }

      // Grok status overlay (top) + speaking indicator
      if grokVM.isGrokActive || grokVM.isWakeWordListening {
        VStack {
          GrokStatusBar(grokVM: grokVM)
          Spacer()

          VStack(spacing: 8) {
            if !grokVM.userTranscript.isEmpty || !grokVM.aiTranscript.isEmpty {
              TranscriptView(
                userText: grokVM.userTranscript,
                aiText: grokVM.aiTranscript
              )
            }

            ToolCallStatusView(status: grokVM.toolCallStatus)

            if grokVM.isModelSpeaking {
              HStack(spacing: 8) {
                Image(systemName: "speaker.wave.2.fill")
                  .foregroundColor(.white)
                  .font(.system(size: 14))
                SpeakingIndicator()
              }
              .padding(.horizontal, 16)
              .padding(.vertical, 8)
              .background(Color.black.opacity(0.5))
              .cornerRadius(20)
            }
          }
          .padding(.bottom, 80)
        }
        .padding(.all, 24)
      }

      // WebRTC status overlay (top)
      if webrtcVM.isActive {
        VStack {
          WebRTCStatusBar(webrtcVM: webrtcVM)
          Spacer()
        }
        .padding(.all, 24)
      }

      // Bottom controls layer
      VStack {
        Spacer()
        ControlsView(viewModel: viewModel, grokVM: grokVM, webrtcVM: webrtcVM)
      }
      .padding(.all, 24)
    }
    .onAppear {
      grokVM.useDisplayDeviceSession(viewModel.displayDeviceSession)
      if SettingsManager.shared.wakeWordEnabled, !grokVM.isGrokActive, !grokVM.isWakeWordListening, !webrtcVM.isActive {
        grokVM.startWakeWordListening()
      }
    }
    .onDisappear {
      Task {
        if viewModel.streamingStatus != .stopped {
          await viewModel.stopSession()
        }
        grokVM.stopWakeWordListening()
        if grokVM.isGrokActive {
          grokVM.stopSession(resumeWakeWord: false)
        }
        if webrtcVM.isActive {
          webrtcVM.stopSession()
        }
      }
    }
    // Show captured photos from DAT SDK in a preview sheet
    .sheet(isPresented: $viewModel.showPhotoPreview) {
      if let photo = viewModel.capturedPhoto {
        PhotoPreviewView(
          photo: photo,
          onDismiss: {
            viewModel.dismissPhotoPreview()
          }
        )
      }
    }
    // Grok error alert
    .alert("AI Assistant", isPresented: Binding(
      get: { grokVM.errorMessage != nil },
      set: { if !$0 { grokVM.errorMessage = nil } }
    )) {
      Button("OK") { grokVM.errorMessage = nil }
    } message: {
      Text(grokVM.errorMessage ?? "")
    }
    // WebRTC error alert
    .alert("Live Stream", isPresented: Binding(
      get: { webrtcVM.errorMessage != nil },
      set: { if !$0 { webrtcVM.errorMessage = nil } }
    )) {
      Button("OK") { webrtcVM.errorMessage = nil }
    } message: {
      Text(webrtcVM.errorMessage ?? "")
    }
  }
}

// Extracted controls for clarity
struct ControlsView: View {
  @ObservedObject var viewModel: StreamSessionViewModel
  @ObservedObject var grokVM: GrokSessionViewModel
  @ObservedObject var webrtcVM: WebRTCSessionViewModel

  var body: some View {
    // Controls row
    HStack(spacing: 8) {
      CustomButton(
        title: "Stop streaming",
        style: .destructive,
        isDisabled: false
      ) {
        Task {
          grokVM.stopWakeWordListening()
          if grokVM.isGrokActive {
            grokVM.stopSession(resumeWakeWord: false)
          }
          await viewModel.stopSession()
        }
      }

      // Photo button (glasses mode only -- DAT SDK capture)
      if viewModel.streamingMode == .glasses {
        CircleButton(icon: "camera.fill", text: nil) {
          viewModel.capturePhoto()
        }
      }

      // Grok AI button (disabled when WebRTC is active — audio conflict)
      CircleButton(
        icon: grokVM.isGrokActive || grokVM.isWakeWordListening ? "waveform.circle.fill" : "waveform.circle",
        text: grokVM.isWakeWordListening ? "Wake" : "AI"
      ) {
        Task {
          if grokVM.isGrokActive {
            grokVM.stopSession()
          } else if grokVM.isWakeWordListening {
            grokVM.stopWakeWordListening()
          } else {
            grokVM.useDisplayDeviceSession(viewModel.displayDeviceSession)
            if SettingsManager.shared.wakeWordEnabled {
              grokVM.startWakeWordListening()
            } else {
              await grokVM.startSession()
            }
          }
        }
      }
      .opacity(webrtcVM.isActive ? 0.4 : 1.0)
      .disabled(webrtcVM.isActive)

      // WebRTC Live Stream button (disabled when Grok is active — audio conflict)
      CircleButton(
        icon: webrtcVM.isActive
          ? "antenna.radiowaves.left.and.right.circle.fill"
          : "antenna.radiowaves.left.and.right.circle",
        text: "Live"
      ) {
        Task {
          if webrtcVM.isActive {
            webrtcVM.stopSession()
          } else {
            await webrtcVM.startSession()
          }
        }
      }
      .opacity(grokVM.isGrokActive || grokVM.isWakeWordListening ? 0.4 : 1.0)
      .disabled(grokVM.isGrokActive || grokVM.isWakeWordListening)
    }
  }
}
