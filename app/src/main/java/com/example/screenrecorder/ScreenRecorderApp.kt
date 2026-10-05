package com.example.screenrecorder

import android.app.Application
import com.example.screenrecorder.repository.RecordingsRepository
import com.example.screenrecorder.repository.SettingsRepository

class ScreenRecorderApp : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val recordingsRepository: RecordingsRepository by lazy { RecordingsRepository(this) }
}