package com.basira.app.controllers.reporting

import com.basira.core.reporting.NetworkFailureKind
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkErrorClassifierTest {

    private fun classify(throwable: Throwable, online: Boolean = true) = NetworkErrorClassifier.classify(throwable, online)

    @Test
    fun `transport failures are the connection and are never reportable`() {
        assertEquals(NetworkFailureKind.CONNECTION_FAILED, classify(ConnectException()))
        assertEquals(NetworkFailureKind.CONNECT_TIMEOUT, classify(SocketTimeoutException("connect timed out")))
        assertEquals(NetworkFailureKind.DNS_FAILURE, classify(UnknownHostException()))
        assertEquals(NetworkFailureKind.CANCELED, classify(IOException("Canceled")))
        assertEquals(NetworkFailureKind.TRANSPORT_FAILURE, classify(IOException("unexpected end of stream")))
        assertEquals(NetworkFailureKind.OFFLINE, classify(SocketTimeoutException("timeout"), online = false))
        listOf(ConnectException(), UnknownHostException(), IOException("Canceled")).forEach {
            assertFalse(classify(it).reportable)
        }
    }

    @Test
    fun `backend and contract failures are reportable`() {
        assertEquals(NetworkFailureKind.READ_TIMEOUT, classify(SocketTimeoutException("timeout")))
        assertEquals(NetworkFailureKind.CALL_TIMEOUT, classify(InterruptedIOException("timeout")))
        assertEquals(NetworkFailureKind.TLS_FAILURE, classify(SSLHandshakeException("expired"), online = false))
        assertEquals(NetworkFailureKind.MALFORMED_RESPONSE, classify(SerializationException("bad")))
        assertEquals(NetworkFailureKind.UNKNOWN, classify(IllegalStateException("bug")))
        assertTrue(classify(SocketTimeoutException("timeout")).reportable)
    }

    @Test
    fun `the cause chain is walked`() {
        assertEquals(NetworkFailureKind.CONNECTION_FAILED, classify(RuntimeException("wrapped", ConnectException())))
    }

    @Test
    fun `a coroutine timeout is a cancellation`() {
        val timeout = runCatching { runBlocking { withTimeout(1) { kotlinx.coroutines.delay(1_000) } } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        assertEquals(NetworkFailureKind.CANCELED, classify(timeout!!))
    }

    @Test
    fun `status codes name the backend failure`() {
        assertEquals(NetworkFailureKind.HTTP_UNAUTHORIZED, NetworkErrorClassifier.classifyHttpStatus(401))
        assertEquals(NetworkFailureKind.HTTP_NOT_FOUND, NetworkErrorClassifier.classifyHttpStatus(404))
        assertEquals(NetworkFailureKind.HTTP_RATE_LIMITED, NetworkErrorClassifier.classifyHttpStatus(429))
        assertEquals(NetworkFailureKind.HTTP_CLIENT_ERROR, NetworkErrorClassifier.classifyHttpStatus(400))
        assertEquals(NetworkFailureKind.HTTP_SERVER_ERROR, NetworkErrorClassifier.classifyHttpStatus(503))
    }
}
