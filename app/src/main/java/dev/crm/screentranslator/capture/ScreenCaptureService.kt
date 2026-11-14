package dev.crm.screentranslator.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.crm.screentranslator.R
import dev.crm.screentranslator.data.SettingsStore
import dev.crm.screentranslator.pipeline.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single foreground service: owns MediaProjection capture, the translation
 * pipeline, the floating bubble and the overlay windows.
 *
 * One service, one notification, one foreground-service type
 * (`mediaProjection`, required on API 34+). The app UI (MainActivity)
 * observes [isRunning] and sends intents; it never binds to the service.
 *
 * Flow:
 *  1. MainActivity gets MediaProjection consent (system dialog, every session).
 *  2. MainActivity starts this service with ACTION_START + result data.
 *  3. The service creates a VirtualDisplay + ImageReader and shows the
 *     floating bubble.
 *  4. Tap the bubble (or auto-translate on settle) -> ONE still frame ->
 *     [TranslationPipeline] -> overlay windows.
 *  5. ACTION_STOP tears everything down.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: SettingsStore
    private lateinit var pipeline: TranslationPipeline
    private lateinit var overlayWindows: OverlayWindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureController: CaptureController? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        pipeline = TranslationPipeline(this)
        overlayWindows = OverlayWindowManager(this, settings)
        createNotificationChannel()
        // Keep the overlay in sync with pipeline results.
        scope.launch {
            pipeline.bubbles.collect { bubbles ->
                overlayWindows.showBubbles(bubbles)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                if (resultCode == 0 || resultData == null) {
                    Log.w(TAG, "ACTION_START without projection data; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, resultData)
            }
            ACTION_TRANSLATE_NOW -> translateNow()
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return // already running
        startForegroundWithType()
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2,
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslator",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null,
        )
        captureController = CaptureController(imageReader!!, pipeline, scope)
        overlayWindows.showBubble { translateNow() }
        isRunningFlow.value = true
        Log.i(TAG, "Capture started (${metrics.widthPixels}x${metrics.heightPixels})")
    }

    /** Tap the bubble -> capture one settled frame -> translate it. */
    private fun translateNow() {
        scope.launch {
            captureController?.captureSettledAndTranslate()
        }
    }

    private fun stopCapture() {
        overlayWindows.removeAll()
        pipeline.clearOverlay()
        captureController = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        isRunningFlow.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pipeline.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_active),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.capture_active_desc) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun startForegroundWithType() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_active))
            .setContentText(getString(R.string.capture_active_desc))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.capture_stop_action),
                stopPending,
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_translator_capture"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "dev.crm.screentranslator.START"
        const val ACTION_STOP = "dev.crm.screentranslator.STOP"
        const val ACTION_TRANSLATE_NOW = "dev.crm.screentranslator.TRANSLATE_NOW"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private val isRunningFlow = MutableStateFlow(false)

        /** Observed by MainActivity to show start/stop state. */
        val isRunning: StateFlow<Boolean> = isRunningFlow.asStateFlow()

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }

        fun translateIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_TRANSLATE_NOW
            }
    }
}
