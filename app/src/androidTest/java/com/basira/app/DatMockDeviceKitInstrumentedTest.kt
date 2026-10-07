package com.basira.app

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.basira.app.core.DefaultDispatcherProvider
import com.basira.app.data.glasses.DatGlassesRepository
import com.basira.app.data.glasses.DatSdkInitializer
import com.basira.app.data.glasses.MetaAiAppDetector
import com.basira.app.data.glasses.SimulatedGlassesController
import com.basira.app.data.image.ImageProcessor
import com.basira.app.mock.MockVideoFeed
import com.basira.core.reporting.NoOpErrorReporter
import com.basira.core.result.AppResult
import com.basira.domain.model.CameraPermissionStatus
import com.basira.domain.model.LinkStatus
import com.basira.domain.model.RegistrationStatus
import com.basira.domain.model.SessionStatus
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [DatGlassesRepository] against the official DAT MockDeviceKit on a physical phone:
 * registration, device availability, session start, camera permission, and one photo capture.
 *
 * Run with `./gradlew connectedDebugAndroidTest` on a USB-connected phone. Requires no glasses and
 * no Meta AI app. Not executed in CI without a device.
 */
@RunWith(AndroidJUnit4::class)
class DatMockDeviceKitInstrumentedTest {

    private lateinit var context: Context
    private lateinit var kit: MockDeviceKitInterface
    private lateinit var glasses: MockGlasses
    private lateinit var scope: CoroutineScope
    private lateinit var repository: DatGlassesRepository

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT").close()
        kit = MockDeviceKit.getInstance(context)
        kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        glasses.services.camera.setCameraFeed(Uri.fromFile(MockVideoFeed.create(File(context.cacheDir, "mock_feed.mp4"))))
        glasses.services.camera.setCapturedImage(assetUri("02_obstacle_stairs.jpg"))

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sdk = DatSdkInitializer(context, NoOpErrorReporter).also { it.initializeIfPermitted() }
        repository = DatGlassesRepository(
            sdk = sdk,
            metaAi = MetaAiAppDetector(context),
            simulation = object : SimulatedGlassesController {
                override val isActive = true
                override fun prepare() = Unit
            },
            imageProcessor = ImageProcessor(DefaultDispatcherProvider(), NoOpErrorReporter),
            scope = scope,
            errorReporter = NoOpErrorReporter,
        )
    }

    @After
    fun tearDown() {
        repository.stopSession()
        scope.cancel()
        kit.disable()
    }

    private fun assetUri(name: String): Uri {
        val target = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    @Test
    fun registrationAndDeviceAvailabilityAreObserved() = runBlocking {
        val status = withTimeout(15_000) {
            repository.status.first { it.registration == RegistrationStatus.REGISTERED && it.device?.link == LinkStatus.CONNECTED }
        }
        assertTrue(status.isSimulated)
    }

    @Test
    fun sessionStartsAndOnePhotoIsCaptured() = runBlocking {
        withTimeout(15_000) { repository.status.first { it.device?.link == LinkStatus.CONNECTED } }
        repository.startSession()
        withTimeout(20_000) { repository.status.first { it.session == SessionStatus.ACTIVE } }

        val result = repository.captureImage()

        assertTrue("capture failed: $result", result is AppResult.Success)
        val image = (result as AppResult.Success).value
        assertTrue(image.jpegBytes.size in 1..1_500_000)
    }

    @Test
    fun deniedCameraPermissionIsReported() = runBlocking {
        withTimeout(15_000) { repository.status.first { it.registration == RegistrationStatus.REGISTERED } }
        kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)

        assertEquals(CameraPermissionStatus.NOT_GRANTED, repository.refreshCameraPermission())
    }
}
