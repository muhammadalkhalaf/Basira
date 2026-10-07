package com.basira.app.data.glasses

import com.basira.core.error.AppError
import com.basira.core.error.DeviceHealthReason
import com.basira.core.error.UpdateTarget
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError

/**
 * Translates typed DAT errors into framework-independent [AppError] values and decides which
 * session failures may be reconnected automatically.
 */
object DatErrorMapper {

    /**
     * Maps a session error.
     *
     * @param error DAT session error.
     * @return the equivalent application error.
     */
    fun session(error: DeviceSessionError): AppError = when (error) {
        DeviceSessionError.NO_ELIGIBLE_DEVICE -> AppError.GlassesUnavailable
        DeviceSessionError.DEVICE_DISCONNECTED,
        DeviceSessionError.SESSION_ENDED_BY_DEVICE,
        DeviceSessionError.DWA_UNAVAILABLE,
        -> AppError.GlassesDisconnected
        DeviceSessionError.CAPABILITY_DENIED -> AppError.CameraPermissionRequired
        DeviceSessionError.THERMAL_CRITICAL,
        DeviceSessionError.THERMAL_EMERGENCY,
        DeviceSessionError.PEAK_POWER_SHUTDOWN,
        -> AppError.DeviceHealth(DeviceHealthReason.THERMAL)
        DeviceSessionError.BATTERY_CRITICAL -> AppError.DeviceHealth(DeviceHealthReason.BATTERY_LOW)
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED -> AppError.IncompatibleVersion(UpdateTarget.GLASSES_DAT_APP)
        DeviceSessionError.INSUFFICIENT_SDK_VERSION -> AppError.IncompatibleVersion(UpdateTarget.THIS_APP)
        DeviceSessionError.SESSION_ALREADY_STOPPED,
        DeviceSessionError.SESSION_IDLE,
        DeviceSessionError.CAPABILITY_ALREADY_ADDED,
        DeviceSessionError.CAPABILITY_NOT_FOUND,
        DeviceSessionError.SESSION_ALREADY_EXISTS,
        DeviceSessionError.UNEXPECTED_ERROR,
        DeviceSessionError.DWA_OUT_OF_STU_RANGE,
        -> AppError.CaptureFailed
    }

    /**
     * Returns `true` when [error] is informational and must not end the session or fail a capture.
     *
     * `DWA_OUT_OF_STU_RANGE` is documented as a non-blocking compatibility warning.
     */
    fun isNonBlocking(error: DeviceSessionError): Boolean = error == DeviceSessionError.DWA_OUT_OF_STU_RANGE

    /**
     * Returns `true` when a session that ended with [error] may be reconnected automatically.
     * Permission, registration, and compatibility failures are never retried automatically.
     */
    fun isReconnectable(error: DeviceSessionError?): Boolean = when (error) {
        null,
        DeviceSessionError.DEVICE_DISCONNECTED,
        DeviceSessionError.SESSION_ENDED_BY_DEVICE,
        DeviceSessionError.DWA_UNAVAILABLE,
        DeviceSessionError.UNEXPECTED_ERROR,
        -> true
        else -> false
    }

    /**
     * Maps a stream error.
     *
     * @param error DAT stream error.
     * @return the equivalent application error.
     */
    fun stream(error: StreamError): AppError = when (error) {
        StreamError.PERMISSIONS_DENIED -> AppError.CameraPermissionRequired
        StreamError.HINGE_CLOSED -> AppError.DeviceHealth(DeviceHealthReason.FOLDED)
        StreamError.THERMAL_HOT, StreamError.PEAK_POWER_LIMIT -> AppError.DeviceHealth(DeviceHealthReason.THERMAL)
        StreamError.BATTERY_LOW -> AppError.DeviceHealth(DeviceHealthReason.BATTERY_LOW)
        StreamError.TIMEOUT, StreamError.CRITICAL_STREAM_ERROR, StreamError.STREAM_ERROR -> AppError.CaptureFailed
    }

    /**
     * Maps a photo-capture error.
     *
     * @param error DAT capture error.
     * @return the equivalent application error.
     */
    fun capture(error: CaptureError): AppError = when (error) {
        is CaptureError.CaptureInProgress -> AppError.CaptureBusy
        is CaptureError.DeviceDisconnected -> AppError.GlassesDisconnected
        is CaptureError.NotStreaming, is CaptureError.CaptureFailed -> AppError.CaptureFailed
        else -> AppError.CaptureFailed
    }
}
