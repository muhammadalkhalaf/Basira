package com.basira.app.core

import android.util.Log
import com.basira.app.BuildConfig
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.logging.AppLogger
import com.basira.core.logging.LogRedactor
import com.basira.domain.repository.Clock
import com.basira.domain.repository.RequestIdGenerator
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Production [DispatcherProvider] backed by [Dispatchers]. */
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

/**
 * [AppLogger] writing to Logcat.
 *
 * Every message passes through [LogRedactor]. Release builds drop debug and info messages and record
 * only the exception class name, never its message, so no personal content can leak.
 */
class AndroidLogger @Inject constructor() : AppLogger {
    private val verbose = BuildConfig.DEBUG

    override fun debug(tag: String, message: String) {
        if (verbose) Log.d(prefix(tag), LogRedactor.redact(message))
    }

    override fun info(tag: String, message: String) {
        if (verbose) Log.i(prefix(tag), LogRedactor.redact(message))
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(prefix(tag), LogRedactor.redact(message) + describe(throwable))
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(prefix(tag), LogRedactor.redact(message) + describe(throwable))
    }

    private fun prefix(tag: String) = "Basira:$tag"

    private fun describe(throwable: Throwable?): String =
        if (throwable == null) "" else " [${throwable::class.java.simpleName}]"
}

/** [RequestIdGenerator] producing random UUIDs. */
class UuidRequestIdGenerator @Inject constructor() : RequestIdGenerator {
    override fun next(): String = UUID.randomUUID().toString()
}

/** [Clock] backed by the system wall clock. */
class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Build-time feature switches, see app/build.gradle.kts. */
object BuildModes {
    /** Glasses implementation selected for this build: "real", "mockdevicekit", or "fake". */
    val glassesMode: String get() = BuildConfig.GLASSES_MODE

    /** Vision source for this build: "remote" (Gemini directly) or "fake". */
    val visionMode: String get() = BuildConfig.VISION_MODE

    /** `true` when glasses are simulated (MockDeviceKit or bundled sample images). */
    val isSimulatedGlasses: Boolean get() = glassesMode != "real"

    /** `true` when descriptions come from the bundled fake; Gemini is then never called. */
    val isFakeVision: Boolean get() = visionMode != "remote"
}
