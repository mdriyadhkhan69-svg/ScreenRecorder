package com.example.screenrecorder.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.screenrecorder.CaptureActivity
import com.example.screenrecorder.model.RecorderState
import com.example.screenrecorder.recording.RecorderStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordingTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch { RecorderStateHolder.state.collect { render(it.state) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (RecorderStateHolder.state.value.state) {
            RecorderState.IDLE, RecorderState.COMPLETED, RecorderState.ERROR -> launchCapture()
            RecorderState.RECORDING, RecorderState.PAUSED ->
                startService(RecordingService.actionIntent(this, RecordingService.ACTION_STOP))
            else -> Unit // STARTING / STOPPING: ignore repeated taps
        }
    }

    private fun launchCapture() {
        val intent = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: RecorderState) {
        val tile = qsTile ?: return
        tile.label = "Screen Recorder"
        when (state) {
            RecorderState.RECORDING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Recording • tap to stop"
            }
            RecorderState.PAUSED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Paused • tap to stop"
            }
            RecorderState.STARTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Starting…"
            }
            RecorderState.STOPPING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Saving…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Tap to record"
            }
        }
        tile.updateTile()
    }
}