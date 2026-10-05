package com.example.screenrecorder.model

import android.net.Uri

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedSec: Long
)