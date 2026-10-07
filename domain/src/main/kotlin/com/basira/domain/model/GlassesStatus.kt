package com.basira.domain.model

import com.basira.core.error.AppError
import com.basira.core.error.UpdateTarget

/** Initialization state of the glasses SDK. */
enum class SdkState {
    /** The SDK has not been initialized yet. */
    NOT_INITIALIZED,

    /** The Android Bluetooth permission required by the SDK has not been granted. */
    PERMISSION_REQUIRED,

    /** The SDK is initialized and observing devices. */
    READY,

    /** Initialization failed; glasses features are unavailable. */
    FAILED,
}

/** Registration of this application with Meta AI. */
enum class RegistrationStatus {
    /** The SDK has not reported a registration state yet. */
    UNKNOWN,

    /** The application is not registered. */
    UNREGISTERED,

    /** The registration flow is in progress in Meta AI. */
    REGISTERING,

    /** The application is registered. */
    REGISTERED,

    /** Unregistration is in progress. */
    UNREGISTERING,
}

/** Bluetooth link state of the selected glasses. */
enum class LinkStatus {
    /** Not connected. */
    DISCONNECTED,

    /** Connecting. */
    CONNECTING,

    /** Connected. */
    CONNECTED,
}

/** Whether the glasses are being worn. */
enum class WornStatus {
    /** The glasses report that they are worn. */
    WORN,

    /** The glasses report that they are not worn. */
    NOT_WORN,

    /** The glasses did not report a worn state. */
    UNKNOWN,
}

/** Compatibility between the SDK, the glasses firmware, and the on-glasses DAT app. */
enum class CompatibilityStatus {
    /** Compatible. */
    COMPATIBLE,

    /** Glasses firmware must be updated from Meta AI. */
    FIRMWARE_UPDATE_REQUIRED,

    /** This application must be rebuilt with a newer SDK. */
    APP_UPDATE_REQUIRED,

    /** Not reported yet. */
    UNKNOWN,
}

/** Lifecycle of the device session owned by the application. */
enum class SessionStatus {
    /** No session exists. */
    IDLE,

    /** A session is being created or started. */
    CONNECTING,

    /** The session is started and capabilities can be attached. */
    ACTIVE,

    /** The device paused the session, for example because the glasses were removed. */
    PAUSED,

    /** The session ended unexpectedly and a bounded reconnect is scheduled. */
    RECONNECTING,

    /** The session failed and will not be retried automatically. */
    FAILED,
}

/** DAT camera permission state. */
enum class CameraPermissionStatus {
    /** Not checked yet. */
    UNKNOWN,

    /** Granted in Meta AI. */
    GRANTED,

    /** Not granted yet; the user must grant it through Meta AI. */
    NOT_GRANTED,

    /** The user explicitly denied the request in Meta AI. Never re-requested automatically. */
    DENIED_BY_USER,
}

/**
 * Framework-independent snapshot of the selected glasses.
 *
 * @property name user-visible device name.
 * @property link Bluetooth link state.
 * @property worn worn state.
 * @property hingeOpen whether the glasses are unfolded; `null` when unknown.
 * @property batteryPercent battery level from 0 to 100; `null` when unknown.
 * @property charging whether the glasses are charging; `null` when unknown.
 * @property compatibility compatibility status.
 * @property thermalWarning whether the glasses report a severe or worse thermal level.
 */
data class GlassesDevice(
    val name: String,
    val link: LinkStatus,
    val worn: WornStatus,
    val hingeOpen: Boolean?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val compatibility: CompatibilityStatus,
    val thermalWarning: Boolean,
)

/**
 * Aggregated glasses state observed by the domain layer.
 *
 * @property sdk SDK initialization state.
 * @property metaAiInstalled whether the Meta AI companion app is installed.
 * @property registration registration state with Meta AI.
 * @property device selected device, or `null` when none is linked.
 * @property session device session lifecycle.
 * @property cameraPermission DAT camera permission.
 * @property sessionError last terminal session error, if any.
 * @property isSimulated `true` when backed by MockDeviceKit or local sample images.
 */
data class GlassesStatus(
    val sdk: SdkState = SdkState.NOT_INITIALIZED,
    val metaAiInstalled: Boolean = false,
    val registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
    val device: GlassesDevice? = null,
    val session: SessionStatus = SessionStatus.IDLE,
    val cameraPermission: CameraPermissionStatus = CameraPermissionStatus.UNKNOWN,
    val sessionError: AppError? = null,
    val isSimulated: Boolean = false,
) {
    /**
     * Returns the first condition that prevents a capture, in the order the user must fix them, or
     * `null` when the glasses are ready.
     */
    fun blockingIssue(): AppError? = when {
        sdk == SdkState.FAILED -> AppError.Unexpected(fatal = true)
        !metaAiInstalled -> AppError.MetaAiNotInstalled
        sdk == SdkState.PERMISSION_REQUIRED -> AppError.BluetoothPermissionRequired
        sdk == SdkState.NOT_INITIALIZED -> null
        registration == RegistrationStatus.UNKNOWN -> null
        registration != RegistrationStatus.REGISTERED -> AppError.RegistrationRequired
        device == null -> AppError.GlassesUnavailable
        device.compatibility == CompatibilityStatus.FIRMWARE_UPDATE_REQUIRED ->
            AppError.IncompatibleVersion(UpdateTarget.GLASSES_FIRMWARE)
        device.compatibility == CompatibilityStatus.APP_UPDATE_REQUIRED ->
            AppError.IncompatibleVersion(UpdateTarget.THIS_APP)
        sessionError is AppError.IncompatibleVersion -> sessionError
        device.link != LinkStatus.CONNECTED -> AppError.GlassesDisconnected
        cameraPermission == CameraPermissionStatus.NOT_GRANTED -> AppError.CameraPermissionRequired
        cameraPermission == CameraPermissionStatus.DENIED_BY_USER -> AppError.CameraPermissionDenied
        else -> null
    }

    /** `true` while the SDK is still starting and readiness cannot be judged yet. */
    val isResolving: Boolean
        get() = metaAiInstalled &&
            (sdk == SdkState.NOT_INITIALIZED || sdk == SdkState.READY && registration == RegistrationStatus.UNKNOWN)

    /** `true` while the Meta AI registration flow is running. */
    val isRegistering: Boolean
        get() = registration == RegistrationStatus.REGISTERING
}
