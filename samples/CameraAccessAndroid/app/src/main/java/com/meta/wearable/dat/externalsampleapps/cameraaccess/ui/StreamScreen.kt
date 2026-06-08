/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.grok.GrokSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    grokViewModel: GrokSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val grokUiState by grokViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val latestGrokUiState by rememberUpdatedState(grokUiState)
    val latestWebRTCUiState by rememberUpdatedState(webrtcUiState)

    // Wire Grok VM to Stream VM for frame forwarding
    LaunchedEffect(grokViewModel) {
        streamViewModel.grokViewModel = grokViewModel
        grokViewModel.setDisplayDeviceSession(streamViewModel.currentDeviceSession)
    }

    // Wire WebRTC VM to Stream VM for frame forwarding
    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            grokViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            grokViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
        grokViewModel.setDisplayDeviceSession(streamViewModel.currentDeviceSession)
        if (SettingsManager.wakeWordEnabled) {
            grokViewModel.startWakeWordListening(context)
        }
    }

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
            grokViewModel.stopWakeWordListening()
            if (latestGrokUiState.isGrokActive) {
                grokViewModel.stopSession(resumeWakeWord = false)
            }
            if (latestWebRTCUiState.isActive) {
                webrtcViewModel.stopSession()
            }
        }
    }

    // Show errors as toasts
    LaunchedEffect(grokUiState.errorMessage) {
        grokUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            grokViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Video feed
        streamUiState.videoFrame?.let { videoFrame ->
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (streamUiState.streamState == StreamState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Overlays + controls
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Top overlays (below status bar)
            Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp)) {
                // Grok overlay
                if (grokUiState.isGrokActive || grokUiState.isWakeWordListening) {
                    GrokOverlay(uiState = grokUiState)
                }

                // WebRTC overlay
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
            }

            // Controls at bottom
            ControlsRow(
                onStopStream = {
                    grokViewModel.stopWakeWordListening()
                    if (grokUiState.isGrokActive) grokViewModel.stopSession(resumeWakeWord = false)
                    if (webrtcUiState.isActive) webrtcViewModel.stopSession()
                    streamViewModel.stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                },
                onCapturePhoto = { streamViewModel.capturePhoto() },
                onToggleAI = {
                    if (grokUiState.isGrokActive) {
                        grokViewModel.stopSession()
                    } else if (grokUiState.isWakeWordListening) {
                        grokViewModel.stopWakeWordListening()
                    } else {
                        grokViewModel.setDisplayDeviceSession(streamViewModel.currentDeviceSession)
                        if (SettingsManager.wakeWordEnabled) {
                            grokViewModel.startWakeWordListening(context)
                        } else {
                            grokViewModel.startSession()
                        }
                    }
                },
                isAIActive = grokUiState.isGrokActive || grokUiState.isWakeWordListening,
                onToggleLive = {
                    if (webrtcUiState.isActive) {
                        webrtcViewModel.stopSession()
                    } else if (!grokUiState.isGrokActive) {
                        grokViewModel.stopWakeWordListening()
                        webrtcViewModel.startSession()
                    }
                },
                isLiveActive = webrtcUiState.isActive,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // Share photo dialog
    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}
