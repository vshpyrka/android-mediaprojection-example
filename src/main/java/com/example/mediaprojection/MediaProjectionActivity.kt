package com.example.mediaprojection

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.mediaprojection.databinding.ActivityMediaProjectionBinding
import com.google.android.material.snackbar.Snackbar

class MediaProjectionActivity : AppCompatActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        ::handlePermissionResult
    )

    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::launchRecordingService
    )

    private lateinit var binding: ActivityMediaProjectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaProjectionBinding.inflate(layoutInflater)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }

        setContentView(binding.root)
        binding.start.setOnClickListener {
            checkPermissionOrStartRecording()
        }

        binding.stop.setOnClickListener {
            stopProjection()
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(MediaProjectionService.RECORDING_COMPLETE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val path = intent.getStringExtra(MediaProjectionService.FILE_PATH)
            binding.videoView.setVideoPath(path)
            binding.videoView.isVisible = true
            binding.videoView.start()
        }
    }

    private fun checkPermissionOrStartRecording() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startProjection()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            ) -> {
                showPermissionRationale()
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun handlePermissionResult(isGranted: Boolean) {
        if (isGranted) {
            startProjection()
        } else {
            showPermissionRationale()
        }
    }

    private fun showPermissionRationale() {
        Snackbar.make(
            this@MediaProjectionActivity.window.decorView,
            "Audio permission is required",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("Permissions") {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }.show()
    }

    private fun launchRecordingService(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MediaProjectionService::class.java).apply {
                    putExtra(MediaProjectionService.SCREEN_CAPTURE_INTENT_RESULT_DATA, result.data)
                }
            )
        } else {
            showRecordingFailed()
        }
    }

    private fun showRecordingFailed() {
        Snackbar.make(this.window.decorView, "Recording Cancelled", Snackbar.LENGTH_LONG).show()
    }

    private fun startProjection() {
        val projectionManager =
            ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        if (projectionManager != null) {
            startMediaProjection.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun stopProjection() {
        stopService(Intent(this, MediaProjectionService::class.java))
    }
}
