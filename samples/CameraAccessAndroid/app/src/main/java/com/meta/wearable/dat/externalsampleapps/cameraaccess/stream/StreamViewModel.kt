/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing device sessions with wearable devices
// - Attaching camera streams to a session
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Forwarding frames to Grok and WebRTC integrations

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.externalsampleapps.cameraaccess.grok.GrokSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val TERMINAL_STREAM_STATES = setOf(StreamState.CLOSED)
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null
  private var stream: Stream? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var previousDeviceSessionState: DeviceSessionState? = null

  // VisionClaw additions
  var grokViewModel: GrokSessionViewModel? = null
    set(value) {
      field = value
      value?.setDisplayDeviceSession(currentDeviceSession)
    }
  var webrtcViewModel: WebRTCSessionViewModel? = null
  private var phoneCameraManager: PhoneCameraManager? = null

  val currentDeviceSession: DeviceSession?
    get() = session?.takeIf { it.state.value == DeviceSessionState.STARTED }

  fun startStream() {
    stopJobsOnly()
    phoneCameraManager?.stop()
    phoneCameraManager = null
    previousDeviceSessionState = null

    StreamingService.start(getApplication())
    _uiState.update {
      INITIAL_STATE.copy(
          streamingMode = StreamingMode.GLASSES,
          streamState = StreamState.STARTING,
      )
    }

    if (session == null) {
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            grokViewModel?.setDisplayDeviceSession(createdSession)
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            createdSession.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            handleSessionError(error)
          }
      if (session == null) return
    }

    startStreamInternal()
  }

  private fun startStreamInternal() {
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val previousState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        when (currentState) {
          DeviceSessionState.STARTED -> {
            grokViewModel?.setDisplayDeviceSession(session)
            if (previousState == DeviceSessionState.PAUSED && stream != null) {
              Log.d(TAG, "Session resumed from PAUSED; keeping existing stream")
              return@collect
            }
            attachCameraStream()
          }
          DeviceSessionState.PAUSED -> {
            Log.d(TAG, "Session paused; keeping stream for resume")
          }
          DeviceSessionState.STOPPED -> {
            if (previousState != null) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
          else -> Unit
        }
      }
    }
  }

  private fun attachCameraStream() {
    videoJob?.cancel()
    streamStateJob?.cancel()
    streamErrorJob?.cancel()
    stream?.stop()
    stream = null

    session
        ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))
        ?.onSuccess { addedStream ->
          stream = addedStream
          videoJob = viewModelScope.launch {
            addedStream.videoStream.collect { handleVideoFrame(it) }
          }
          streamStateJob = viewModelScope.launch {
            addedStream.state.collect { currentState ->
              val previousState = _uiState.value.streamState
              _uiState.update { it.copy(streamState = currentState) }

              val wasActive = previousState !in TERMINAL_STREAM_STATES
              val isTerminated = currentState in TERMINAL_STREAM_STATES
              if (wasActive && isTerminated) {
                stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              }
            }
          }
          streamErrorJob = viewModelScope.launch {
            addedStream.errorStream.collect { error ->
              Log.d(TAG, "Stream error received: $error (${error.description})")
              if (error == StreamError.STREAM_ERROR) return@collect
              wearablesViewModel.setRecentError(error.description)
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
          addedStream.start()
        }
        ?.onFailure { error, _ ->
          Log.e(TAG, "Failed to add stream to session: ${error.description}")
          wearablesViewModel.setRecentError(error.description)
          stopStream()
          wearablesViewModel.navigateToDeviceSelection()
        }
  }

  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    stopJobsOnly()
    stream?.stop()
    stream = null
    session?.stop()
    session = null
    grokViewModel?.setDisplayDeviceSession(null)
    StreamingService.start(getApplication())

    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager

    manager.onFrameCaptured = { bitmap ->
      publishFrame(bitmap)
    }

    _uiState.update {
      INITIAL_STATE.copy(
          streamingMode = StreamingMode.PHONE,
          streamState = StreamState.STREAMING,
      )
    }
    manager.start(lifecycleOwner)
    Log.d(TAG, "Phone camera mode started")
  }

  fun stopStream() {
    StreamingService.stop(getApplication())
    stopJobsOnly()
    stream?.stop()
    stream = null
    session?.stop()
    session = null
    grokViewModel?.setDisplayDeviceSession(null)
    phoneCameraManager?.stop()
    phoneCameraManager = null
    _uiState.update { INITIAL_STATE }
  }

  private fun stopJobsOnly() {
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamState != StreamState.STREAMING) {
      Log.w(TAG, "Cannot capture photo: stream not active (state=${uiState.value.streamState})")
      return
    }

    if (uiState.value.streamingMode == StreamingMode.PHONE) {
      uiState.value.videoFrame?.let { frame ->
        _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
      }
      return
    }

    Log.d(TAG, "Starting photo capture")
    _uiState.update { it.copy(isCapturing = true) }

    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo capture successful")
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update { it.copy(isCapturing = false) }
          }
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to share photo", e)
    }
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    wearablesViewModel.setRecentError(error.description)
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val jpeg =
        ByteArrayOutputStream().use { stream ->
          image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    if (bitmap == null) {
      Log.w(TAG, "Failed to decode stream frame")
      return
    }
    publishFrame(bitmap)
  }

  private fun publishFrame(bitmap: Bitmap) {
    _uiState.update { it.copy(videoFrame = bitmap) }
    grokViewModel?.sendVideoFrameIfThrottled(bitmap)
    webrtcViewModel?.pushVideoFrame(bitmap)
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU).
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size)

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n]
      output[size + n * 2 + 1] = input[size + n]
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
        ?: throw IOException("Unable to decode HEIC photo")
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> Unit
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
