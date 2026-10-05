package com.example.screenrecorder.ui

import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.screenrecorder.model.Recording
import com.example.screenrecorder.util.formatDate
import com.example.screenrecorder.util.formatElapsed
import com.example.screenrecorder.util.formatSize
import com.example.screenrecorder.util.resolutionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ItemDialog { RENAME, DELETE, DETAILS }

@Composable
fun RecordingItem(rec: Recording, vm: MainViewModel, actions: RecorderActions) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ItemDialog?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Play recording") { actions.onPlay(rec) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Thumbnail(rec.uri, Modifier.width(96.dp).height(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rec.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatElapsed(rec.durationMs)} • ${resolutionLabel(rec.width, rec.height)} • ${formatSize(rec.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDate(rec.dateAddedSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options for ${rec.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Play") }, onClick = { menuOpen = false; actions.onPlay(rec) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; dialog = ItemDialog.RENAME })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; actions.onShare(rec) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; dialog = ItemDialog.DELETE })
                    DropdownMenuItem(text = { Text("Details") }, onClick = { menuOpen = false; dialog = ItemDialog.DETAILS })
                }
            }
        }
    }

    when (dialog) {
        ItemDialog.RENAME -> {
            var text by remember { mutableStateOf(rec.name.removeSuffix(".mp4")) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Rename recording") },
                text = {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.rename(rec, text) { ok ->
                            if (!ok) Toast.makeText(context, "Couldn't rename the recording", Toast.LENGTH_SHORT).show()
                        }
                        dialog = null
                    }) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
            )
        }
        ItemDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete recording?") },
            text = { Text("${rec.name} will be permanently deleted from your device.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(rec); dialog = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        ItemDialog.DETAILS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Name: ${rec.name}")
                    Text("Location: Movies/ScreenRecorder")
                    Text("Duration: ${formatElapsed(rec.durationMs)}")
                    Text("Resolution: ${rec.width} × ${rec.height}")
                    Text("Size: ${formatSize(rec.sizeBytes)}")
                    Text("Date: ${formatDate(rec.dateAddedSec)}")
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } }
        )
        null -> Unit
    }
}

@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(320, 180), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}