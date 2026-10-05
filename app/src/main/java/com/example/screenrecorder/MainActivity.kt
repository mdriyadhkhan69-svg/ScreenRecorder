package com.example.screenrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.screenrecorder.model.AudioMode
import com.example.screenrecorder.model.Recording
import com.example.screenrecorder.recording.RecorderStateHolder
import com.example.screenrecorder.service.RecordingService
import com.example.screenrecorder.ui.AppRoot
import com.example.screenrecorder.ui.MainViewModel
import com.example.screenrecorder.ui.PlayerActivity
import com.example.screenrecorder.ui.RecorderActions
import com.example.screenrecorder.ui.theme.ScreenRecorderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestAudioStep() }

    private val audioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("Audio permission denied. Recording without audio.")
            }
            requestCaptureStep()
        }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this, RecordingService.startIntent(this, result.resultCode, data)
                )
            } else {
                toast("Screen capture permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = RecorderActions(
            onStart = ::beginStartFlow,
            onPause = { sendAction(RecordingService.ACTION_PAUSE) },
            onResume = { sendAction(RecordingService.ACTION_RESUME) },
            onStop = { sendAction(RecordingService.ACTION_STOP) },
            onPlay = ::play,
            onShare = ::share
        )
        setContent {
            ScreenRecorderTheme {
                AppRoot(viewModel, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun beginStartFlow() {
        if (RecorderStateHolder.state.value.isActive) return
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAudioStep()
        }
    }

    private fun requestAudioStep() {
        if (viewModel.settings.value.audio != AudioMode.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestCaptureStep()
        }
    }

    private fun requestCaptureStep() {
        if (RecorderStateHolder.state.value.isActive) return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun sendAction(action: String) {
        startService(RecordingService.actionIntent(this, action))
    }

    private fun play(rec: Recording) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(rec.uri))
    }

    private fun share(rec: Recording) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, rec.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}