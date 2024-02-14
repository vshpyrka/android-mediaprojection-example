package com.example.mediaprojection

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_NOTIFICATION_ID = 1000
private const val VIDEO_FRAME_RATE = 30
private const val VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 6
private const val MAX_DURATION_MS = 60 * 60 * 1000
private const val MAX_FILE_SIZE_BYTES = 5000000000L
private const val AUDIO_BIT_RATE = 196000
private const val AUDIO_SAMPLE_RATE = 44100
private const val TOTAL_NUM_TRACKS = 1

class MediaProjectionService : Service() {

    companion object {
        const val SCREEN_CAPTURE_INTENT_RESULT_DATA = "result_data"
        const val RECORDING_COMPLETE_ACTION = "com.example.broadcast.MY_NOTIFICATION"
        const val FILE_PATH = "file_path"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaProjectionCallback = MediaProjectionCallback()
    private var mediaRecorder: MediaRecorder? = null
    private val isRecording = AtomicBoolean()
    private lateinit var notificationManager: NotificationManager
    private var mTempVideoFile: File? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        println("AAA MediaProjectionService created")
        notificationManager = NotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        println("AAA MediaProjectionService started, action=${intent?.action} flags=$flags, startId=$startId")
        if (intent == null) {
            return START_NOT_STICKY
        }
        if (!hasAudioRecordingPermission()) {
            notificationManager.showAudioPermissionRequiredNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        notificationManager.createNotificationChannel()
        startForeground(
            SERVICE_NOTIFICATION_ID,
            notificationManager.getForegroundServiceNotification()
        )
        prepareMediaProjection(intent)
        if (!prepareRecording()) {
            stopSelf()
            return START_NOT_STICKY
        } else {
            createVirtualDisplay()
            startRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        println("AAA MediaProjectionService destroyed")
        stopRecording()
        if (mTempVideoFile != null) {
            Intent().also { intent ->
                intent.setAction(RECORDING_COMPLETE_ACTION)
                intent.putExtra(FILE_PATH, mTempVideoFile?.path)
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(intent)
            }
        }
        super.onDestroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun prepareMediaProjection(intent: Intent) {
        val mediaProjectionManager =
            ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        val mediaProjectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(SCREEN_CAPTURE_INTENT_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(SCREEN_CAPTURE_INTENT_RESULT_DATA)
        }
        mediaProjection =
            mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, mediaProjectionIntent!!)
        mediaProjection?.registerCallback(mediaProjectionCallback, null)
    }

    private fun hasAudioRecordingPermission(): Boolean {
        return PermissionChecker.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PermissionChecker.PERMISSION_GRANTED
    }

    private fun prepareRecording(): Boolean {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            @Suppress("DEPRECATION")
            (MediaRecorder())
        }
        val dir = applicationContext.externalCacheDir?.path + File.separator + "Recordings"
        val folder = File(dir)
        if (!folder.exists()) {
            val created = folder.mkdirs()
            println("AAA created folder=$created")
            if (!created) return false
        }
        val filePath = dir + File.separator + System.currentTimeMillis() + ".mp4"
        mTempVideoFile = File(filePath)
        println("AAA MediaProjection recording file - $mTempVideoFile")
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            // Set up video
            val displayManager = ContextCompat.getSystemService(
                this@MediaProjectionService,
                DisplayManager::class.java
            ) ?: throw IllegalStateException("Failed to get DisplayManager service")
            val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val widthPixels: Int
            val heightPixels: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
                widthPixels = metrics.bounds.width()
                heightPixels = metrics.bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                defaultDisplay.getRealMetrics(metrics)
                widthPixels = metrics.widthPixels
                heightPixels = metrics.heightPixels
            }
            var refreshRate = defaultDisplay.refreshRate.toInt()
            val dimens: IntArray = getSupportedSize(widthPixels, heightPixels, refreshRate)
            val width = dimens[0]
            val height = dimens[1]
            refreshRate = dimens[2]
            val vidBitRate: Int = (width * height * refreshRate / VIDEO_FRAME_RATE
                    * VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setVideoEncodingProfileLevel(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel3
                )
            }
            setVideoSize(width, height)
            setVideoFrameRate(refreshRate)
            setVideoEncodingBitRate(vidBitRate)
            setMaxDuration(MAX_DURATION_MS)
            setMaxFileSize(MAX_FILE_SIZE_BYTES)

            setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
            setAudioChannels(TOTAL_NUM_TRACKS)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)

            setOutputFile(filePath)

            setOnInfoListener { _, what, _ ->
                val info = when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN -> "MEDIA_RECORDER_INFO_UNKNOWN"
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> "MEDIA_RECORDER_INFO_MAX_DURATION_REACHED"
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> "MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED"
                    else -> ""
                }
                println("AAA Media recorder info: $info")
            }
        }
        return try {
            mediaRecorder?.prepare()
            true
        } catch (ioException: IOException) {
            ioException.printStackTrace()
            // throw error notification
            false
        }
    }

    /**
     * https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/screenrecord/ScreenMediaRecorder.java#190
     *
     * Find the highest supported screen resolution and refresh rate for the given dimensions on
     * this device, up to actual size and given rate.
     * If possible this will return the same values as given, but values may be smaller on some
     * devices.
     *
     * @param screenWidth Actual pixel width of screen
     * @param screenHeight Actual pixel height of screen
     * @param refreshRate Desired refresh rate
     * @return array with supported width, height, and refresh rate
     */
    @Throws(IOException::class)
    private fun getSupportedSize(screenWidth: Int, screenHeight: Int, refreshRate: Int): IntArray {
        var newRefreshRate = refreshRate
        val videoType = MediaFormat.MIMETYPE_VIDEO_AVC
        // Get max size from the decoder, to ensure recordings will be playable on device
        val decoder = MediaCodec.createDecoderByType(videoType)
        val vc = decoder.codecInfo.getCapabilitiesForType(videoType).videoCapabilities
        decoder.release()
        // Check if we can support screen size as-is
        val width = vc.supportedWidths.upper
        val height = vc.supportedHeights.upper
        var screenWidthAligned = screenWidth
        if (screenWidthAligned % vc.widthAlignment != 0) {
            screenWidthAligned -= screenWidthAligned % vc.widthAlignment
        }
        var screenHeightAligned = screenHeight
        if (screenHeightAligned % vc.heightAlignment != 0) {
            screenHeightAligned -= screenHeightAligned % vc.heightAlignment
        }
        if (width >= screenWidthAligned && height >= screenHeightAligned && vc.isSizeSupported(
                screenWidthAligned,
                screenHeightAligned
            )
        ) {
            // Desired size is supported, now get the rate
            val maxRate = vc.getSupportedFrameRatesFor(
                screenWidthAligned,
                screenHeightAligned
            ).upper.toInt()
            if (maxRate < newRefreshRate) {
                newRefreshRate = maxRate
            }
            println("AAA Screen size supported at rate $newRefreshRate")
            return intArrayOf(screenWidthAligned, screenHeightAligned, newRefreshRate)
        }
        // Otherwise, resize for max supported size
        val scale = (width.toDouble() / screenWidth).coerceAtMost(height.toDouble() / screenHeight)
        var scaledWidth = (screenWidth * scale).toInt()
        var scaledHeight = (screenHeight * scale).toInt()
        if (scaledWidth % vc.widthAlignment != 0) {
            scaledWidth -= scaledWidth % vc.widthAlignment
        }
        if (scaledHeight % vc.heightAlignment != 0) {
            scaledHeight -= scaledHeight % vc.heightAlignment
        }
        // Find max supported rate for size
        val maxRate = vc.getSupportedFrameRatesFor(scaledWidth, scaledHeight)
            .upper.toInt()
        if (maxRate < newRefreshRate) {
            newRefreshRate = maxRate
        }
        println(
            "AAA Resized by " + scale + ": " + scaledWidth + ", " + scaledHeight
                    + ", " + newRefreshRate
        )
        return intArrayOf(scaledWidth, scaledHeight, newRefreshRate)
    }

    private fun createVirtualDisplay() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DeviceScreenCapturing",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            null
        )
    }

    private fun startRecording() {
        isRecording.set(true)
        mediaRecorder?.start()
    }

    private fun stopRecording() {
        mediaRecorder?.run {
            if (isRecording.get()) {
                stop()
                release()
                mediaRecorder = null
            }
        }
        isRecording.set(false)
        virtualDisplay?.run {
            release()
            virtualDisplay = null

        }
        mediaProjection?.run {
            stop()
            unregisterCallback(mediaProjectionCallback)
            mediaProjection = null
        }
    }

    private class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            println("AAA MediaProjection Stopped")
        }
    }
}
