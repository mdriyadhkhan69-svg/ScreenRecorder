package com.example.screenrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.screenrecorder.model.Recording

@Composable
fun RecordingsScreen(recordings: List<Recording>, vm: MainViewModel, actions: RecorderActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("My Recordings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        if (recordings.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Your recordings stay on your device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(recordings, key = { it.id }) { rec ->
                RecordingItem(rec, vm, actions)
            }
        }
    }
}