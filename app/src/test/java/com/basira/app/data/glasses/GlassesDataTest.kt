package com.basira.app.data.glasses

import com.basira.app.testutil.ResourceSampleImageSource
import com.basira.core.error.AppError
import com.basira.core.error.DeviceHealthReason
import com.basira.core.error.UpdateTarget
import com.basira.core.result.AppResult
import com.basira.domain.model.CameraPermissionStatus
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatErrorMapperTest {

    @Test
    fun `session errors map to typed application errors`() {
        assertEquals(AppError.GlassesUnavailable, DatErrorMapper.session(DeviceSessionError.NO_ELIGIBLE_DEVICE))
        assertEquals(AppError.GlassesDisconnected, DatErrorMapper.session(DeviceSessionError.DEVICE_DISCONNECTED))
        assertEquals(AppError.CameraPermissionRequired, DatErrorMapper.session(DeviceSessionError.CAPABILITY_DENIED))
        assertEquals(
            AppError.IncompatibleVersion(UpdateTarget.GLASSES_DAT_APP),
            DatErrorMapper.session(DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED),
        )
        assertEquals(AppError.IncompatibleVersion(UpdateTarget.THIS_APP), DatErrorMapper.session(DeviceSessionError.INSUFFICIENT_SDK_VERSION))
        assertEquals(AppError.DeviceHealth(DeviceHealthReason.BATTERY_LOW), DatErrorMapper.session(DeviceSessionError.BATTERY_CRITICAL))
    }

    @Test
    fun `permission, registration and compatibility failures are never reconnected automatically`() {
        assertTrue(DatErrorMapper.isReconnectable(DeviceSessionError.DEVICE_DISCONNECTED))
        assertTrue(DatErrorMapper.isReconnectable(DeviceSessionError.SESSION_ENDED_BY_DEVICE))
        assertFalse(DatErrorMapper.isReconnectable(DeviceSessionError.CAPABILITY_DENIED))
        assertFalse(DatErrorMapper.isReconnectable(DeviceSessionError.INSUFFICIENT_SDK_VERSION))
        assertFalse(DatErrorMapper.isReconnectable(DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED))
        assertFalse(DatErrorMapper.isReconnectable(DeviceSessionError.THERMAL_CRITICAL))
    }

    @Test
    fun `stream and capture errors`() {
        assertEquals(AppError.CameraPermissionRequired, DatErrorMapper.stream(StreamError.PERMISSIONS_DENIED))
        assertEquals(AppError.DeviceHealth(DeviceHealthReason.FOLDED), DatErrorMapper.stream(StreamError.HINGE_CLOSED))
        assertEquals(AppError.CaptureBusy, DatErrorMapper.capture(CaptureError.CaptureInProgress))
        assertEquals(AppError.GlassesDisconnected, DatErrorMapper.capture(CaptureError.DeviceDisconnected))
        assertTrue(DatErrorMapper.isNonBlocking(DeviceSessionError.DWA_OUT_OF_STU_RANGE))
    }
}

class FakeGlassesRepositoryTest {

    private val repository = FakeGlassesRepository(ResourceSampleImageSource(), captureDelayMillis = 100)

    @Test
    fun `captures cycle through the standard sample images`() = runTest {
        val sizes = (0 until 4).map { (repository.captureImage() as AppResult.Success).value.jpegBytes.size }
        assertEquals(4, sizes.distinct().size)
    }

    @Test
    fun `a concurrent capture is rejected as busy`() = runTest {
        val first = async { repository.captureImage() }
        delay(10)
        assertEquals(AppResult.Failure(AppError.CaptureBusy), repository.captureImage())
        assertTrue(first.await() is AppResult.Success)
    }

    @Test
    fun `disconnection during capture fails the capture`() = runTest {
        val capture = async { repository.captureImage() }
        delay(10)
        repository.simulateConnection(false)
        assertEquals(AppResult.Failure(AppError.GlassesDisconnected), capture.await())
    }

    @Test
    fun `denied permission and missing registration block the capture`() = runTest {
        repository.onCameraPermissionResult(false)
        assertEquals(CameraPermissionStatus.DENIED_BY_USER, repository.status.value.cameraPermission)
        assertEquals(AppResult.Failure(AppError.CameraPermissionDenied), repository.captureImage())

        repository.onCameraPermissionResult(true)
        repository.simulateRegistration(false)
        assertEquals(AppResult.Failure(AppError.RegistrationRequired), repository.captureImage())
    }
}
