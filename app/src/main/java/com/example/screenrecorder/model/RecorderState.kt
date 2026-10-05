package com.example.screenrecorder.model

import android.net.Uri

enum class RecorderState { IDLE, STARTING, RECORDING, PAUSED, STOPPING, COMPLETED, ERROR }

data class RecorderUiState(
    val state: RecorderState = RecorderState.IDLE,
    val elapsedMs: Long = 0L,
    val countdown: Int = 0,
    val message: String? = null,
    val savedUri: Uri? = null
) {
    val isActive: Boolean
        get() = state == RecorderState.STARTING ||
                state == RecorderState.RECORDING ||
                state == RecorderState.PAUSED ||
                state == RecorderState.STOPPING
}