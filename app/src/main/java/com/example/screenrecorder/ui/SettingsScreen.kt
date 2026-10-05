package com.example.screenrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.screenrecorder.model.AudioMode
import com.example.screenrecorder.model.OrientationMode

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val caps = vm.caps
    val context = LocalContext.current

    val resOptions = caps.resolutions.map { it.label to it.shortSide }
    val selectedRes = if (resOptions.any { it.second == settings.resolution }) settings.resolution else 0
    val fpsOptions = buildList {
        add("30 FPS" to 30)
        if (caps.supports60) add("60 FPS" to 60)
    }
    val selectedFps = if (settings.fps == 60 && caps.supports60) 60 else 30

    @Suppress("DEPRECATION")
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        SectionCard("Recording") {
            ChoiceRow("Resolution", resOptions, selectedRes) { v -> vm.updateSettings { it.copy(resolution = v) } }
            ChoiceRow("Frame rate", fpsOptions, selectedFps) { v -> vm.updateSettings { it.copy(fps = v) } }
            InfoRow("Codec", "H.264 (AVC)")
            InfoRow("Bitrate", "Auto")
            ChoiceRow(
                "Orientation",
                OrientationMode.values().map { it.label to it },
                settings.orientation
            ) { v -> vm.updateSettings { it.copy(orientation = v) } }
            ChoiceRow(
                "Countdown",
                listOf("Off" to 0, "3 seconds" to 3, "5 seconds" to 5),
                settings.countdownSeconds
            ) { v -> vm.updateSettings { it.copy(countdownSeconds = v) } }
        }

        SectionCard("Audio") {
            ChoiceRow(
                "Audio source",
                AudioMode.values().map { it.label to it },
                settings.audio
            ) { v -> vm.updateSettings { it.copy(audio = v) } }
        }

        SectionCard("Storage") {
            InfoRow("Location", "Movies/ScreenRecorder (Android MediaStore)")
        }

        SectionCard("Behavior") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen awake while app is open", Modifier.weight(1f))
                Switch(
                    checked = settings.keepScreenOn,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
                )
            }
        }

        SectionCard("About") {
            InfoRow("Version", versionName)
            InfoRow("Privacy", "Your recordings stay on your device. No account, no analytics, no tracking.")
            InfoRow("About", "Screen Recorder is an offline-first screen recorder.")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (label, value) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}