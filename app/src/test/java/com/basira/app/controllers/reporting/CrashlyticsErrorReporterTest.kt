package com.basira.app.controllers.reporting

import com.basira.core.logging.NoOpLogger
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.reporting.NetworkFailureKind
import com.basira.domain.fakes.FakeConnectivityObserver
import com.basira.domain.model.ConnectivityStatus
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsErrorReporterTest {

    private class RecordingSink : ReportSink {
        val lines = mutableListOf<String>()
        val records = mutableListOf<Pair<Throwable, Map<String, Any>>>()
        override fun log(line: String) { lines += line }
        override fun record(description: String, failure: Throwable, keys: Map<String, Any>) { records += failure to keys }
    }

    private val sink = RecordingSink()
    private val connectivity = FakeConnectivityObserver(ConnectivityStatus.ONLINE)
    private var now = 1_000L
    private val reporter = CrashlyticsErrorReporter(sink, connectivity, NoOpLogger) { now }

    private fun networkReport(throwable: Throwable) = ErrorReport(
        domain = ErrorDomain.NETWORK,
        operation = "gemini POST /v1beta/interactions",
        severity = ErrorSeverity.ERROR,
        outcome = "call_failed",
        throwable = throwable,
    )

    @Test
    fun `connection failures only leave a breadcrumb`() {
        reporter.report(networkReport(ConnectException("Failed to connect")))
        reporter.report(networkReport(SocketTimeoutException("connect timed out")))
        reporter.report(networkReport(IOException("Canceled")))
        connectivity.status.value = ConnectivityStatus.OFFLINE
        reporter.report(networkReport(UnknownHostException("generativelanguage.googleapis.com")))
        reporter.report(networkReport(SocketTimeoutException("timeout")))

        assertTrue(sink.records.isEmpty())
        assertEquals(5, sink.lines.size)
        assertTrue(sink.lines.first().contains("[connection_failed]"))
    }

    @Test
    fun `a backend failure is sent with its classification and context`() {
        reporter.report(networkReport(SocketTimeoutException("timeout")))

        val (failure, keys) = sink.records.single()
        assertTrue(failure is ReportedFailure)
        assertTrue(failure.cause is SocketTimeoutException)
        assertEquals("network", keys["error.domain"])
        assertEquals("error", keys["error.severity"])
        assertEquals("call_failed", keys["error.outcome"])
        assertEquals("read_timeout", keys["http.kind"])
        assertEquals("timeout", keys["error.message"])
        assertEquals(true, keys["network.available"])
        assertTrue(keys.containsKey("context.gemini_model"))
    }

    @Test
    fun `a failure on the phone is never mistaken for the connection`() {
        reporter.report(
            ErrorReport(ErrorDomain.STORAGE, "imageArchive.save", ErrorSeverity.WARNING, "image_not_saved", throwable = IOException("ENOSPC")),
        )

        val keys = sink.records.single().second
        assertEquals("storage", keys["error.domain"])
        assertFalse(keys.containsKey("http.kind"))
    }

    @Test
    fun `an unreadable answer stays reportable even offline`() {
        connectivity.status.value = ConnectivityStatus.OFFLINE
        reporter.report(
            ErrorReport(ErrorDomain.VISION, "gemini.answer", ErrorSeverity.ERROR, "error_announced", throwable = SerializationException("bad")),
        )

        assertEquals(NetworkFailureKind.MALFORMED_RESPONSE.key, sink.records.single().second["http.kind"])
    }

    @Test
    fun `a repeated failure is sent once a minute with the count it suppressed`() {
        val report = ErrorReport(ErrorDomain.AUDIO, "tts.speak", ErrorSeverity.WARNING, "utterance_not_spoken", reason = "error_-3")
        repeat(5) { reporter.report(report) }
        assertEquals(1, sink.records.size)

        now += CrashlyticsErrorReporter.THROTTLE_WINDOW_MS
        reporter.report(report)

        assertEquals(2, sink.records.size)
        assertEquals(4, sink.records.last().second["error.suppressed_before"])
    }

    @Test
    fun `different reasons of one operation are throttled apart`() {
        reporter.report(ErrorReport(ErrorDomain.GLASSES, "dat.session", ErrorSeverity.ERROR, "session_error_shown", reason = "UNEXPECTED_ERROR"))
        reporter.report(ErrorReport(ErrorDomain.GLASSES, "dat.session", ErrorSeverity.ERROR, "session_error_shown", reason = "SESSION_ALREADY_EXISTS"))

        assertEquals(2, sink.records.size)
    }

    @Test
    fun `nothing is sanitised`() {
        val message = "API key AIzaSyFakeFakeFake for patient 1234567 at user@example.com"
        reporter.report(ErrorReport(ErrorDomain.UI, "activity.start", ErrorSeverity.WARNING, "fallback_tried", message = message))

        assertEquals(message, sink.records.single().second["error.message"])
    }

    @Test
    fun `a sink that throws never reaches the caller`() {
        val failing = CrashlyticsErrorReporter(
            object : ReportSink {
                override fun log(line: String) = throw IllegalStateException("Firebase not initialized")
                override fun record(description: String, failure: Throwable, keys: Map<String, Any>) =
                    throw IllegalStateException("Firebase not initialized")
            },
            connectivity,
            NoOpLogger,
        ) { now }

        failing.report(networkReport(SocketTimeoutException("timeout")))
        failing.breadcrumb("still fine")
    }
}
