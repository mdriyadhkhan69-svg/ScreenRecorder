package com.example.screenrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.screenrecorder.ScreenRecorderApp
import com.example.screenrecorder.model.AppSettings
import com.example.screenrecorder.model.RecorderState
import com.example.screenrecorder.model.Recording
import com.example.screenrecorder.recording.DeviceCaps
import com.example.screenrecorder.recording.DeviceCapabilities
import com.example.screenrecorder.recording.RecorderStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as ScreenRecorderApp
    private val settingsRepo = appRef.settingsRepository
    private val recordingsRepo = appRef.recordingsRepository

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val recorderState = RecorderStateHolder.state

    val caps: DeviceCaps by lazy { DeviceCapabilities.detect(appRef) }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            recorderState.map { it.state }.distinctUntilChanged().collect { s ->
                if (s == RecorderState.COMPLETED || s == RecorderState.IDLE) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _recordings.value = withContext(Dispatchers.IO) { recordingsRepo.query() }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsRepo.update(transform)

    fun rename(rec: Recording, newName: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { recordingsRepo.rename(rec, newName) }
            if (ok) refresh()
            onDone(ok)
        }
    }

    fun delete(rec: Recording) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingsRepo.delete(rec) }
            refresh()
        }
    }
}