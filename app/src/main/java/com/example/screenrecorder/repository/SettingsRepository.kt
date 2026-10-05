package com.example.screenrecorder.repository

import android.content.Context
import com.example.screenrecorder.model.AppSettings
import com.example.screenrecorder.model.AudioMode
import com.example.screenrecorder.model.OrientationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        resolution = prefs.getInt("resolution", 0),
        fps = prefs.getInt("fps", 30),
        orientation = runCatching {
            OrientationMode.valueOf(prefs.getString("orientation", null) ?: "AUTO")
        }.getOrDefault(OrientationMode.AUTO),
        countdownSeconds = prefs.getInt("countdown", 0),
        audio = runCatching {
            AudioMode.valueOf(prefs.getString("audio", null) ?: "NONE")
        }.getOrDefault(AudioMode.NONE),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false)
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putInt("resolution", next.resolution)
            .putInt("fps", next.fps)
            .putString("orientation", next.orientation.name)
            .putInt("countdown", next.countdownSeconds)
            .putString("audio", next.audio.name)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .apply()
    }
}