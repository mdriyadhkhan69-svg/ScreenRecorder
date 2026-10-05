package com.example.screenrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.screenrecorder.model.RecorderState
import com.example.screenrecorder.model.RecorderUiState
import com.example.screenrecorder.model.Recording
import com.example.screenrecorder.util.formatElapsed

@Composable
fun HomeScreen(
    state: RecorderUiState,
    recordings: List<Recording>,
    vm: MainViewModel,
    actions: RecorderActions,
    onOpenSettings: () -> Unit,
    onSeeAll: () -> Unit
) {
    val idleLike = state.state == RecorderState.IDLE ||
            state.state == RecorderState.COMPLETED ||
            state.state == RecorderState.ERROR
    val canPauseResume = state.state == RecorderState.RECORDING || state.state == RecorderState.PAUSED
    val canStop = canPauseResume || state.state == RecorderState.STARTING

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Screen Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Offline • HD Recording",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        item { StatusCard(state) }

        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = actions.onStart,
                    enabled = idleLike,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(150.dp)
                        .semantics { contentDescription = "Start recording" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("START", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = if (state.state == RecorderState.PAUSED) actions.onResume else actions.onPause,
                        enabled = canPauseResume,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text(if (state.state == RecorderState.PAUSED) "RESUME" else "PAUSE", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = actions.onStop,
                        enabled = canStop,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text("STOP", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent recordings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (recordings.isNotEmpty()) {
                    TextButton(onClick = onSeeAll) { Text("See all") }
                }
            }
        }

        if (recordings.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Your recordings stay on your device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items(recordings.take(5), key = { it.id }) { rec ->
                RecordingItem(rec, vm, actions)
            }
        }
    }
}

@Composable
private fun StatusCard(state: RecorderUiState) {
    val title = when (state.state) {
        RecorderState.IDLE -> "Ready to record"
        RecorderState.STARTING -> if (state.countdown > 0) "Starting in" else "Starting…"
        RecorderState.RECORDING -> "Recording"
        RecorderState.PAUSED -> "Paused"
        RecorderState.STOPPING -> "Saving video…"
        RecorderState.COMPLETED -> "Recording saved"
        RecorderState.ERROR -> state.message ?: "Recording failed"
    }
    val showTimer = state.state == RecorderState.RECORDING ||
            state.state == RecorderState.PAUSED ||
            state.state == RecorderState.STOPPING
    val subtitle = if (state.state == RecorderState.ERROR) null else state.message

    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                (if (state.state == RecorderState.RECORDING) "● " else "") + title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (state.state == RecorderState.STARTING && state.countdown > 0) {
                Text(
                    state.countdown.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showTimer) {
                Text(
                    formatElapsed(state.elapsedMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (state.state == RecorderState.IDLE) {
                Text(
                    "Your recordings stay on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}