package com.basira.core.error

/**
 * Typed, framework-independent failure model shared by every layer of the application.
 *
 * Data-layer implementations translate SDK, HTTP, and platform failures into one of these values so
 * that the domain and presentation layers never see DAT, Retrofit, or Android types. Every value is
 * immutable and safe to log because none of them carry image bytes, recognized text, or tokens.
 */
sealed interface AppError {

    /** The Meta AI companion app is not installed, so DAT registration and permissions cannot work. */
    data object MetaAiNotInstalled : AppError

    /** The Android Bluetooth permission needed by the glasses SDK has not been granted. */
    data object BluetoothPermissionRequired : AppError

    /** The application has not been registered with Meta AI through the DAT registration flow. */
    data object RegistrationRequired : AppError

    /** The DAT registration flow failed or was rejected; it must be restarted by the user. */
    data object RegistrationFailed : AppError

    /** No linked glasses are currently known to the SDK. */
    data object GlassesUnavailable : AppError

    /** Linked glasses exist but the link or the device session dropped. */
    data object GlassesDisconnected : AppError

    /** The DAT camera permission has not been requested yet. */
    data object CameraPermissionRequired : AppError

    /** The user denied the DAT camera permission in Meta AI. Never retried automatically. */
    data object CameraPermissionDenied : AppError

    /**
     * Glasses firmware, the on-glasses DAT app, or this application's SDK build is incompatible.
     *
     * @property target which component must be updated to recover.
     */
    data class IncompatibleVersion(val target: UpdateTarget) : AppError

    /**
     * The glasses refused or aborted work for a device-health reason.
     *
     * @property reason the health condition reported by the device.
     */
    data class DeviceHealth(val reason: DeviceHealthReason) : AppError

    /** A capture is already running on the glasses. */
    data object CaptureBusy : AppError

    /** The glasses camera failed to produce an image. */
    data object CaptureFailed : AppError

    /** The captured image could not be decoded, processed, or was rejected by the vision provider. */
    data object InvalidImage : AppError

    /** Object-finding mode was requested without a usable object name. */
    data object MissingTargetObject : AppError

    /** The phone has no validated internet connection. */
    data object Offline : AppError

    /** The vision provider or the network did not answer within the configured time budget. */
    data object Timeout : AppError

    /**
     * The vision provider rejected the request because of quotas or rate limits.
     *
     * @property retryAfterMillis server-provided delay before another request is acceptable, if any.
     */
    data class RateLimited(val retryAfterMillis: Long?) : AppError

    /** The short-lived application token is missing, expired, or rejected (HTTP 401). */
    data object AuthenticationExpired : AppError

    /** The vision provider refused the request for this installation (HTTP 403 from the provider). */
    data object Forbidden : AppError

    /** The vision provider answered with a body that does not satisfy the response contract. */
    data object InvalidServerResponse : AppError

    /**
     * The vision provider failed with a server-side error.
     *
     * @property httpCode HTTP status code returned by the server.
     */
    data class ServerError(val httpCode: Int) : AppError

    /** A transport-level failure (DNS, TLS, connection reset) that is not a timeout. */
    data object Transport : AppError

    /** No Text-to-Speech engine is installed or it failed to initialize. */
    data object TextToSpeechUnavailable : AppError

    /** A Text-to-Speech engine exists but has no Arabic voice data installed. */
    data object ArabicVoiceMissing : AppError

    /** Speech recognition is unavailable on this phone. */
    data object SpeechRecognitionUnavailable : AppError

    /** The microphone permission for voice commands was not granted. */
    data object MicrophonePermissionRequired : AppError

    /** Speech recognition finished without recognizing a supported command. */
    data object VoiceCommandNotUnderstood : AppError

    /**
     * The description service is not configured in this build, for example the Gemini API key is
     * missing or the configured model does not exist. No request is sent.
     */
    data object ServiceNotConfigured : AppError

    /**
     * The AI provider rejected the API key (invalid, expired, disabled, or restricted away from this
     * API). Never retried automatically; the operator must ship a build with a working key.
     */
    data object ApiKeyRejected : AppError

    /** The AI provider's daily quota or prepaid credit is exhausted. Not retried automatically. */
    data object QuotaExceeded : AppError

    /** The AI provider blocked this request or its answer for policy or safety reasons. */
    data object ContentBlocked : AppError

    /** The operation was cancelled by the user or by lifecycle teardown. */
    data object Cancelled : AppError

    /**
     * An unexpected failure that has no dedicated mapping.
     *
     * @property fatal `true` when the application cannot continue without a restart or reinstall.
     */
    data class Unexpected(val fatal: Boolean = false) : AppError
}

/** Component that must be updated to resolve [AppError.IncompatibleVersion]. */
enum class UpdateTarget {
    /** Glasses firmware, updated from the Meta AI app. */
    GLASSES_FIRMWARE,

    /** The DAT application that runs on the glasses, updated from the Meta AI app. */
    GLASSES_DAT_APP,

    /** This Android application must be rebuilt with a newer DAT SDK. */
    THIS_APP,
}

/** Device-health conditions that stop glasses work. */
enum class DeviceHealthReason {
    /** The glasses are too hot to operate the camera. */
    THERMAL,

    /** The glasses battery is too low for camera work. */
    BATTERY_LOW,

    /** The glasses hinge is closed (folded) so the camera cannot be used. */
    FOLDED,
}
