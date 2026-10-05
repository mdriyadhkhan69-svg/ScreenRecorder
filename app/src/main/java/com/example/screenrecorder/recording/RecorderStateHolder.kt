package com.example.screenrecorder.recording

import com.example.screenrecorder.model.RecorderUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth. Only RecordingService writes to it. */
object RecorderStateHolder {
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    fun set(value: RecorderUiState) {
        _state.value = value
    }

    fun update(transform: (RecorderUiState) -> RecorderUiState) {
        _state.update(transform)
    }
}