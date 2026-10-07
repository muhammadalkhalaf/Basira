package com.basira.domain.repository

import com.basira.core.result.AppResult
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.CapturedImage
import com.basira.domain.model.GlassesStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Access to the camera of already paired smart glasses.
 *
 * Implementations own the device session and release every camera resource as soon as a capture
 * finishes. Activity-bound flows (registration and the Meta AI permission screen) are not part of
 * this interface because the domain layer must not depend on Android types; the presentation layer
 * launches them and reports results through [onCameraPermissionResult].
 */
interface GlassesRepository {

    /** Continuously updated glasses state. */
    val status: StateFlow<GlassesStatus>

    /**
     * Opens the device session if it is not open yet. Idempotent and non-blocking: the session state
     * is reported through [status]. Unexpected session loss is reconnected with bounded exponential
     * backoff; permission, registration, and compatibility failures are never retried automatically.
     */
    fun startSession()

    /** Closes the device session and cancels any reconnect attempt. Idempotent. */
    fun stopSession()

    /**
     * Re-reads the DAT camera permission from Meta AI.
     *
     * @return the current permission status; [CameraPermissionStatus.UNKNOWN] when it cannot be read.
     */
    suspend fun refreshCameraPermission(): CameraPermissionStatus

    /**
     * Records the outcome of the Meta AI permission screen launched by the presentation layer.
     *
     * @param granted whether the user granted camera access.
     */
    fun onCameraPermissionResult(granted: Boolean)

    /**
     * Captures exactly one still image and releases the camera afterwards.
     *
     * Only one capture may run at a time; a concurrent call fails with
     * [com.basira.core.error.AppError.CaptureBusy]. Cancelling the calling coroutine stops the camera
     * immediately.
     *
     * @return an upload-ready image, or a typed failure such as glasses disconnection or denied
     * permission.
     */
    suspend fun captureImage(): AppResult<CapturedImage>
}
