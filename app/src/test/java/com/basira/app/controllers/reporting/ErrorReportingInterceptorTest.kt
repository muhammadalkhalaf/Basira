package com.basira.app.controllers.reporting

import com.basira.app.data.vision.gemini.errorResponse
import com.basira.app.data.vision.gemini.geminiRepository
import com.basira.app.data.vision.gemini.interactionBody
import com.basira.app.data.vision.gemini.okResponse
import com.basira.app.testutil.ResourceSampleImageSource
import com.basira.core.error.AppError
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.NetworkFailureKind
import com.basira.domain.fakes.RecordingErrorReporter
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.CapturedImage
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.model.Verbosity
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErrorReportingInterceptorTest {

    private lateinit var server: MockWebServer
    private val reporter = RecordingErrorReporter()
    private val image: CapturedImage = ResourceSampleImageSource().load(0)!!

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun request() = VisionAnalysisRequest(image, AnalysisMode.SCENE_DESCRIPTION, null, "ar", Verbosity.SHORT, "req-1")

    @Test
    fun `an error status is one report per call, grouped by the endpoint, with the error body`() = runBlocking {
        server.enqueue(errorResponse(404, "model_not_found", "models/gemini-x is not found"))

        geminiRepository(server, errorReporter = reporter).analyze(request())

        val report = reporter.reports.single()
        assertEquals(ErrorDomain.NETWORK, report.domain)
        assertEquals("gemini POST /v1beta/interactions", report.operation)
        assertEquals(NetworkFailureKind.HTTP_NOT_FOUND, report.networkFailureKind)
        assertTrue(report.message!!.contains("models/gemini-x is not found"))
        assertEquals("404", report.attributes["http.status"])
    }

    @Test
    fun `the error body is still readable by the caller after the interceptor peeked at it`() = runBlocking {
        server.enqueue(errorResponse(400, "invalid_request", "API key not valid. Please pass a valid API key."))

        val result = geminiRepository(server, errorReporter = reporter).analyze(request())

        assertEquals(AppError.ApiKeyRejected, (result as VisionAnalysisResult.Failure).error)
    }

    @Test
    fun `a call without an answer is handed to the reporter with what it threw`() = runBlocking {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        server.enqueue(okResponse())

        geminiRepository(server, errorReporter = reporter).analyze(request())

        val report = reporter.reports.single()
        assertEquals(ErrorDomain.NETWORK, report.domain)
        assertTrue(report.throwable is IOException)
    }

    @Test
    fun `a refused connection is reported by the interceptor only, never again by the repository`() = runBlocking {
        val repository = geminiRepository(server, errorReporter = reporter)
        server.close()

        repository.analyze(request())

        // Every attempt is handed over once; the reporter then drops it as a connection failure.
        assertTrue(reporter.reports.isNotEmpty())
        assertTrue(reporter.reports.all { it.domain == ErrorDomain.NETWORK && it.throwable is ConnectException })
    }

    @Test
    fun `a 2xx answer that breaks the contract is reported by the parser with its reason`() = runBlocking {
        server.enqueue(okResponse(interactionBody(text = "not json")))

        geminiRepository(server, errorReporter = reporter).analyze(request())

        val report = reporter.reports.single()
        assertEquals(ErrorDomain.VISION, report.domain)
        assertEquals("gemini.answer", report.operation)
        assertEquals("not_a_json_object", report.reason)
    }

    @Test
    fun `a successful call reports nothing`() = runBlocking {
        server.enqueue(okResponse())

        geminiRepository(server, errorReporter = reporter).analyze(request())

        assertTrue(reporter.reports.isEmpty())
    }
}
