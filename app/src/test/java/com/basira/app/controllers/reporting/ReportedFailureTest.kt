package com.basira.app.controllers.reporting

import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorSeverity
import com.basira.core.reporting.NetworkFailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReportedFailureTest {

    @Test
    fun `the top frame names the endpoint and what went wrong with it`() {
        val failure = ReportedFailure(
            ErrorReport(
                domain = ErrorDomain.NETWORK,
                operation = "gemini POST /v1beta/interactions",
                severity = ErrorSeverity.ERROR,
                outcome = "call_failed",
                networkFailureKind = NetworkFailureKind.HTTP_SERVER_ERROR,
            ),
        )

        val top = failure.stackTrace.first()
        assertEquals("com.basira.app.network.gemini_POST_v1beta_interactions", top.className)
        assertEquals("http_server_error", top.methodName)
        assertNull(top.fileName)
    }

    @Test
    fun `the caught throwable stays attached and its frames follow the label`() {
        val caught = IOException("outer", SocketTimeoutException("timeout"))
        val failure = ReportedFailure(
            ErrorReport(ErrorDomain.VISION, "gemini.analyze", ErrorSeverity.ERROR, "timeout_announced", throwable = caught),
        )

        assertSame(caught, failure.cause)
        assertEquals("SocketTimeoutException", failure.stackTrace.first().methodName)
        assertEquals(caught.stackTrace.first(), failure.stackTrace[1])
    }

    @Test
    fun `without a throwable the frames start at the caller, not at the reporter`() {
        val failure = ReportedFailure(
            ErrorReport(ErrorDomain.GLASSES, "dat.reconnect", ErrorSeverity.ERROR, "session_failed", reason = "budget_exhausted"),
        )

        assertEquals("budget_exhausted", failure.stackTrace.first().methodName)
        assertEquals(ReportedFailureTest::class.java.name, failure.stackTrace[1].className)
    }
}
