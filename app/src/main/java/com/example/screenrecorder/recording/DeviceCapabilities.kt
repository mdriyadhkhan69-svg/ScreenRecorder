package com.example.screenrecorder.recording

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display
import com.example.screenrecorder.model.AppSettings
import com.example.screenrecorder.model.OrientationMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ResolutionOption(val label: String, val shortSide: Int)

data class DeviceCaps(
    val nativeWidth: Int,
    val nativeHeight: Int,
    val densityDpi: Int,
    val resolutions: List<ResolutionOption>,
    val supports60: Boolean
)

data class CaptureSpec(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int,
    val codecName: String
)

object DeviceCapabilities {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    @Suppress("DEPRECATION")
    private fun metrics(context: Context): DisplayMetrics {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }

    private fun refreshRate(context: Context): Float {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY).refreshRate
    }

    private fun encoderInfo(): MediaCodecInfo? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }
        return infos.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: infos.firstOrNull()
    }

    private fun sizeFor(
        vc: MediaCodecInfo.VideoCapabilities,
        nativeShort: Int,
        nativeLong: Int,
        targetShort: Int,
        landscape: Boolean,
        fps: Int
    ): Pair<Int, Int>? {
        if (targetShort <= 0 || targetShort > nativeShort) return null // never upscale
        val scale = targetShort.toDouble() / nativeShort
        val longPx = (nativeLong * scale).roundToInt()
        var w = if (landscape) longPx else targetShort
        var h = if (landscape) targetShort else longPx
        val wa = max(1, vc.widthAlignment)
        val ha = max(1, vc.heightAlignment)
        w = w / wa * wa
        h = h / ha * ha
        if (w <= 0 || h <= 0) return null
        return if (vc.areSizeAndRateSupported(w, h, fps.toDouble())) Pair(w, h) else null
    }

    fun detect(context: Context): DeviceCaps {
        val m = metrics(context)
        val nShort = min(m.widthPixels, m.heightPixels)
        val nLong = max(m.widthPixels, m.heightPixels)
        val vc = encoderInfo()?.getCapabilitiesForType(MIME)?.videoCapabilities
        val options = mutableListOf(ResolutionOption("Auto", 0))
        var supports60 = false
        if (vc != null) {
            listOf(720 to "720p", 1080 to "1080p", 1440 to "1440p", 2160 to "4K (2160p)").forEach { (s, label) ->
                if (sizeFor(vc, nShort, nLong, s, false, 30) != null) {
                    options.add(ResolutionOption(label, s))
                }
            }
            if (refreshRate(context) >= 59f) {
                supports60 = listOf(min(1080, nShort), min(720, nShort), nShort).distinct().any { t ->
                    sizeFor(vc, nShort, nLong, t, false, 60) != null
                }
            }
        }
        return DeviceCaps(m.widthPixels, m.heightPixels, m.densityDpi, options, supports60)
    }

    /** Picks a real, encoder-supported size/fps/bitrate. Falls back safely instead of failing. */
    fun resolve(context: Context, settings: AppSettings): CaptureSpec? {
        val m = metrics(context)
        val nShort = min(m.widthPixels, m.heightPixels)
        val nLong = max(m.widthPixels, m.heightPixels)
        val landscape = when (settings.orientation) {
            OrientationMode.AUTO -> m.widthPixels > m.heightPixels
            OrientationMode.PORTRAIT -> false
            OrientationMode.LANDSCAPE -> true
        }
        val info = encoderInfo() ?: return null
        val vc = info.getCapabilitiesForType(MIME).videoCapabilities
        val targets = (if (settings.resolution > 0) listOf(settings.resolution) else emptyList()) +
                listOf(min(1080, nShort), min(720, nShort), nShort)
        val requestedFps = if (settings.fps >= 60 && refreshRate(context) >= 59f) 60 else 30
        val fpsList = listOf(requestedFps, 30).distinct()
        for (t in targets.distinct()) {
            for (f in fpsList) {
                val size = sizeFor(vc, nShort, nLong, t, landscape, f) ?: continue
                val raw = (size.first.toLong() * size.second * f * 0.07)
                    .toLong().coerceIn(2_000_000L, 60_000_000L).toInt()
                val bitrate = vc.bitrateRange.clamp(raw)
                return CaptureSpec(size.first, size.second, m.densityDpi, f, bitrate, info.name)
            }
        }
        return null
    }
}