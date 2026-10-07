package com.example.screenrecorder.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.screenrecorder.MainActivity
import com.example.screenrecorder.R
import com.example.screenrecorder.ScreenRecorderApp
import com.example.screenrecorder.model.AppSettings
import com.example.screenrecorder.model.AudioMode
import com.example.screenrecorder.model.RecorderState
import com.example.screenrecorder.model.RecorderUiState
import com.example.screenrecorder.recording.DeviceCapabilities
import com.example.screenrecorder.recording.NotEnoughStorageException
import com.example.screenrecorder.recording.RecorderStateHolder
import com.example.screenrecorder.recording.ScreenRecorder
import com.example.screenrecorder.util.formatElapsed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: ScreenRecorder? = null
    private var startJob: Job? = null
    private var tickerJob: Job? = null
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    private val currentState: RecorderState
        get() = RecorderStateHolder.state.value.state

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        if (action != ACTION_START && !RecorderStateHolder.state.value.isActive) stopSelf()
        return START_NOT_STICKY
    }

    // ---------- start ----------

    private fun handleStart(intent: Intent) {
        ensureChannel()
        if (RecorderStateHolder.state.value.isActive) {
            // Duplicate start: keep the single existing session, just satisfy the FGS contract.
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), fgsType)
            return
        }
        val settings = (application as ScreenRecorderApp).settingsRepository.settings.value
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val audio = if (settings.audio != AudioMode.NONE && !hasAudioPermission) AudioMode.NONE else settings.audio

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audio != AudioMode.NONE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        fgsType = type

        accumulatedMs = 0L
        segmentStartMs = 0L
        val startCountdown = if (settings.quickStart) 0 else settings.countdownSeconds
        RecorderStateHolder.set(RecorderUiState(RecorderState.STARTING, countdown = startCountdown))
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            fail("Couldn't start the recording service")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Screen capture permission denied")
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            null
        }
        if (p == null) {
            fail("Screen capture is unavailable")
            return
        }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { onProjectionStopped() }
            }
        }
        projection = p
        projectionCallback = callback
        p.registerCallback(callback, Handler(Looper.getMainLooper()))

        startJob = scope.launch {
            var n = startCountdown
            while (n > 0) {
                RecorderStateHolder.update { it.copy(countdown = n) }
                refreshNotification()
                delay(1000)
                n--
            }
            RecorderStateHolder.update { it.copy(countdown = 0) }
            beginRecording(settings, audio)
        }
    }

    private suspend fun beginRecording(settings: AppSettings, audio: AudioMode) {
        val p = projection ?: return
        val spec = DeviceCapabilities.resolve(this, settings)
        if (spec == null) {
            fail("This device can't encode H.264 video")
            return
        }
        val r = ScreenRecorder(
            applicationContext, p, spec, audio,
            onFailure = { t -> scope.launch { handleFailure(t) } },
            onLowStorage = { scope.launch { finishRecording("Storage almost full. Recording saved.") } }
        )
        recorder = r
        try {
            withContext(Dispatchers.Default) { r.start() }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "recorder start failed", t)
            recorder = null
            fail(if (t is NotEnoughStorageException) "Not enough storage space" else "Recording couldn't start on this device")
            return
        }
        accumulatedMs = 0L
        segmentStartMs = SystemClock.elapsedRealtime()
        RecorderStateHolder.set(RecorderUiState(RecorderState.RECORDING, message = r.audioNote))
        startTicker()
    }

    // ---------- controls ----------

    private fun handlePause() {
        if (currentState != RecorderState.RECORDING) return
        recorder?.pause()
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStartMs
        tickerJob?.cancel()
        RecorderStateHolder.update { it.copy(state = RecorderState.PAUSED, elapsedMs = accumulatedMs) }
        refreshNotification()
    }

    private fun handleResume() {
        if (currentState != RecorderState.PAUSED) return
        recorder?.resume()
        segmentStartMs = SystemClock.elapsedRealtime()
        RecorderStateHolder.update { it.copy(state = RecorderState.RECORDING) }
        startTicker()
    }

    private fun handleStop() {
        when (currentState) {
            RecorderState.STARTING -> scope.launch { abortStart(null) }
            RecorderState.RECORDING, RecorderState.PAUSED -> scope.launch { finishRecording(null) }
            else -> Unit
        }
    }

    private suspend fun finishRecording(message: String?) {
        val st = currentState
        if (st != RecorderState.RECORDING && st != RecorderState.PAUSED) return
        val elapsed = currentElapsed()
        tickerJob?.cancel()
        RecorderStateHolder.update { it.copy(state = RecorderState.STOPPING, elapsedMs = elapsed) }
        refreshNotification()
        val r = recorder
        val note = r?.audioNote
        val uri = withContext(Dispatchers.IO) { runCatching { r?.stop() }.getOrNull() }
        recorder = null
        teardownProjection()
        if (uri != null) {
            RecorderStateHolder.set(
                RecorderUiState(RecorderState.COMPLETED, elapsedMs = elapsed, message = message ?: note, savedUri = uri)
            )
        } else {
            RecorderStateHolder.set(
                RecorderUiState(RecorderState.ERROR, message = "Recording was too short or couldn't be saved")
            )
        }
        endService()
    }

    private suspend fun abortStart(errorMessage: String?) {
        startJob?.cancelAndJoin()
        val r = recorder
        recorder = null
        if (r != null) withContext(Dispatchers.IO) { runCatching { r.stop() } }
        teardownProjection()
        RecorderStateHolder.set(
            if (errorMessage == null) RecorderUiState() else RecorderUiState(RecorderState.ERROR, message = errorMessage)
        )
        endService()
    }

    private suspend fun handleFailure(t: Throwable) {
        val st = currentState
        if (st != RecorderState.RECORDING && st != RecorderState.PAUSED) return
        Log.e(TAG, "recording failed", t)
        tickerJob?.cancel()
        val r = recorder
        recorder = null
        if (r != null) withContext(Dispatchers.IO) { runCatching { r.stop() } }
        fail("Recording stopped because of an error")
    }

    private suspend fun onProjectionStopped() {
        if (projection == null) return
        when (currentState) {
            RecorderState.RECORDING, RecorderState.PAUSED ->
                finishRecording("Screen capture was stopped by the system. Recording saved.")
            RecorderState.STARTING -> abortStart("Screen capture was stopped")
            else -> Unit
        }
    }

    // ---------- helpers ----------

    private fun fail(message: String) {
        tickerJob?.cancel()
        teardownProjection()
        RecorderStateHolder.set(RecorderUiState(RecorderState.ERROR, message = message))
        endService()
    }

    private fun teardownProjection() {
        val p = projection
        val cb = projectionCallback
        projection = null
        projectionCallback = null
        if (p != null) {
            runCatching { if (cb != null) p.unregisterCallback(cb) }
            runCatching { p.stop() }
        }
    }

    private fun endService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun currentElapsed(): Long =
        accumulatedMs + if (currentState == RecorderState.RECORDING) SystemClock.elapsedRealtime() - segmentStartMs else 0L

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val e = currentElapsed()
                RecorderStateHolder.update { it.copy(elapsedMs = e) }
                refreshNotification()
                delay(1000 - (e % 1000))
            }
        }
    }

    // ---------- notification ----------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows recording status and controls"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun servicePending(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val s = RecorderStateHolder.state.value
        val text = when (s.state) {
            RecorderState.STARTING -> if (s.countdown > 0) "Starting in ${s.countdown}…" else "Starting…"
            RecorderState.RECORDING -> "● Recording ${formatElapsed(s.elapsedMs)}"
            RecorderState.PAUSED -> "Paused ${formatElapsed(s.elapsedMs)}"
            RecorderState.STOPPING -> "Saving video…"
            else -> "Screen Recorder"
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (s.state) {
            RecorderState.STARTING -> b.addAction(0, "Cancel", servicePending(ACTION_STOP))
            RecorderState.RECORDING -> {
                b.addAction(0, "Pause", servicePending(ACTION_PAUSE))
                b.addAction(0, "Stop", servicePending(ACTION_STOP))
            }
            RecorderState.PAUSED -> {
                b.addAction(0, "Resume", servicePending(ACTION_RESUME))
                b.addAction(0, "Stop", servicePending(ACTION_STOP))
            }
            else -> Unit
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        val r = recorder
        recorder = null
        if (r != null) Thread { runCatching { r.stop() } }.start()
        teardownProjection()
        if (RecorderStateHolder.state.value.isActive) {
            RecorderStateHolder.set(RecorderUiState(RecorderState.ERROR, message = "Recording was interrupted"))
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.example.screenrecorder.action.START"
        const val ACTION_PAUSE = "com.example.screenrecorder.action.PAUSE"
        const val ACTION_RESUME = "com.example.screenrecorder.action.RESUME"
        const val ACTION_STOP = "com.example.screenrecorder.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)
    }
}