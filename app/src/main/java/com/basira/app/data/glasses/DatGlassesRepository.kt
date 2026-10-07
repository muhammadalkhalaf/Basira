package com.basira.app.data.glasses

import com.basira.app.data.image.ImageProcessor
import com.basira.app.data.image.RawPhoto
import com.basira.core.coroutines.ApplicationScope
import com.basira.core.error.AppError
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.result.AppResult
import com.basira.core.retry.ExponentialBackoff
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
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.ChargingState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.ThermalLevel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [GlassesRepository] backed by the official Meta Wearables Device Access Toolkit (DAT 1.0.0).
 *
 * Lifecycle, following the DAT session documentation:
 * 1. Observe `Wearables.registrationState`, `Wearables.devices`, and `Wearables.devicesMetadata`.
 * 2. [startSession] creates a [DeviceSession] with an [AutoDeviceSelector], subscribes to `state` and
 *    `errors` before calling `start()`, and keeps it open while the user wants an active session.
 * 3. [captureImage] attaches a [Camera] only after the session reports `STARTED`, starts its stream,
 *    waits for `STREAMING`, calls `capturePhoto()` once, then stops and removes the camera
 *    immediately so the glasses camera is never left running.
 * 4. An unexpected stop is reconnected with bounded exponential backoff with jitter. Permission,
 *    registration, and compatibility failures are surfaced and never retried automatically.
 * 5. Every DAT failure is reported through [ErrorReporter], except the ones that only describe the
 *    state of the glasses (disconnected, too hot, no permission), which leave a breadcrumb.
 *
 * Uses only stable (publishable) DAT APIs: the experimental standalone `Camera.photo`, Inputs,
 * Speech, and audio streaming capabilities are intentionally not used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DatGlassesRepository @Inject constructor(
    private val sdk: DatSdkInitializer,
    private val metaAi: MetaAiAppDetector,
    private val simulation: SimulatedGlassesController,
    private val imageProcessor: ImageProcessor,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val errorReporter: ErrorReporter,
) : GlassesRepository {

    private val _status = MutableStateFlow(
        GlassesStatus(metaAiInstalled = simulation.isActive || metaAi.isInstalled(), isSimulated = simulation.isActive),
    )
    override val status: StateFlow<GlassesStatus> = _status.asStateFlow()

    private val deviceSelector by lazy { AutoDeviceSelector() }
    private val captureMutex = Mutex()
    private val backoff = ExponentialBackoff(
        baseDelayMillis = RECONNECT_BASE_MILLIS,
        maxDelayMillis = RECONNECT_MAX_MILLIS,
        maxAttempts = RECONNECT_MAX_ATTEMPTS,
    )

    private var observing = false
    private var sessionWanted = false
    private var session: DeviceSession? = null
    private var sessionJobs: List<Job> = emptyList()
    private var lastSessionError: DeviceSessionError? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    init {
        scope.launch {
            sdk.state.collect { state ->
                _status.update { it.copy(sdk = state) }
                if (state == SdkState.READY) startObserving()
            }
        }
    }

    /** Re-reads environment facts that can change while the app is in the background. */
    fun refreshEnvironment() {
        val installed = simulation.isActive || metaAi.isInstalled()
        _status.update { it.copy(metaAiInstalled = installed) }
        sdk.initializeIfPermitted()
    }

    override fun startSession() {
        sessionWanted = true
        scope.launch { ensureSession() }
    }

    override fun stopSession() {
        sessionWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        session?.stop()
        _status.update { it.copy(session = SessionStatus.IDLE) }
    }

    override suspend fun refreshCameraPermission(): CameraPermissionStatus {
        if (sdk.state.value != SdkState.READY) return CameraPermissionStatus.UNKNOWN
        val status = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            { permission ->
                if (permission == PermissionStatus.Granted) {
                    CameraPermissionStatus.GRANTED
                } else if (_status.value.cameraPermission == CameraPermissionStatus.DENIED_BY_USER) {
                    CameraPermissionStatus.DENIED_BY_USER
                } else {
                    CameraPermissionStatus.NOT_GRANTED
                }
            },
            { error, _ ->
                reportDatError(
                    operation = "dat.checkPermissionStatus",
                    error = error,
                    description = error.description,
                    deviceCondition = error == PermissionError.META_AI_NOT_INSTALLED,
                    outcome = "permission_unknown",
                    severity = ErrorSeverity.WARNING,
                )
                if (error == PermissionError.META_AI_NOT_INSTALLED) {
                    _status.update { it.copy(metaAiInstalled = false) }
                }
                CameraPermissionStatus.UNKNOWN
            },
        )
        if (status != CameraPermissionStatus.UNKNOWN) _status.update { it.copy(cameraPermission = status) }
        return status
    }

    override fun onCameraPermissionResult(granted: Boolean) {
        _status.update {
            it.copy(cameraPermission = if (granted) CameraPermissionStatus.GRANTED else CameraPermissionStatus.DENIED_BY_USER)
        }
    }

    override suspend fun captureImage(): AppResult<CapturedImage> {
        if (!captureMutex.tryLock()) return AppResult.Failure(AppError.CaptureBusy)
        try {
            if (sdk.state.value != SdkState.READY) return AppResult.Failure(AppError.GlassesUnavailable)
            _status.value.blockingIssue()?.let { return AppResult.Failure(it) }
            if (refreshCameraPermission() == CameraPermissionStatus.NOT_GRANTED) {
                return AppResult.Failure(AppError.CameraPermissionRequired)
            }
            val active = awaitActiveSession() ?: return AppResult.Failure(
                lastSessionError?.let(DatErrorMapper::session) ?: AppError.GlassesDisconnected,
            )
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { captureWithCamera(active) }
                ?: reportCaptureFailure("dat.capture", "timeout")
        } finally {
            captureMutex.unlock()
        }
    }

    // region Observation

    private fun startObserving() {
        if (observing) return
        observing = true
        simulation.prepare()
        scope.launch { Wearables.registrationState.collect(::onRegistrationState) }
        scope.launch {
            Wearables.registrationErrorStream.collect { error ->
                reportDatError(
                    operation = "dat.registration",
                    error = error,
                    description = error.description,
                    deviceCondition = error == RegistrationError.META_AI_NOT_INSTALLED,
                    outcome = "registration_not_completed",
                )
                if (error == RegistrationError.META_AI_NOT_INSTALLED) {
                    _status.update { it.copy(metaAiInstalled = false) }
                }
            }
        }
        scope.launch { observeSelectedDevice() }
    }

    private suspend fun onRegistrationState(state: RegistrationState) {
        val mapped = when (state) {
            RegistrationState.REGISTERED -> RegistrationStatus.REGISTERED
            RegistrationState.REGISTERING -> RegistrationStatus.REGISTERING
            RegistrationState.UNREGISTERING -> RegistrationStatus.UNREGISTERING
            RegistrationState.AVAILABLE, RegistrationState.UNAVAILABLE -> RegistrationStatus.UNREGISTERED
        }
        _status.update { it.copy(registration = mapped) }
        if (mapped == RegistrationStatus.REGISTERED) {
            refreshCameraPermission()
            if (sessionWanted) ensureSession()
        } else if (mapped == RegistrationStatus.UNREGISTERED) {
            // Unregistering does not stop a live session; the app must do it (CameraAccess sample).
            session?.stop()
        }
    }

    private suspend fun observeSelectedDevice() {
        combine(deviceSelector.activeDeviceFlow(), Wearables.devices) { active: DeviceIdentifier?, all ->
            active ?: all.firstOrNull()
        }
            .distinctUntilChanged()
            .flatMapLatest { id -> id?.let { Wearables.devicesMetadata[it] } ?: flowOf(null) }
            .collect { device -> onDeviceChanged(device?.let(::toGlassesDevice)) }
    }

    private fun onDeviceChanged(device: GlassesDevice?) {
        val wasConnected = _status.value.device?.link == LinkStatus.CONNECTED
        _status.update { it.copy(device = device) }
        val isConnected = device?.link == LinkStatus.CONNECTED
        if (isConnected && !wasConnected && sessionWanted && session == null) {
            // A device came back: give the reconnect budget back and try immediately.
            reconnectAttempt = 0
            reconnectJob?.cancel()
            scope.launch { ensureSession() }
        }
    }

    private fun toGlassesDevice(device: Device): GlassesDevice = GlassesDevice(
        name = device.name,
        link = when (device.linkState) {
            LinkState.CONNECTED -> LinkStatus.CONNECTED
            LinkState.CONNECTING -> LinkStatus.CONNECTING
            LinkState.DISCONNECTED -> LinkStatus.DISCONNECTED
        },
        worn = when (device.donState) {
            DonState.DONNED -> WornStatus.WORN
            DonState.DOFFED -> WornStatus.NOT_WORN
            DonState.UNKNOWN -> WornStatus.UNKNOWN
        },
        hingeOpen = when (device.hingeState) {
            HingeState.OPEN -> true
            HingeState.CLOSED -> false
            HingeState.UNKNOWN -> null
        },
        batteryPercent = device.batteryLevel.takeIf { it in 0..100 },
        charging = when (device.chargingState) {
            ChargingState.CHARGING -> true
            ChargingState.NOT_CHARGING -> false
            ChargingState.UNKNOWN -> null
        },
        compatibility = when (device.compatibility) {
            DeviceCompatibility.COMPATIBLE -> CompatibilityStatus.COMPATIBLE
            DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> CompatibilityStatus.FIRMWARE_UPDATE_REQUIRED
            DeviceCompatibility.SDK_UPDATE_REQUIRED -> CompatibilityStatus.APP_UPDATE_REQUIRED
            DeviceCompatibility.UNDEFINED -> CompatibilityStatus.UNKNOWN
        },
        thermalWarning = device.thermalLevel in HOT_LEVELS,
    )

    // endregion

    // region Session

    @Synchronized
    private fun ensureSession() {
        if (session != null) return
        if (sdk.state.value != SdkState.READY) return
        if (_status.value.registration != RegistrationStatus.REGISTERED) return
        _status.update { it.copy(session = SessionStatus.CONNECTING) }
        Wearables.createSession(deviceSelector).fold(
            { created ->
                session = created
                lastSessionError = null
                // Subscribe before start() so no transition is missed (DAT docs).
                sessionJobs = listOf(
                    scope.launch { created.state.collect { onSessionState(created, it) } },
                    scope.launch { created.errors.collect { onSessionError(it) } },
                )
                created.start()
            },
            { error, _ ->
                onSessionError(error, operation = "dat.createSession")
                onSessionEnded()
            },
        )
    }

    private fun onSessionState(owner: DeviceSession, state: DeviceSessionState) {
        if (owner !== session) return
        val mapped = when (state) {
            DeviceSessionState.IDLE, DeviceSessionState.STARTING -> SessionStatus.CONNECTING
            DeviceSessionState.STARTED -> SessionStatus.ACTIVE
            DeviceSessionState.PAUSED -> SessionStatus.PAUSED
            DeviceSessionState.STOPPING -> _status.value.session
            DeviceSessionState.STOPPED -> null
        }
        if (mapped == null) {
            sessionJobs.forEach { it.cancel() }
            sessionJobs = emptyList()
            session = null
            onSessionEnded()
            return
        }
        if (mapped == SessionStatus.ACTIVE) reconnectAttempt = 0
        _status.update { it.copy(session = mapped, sessionError = null) }
    }

    private fun onSessionError(error: DeviceSessionError, operation: String = "dat.session") {
        reportDatError(
            operation = operation,
            error = error,
            description = error.description,
            deviceCondition = DatErrorMapper.isDeviceCondition(error),
            outcome = if (DatErrorMapper.isReconnectable(error)) "reconnect_scheduled" else "session_error_shown",
        )
        if (DatErrorMapper.isNonBlocking(error)) return
        lastSessionError = error
        val mapped = DatErrorMapper.session(error)
        _status.update { it.copy(sessionError = mapped) }
        if (error == DeviceSessionError.CAPABILITY_DENIED) {
            _status.update { it.copy(cameraPermission = CameraPermissionStatus.NOT_GRANTED) }
        }
    }

    private fun onSessionEnded() {
        val error = lastSessionError
        if (!sessionWanted) {
            _status.update { it.copy(session = SessionStatus.IDLE) }
            return
        }
        if (!DatErrorMapper.isReconnectable(error)) {
            _status.update { it.copy(session = SessionStatus.FAILED) }
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectAttempt += 1
        val wait = backoff.delayForAttempt(reconnectAttempt)
        if (wait == null) {
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.GLASSES,
                    operation = "dat.reconnect",
                    severity = ErrorSeverity.ERROR,
                    outcome = "session_failed",
                    reason = "budget_exhausted",
                    attributes = mapOf(
                        "dat.attempts" to (reconnectAttempt - 1).toString(),
                        "dat.last_error" to (lastSessionError?.let(DatErrorMapper::reason) ?: "none"),
                    ),
                ),
            )
            _status.update { it.copy(session = SessionStatus.FAILED) }
            return
        }
        _status.update { it.copy(session = SessionStatus.RECONNECTING) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (sessionWanted) ensureSession()
        }
    }

    private suspend fun awaitActiveSession(): DeviceSession? {
        if (_status.value.session != SessionStatus.ACTIVE) {
            sessionWanted = true
            if (session == null) {
                reconnectJob?.cancel()
                reconnectAttempt = 0
                ensureSession()
            }
            withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS) {
                status.first { it.session == SessionStatus.ACTIVE || it.session == SessionStatus.FAILED }
            }
        }
        return session.takeIf { _status.value.session == SessionStatus.ACTIVE }
    }

    // endregion

    // region Capture

    /** First relevant stream event after `start()`. */
    private sealed interface StreamOutcome {
        /** Frames are flowing; `capturePhoto()` may be called. */
        data object Streaming : StreamOutcome

        /** The stream closed before streaming. */
        data object Closed : StreamOutcome

        /**
         * A blocking stream error.
         *
         * @property error the DAT stream error.
         */
        data class Failed(val error: StreamError) : StreamOutcome
    }

    private suspend fun captureWithCamera(active: DeviceSession): AppResult<CapturedImage> {
        val added = active.addCamera(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = STREAM_FRAME_RATE, compressVideo = true),
        )
        added.errorOrNull()?.let { error ->
            reportDatError("dat.addCamera", error, error.description, DatErrorMapper.isDeviceCondition(error), "error_announced")
            return AppResult.Failure(DatErrorMapper.session(error))
        }
        val camera = added.getOrNull() ?: return reportCaptureFailure("dat.addCamera", "no_camera")
        try {
            val stream = camera.stream
            stream.start().errorOrNull()?.let { error ->
                reportDatError("dat.stream.start", error, error.toString(), DatErrorMapper.isDeviceCondition(error), "error_announced")
                return AppResult.Failure(DatErrorMapper.stream(error))
            }
            val outcome = withTimeoutOrNull(STREAM_START_TIMEOUT_MILLIS) {
                merge(
                    stream.state.filter { it == StreamState.STREAMING || it == StreamState.CLOSED }
                        .map { if (it == StreamState.STREAMING) StreamOutcome.Streaming else StreamOutcome.Closed },
                    // STREAM_ERROR is informational per DAT docs and must not abort the capture.
                    stream.errorStream.filter { it != StreamError.STREAM_ERROR }.map { StreamOutcome.Failed(it) },
                ).first()
            }
            when (outcome) {
                null -> return reportCaptureFailure("dat.stream.awaitStreaming", "timeout")
                StreamOutcome.Closed -> {
                    errorReporter.breadcrumb("dat.stream.awaitStreaming CLOSED [device condition]")
                    return AppResult.Failure(AppError.GlassesDisconnected)
                }
                is StreamOutcome.Failed -> {
                    val error = outcome.error
                    reportDatError("dat.stream", error, error.toString(), DatErrorMapper.isDeviceCondition(error), "error_announced")
                    return AppResult.Failure(DatErrorMapper.stream(error))
                }
                StreamOutcome.Streaming -> Unit
            }
            val photo = stream.capturePhoto()
            photo.errorOrNull()?.let { error ->
                reportDatError("dat.capturePhoto", error, error.toString(), DatErrorMapper.isDeviceCondition(error), "error_announced")
                return AppResult.Failure(DatErrorMapper.capture(error))
            }
            val data = photo.getOrNull() ?: return reportCaptureFailure("dat.capturePhoto", "no_data")
            return imageProcessor.process(data.toRawPhoto())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            withContext(NonCancellable) { releaseCamera(active, camera) }
        }
    }

    private fun releaseCamera(owner: DeviceSession, camera: Camera) {
        // Stopping the camera cascades to its stream; removing it lets a later addCamera succeed.
        runCatching { camera.stop() }.onFailure {
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.GLASSES,
                    operation = "dat.camera.stop",
                    severity = ErrorSeverity.WARNING,
                    outcome = "camera_removed_anyway",
                    throwable = it,
                ),
            )
        }
        owner.removeCamera().onFailure { error, _ ->
            if (error != DeviceSessionError.CAPABILITY_NOT_FOUND) {
                reportDatError(
                    operation = "dat.removeCamera",
                    error = error,
                    description = error.description,
                    deviceCondition = DatErrorMapper.isDeviceCondition(error),
                    outcome = "camera_left_attached",
                    severity = ErrorSeverity.WARNING,
                )
            }
        }
    }

    private fun PhotoData.toRawPhoto(): RawPhoto = when (this) {
        is PhotoData.Bitmap -> RawPhoto.Decoded(bitmap)
        is PhotoData.HEIC -> {
            val buffer = data.duplicate().apply { rewind() }
            RawPhoto.Encoded(ByteArray(buffer.remaining()).also { buffer.get(it) })
        }
        else -> RawPhoto.Encoded(ByteArray(0))
    }

    // endregion

    // region Reporting

    /** Reports a typed DAT error, or only leaves a breadcrumb when it describes the glasses' state. */
    private fun reportDatError(
        operation: String,
        error: Any,
        description: String,
        deviceCondition: Boolean,
        outcome: String,
        severity: ErrorSeverity = ErrorSeverity.ERROR,
    ) {
        val reason = DatErrorMapper.reason(error)
        if (deviceCondition) {
            errorReporter.breadcrumb("$operation $reason [device condition]")
            return
        }
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.GLASSES,
                operation = operation,
                severity = severity,
                outcome = outcome,
                message = description,
                reason = reason,
            ),
        )
    }

    /** Reports a capture step that failed without a DAT error and returns the failure the user hears. */
    private fun reportCaptureFailure(operation: String, reason: String): AppResult.Failure {
        errorReporter.report(
            ErrorReport(
                domain = ErrorDomain.GLASSES,
                operation = operation,
                severity = ErrorSeverity.ERROR,
                outcome = "error_announced",
                reason = reason,
            ),
        )
        return AppResult.Failure(AppError.CaptureFailed)
    }

    // endregion

    private companion object {
        const val STREAM_FRAME_RATE = 7
        const val SESSION_START_TIMEOUT_MILLIS = 20_000L
        const val STREAM_START_TIMEOUT_MILLIS = 15_000L
        const val CAPTURE_TIMEOUT_MILLIS = 30_000L
        const val RECONNECT_BASE_MILLIS = 1_000L
        const val RECONNECT_MAX_MILLIS = 30_000L
        const val RECONNECT_MAX_ATTEMPTS = 5
        val HOT_LEVELS = setOf(ThermalLevel.SEVERE, ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN)
    }
}
