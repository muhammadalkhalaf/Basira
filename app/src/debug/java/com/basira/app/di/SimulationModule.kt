package com.basira.app.di

import android.content.Context
import android.net.Uri
import com.basira.app.core.BuildModes
import com.basira.app.data.glasses.SimulatedGlassesController
import com.basira.app.mock.MockVideoFeed
import com.basira.core.logging.AppLogger
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only [SimulatedGlassesController] that drives DAT MockDeviceKit when the build was made with
 * `-Pbasira.glasses=mockdevicekit`.
 *
 * It enables MockDeviceKit (registered, permissions granted), pairs a simulated Ray-Ban Meta, powers
 * it on, unfolds and dons it, and configures the photo returned by `capturePhoto()` from the bundled
 * sample images. MockDeviceKit refuses to stream without a feed, so `assets/mock/feed.mp4` is used when
 * present and otherwise a short H.264 clip is generated on the device ([MockVideoFeed]).
 */
@Singleton
class MockDeviceKitController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: AppLogger,
    private val errorReporter: ErrorReporter,
) : SimulatedGlassesController {

    override val isActive: Boolean = BuildModes.glassesMode == "mockdevicekit"
    private var prepared = false

    @Synchronized
    override fun prepare() {
        if (!isActive || prepared) return
        prepared = true
        val kit = MockDeviceKit.getInstance(context)
        if (!kit.isEnabled) kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        if (kit.pairedDevices.isNotEmpty()) return
        kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
            { glasses ->
                glasses.powerOn()
                glasses.unfold()
                glasses.don()
                copyAsset("sample_images/01_text_sign.jpg")?.let { glasses.services.camera.setCapturedImage(it) }
                val feed = copyAsset("mock/feed.mp4") ?: generatedFeed()
                feed?.let { glasses.services.camera.setCameraFeed(it) }
                logger.info(TAG, "MockDeviceKit glasses paired")
            },
            { error, _ -> report("mockDeviceKit.pairGlasses", message = error.description) },
        )
    }

    private fun copyAsset(path: String): Uri? = try {
        val target = File(context.cacheDir, "mockdevicekit/${path.substringAfterLast('/')}")
        target.parentFile?.mkdirs()
        context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        Uri.fromFile(target)
    } catch (e: IOException) {
        report("mockDeviceKit.copyAsset", throwable = e, attributes = mapOf("asset.path" to path))
        null
    }

    private fun generatedFeed(): Uri? = try {
        Uri.fromFile(MockVideoFeed.create(File(context.cacheDir, "mockdevicekit/generated_feed.mp4").apply { parentFile?.mkdirs() }))
    } catch (e: IOException) {
        report("mockDeviceKit.generateFeed", throwable = e)
        null
    } catch (e: IllegalStateException) {
        report("mockDeviceKit.generateFeed", throwable = e)
        null
    }

    /** Debug-only: reaches Crashlytics only with `-Pbasira.firebase.debugCollection=true`. */
    private fun report(
        operation: String,
        message: String? = null,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) = errorReporter.report(
        ErrorReport(
            domain = ErrorDomain.GLASSES,
            operation = operation,
            severity = ErrorSeverity.WARNING,
            outcome = "simulation_degraded",
            message = message,
            throwable = throwable,
            attributes = attributes,
        ),
    )

    private companion object {
        const val TAG = "MockDeviceKit"
    }
}

/** Debug binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SimulationModule {
    @Binds abstract fun bind(impl: MockDeviceKitController): SimulatedGlassesController
}
