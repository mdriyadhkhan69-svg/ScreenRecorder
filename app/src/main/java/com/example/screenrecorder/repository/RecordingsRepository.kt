package com.example.screenrecorder.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.screenrecorder.model.Recording

class RecordingsRepository(private val context: Context) {

    private val collection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun query(): List<Recording> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/ScreenRecorder%")
        val result = mutableListOf<Recording>()
        try {
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    var duration = c.getLong(durCol)
                    var width = c.getInt(wCol)
                    var height = c.getInt(hCol)
                    if (duration <= 0L || width <= 0 || height <= 0) {
                        val probed = probe(uri)
                        if (probed != null) {
                            duration = probed.first
                            width = probed.second
                            height = probed.third
                        }
                    }
                    result.add(
                        Recording(
                            id = id,
                            uri = uri,
                            name = c.getString(nameCol) ?: "Screen.mp4",
                            durationMs = duration,
                            width = width,
                            height = height,
                            sizeBytes = c.getLong(sizeCol),
                            dateAddedSec = c.getLong(dateCol)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "query failed", t)
        }
        return result
    }

    private fun probe(uri: Uri): Triple<Long, Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Triple(d, w, h)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun rename(recording: Recording, newName: String): Boolean {
        var name = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (name.isEmpty()) return false
        if (!name.endsWith(".mp4", ignoreCase = true)) name += ".mp4"
        return try {
            val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) }
            context.contentResolver.update(recording.uri, values, null, null) > 0
        } catch (t: Throwable) {
            Log.e(TAG, "rename failed", t)
            false
        }
    }

    fun delete(recording: Recording): Boolean = try {
        context.contentResolver.delete(recording.uri, null, null) > 0
    } catch (t: Throwable) {
        Log.e(TAG, "delete failed", t)
        false
    }

    private companion object {
        const val TAG = "RecordingsRepository"
    }
}