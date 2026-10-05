package com.example.screenrecorder.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

fun formatSize(bytes: Long): String {
    val gb = 1024L * 1024L * 1024L
    val mb = 1024L * 1024L
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb.toDouble())
        bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb.toDouble())
        else -> String.format(Locale.US, "%d KB", bytes / 1024L)
    }
}

fun resolutionLabel(width: Int, height: Int): String {
    val s = minOf(width, height)
    return when {
        s <= 0 -> "—"
        s >= 2100 -> "2160p"
        s >= 1400 -> "1440p"
        s >= 1000 -> "1080p"
        s >= 700 -> "720p"
        else -> "${s}p"
    }
}

fun formatDate(epochSec: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSec * 1000))