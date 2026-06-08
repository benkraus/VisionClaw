package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var grokAPIKey by remember { mutableStateOf(SettingsManager.grokAPIKey) }
    var grokAuthBrokerURL by remember { mutableStateOf(SettingsManager.grokAuthBrokerURL) }
    var grokAuthBrokerToken by remember { mutableStateOf(SettingsManager.grokAuthBrokerToken) }
    var grokVoice by remember { mutableStateOf(SettingsManager.grokVoice) }
    var systemPrompt by remember { mutableStateOf(SettingsManager.grokSystemPrompt) }
    var soulPrompt by remember { mutableStateOf(SettingsManager.grokSoulPrompt) }
    var openClawHost by remember { mutableStateOf(SettingsManager.openClawHost) }
    var openClawPort by remember { mutableStateOf(SettingsManager.openClawPort.toString()) }
    var openClawHookToken by remember { mutableStateOf(SettingsManager.openClawHookToken) }
    var openClawGatewayToken by remember { mutableStateOf(SettingsManager.openClawGatewayToken) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var videoStreamingEnabled by remember { mutableStateOf(SettingsManager.videoStreamingEnabled) }
    var visionSummariesEnabled by remember { mutableStateOf(SettingsManager.visionSummariesEnabled) }
    var displayHUDEnabled by remember { mutableStateOf(SettingsManager.displayHUDEnabled) }
    var proactiveNotificationsEnabled by remember { mutableStateOf(SettingsManager.proactiveNotificationsEnabled) }
    var porcupineAccessKey by remember { mutableStateOf(SettingsManager.porcupineAccessKey) }
    var wakeWordEnabled by remember { mutableStateOf(SettingsManager.wakeWordEnabled) }
    var wakeWordBuiltInKeyword by remember { mutableStateOf(SettingsManager.wakeWordBuiltInKeyword) }
    var wakeWordKeywordPath by remember { mutableStateOf(SettingsManager.wakeWordKeywordPath) }
    var wakeWordSensitivity by remember { mutableStateOf(SettingsManager.wakeWordSensitivity) }
    var wakeWordAutoResume by remember { mutableStateOf(SettingsManager.wakeWordAutoResume) }
    var showResetDialog by remember { mutableStateOf(false) }

    fun save() {
        SettingsManager.grokAPIKey = grokAPIKey.trim()
        SettingsManager.grokAuthBrokerURL = grokAuthBrokerURL.trim()
        SettingsManager.grokAuthBrokerToken = grokAuthBrokerToken.trim()
        SettingsManager.grokVoice = grokVoice.trim().ifEmpty { "eve" }
        SettingsManager.grokSystemPrompt = systemPrompt.trim()
        SettingsManager.grokSoulPrompt = soulPrompt.trim()
        SettingsManager.openClawHost = openClawHost.trim()
        openClawPort.trim().toIntOrNull()?.let { SettingsManager.openClawPort = it }
        SettingsManager.openClawHookToken = openClawHookToken.trim()
        SettingsManager.openClawGatewayToken = openClawGatewayToken.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.videoStreamingEnabled = videoStreamingEnabled
        SettingsManager.visionSummariesEnabled = visionSummariesEnabled
        SettingsManager.displayHUDEnabled = displayHUDEnabled
        SettingsManager.proactiveNotificationsEnabled = proactiveNotificationsEnabled
        SettingsManager.porcupineAccessKey = porcupineAccessKey.trim()
        SettingsManager.wakeWordEnabled = wakeWordEnabled
        SettingsManager.wakeWordBuiltInKeyword = wakeWordBuiltInKeyword.trim().ifEmpty { "jarvis" }
        SettingsManager.wakeWordKeywordPath = wakeWordKeywordPath.trim()
        SettingsManager.wakeWordSensitivity = wakeWordSensitivity
        SettingsManager.wakeWordAutoResume = wakeWordAutoResume
    }

    fun reload() {
        grokAPIKey = SettingsManager.grokAPIKey
        grokAuthBrokerURL = SettingsManager.grokAuthBrokerURL
        grokAuthBrokerToken = SettingsManager.grokAuthBrokerToken
        grokVoice = SettingsManager.grokVoice
        systemPrompt = SettingsManager.grokSystemPrompt
        soulPrompt = SettingsManager.grokSoulPrompt
        openClawHost = SettingsManager.openClawHost
        openClawPort = SettingsManager.openClawPort.toString()
        openClawHookToken = SettingsManager.openClawHookToken
        openClawGatewayToken = SettingsManager.openClawGatewayToken
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        videoStreamingEnabled = SettingsManager.videoStreamingEnabled
        visionSummariesEnabled = SettingsManager.visionSummariesEnabled
        displayHUDEnabled = SettingsManager.displayHUDEnabled
        proactiveNotificationsEnabled = SettingsManager.proactiveNotificationsEnabled
        porcupineAccessKey = SettingsManager.porcupineAccessKey
        wakeWordEnabled = SettingsManager.wakeWordEnabled
        wakeWordBuiltInKeyword = SettingsManager.wakeWordBuiltInKeyword
        wakeWordKeywordPath = SettingsManager.wakeWordKeywordPath
        wakeWordSensitivity = SettingsManager.wakeWordSensitivity
        wakeWordAutoResume = SettingsManager.wakeWordAutoResume
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = {
                    save()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Grok section
            SectionHeader("Grok API")
            MonoTextField(
                value = grokAPIKey,
                onValueChange = { grokAPIKey = it },
                label = "API Key",
                placeholder = "Enter Grok API key",
            )
            MonoTextField(
                value = grokAuthBrokerURL,
                onValueChange = { grokAuthBrokerURL = it },
                label = "Auth Broker URL",
                placeholder = "https://your-host.example.com/api/grok/token",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = grokAuthBrokerToken,
                onValueChange = { grokAuthBrokerToken = it },
                label = "Auth Broker Token",
                placeholder = "Broker auth token",
            )
            MonoTextField(
                value = grokVoice,
                onValueChange = { grokVoice = it },
                label = "Voice",
                placeholder = "eve, ara, rex, sal, or leo",
            )

            SectionHeader("System Prompt")
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            SectionHeader("Soul Context")
            OutlinedTextField(
                value = soulPrompt,
                onValueChange = { soulPrompt = it },
                label = { Text("Optional soul/personality context") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            // OpenClaw section
            SectionHeader("OpenClaw")
            MonoTextField(
                value = openClawHost,
                onValueChange = { openClawHost = it },
                label = "Host",
                placeholder = "http://your-mac.local",
                keyboardType = KeyboardType.Uri,
            )
            MonoTextField(
                value = openClawPort,
                onValueChange = { openClawPort = it },
                label = "Port",
                placeholder = "18789",
                keyboardType = KeyboardType.Number,
            )
            MonoTextField(
                value = openClawHookToken,
                onValueChange = { openClawHookToken = it },
                label = "Hook Token",
                placeholder = "Hook token",
            )
            MonoTextField(
                value = openClawGatewayToken,
                onValueChange = { openClawGatewayToken = it },
                label = "Gateway Token",
                placeholder = "Gateway auth token",
            )

            // WebRTC section
            SectionHeader("WebRTC")
            MonoTextField(
                value = webrtcSignalingURL,
                onValueChange = { webrtcSignalingURL = it },
                label = "Signaling URL",
                placeholder = "wss://your-server.example.com",
                keyboardType = KeyboardType.Uri,
            )

            // Video
            SectionHeader("Video")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Video Streaming", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Disable to save battery. Audio remains active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = videoStreamingEnabled,
                    onCheckedChange = { videoStreamingEnabled = it },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Vision Summaries", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sample frames through Grok image understanding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = visionSummariesEnabled,
                    onCheckedChange = { visionSummariesEnabled = it },
                )
            }

            SectionHeader("Display HUD")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Display HUD", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Send Grok, OpenClaw, transcript, and visual cards to Ray-Ban Display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = displayHUDEnabled,
                    onCheckedChange = { displayHUDEnabled = it },
                )
            }

            SectionHeader("Wake Word")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Wake Word", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Listen locally with Picovoice Porcupine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = { wakeWordEnabled = it },
                )
            }
            MonoTextField(
                value = porcupineAccessKey,
                onValueChange = { porcupineAccessKey = it },
                label = "Picovoice AccessKey",
                placeholder = "Enter Picovoice AccessKey",
            )
            MonoTextField(
                value = wakeWordBuiltInKeyword,
                onValueChange = { wakeWordBuiltInKeyword = it },
                label = "Built-in Keyword",
                placeholder = "jarvis",
            )
            MonoTextField(
                value = wakeWordKeywordPath,
                onValueChange = { wakeWordKeywordPath = it },
                label = "Custom Keyword Path",
                placeholder = "optional custom .ppn path or asset path",
            )
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "%.2f".format(wakeWordSensitivity),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = wakeWordSensitivity,
                    onValueChange = { wakeWordSensitivity = it },
                    valueRange = 0.1f..0.95f,
                    steps = 16,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Auto-resume", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Restart wake listening after Grok stops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wakeWordAutoResume,
                    onCheckedChange = { wakeWordAutoResume = it },
                )
            }

            // Notifications
            SectionHeader("Notifications")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column {
                    Text("Proactive Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Receive updates from OpenClaw spoken through glasses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = proactiveNotificationsEnabled,
                    onCheckedChange = { proactiveNotificationsEnabled = it },
                )
            }

            // Reset
            TextButton(onClick = { showResetDialog = true }) {
                Text("Reset to Defaults", color = Color.Red)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings") },
            text = { Text("This will reset all settings to the values built into the app.") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsManager.resetAll()
                    reload()
                    showResetDialog = false
                }) {
                    Text("Reset", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
