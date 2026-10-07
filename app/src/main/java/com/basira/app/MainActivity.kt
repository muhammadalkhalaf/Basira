package com.basira.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.basira.app.data.glasses.DatSdkInitializer
import com.basira.app.data.glasses.GlassesSetupLauncher
import com.basira.app.data.glasses.MetaAiAppDetector
import com.basira.app.data.glasses.PendingRegistrationRequest
import com.basira.app.localization.SetupAction
import com.basira.app.presentation.navigation.BasiraNavHost
import com.basira.app.presentation.setup.ActivityEffect
import com.basira.app.presentation.setup.ActivityEffectBus
import com.basira.app.presentation.setup.EnvironmentRefresher
import com.basira.app.presentation.theme.BasiraTheme
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.repository.GlassesRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single activity. It owns every Activity-bound flow (runtime permissions, the Meta AI registration
 * and camera-permission screens, store and settings intents) and executes [ActivityEffect]s sent by
 * ViewModels, so no ViewModel ever holds an Activity reference.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var setupLauncher: GlassesSetupLauncher

    @Inject lateinit var effectBus: ActivityEffectBus

    @Inject lateinit var glasses: GlassesRepository

    @Inject lateinit var engine: AssistantEngine

    @Inject lateinit var datSdk: DatSdkInitializer

    @Inject lateinit var metaAi: MetaAiAppDetector

    @Inject lateinit var environment: EnvironmentRefresher

    @Inject lateinit var errorReporter: ErrorReporter

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Unit>
    private lateinit var bluetoothLauncher: ActivityResultLauncher<String>
    private lateinit var microphoneLauncher: ActivityResultLauncher<String>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private val pendingRegistration = mutableStateOf<PendingRegistrationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerLaunchers()
        enableEdgeToEdge()
        setContent {
            BasiraTheme {
                BasiraNavHost()
                pendingRegistration.value?.let { request -> RegistrationRequestDialog(request) }
            }
        }
        handleDeepLink(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { effectBus.effects.collect(::execute) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        environment.refresh()
    }

    private fun registerLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(setupLauncher.cameraPermissionContract) { granted ->
            glasses.onCameraPermissionResult(granted)
            lifecycleScope.launch { glasses.refreshCameraPermission() }
        }
        bluetoothLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            datSdk.initializeIfPermitted()
            environment.refresh()
        }
        microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The recognizer re-checks the permission and announces when it is still missing.
            engine.dispatch(AssistantAction.ListenForCommand)
        }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.data == null) return
        setupLauncher.handleIntent(intent) { request -> pendingRegistration.value = request }
    }

    private fun execute(effect: ActivityEffect) {
        when (effect) {
            is ActivityEffect.Setup -> runSetup(effect.action)
            ActivityEffect.RequestMicrophoneThenListen -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            ActivityEffect.RequestNotifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppSettings()
            }
            ActivityEffect.Finish -> finishAndRemoveTask()
        }
    }

    private fun runSetup(action: SetupAction) {
        when (action) {
            SetupAction.INSTALL_META_AI -> startSafely(metaAi.storeIntent()) || startSafely(metaAi.storeWebIntent())
            SetupAction.OPEN_META_AI -> metaAi.launchIntent()?.let(::startSafely) ?: startSafely(metaAi.storeIntent())
            SetupAction.ALLOW_BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                datSdk.initializeIfPermitted()
            }
            SetupAction.CONNECT_META_AI -> setupLauncher.startRegistration(this)
            SetupAction.ALLOW_CAMERA -> cameraPermissionLauncher.launch(Unit)
            SetupAction.UPDATE_FIRMWARE -> if (!setupLauncher.openFirmwareUpdate(this)) metaAi.launchIntent()?.let(::startSafely)
            SetupAction.UPDATE_GLASSES_APP -> if (!setupLauncher.openGlassesAppUpdate(this)) metaAi.launchIntent()?.let(::startSafely)
            SetupAction.INSTALL_ARABIC_VOICE ->
                startSafely(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) || startSafely(Intent(TTS_SETTINGS_ACTION))
            SetupAction.OPEN_TTS_SETTINGS -> startSafely(Intent(TTS_SETTINGS_ACTION)) || openAppSettings()
        }
    }

    private fun openAppSettings(): Boolean =
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.UI,
                operation = "activity.start",
                severity = ErrorSeverity.WARNING,
                outcome = "fallback_tried",
                throwable = e,
                reason = intent.action,
            ),
        )
        false
    }

    @androidx.compose.runtime.Composable
    private fun RegistrationRequestDialog(request: PendingRegistrationRequest) {
        AlertDialog(
            onDismissRequest = {
                request.decline()
                pendingRegistration.value = null
            },
            title = { Text(stringResource(R.string.registration_request_title)) },
            text = { Text(stringResource(R.string.registration_request_text)) },
            confirmButton = {
                TextButton(onClick = {
                    request.accept(this)
                    pendingRegistration.value = null
                }) { Text(stringResource(R.string.registration_request_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    request.decline()
                    pendingRegistration.value = null
                }) { Text(stringResource(R.string.registration_request_decline)) }
            },
        )
    }

    private companion object {
        const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
    }
}
