package com.basira.app.presentation.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.basira.app.core.BuildModes
import com.basira.app.data.glasses.DatGlassesRepository
import com.basira.app.localization.SetupAction
import com.basira.domain.model.SpeechAvailability
import com.basira.domain.repository.SpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** One-off requests that need the foreground Activity (permission dialogs, other apps). */
sealed interface ActivityEffect {
    /**
     * Run a setup action.
     *
     * @property action the action.
     */
    data class Setup(val action: SetupAction) : ActivityEffect

    /** Ask for the microphone, then start listening if granted. */
    data object RequestMicrophoneThenListen : ActivityEffect

    /** Ask for the notification permission (Android 13+). */
    data object RequestNotifications : ActivityEffect

    /** Close the app (consent declined). */
    data object Finish : ActivityEffect
}

/**
 * App-scoped channel from ViewModels to the Activity. Effects are consumed exactly once by the
 * started Activity; ViewModels never hold an Activity reference.
 */
@Singleton
class ActivityEffectBus @Inject constructor() {
    private val channel = Channel<ActivityEffect>(Channel.BUFFERED)

    /** Effects to consume. */
    val effects: Flow<ActivityEffect> = channel.receiveAsFlow()

    /**
     * Queues [effect].
     *
     * @param effect effect to deliver.
     */
    fun send(effect: ActivityEffect) {
        channel.trySend(effect)
    }
}

/** Reads Android runtime permission state without exposing [Context] to ViewModels. */
@Singleton
class PermissionStatusProvider @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** Whether the microphone permission is granted. */
    fun hasMicrophone(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    /** Whether the Bluetooth (nearby devices) permission is granted or not needed. */
    fun hasBluetooth(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Whether notifications can be shown. */
    fun hasNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Re-checks facts that can change while the app was in the background. */
class EnvironmentRefresher @Inject constructor(
    private val dat: Provider<DatGlassesRepository>,
    private val speech: SpeechOutput,
) {
    /** Re-reads Meta AI installation, SDK initialization, and voice availability. */
    fun refresh() {
        if (BuildModes.glassesMode != "fake") dat.get().refreshEnvironment()
        val availability = speech.state.value.availability
        if (availability == SpeechAvailability.VOICE_MISSING || availability == SpeechAvailability.ENGINE_UNAVAILABLE) {
            speech.refreshAvailability()
        }
    }
}
