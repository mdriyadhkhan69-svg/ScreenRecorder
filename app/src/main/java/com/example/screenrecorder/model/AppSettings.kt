package com.example.screenrecorder.model

enum class AudioMode(val label: String) {
    NONE("None"),
    INTERNAL("Internal"),
    MICROPHONE("Microphone"),
    INTERNAL_AND_MIC("Internal + Mic")
}

enum class OrientationMode(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

data class AppSettings(
    /** Short side in pixels. 0 = Auto. */
    val resolution: Int = 0,
    val fps: Int = 30,
    val orientation: OrientationMode = OrientationMode.AUTO,
    val countdownSeconds: Int = 0,
    val audio: AudioMode = AudioMode.NONE,
    val keepScreenOn: Boolean = false
)