package dev.crm.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.crm.screentranslator.capture.ScreenCaptureService
import dev.crm.screentranslator.data.Engine
import dev.crm.screentranslator.data.SettingsStore
import dev.crm.screentranslator.ui.EnginePickerSheet
import dev.crm.screentranslator.ui.HomeScreen
import dev.crm.screentranslator.ui.ModelManagerScreen
import dev.crm.screentranslator.ui.SettingsScreen

private enum class Screen { HOME, SETTINGS, MODELS }

/**
 * Launcher activity. Owns the three permission flows (overlay, notification,
 * MediaProjection) and hosts the Compose screens. No translation logic lives
 * here — that is [ScreenCaptureService] + the pipeline.
 *
 * Start flow (in order): overlay permission -> notification permission
 * (API 33+) -> MediaProjection consent dialog -> foreground service.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var projectionManager: MediaProjectionManager

    private var hasOverlayPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                ScreenCaptureService.startIntent(this, result.resultCode, result.data!!),
            )
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) launchProjectionIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        refreshPermissions()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.HOME) }
                    var showEngineSheet by remember { mutableStateOf(false) }
                    var engine by remember { mutableStateOf(settings.engine) }
                    var sourceLang by remember { mutableStateOf(settings.sourceLanguage) }
                    var targetLang by remember { mutableStateOf(settings.targetLanguage) }
                    val isRunning by ScreenCaptureService.isRunning.collectAsState()

                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            isRunning = isRunning,
                            hasOverlayPermission = hasOverlayPermission,
                            hasNotificationPermission = hasNotificationPermission,
                            engine = engine,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            onStart = ::startCaptureFlow,
                            onStop = {
                                startService(ScreenCaptureService.stopIntent(this))
                            },
                            onPickEngine = { showEngineSheet = true },
                            onSourceLangChange = {
                                sourceLang = it; settings.sourceLanguage = it
                            },
                            onTargetLangChange = {
                                targetLang = it; settings.targetLanguage = it
                            },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenModels = { screen = Screen.MODELS },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onOpenModels = { screen = Screen.MODELS },
                            onBack = { screen = Screen.HOME },
                        )
                        Screen.MODELS -> ModelManagerScreen(
                            onBack = { screen = Screen.HOME },
                        )
                    }

                    if (showEngineSheet) {
                        EnginePickerSheet(
                            current = engine,
                            onSelect = {
                                engine = it
                                settings.engine = it
                                showEngineSheet = false
                            },
                            onDismiss = { showEngineSheet = false },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        hasNotificationPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCaptureFlow() {
        if (!hasOverlayPermission) {
            // System overlay-permission screen; user returns via onResume.
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        if (!hasNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjectionIntent()
    }

    private fun launchProjectionIntent() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
