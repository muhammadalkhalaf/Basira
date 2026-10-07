package com.basira.domain.assistant

import com.basira.core.error.AppError
import com.basira.core.error.UpdateTarget
import com.basira.domain.model.AnalysisMode
import com.basira.domain.usecase.NoResultReason

/**
 * Every user-visible state of the assistant. Each value has a spoken announcement and a recovery
 * action in the presentation layer.
 */
sealed interface AssistantPhase {

    /** Dependencies are still starting. */
    data object Initializing : AssistantPhase

    /** The Meta AI app is not installed. */
    data object MetaAiMissing : AssistantPhase

    /** The Android Bluetooth permission is missing. */
    data object BluetoothPermissionRequired : AssistantPhase

    /** The app must be registered with Meta AI. */
    data object RegistrationRequired : AssistantPhase

    /** The Meta AI registration flow is running. */
    data object Registering : AssistantPhase

    /** No glasses are linked. */
    data object GlassesUnavailable : AssistantPhase

    /** Linked glasses are not connected. */
    data object GlassesDisconnected : AssistantPhase

    /** The camera permission must be requested. */
    data object PermissionRequired : AssistantPhase

    /** The user denied the camera permission. */
    data object PermissionDenied : AssistantPhase

    /**
     * Firmware, the on-glasses DAT app, or this app must be updated.
     *
     * @property target the component to update.
     */
    data class UnsupportedVersion(val target: UpdateTarget) : AssistantPhase

    /**
     * Speech in the app language cannot be produced.
     *
     * @property voiceMissing `true` when an engine exists but lacks voice data for the app language.
     */
    data class SpeechUnavailable(val voiceMissing: Boolean) : AssistantPhase

    /** No internet connection. */
    data object Offline : AssistantPhase

    /** Ready for a request. */
    data object Ready : AssistantPhase

    /** Listening for a voice command. */
    data object Listening : AssistantPhase

    /**
     * Capturing a photo.
     *
     * @property mode requested mode.
     */
    data class Capturing(val mode: AnalysisMode) : AssistantPhase

    /**
     * Uploading the photo.
     *
     * @property mode requested mode.
     */
    data class Uploading(val mode: AnalysisMode) : AssistantPhase

    /**
     * Waiting for the analysis.
     *
     * @property mode requested mode.
     */
    data class Analyzing(val mode: AnalysisMode) : AssistantPhase

    /** Speaking a description. */
    data object Speaking : AssistantPhase

    /** A description was delivered and can be repeated. */
    data object Loaded : AssistantPhase

    /**
     * The vision provider answered without a speakable result.
     *
     * @property reason why.
     */
    data class EmptyResult(val reason: NoResultReason) : AssistantPhase

    /** The request timed out. */
    data object Timeout : AssistantPhase

    /**
     * The vision provider rate-limited the request.
     *
     * @property retryAfterMillis suggested wait, if known.
     */
    data class RateLimited(val retryAfterMillis: Long?) : AssistantPhase

    /** The application token expired or was rejected. */
    data object AuthenticationExpired : AssistantPhase

    /** The captured image was unusable. */
    data object InvalidImage : AssistantPhase

    /** The vision provider response was malformed. */
    data object InvalidServerResponse : AssistantPhase

    /**
     * A failure the user can retry.
     *
     * @property error underlying failure.
     */
    data class RecoverableError(val error: AppError) : AssistantPhase

    /**
     * A failure that needs an app restart, reinstall, or operator action.
     *
     * @property error underlying failure.
     */
    data class FatalError(val error: AppError) : AssistantPhase

    /** `true` while an operation is running that must not be started twice. */
    val isBusy: Boolean
        get() = this is Listening || this is Capturing || this is Uploading || this is Analyzing

    /** `true` for phases that describe the environment rather than the outcome of a request. */
    val isEnvironmental: Boolean
        get() = this is MetaAiMissing || this is BluetoothPermissionRequired || this is RegistrationRequired ||
            this is Registering || this is GlassesUnavailable || this is GlassesDisconnected ||
            this is PermissionRequired || this is PermissionDenied || this is UnsupportedVersion ||
            this is SpeechUnavailable || this is Offline

    companion object {
        /**
         * Maps a failure to the phase that represents it.
         *
         * @param error failure to map.
         * @return the corresponding phase.
         */
        fun fromError(error: AppError): AssistantPhase = when (error) {
            AppError.MetaAiNotInstalled -> MetaAiMissing
            AppError.BluetoothPermissionRequired -> BluetoothPermissionRequired
            AppError.RegistrationRequired, AppError.RegistrationFailed -> RegistrationRequired
            AppError.GlassesUnavailable -> GlassesUnavailable
            AppError.GlassesDisconnected -> GlassesDisconnected
            AppError.CameraPermissionRequired -> PermissionRequired
            AppError.CameraPermissionDenied -> PermissionDenied
            is AppError.IncompatibleVersion -> UnsupportedVersion(error.target)
            AppError.TextToSpeechUnavailable -> SpeechUnavailable(voiceMissing = false)
            AppError.VoiceDataMissing -> SpeechUnavailable(voiceMissing = true)
            AppError.Offline -> Offline
            AppError.Timeout -> Timeout
            is AppError.RateLimited -> RateLimited(error.retryAfterMillis)
            AppError.AuthenticationExpired -> AuthenticationExpired
            AppError.InvalidImage -> InvalidImage
            AppError.InvalidServerResponse -> InvalidServerResponse
            AppError.Forbidden, AppError.ServiceNotConfigured, AppError.ApiKeyRejected -> FatalError(error)
            is AppError.Unexpected -> if (error.fatal) FatalError(error) else RecoverableError(error)
            else -> RecoverableError(error)
        }
    }
}
