package com.basira.app.controllers.reporting

import com.basira.app.BuildConfig
import com.basira.app.core.BuildModes
import com.basira.core.logging.AppLogger
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.NetworkFailureKind
import com.basira.core.reporting.rootCause
import com.basira.domain.model.ConnectivityStatus
import com.basira.domain.repository.ConnectivityObserver
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a report ends up. Production writes to Crashlytics; tests record what would have been sent.
 */
internal interface ReportSink {

    /** Writes one breadcrumb line. */
    fun log(line: String)

    /**
     * Records one non-fatal.
     *
     * @param description the line logged right before it.
     * @param failure the throwable Crashlytics groups the issue by.
     * @param keys custom keys attached to this one event (String, Boolean, Int, or Long values).
     */
    fun record(description: String, failure: Throwable, keys: Map<String, Any>)
}

/**
 * [ReportSink] backed by Firebase Crashlytics.
 *
 * Whether anything is sent is decided by the manifest switch the app already uses (on in release, off
 * in debug unless `basira.firebase.debugCollection=true`), so a debug report is not even recorded on
 * the device. Without `google-services.json` Firebase is not initialized and every call throws, which
 * [CrashlyticsErrorReporter] swallows.
 */
internal object CrashlyticsReportSink : ReportSink {

    override fun log(line: String) {
        FirebaseCrashlytics.getInstance().log(line)
    }

    override fun record(description: String, failure: Throwable, keys: Map<String, Any>) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        if (!crashlytics.isCrashlyticsCollectionEnabled) return
        val customKeys = CustomKeysAndValues.Builder()
        keys.forEach { (key, value) ->
            when (value) {
                is Boolean -> customKeys.putBoolean(key, value)
                is Int -> customKeys.putInt(key, value)
                is Long -> customKeys.putLong(key, value)
                else -> customKeys.putString(key, value.toString())
            }
        }
        crashlytics.log(description)
        crashlytics.recordException(failure, customKeys.build())
    }
}

/**
 * The one [ErrorReporter] of the app: every non-fatal failure reaches Crashlytics through here.
 *
 * **Only the backend is reported.** A failure of the connection between the phone and the backend is
 * never sent, whichever component reported it: no network, no DNS, a refused or dropped connection,
 * or a call that was cancelled. None of those is a defect anybody can fix. They leave a breadcrumb,
 * so they still explain the failures reported around them. See [NetworkFailureKind].
 *
 * **One operation is one issue.** Every report is recorded as a [ReportedFailure], which makes
 * Crashlytics group it by the operation that failed rather than by the exception type. Without it
 * every failed Gemini call would share one `IOException` issue, because that is where OkHttp throws.
 *
 * **A repeated failure is throttled.** The same signature is sent at most once per [THROTTLE_WINDOW_MS];
 * the next report after the window carries how often it was suppressed in between
 * (`error.suppressed_before`). One session sends at most [MAX_REPORTS_PER_SESSION] reports.
 *
 * Nothing is sanitised: messages, URLs, error bodies, and attributes are sent exactly as reported.
 *
 * What a report carries: the classification (`error.domain`, `error.severity`, `error.outcome`,
 * `http.kind`), the context every report shares (build mode, model, language, network, uptime), and
 * the attributes the caller added about that one failure. Every report is also written to Logcat.
 *
 * Reporting never changes what the app does: every path here swallows its own failures, so a
 * Crashlytics that is unavailable, disabled, or not configured can never take a screen down.
 */
@Singleton
class CrashlyticsErrorReporter internal constructor(
    private val sink: ReportSink,
    private val connectivity: ConnectivityObserver,
    private val logger: AppLogger,
    private val uptimeMillis: () -> Long,
) : ErrorReporter {

    @Inject
    constructor(connectivity: ConnectivityObserver, logger: AppLogger) :
        this(CrashlyticsReportSink, connectivity, logger, android.os.SystemClock::elapsedRealtime)

    private val throttles = ConcurrentHashMap<String, Throttle>()
    private val reportsSent = AtomicInteger()
    private val startedAtMillis = uptimeMillis()

    override fun report(report: ErrorReport) {
        try {
            deliver(report)
        } catch (failure: Exception) {
            // A failing reporter must stay invisible to the user.
            logger.warn(TAG, "The failure could not be reported", failure)
        }
    }

    override fun breadcrumb(message: String) {
        if (message.isBlank()) return
        logger.debug(TAG, message)
        try {
            sink.log(message)
        } catch (_: Exception) {
            // A breadcrumb that was not written changes nothing.
        }
    }

    private fun deliver(original: ErrorReport) {
        val kind = resolveFailureKind(original)
        if (kind != null && !kind.reportable) {
            // The connection failed, which is not a defect and not what the dashboard is watched for.
            breadcrumb("${original.operation} not reported [${kind.key}]")
            return
        }
        val report = if (kind != null && original.networkFailureKind == null) original.copy(networkFailureKind = kind) else original
        val suppressed = countSuppressedAndDecide(report.signature)
        if (suppressed < 0) return
        if (reportsSent.incrementAndGet() > MAX_REPORTS_PER_SESSION) return

        val description = report.describe()
        // Recorded as a ReportedFailure rather than as what was caught, so Crashlytics groups it by the
        // operation that failed. The caught throwable stays attached as the cause.
        val failure = ReportedFailure(report)
        logger.warn(TAG, description, failure)
        sink.record(description, failure, buildKeys(report, suppressed))
    }

    /**
     * Names the failure so the connection can be told apart from the backend. A report that already
     * carries a kind was classified by whoever built it; otherwise the throwable is classified here,
     * but only for a subsystem that reaches over the network. Returns `null` when nothing about the
     * connection can be said, which leaves the report to be sent as it is.
     */
    private fun resolveFailureKind(report: ErrorReport): NetworkFailureKind? {
        report.networkFailureKind?.let { return it }
        val throwable = report.throwable ?: return null
        if (!report.domain.backendFacing) return null
        return NetworkErrorClassifier.classify(throwable, isInternetAvailable())
    }

    /** Assembles everything the report is filtered and read by. */
    private fun buildKeys(report: ErrorReport, suppressed: Int): Map<String, Any> = buildMap {
        put("error.domain", report.domain.key)
        put("error.severity", report.severity.key)
        put("error.operation", report.operation)
        put("error.outcome", report.outcome)
        put("error.signature", report.signature)
        put("error.thread", Thread.currentThread().name)
        report.resolveMessage().takeIf { it.isNotEmpty() }?.let { put("error.message", it) }
        report.reason?.let { put("error.reason", it) }
        report.throwable?.let { put("error.cause", it.rootCause()::class.java.name) }
        report.networkFailureKind?.let { put("http.kind", it.key) }
        if (suppressed > 0) put("error.suppressed_before", suppressed)
        put("session.uptime_seconds", (uptimeMillis() - startedAtMillis) / 1_000L)
        put("network.available", isInternetAvailable())
        put("app.version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        put("context.glasses_mode", BuildModes.glassesMode)
        put("context.vision_mode", BuildModes.visionMode)
        put("context.gemini_model", BuildConfig.GEMINI_MODEL)
        put("context.language", Locale.getDefault().toLanguageTag())
        report.attributes.entries.take(ErrorReport.MAX_ATTRIBUTES).forEach { (key, value) -> put(key, value) }
    }

    /**
     * Decides whether this failure is reported now. Returns how often it was suppressed since it was
     * last reported, or -1 when it is still inside the window and this one is suppressed as well.
     */
    private fun countSuppressedAndDecide(signature: String): Int {
        // A session that produced this many distinct failures has already been reported on.
        if (throttles.size > MAX_TRACKED_SIGNATURES) throttles.clear()
        val now = uptimeMillis()
        val created = Throttle(now)
        val existing = throttles.putIfAbsent(signature, created) ?: return 0
        return existing.recordAndDecide(now)
    }

    /** Whether the phone had a usable network, which separates a backend that is down from a lift. */
    private fun isInternetAvailable(): Boolean = connectivity.status.value != ConnectivityStatus.OFFLINE

    /** Remembers when a failure was last reported and how often it happened while it stayed silent. */
    private class Throttle(private var lastReportedAtMillis: Long) {
        private var suppressedCount = 0

        @Synchronized
        fun recordAndDecide(nowMillis: Long): Int {
            if (nowMillis - lastReportedAtMillis < THROTTLE_WINDOW_MS) {
                suppressedCount++
                return -1
            }
            val suppressed = suppressedCount
            suppressedCount = 0
            lastReportedAtMillis = nowMillis
            return suppressed
        }
    }

    /** Limits. */
    companion object {
        private const val TAG = "ErrorReporter"

        /** How long the same failure stays silent after it was reported once. */
        const val THROTTLE_WINDOW_MS: Long = 60_000L

        /** The most reports one process sends; a session that failed this often has said enough. */
        const val MAX_REPORTS_PER_SESSION: Int = 100

        /** How many distinct failures are remembered for throttling before the table starts over. */
        private const val MAX_TRACKED_SIGNATURES = 200
    }
}
