/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// StreamSessionViewModel.swift
//
// Core view model demonstrating video streaming from Meta wearable devices using the DAT SDK.
// This class showcases the key streaming patterns: device selection, session management,
// video frame handling, photo capture, and error handling.
//

import CoreImage
import CoreMedia
import CoreVideo
import Foundation
import MWDATCamera
import MWDATCore
import SwiftUI
import VideoToolbox

enum StreamingStatus {
  case streaming
  case waiting
  case stopped
}

enum StreamingMode {
  case glasses
  case iPhone
}

@MainActor
class StreamSessionViewModel: ObservableObject {
  @Published var currentVideoFrame: UIImage?
  @Published var hasReceivedFirstFrame: Bool = false
  @Published var streamingStatus: StreamingStatus = .stopped
  @Published var showError: Bool = false
  @Published var errorMessage: String = ""
  @Published var hasActiveDevice: Bool = false
  @Published var streamingMode: StreamingMode = .glasses
  @Published var selectedResolution: StreamingResolution = .low

  var isStreaming: Bool {
    streamingStatus != .stopped
  }

  var activeDeviceSession: DeviceSession? {
    guard let deviceSession, deviceSession.state == .started else { return nil }
    return deviceSession
  }

  var displayDeviceSession: DeviceSession? {
    deviceSession
  }

  var resolutionLabel: String {
    switch selectedResolution {
    case .low: return "360x640"
    case .medium: return "504x896"
    case .high: return "720x1280"
    @unknown default: return "Unknown"
    }
  }

  @Published var capturedPhoto: UIImage?
  @Published var showPhotoPreview: Bool = false
  @Published var showPhotoCaptureError: Bool = false
  @Published var isCapturingPhoto: Bool = false

  // Grok voice integration
  var grokSessionVM: GrokSessionViewModel?

  // WebRTC Live streaming integration
  var webrtcSessionVM: WebRTCSessionViewModel?

  private let wearables: WearablesInterface
  private let deviceSelector: AutoDeviceSelector
  private var deviceSession: DeviceSession?
  private var stream: MWDATCamera.Stream?

  private var stateListenerToken: AnyListenerToken?
  private var videoFrameListenerToken: AnyListenerToken?
  private var errorListenerToken: AnyListenerToken?
  private var photoDataListenerToken: AnyListenerToken?
  private var deviceMonitorTask: Task<Void, Never>?
  private var sessionStateTask: Task<Void, Never>?
  private var sessionErrorTask: Task<Void, Never>?
  private var iPhoneCameraManager: IPhoneCameraManager?

  // CPU-based CIContext for rendering decoded pixel buffers in background
  private let cpuCIContext = CIContext(options: [.useSoftwareRenderer: true])
  // VideoDecoder for decompressing HEVC/H.264 frames in background
  private let videoDecoder = VideoDecoder()
  private var backgroundFrameCount = 0
  private var bgDiagLogged = false

  init(wearables: WearablesInterface) {
    self.wearables = wearables
    self.deviceSelector = AutoDeviceSelector(wearables: wearables)

    deviceMonitorTask = Task { @MainActor [weak self] in
      guard let self else { return }
      for await device in deviceSelector.activeDeviceStream() {
        self.hasActiveDevice = device != nil
      }
    }

    setupVideoDecoder()
  }

  deinit {
    deviceMonitorTask?.cancel()
    sessionStateTask?.cancel()
    sessionErrorTask?.cancel()
  }

  private func setupVideoDecoder() {
    videoDecoder.setFrameCallback { [weak self] decodedFrame in
      Task { @MainActor [weak self] in
        guard let self else { return }
        let pixelBuffer = decodedFrame.pixelBuffer
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let rect = CGRect(x: 0, y: 0, width: width, height: height)
        if let cgImage = self.cpuCIContext.createCGImage(ciImage, from: rect) {
          let image = UIImage(cgImage: cgImage)
          self.grokSessionVM?.sendVideoFrameIfThrottled(image: image)
          self.webrtcSessionVM?.pushVideoFrame(image)
          if self.backgroundFrameCount <= 5 || self.backgroundFrameCount % 120 == 0 {
            NSLog(
              "[Stream] Background frame #%d decoded and forwarded (%dx%d)",
              self.backgroundFrameCount,
              width,
              height
            )
          }
        }
      }
    }
  }

  /// Changes the requested glasses camera resolution. Only call while not streaming.
  func updateResolution(_ resolution: StreamingResolution) {
    guard !isStreaming else { return }
    selectedResolution = resolution
    NSLog("[Stream] Resolution changed to %@", resolutionLabel)
  }

  func handleStartStreaming() async {
    let permission = Permission.camera
    do {
      var status = try await wearables.checkPermissionStatus(permission)
      if status != .granted {
        status = try await wearables.requestPermission(permission)
      }
      guard status == .granted else {
        showError("Permission denied")
        return
      }
      await startSession()
    } catch {
      showError("Permission error: \(error.localizedDescription)")
    }
  }

  func startSession() async {
    streamingMode = .glasses

    do {
      let session = try await startedDeviceSession()
      guard session.state == .started else {
        showError("Device session is not ready. Please try again.")
        return
      }

      let config = StreamConfiguration(
        videoCodec: VideoCodec.raw,
        resolution: selectedResolution,
        frameRate: 24
      )

      guard let newStream = try session.addStream(config: config) else {
        throw Self.sessionStartError("The camera stream could not be created.")
      }
      stream = newStream
      streamingStatus = .waiting
      setupListeners(for: newStream)
      await newStream.start()
    } catch DeviceSessionError.datAppOnTheGlassesUpdateRequired {
      showError(DeviceSessionError.datAppOnTheGlassesUpdateRequired.localizedDescription)
    } catch {
      showError("Failed to start stream: \(error.localizedDescription)")
    }
  }

  func stopSession() async {
    if streamingMode == .iPhone {
      stopIPhoneSession()
      return
    }

    guard let activeStream = stream else {
      resetStreamState()
      return
    }

    stream = nil
    clearStreamListeners()
    resetStreamState()
    await activeStream.stop()
  }

  func endSession() {
    stream = nil
    clearStreamListeners()
    resetStreamState()
    sessionStateTask?.cancel()
    sessionStateTask = nil
    sessionErrorTask?.cancel()
    sessionErrorTask = nil
    deviceSession?.stop()
    deviceSession = nil
  }

  func capturePhoto() {
    guard streamingMode == .glasses,
          !isCapturingPhoto,
          streamingStatus == .streaming else {
      showPhotoCaptureError = true
      return
    }

    isCapturingPhoto = true
    let didStartCapture = stream?.capturePhoto(format: .jpeg) ?? false
    if !didStartCapture {
      isCapturingPhoto = false
      showPhotoCaptureError = true
    }
  }

  func dismissError() {
    showError = false
    errorMessage = ""
  }

  func dismissPhotoCaptureError() {
    showPhotoCaptureError = false
  }

  func dismissPhotoPreview() {
    showPhotoPreview = false
    capturedPhoto = nil
  }

  // MARK: - iPhone Camera Mode

  func handleStartIPhone() async {
    let granted = await IPhoneCameraManager.requestPermission()
    if granted {
      startIPhoneSession()
    } else {
      showError("Camera permission denied. Please grant access in Settings.")
    }
  }

  private func startIPhoneSession() {
    streamingMode = .iPhone
    let camera = IPhoneCameraManager()
    camera.onFrameCaptured = { [weak self] image in
      Task { @MainActor [weak self] in
        guard let self else { return }
        self.currentVideoFrame = image
        if !self.hasReceivedFirstFrame {
          self.hasReceivedFirstFrame = true
        }
        self.grokSessionVM?.sendVideoFrameIfThrottled(image: image)
        self.webrtcSessionVM?.pushVideoFrame(image)
      }
    }
    camera.start()
    iPhoneCameraManager = camera
    streamingStatus = .streaming
    NSLog("[Stream] iPhone camera mode started")
  }

  private func stopIPhoneSession() {
    iPhoneCameraManager?.stop()
    iPhoneCameraManager = nil
    resetStreamState()
    streamingMode = .glasses
    NSLog("[Stream] iPhone camera mode stopped")
  }

  // MARK: - DAT 0.7 Session + Stream

  private func startedDeviceSession() async throws -> DeviceSession {
    if let session = deviceSession, session.state == .started {
      return session
    }

    if deviceSession?.state == .stopped {
      deviceSession = nil
    }

    if let session = deviceSession {
      try await waitForSessionStart(session: session)
      return session
    }

    let session = try wearables.createSession(deviceSelector: deviceSelector)
    deviceSession = session
    observeDeviceSession(session)
    try await startDeviceSession(session)
    return session
  }

  private func startDeviceSession(_ session: DeviceSession) async throws {
    let stateStream = session.stateStream()
    let errorStream = session.errorStream()
    try session.start()

    if session.state == .started {
      return
    }

    try await waitForSessionStart(stateStream: stateStream, errorStream: errorStream)
  }

  private func waitForSessionStart(session: DeviceSession) async throws {
    if session.state == .started {
      return
    }

    try await waitForSessionStart(
      stateStream: session.stateStream(),
      errorStream: session.errorStream()
    )
  }

  private func waitForSessionStart(
    stateStream: AsyncStream<DeviceSessionState>,
    errorStream: AsyncStream<DeviceSessionError>
  ) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
      group.addTask {
        for await state in stateStream {
          if state == .started {
            return
          }
          if state == .stopped {
            throw Self.sessionStartError("The device session stopped before it was ready.")
          }
        }
        throw Self.sessionStartError("The device session did not report a ready state.")
      }

      group.addTask {
        for await error in errorStream {
          throw error
        }
        throw Self.sessionStartError("The device session failed before it was ready.")
      }

      _ = try await group.next()
      group.cancelAll()
    }
  }

  private func observeDeviceSession(_ session: DeviceSession) {
    sessionStateTask?.cancel()
    sessionErrorTask?.cancel()

    sessionStateTask = Task { @MainActor [weak self] in
      for await state in session.stateStream() {
        guard let self else { return }
        if state == .stopped {
          self.deviceSession = nil
          if self.streamingMode == .glasses {
            self.stream = nil
            self.clearStreamListeners()
            self.resetStreamState()
          }
        }
      }
    }

    sessionErrorTask = Task { @MainActor [weak self] in
      for await error in session.errorStream() {
        guard let self else { return }
        self.showError(error.localizedDescription)
      }
    }
  }

  nonisolated private static func sessionStartError(_ message: String) -> NSError {
    NSError(
      domain: "VisionClaw.DeviceSession",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: message]
    )
  }

  private func setupListeners(for stream: MWDATCamera.Stream) {
    clearStreamListeners()

    stateListenerToken = stream.statePublisher.listen { [weak self] state in
      Task { @MainActor [weak self] in
        self?.handleStateChange(state)
      }
    }

    videoFrameListenerToken = stream.videoFramePublisher.listen { [weak self] videoFrame in
      Task { @MainActor [weak self] in
        self?.handleVideoFrame(videoFrame)
      }
    }

    errorListenerToken = stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor [weak self] in
        guard let self else { return }
        self.showError(error.localizedDescription)
      }
    }

    photoDataListenerToken = stream.photoDataPublisher.listen { [weak self] photoData in
      Task { @MainActor [weak self] in
        self?.handlePhotoData(photoData)
      }
    }
  }

  private func clearStreamListeners() {
    stateListenerToken = nil
    videoFrameListenerToken = nil
    errorListenerToken = nil
    photoDataListenerToken = nil
  }

  private func handleStateChange(_ state: StreamState) {
    switch state {
    case .stopped:
      resetStreamState()
    case .waitingForDevice, .starting, .stopping, .paused:
      streamingStatus = .waiting
    case .streaming:
      streamingStatus = .streaming
    }
  }

  private func handleVideoFrame(_ videoFrame: VideoFrame) {
    let isInBackground = UIApplication.shared.applicationState == .background

    if !isInBackground {
      backgroundFrameCount = 0
      bgDiagLogged = false
      if let image = videoFrame.makeUIImage() {
        currentVideoFrame = image
        if !hasReceivedFirstFrame {
          hasReceivedFirstFrame = true
        }
        grokSessionVM?.sendVideoFrameIfThrottled(image: image)
        webrtcSessionVM?.pushVideoFrame(image)
      }
      return
    }

    // In background: makeUIImage() uses VideoToolbox GPU rendering which iOS suspends.
    // Instead, use our VideoDecoder (VTDecompressionSession) to decode compressed
    // frames into pixel buffers, then convert via CPU CIContext.
    backgroundFrameCount += 1

    let sampleBuffer = videoFrame.sampleBuffer
    let hasCompressedData = CMSampleBufferGetDataBuffer(sampleBuffer) != nil

    if hasCompressedData {
      do {
        try videoDecoder.decode(sampleBuffer)
      } catch {
        if backgroundFrameCount <= 5 || backgroundFrameCount % 120 == 0 {
          NSLog(
            "[Stream] Background frame #%d decode error: %@",
            backgroundFrameCount,
            String(describing: error)
          )
        }
      }
    } else if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
      let width = CVPixelBufferGetWidth(pixelBuffer)
      let height = CVPixelBufferGetHeight(pixelBuffer)
      let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
      let rect = CGRect(x: 0, y: 0, width: width, height: height)
      if let cgImage = cpuCIContext.createCGImage(ciImage, from: rect) {
        let image = UIImage(cgImage: cgImage)
        grokSessionVM?.sendVideoFrameIfThrottled(image: image)
        webrtcSessionVM?.pushVideoFrame(image)
      }
      videoDecoder.invalidateSession()
    }
  }

  private func handlePhotoData(_ photoData: PhotoData) {
    isCapturingPhoto = false
    if let uiImage = UIImage(data: photoData.data) {
      capturedPhoto = uiImage
      showPhotoPreview = true
    }
  }

  private func resetStreamState() {
    currentVideoFrame = nil
    hasReceivedFirstFrame = false
    streamingStatus = .stopped
    isCapturingPhoto = false
    backgroundFrameCount = 0
    bgDiagLogged = false
    videoDecoder.invalidateSession()
  }

  private func showError(_ message: String) {
    errorMessage = message
    showError = true
  }
}
