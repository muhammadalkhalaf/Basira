package com.basira.app.data.glasses

import com.basira.core.error.AppError
import com.basira.core.result.AppResult
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.CapturedImage
import com.basira.domain.model.CompatibilityStatus
import com.basira.domain.model.GlassesDevice
import com.basira.domain.model.GlassesStatus
import com.basira.domain.model.LinkStatus
import com.basira.domain.model.RegistrationStatus
import com.basira.domain.model.SdkState
import com.basira.domain.model.SessionStatus
import com.basira.domain.model.WornStatus
import com.basira.domain.repository.GlassesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Supplies JPEG sample images for [FakeGlassesRepository]. */
fun interface SampleImageSource {
    /**
     * Loads the sample at [index], wrapping around the available samples.
     *
     * @param index zero-based capture counter.
     * @return an upload-ready image, or `null` when no sample exists.
     */
    fun load(index: Int): CapturedImage?
}

/**
 * Glasses simulation that needs neither hardware, Meta AI, nor DAT.
 *
 * Every capture returns the next bundled sample image (text, obstacle, indoor, empty frame). Test and
 * debug code can script registration, permission, disconnection, and capture failures through the
 * `simulate*` methods. This is never used in release builds.
 *
 * @property images source of sample images.
 * @property captureDelayMillis simulated capture latency.
 */
class FakeGlassesRepository(
    private val images: SampleImageSource,
    private val captureDelayMillis: Long = DEFAULT_CAPTURE_DELAY_MILLIS,
    initialStatus: GlassesStatus = readyStatus(),
) : GlassesRepository {

    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<GlassesStatus> = _status.asStateFlow()

    private val captureMutex = Mutex()
    private var captureIndex = 0
    private var nextFailure: AppError? = null

    /** Number of completed capture attempts. */
    var captureCount: Int = 0
        private set

    override fun startSession() {
        _status.update { if (it.device?.link == LinkStatus.CONNECTED) it.copy(session = SessionStatus.ACTIVE) else it }
    }

    override fun stopSession() {
        _status.update { it.copy(session = SessionStatus.IDLE) }
    }

    override suspend fun refreshCameraPermission(): CameraPermissionStatus = _status.value.cameraPermission

    override fun onCameraPermissionResult(granted: Boolean) {
        _status.update {
            it.copy(cameraPermission = if (granted) CameraPermissionStatus.GRANTED else CameraPermissionStatus.DENIED_BY_USER)
        }
    }

    override suspend fun captureImage(): AppResult<CapturedImage> {
        if (!captureMutex.tryLock()) return AppResult.Failure(AppError.CaptureBusy)
        try {
            captureCount++
            _status.value.blockingIssue()?.let { return AppResult.Failure(it) }
            delay(captureDelayMillis)
            nextFailure?.let {
                nextFailure = null
                return AppResult.Failure(it)
            }
            if (_status.value.device?.link != LinkStatus.CONNECTED) return AppResult.Failure(AppError.GlassesDisconnected)
            val image = images.load(captureIndex++) ?: return AppResult.Failure(AppError.CaptureFailed)
            return AppResult.Success(image)
        } finally {
            captureMutex.unlock()
        }
    }

    /** Simulates completing the Meta AI registration flow. */
    fun simulateRegistration(registered: Boolean) {
        _status.update { it.copy(registration = if (registered) RegistrationStatus.REGISTERED else RegistrationStatus.UNREGISTERED) }
    }

    /**
     * Simulates the glasses link going up or down.
     *
     * @param connected new link state.
     */
    fun simulateConnection(connected: Boolean) {
        _status.update { status ->
            status.copy(
                device = status.device?.copy(link = if (connected) LinkStatus.CONNECTED else LinkStatus.DISCONNECTED),
                session = if (connected) status.session else SessionStatus.RECONNECTING,
            )
        }
    }

    /**
     * Makes the next capture fail with [error].
     *
     * @param error failure to return.
     */
    fun failNextCapture(error: AppError) {
        nextFailure = error
    }

    /**
     * Replaces the whole status, for tests.
     *
     * @param transform status transformation.
     */
    fun updateStatus(transform: (GlassesStatus) -> GlassesStatus) {
        _status.update(transform)
    }

    /** Factory helpers. */
    companion object {
        /** Default simulated capture latency. */
        const val DEFAULT_CAPTURE_DELAY_MILLIS: Long = 600L

        /** A fully ready simulated pair of glasses. */
        fun readyStatus(): GlassesStatus = GlassesStatus(
            sdk = SdkState.READY,
            metaAiInstalled = true,
            registration = RegistrationStatus.REGISTERED,
            device = GlassesDevice(
                name = "Simulated Ray-Ban Meta",
                link = LinkStatus.CONNECTED,
                worn = WornStatus.WORN,
                hingeOpen = true,
                batteryPercent = 85,
                charging = false,
                compatibility = CompatibilityStatus.COMPATIBLE,
                thermalWarning = false,
            ),
            session = SessionStatus.IDLE,
            cameraPermission = CameraPermissionStatus.GRANTED,
            isSimulated = true,
        )
    }
}
