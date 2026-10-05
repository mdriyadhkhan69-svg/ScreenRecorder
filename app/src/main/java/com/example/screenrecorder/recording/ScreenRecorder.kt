package com.example.screenrecorder.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.screenrecorder.model.AudioMode
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotEnoughStorageException : IOException("Not enough storage space")

/**
 * MediaCodec (H.264 surface input) + optional AAC audio, muxed to MP4 in MediaStore.
 * Pause suspends the video encoder input and subtracts paused time from timestamps.
 */
class ScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val spec: CaptureSpec,
    private val requestedAudio: AudioMode,
    private val onFailure: (Throwable) -> Unit,
    private val onLowStorage: () -> Unit
) {
    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)

    /** Set when audio had to be downgraded. */
    @Volatile
    var audioNote: String? = null
        private set

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val muxLock = Any()
    private val pending = ArrayList<Pending>()
    private var tracksAdded = 0
    private var expectedTracks = 1
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var failed = false
    @Volatile private var released = false
    @Volatile private var recordStartUs = 0L
    @Volatile private var pausedTotalUs = 0L
    private var pauseStartUs = 0L
    private var lowStorageFired = false

    private val pcmFormat: AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    fun start() {
        try {
            openOutput()
            setupVideo()
            setupAudio()
            recordStartUs = nanoUs()
            videoThread = Thread({ runVideoLoop() }, "sr-video").also { it.start() }
            if (audioEncoder != null) {
                audioThread = Thread({ runAudioLoop() }, "sr-audio").also { it.start() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            failed = true
            stopRequested = true
            releaseAll()
            discardOutput()
            throw t
        }
    }

    fun pause() {
        if (paused || stopRequested) return
        pauseStartUs = nanoUs()
        setSuspend(true)
        paused = true
    }

    fun resume() {
        if (!paused) return
        pausedTotalUs += nanoUs() - pauseStartUs
        setSuspend(false)
        requestSyncFrame()
        paused = false
    }

    /** Finalizes the MP4. Returns the saved Uri, or null if nothing valid was recorded. */
    fun stop(): Uri? {
        if (released) return null
        stopRequested = true
        if (paused) {
            setSuspend(false)
            paused = false
        }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { videoThread?.join(5000) }
        runCatching { audioThread?.join(5000) }

        var muxOk = false
        synchronized(muxLock) {
            val m = muxer
            if (m != null) {
                val started = muxerStarted
                var stopped = true
                try {
                    if (started) m.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "muxer.stop failed", t)
                    stopped = false
                }
                runCatching { m.release() }
                muxOk = started && stopped
            }
            muxer = null
            muxerStarted = false
        }
        releaseAll()

        val uri = outputUri
        outputUri = null
        if (uri == null) return null
        return if (muxOk && !failed) {
            try {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                Log.e(TAG, "finalize failed", t)
                runCatching { context.contentResolver.delete(uri, null, null) }
                null
            }
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

    // ---------- setup ----------

    private fun openOutput() {
        if (freeBytes() < MIN_FREE_BYTES) throw NotEnoughStorageException()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Screen_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: throw IOException("Could not create output file")
        outputUri = uri
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open output file")
        pfd = descriptor
        muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun setupVideo() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
        }
        val enc = MediaCodec.createByCodecName(spec.codecName)
        videoEncoder = enc
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        inputSurface = surface
        enc.start()
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder",
            spec.width, spec.height, spec.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        ) ?: throw IOException("Could not create virtual display")
    }

    private fun setupAudio() {
        if (requestedAudio == AudioMode.NONE) return
        val permitted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            audioNote = "Audio permission missing. Recording without audio."
            return
        }
        val wantMic = requestedAudio == AudioMode.MICROPHONE || requestedAudio == AudioMode.INTERNAL_AND_MIC
        val wantInternal = requestedAudio == AudioMode.INTERNAL || requestedAudio == AudioMode.INTERNAL_AND_MIC
        if (wantMic) micRecord = buildMic()
        if (wantInternal) internalRecord = buildInternal()
        if (micRecord == null && internalRecord == null) {
            audioNote = "Audio unavailable. Recording without audio."
            return
        }
        if (wantInternal && internalRecord == null) {
            audioNote = "Internal audio unavailable on this device."
        } else if (wantMic && micRecord == null) {
            audioNote = "Microphone unavailable."
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder = enc
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        expectedTracks = 2
    }

    private fun audioBufferSize(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return maxOf(min * 2, CHUNK * 4)
    }

    @SuppressLint("MissingPermission")
    private fun buildMic(): AudioRecord? = try {
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(audioBufferSize())
            .build()
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release()
            null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "mic unavailable", t)
        null
    }

    @SuppressLint("MissingPermission")
    private fun buildInternal(): AudioRecord? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val r = AudioRecord.Builder()
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(audioBufferSize())
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release()
            null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "internal audio unavailable", t)
        null
    }

    // ---------- loops ----------

    private fun runVideoLoop() {
        val enc = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var lastPts = -1L
        var lastCheck = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = registerTrack(enc.outputFormat)
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null && videoTrack >= 0) {
                        var pts = info.presentationTimeUs - recordStartUs - pausedTotalUs
                        if (pts < 0) pts = 0
                        if (pts <= lastPts) pts = lastPts + 1
                        lastPts = pts
                        info.presentationTimeUs = pts
                        writeSample(videoTrack, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheck > 2000) {
                    lastCheck = now
                    if (!lowStorageFired && !stopRequested && freeBytes() < LOW_FREE_BYTES) {
                        lowStorageFired = true
                        onLowStorage()
                    }
                }
            }
        } catch (t: Throwable) {
            reportFailure(t)
        }
    }

    private fun runAudioLoop() {
        val enc = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        val bufA = ByteArray(CHUNK)
        val bufB = ByteArray(CHUNK)
        var frames = 0L
        try {
            micRecord?.startRecording()
            internalRecord?.startRecording()
            while (!stopRequested) {
                readAudio(bufA, bufB)
                drainAudio(enc, info)
                if (paused) continue
                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ib = enc.getInputBuffer(idx) ?: continue
                    ib.clear()
                    ib.put(bufA, 0, CHUNK)
                    enc.queueInputBuffer(idx, 0, CHUNK, frames * 1_000_000L / SAMPLE_RATE, 0)
                    frames += CHUNK / 4
                }
            }
            var queued = false
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (!queued && SystemClock.elapsedRealtime() < deadline) {
                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    enc.queueInputBuffer(
                        idx, 0, 0, frames * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    queued = true
                } else {
                    drainAudio(enc, info)
                }
            }
            val drainDeadline = SystemClock.elapsedRealtime() + 3000
            while (!drainAudio(enc, info) && SystemClock.elapsedRealtime() < drainDeadline) {
                Thread.sleep(5)
            }
        } catch (t: Throwable) {
            reportFailure(t)
        } finally {
            runCatching { micRecord?.stop() }
            runCatching { internalRecord?.stop() }
        }
    }

    private fun readAudio(a: ByteArray, b: ByteArray) {
        val m = micRecord
        val i = internalRecord
        if (m != null && i != null) {
            readFully(m, a)
            readFully(i, b)
            var p = 0
            while (p < CHUNK - 1) {
                val sa = (a[p + 1].toInt() shl 8) or (a[p].toInt() and 0xFF)
                val sb = (b[p + 1].toInt() shl 8) or (b[p].toInt() and 0xFF)
                val mixed = (sa + sb).coerceIn(-32768, 32767)
                a[p] = (mixed and 0xFF).toByte()
                a[p + 1] = ((mixed shr 8) and 0xFF).toByte()
                p += 2
            }
        } else {
            readFully((m ?: i)!!, a)
        }
    }

    private fun readFully(r: AudioRecord, buf: ByteArray) {
        var off = 0
        while (off < buf.size && !stopRequested) {
            val n = r.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        if (off < buf.size) {
            java.util.Arrays.fill(buf, off, buf.size, 0)
            if (off == 0 && !stopRequested) Thread.sleep(20)
        }
    }

    /** Returns true once end-of-stream was seen. */
    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrack = registerTrack(enc.outputFormat)
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && buf != null && audioTrack >= 0) writeSample(audioTrack, buf, info)
                enc.releaseOutputBuffer(idx, false)
                if (eos) return true
            } else {
                return false
            }
        }
    }

    // ---------- muxer ----------

    private fun registerTrack(format: MediaFormat): Int = synchronized(muxLock) {
        val m = muxer ?: throw IllegalStateException("Muxer released")
        val idx = m.addTrack(format)
        tracksAdded++
        if (tracksAdded >= expectedTracks) {
            m.start()
            muxerStarted = true
            val info = MediaCodec.BufferInfo()
            for (p in pending) {
                info.set(0, p.data.size, p.pts, p.flags)
                m.writeSampleData(p.track, ByteBuffer.wrap(p.data), info)
            }
            pending.clear()
        }
        idx
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        synchronized(muxLock) {
            val m = muxer ?: return
            if (muxerStarted) {
                m.writeSampleData(track, buf, info)
            } else {
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                pending.add(Pending(track, bytes, info.presentationTimeUs, info.flags))
            }
        }
    }

    // ---------- helpers ----------

    private fun setSuspend(suspend: Boolean) {
        runCatching {
            videoEncoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspend) 1 else 0)
            })
        }
    }

    private fun requestSyncFrame() {
        runCatching {
            videoEncoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun reportFailure(t: Throwable) {
        Log.e(TAG, "recording failure", t)
        if (failed) return
        failed = true
        if (!stopRequested) onFailure(t)
    }

    private fun releaseAll() {
        if (released) return
        released = true
        runCatching { virtualDisplay?.release() }
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { micRecord?.release() }
        runCatching { internalRecord?.release() }
        runCatching { pfd?.close() }
        synchronized(muxLock) {
            runCatching { muxer?.release() }
            muxer = null
        }
        videoEncoder = null
        audioEncoder = null
        inputSurface = null
        micRecord = null
        internalRecord = null
        pfd = null
        virtualDisplay = null
    }

    private fun discardOutput() {
        val uri = outputUri ?: return
        outputUri = null
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun freeBytes(): Long = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        StatFs(dir.path).availableBytes
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    private fun nanoUs(): Long = System.nanoTime() / 1000

    private companion object {
        const val TAG = "ScreenRecorder"
        const val SAMPLE_RATE = 44100
        const val CHUNK = 4096
        const val MIN_FREE_BYTES = 100L * 1024 * 1024
        const val LOW_FREE_BYTES = 30L * 1024 * 1024
    }
}