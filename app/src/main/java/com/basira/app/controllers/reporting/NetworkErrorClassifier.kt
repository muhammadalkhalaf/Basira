package com.basira.app.controllers.reporting

import com.basira.core.reporting.NetworkFailureKind
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Turns whatever a failed call threw into one [NetworkFailureKind].
 *
 * The throwable that reaches a caller is rarely the interesting one: Retrofit and coroutines wrap it,
 * and OkHttp reports a lost connection and a refused connection with the same type. Everything here
 * walks the cause chain so the same failure is always named the same way, whichever layer reported it.
 */
object NetworkErrorClassifier {

    /** How deep the cause chain is followed, so a self-referencing cause can never loop. */
    private const val MAX_CAUSE_DEPTH = 10

    /**
     * Names why the call failed.
     *
     * @param throwable what the call threw, at any wrapping depth.
     * @param internetAvailable whether the phone had a usable network while the call ran, which is
     *   what separates a backend that is down from a user in a lift.
     */
    fun classify(throwable: Throwable, internetAvailable: Boolean): NetworkFailureKind {
        val statusCode = extractHttpStatusCode(throwable)
        if (statusCode > 0) return classifyHttpStatus(statusCode)
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            classifySingle(cause, internetAvailable)?.let { return it }
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        // Nothing in the chain was a transport failure, so this is the app failing rather than the
        // network, and it stays reportable even on a phone that happens to be offline.
        return NetworkFailureKind.UNKNOWN
    }

    /** Names an answer that arrived with an unsuccessful status code. */
    fun classifyHttpStatus(statusCode: Int): NetworkFailureKind = when (statusCode) {
        401 -> NetworkFailureKind.HTTP_UNAUTHORIZED
        403 -> NetworkFailureKind.HTTP_FORBIDDEN
        404 -> NetworkFailureKind.HTTP_NOT_FOUND
        429 -> NetworkFailureKind.HTTP_RATE_LIMITED
        in 500..599 -> NetworkFailureKind.HTTP_SERVER_ERROR
        in 400..499 -> NetworkFailureKind.HTTP_CLIENT_ERROR
        else -> NetworkFailureKind.UNKNOWN
    }

    /** Returns the status code a Retrofit [HttpException] in the chain carries, or 0 when none does. */
    fun extractHttpStatusCode(throwable: Throwable): Int {
        var cause: Throwable? = throwable
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is HttpException) return cause.code()
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        return 0
    }

    /** Names a single throwable, or returns `null` so the caller keeps walking the cause chain. */
    private fun classifySingle(throwable: Throwable, online: Boolean): NetworkFailureKind? = when {
        throwable is CancellationException || throwable is InterruptedException -> NetworkFailureKind.CANCELED
        // OkHttp reports a cancelled call as a plain IOException that only the message identifies.
        throwable is IOException && throwable.message?.contains("canceled", ignoreCase = true) == true ->
            NetworkFailureKind.CANCELED
        // A broken certificate is a real defect even on a phone that just went offline.
        throwable is SSLException || throwable is GeneralSecurityException -> NetworkFailureKind.TLS_FAILURE
        throwable is SerializationException -> NetworkFailureKind.MALFORMED_RESPONSE
        throwable is UnknownHostException -> if (online) NetworkFailureKind.DNS_FAILURE else NetworkFailureKind.OFFLINE
        throwable is SocketTimeoutException -> when {
            !online -> NetworkFailureKind.OFFLINE
            // OkHttp only says "connect timed out" while it is still opening the connection.
            throwable.message?.contains("connect", ignoreCase = true) == true -> NetworkFailureKind.CONNECT_TIMEOUT
            else -> NetworkFailureKind.READ_TIMEOUT
        }
        // InterruptedIOException is how OkHttp reports the whole-call timeout it was configured with.
        throwable is TimeoutException || throwable is InterruptedIOException ->
            if (online) NetworkFailureKind.CALL_TIMEOUT else NetworkFailureKind.OFFLINE
        throwable is ConnectException || throwable is NoRouteToHostException || throwable is PortUnreachableException ->
            if (online) NetworkFailureKind.CONNECTION_FAILED else NetworkFailureKind.OFFLINE
        throwable is EOFException || throwable is SocketException ->
            if (online) NetworkFailureKind.CONNECTION_LOST else NetworkFailureKind.OFFLINE
        // The address itself could not be read, so nothing was sent: the app's defect, never dropped.
        throwable is MalformedURLException || throwable is URISyntaxException -> NetworkFailureKind.MALFORMED_REQUEST
        // The answer broke HTTP itself: an unreadable status line or a length that does not match.
        throwable is ProtocolException -> NetworkFailureKind.MALFORMED_RESPONSE
        // Everything left that OkHttp reports as an IOException happened on the connection, for
        // example "unexpected end of stream" and a reset HTTP/2 stream.
        throwable is IOException -> if (online) NetworkFailureKind.TRANSPORT_FAILURE else NetworkFailureKind.OFFLINE
        else -> null
    }
}
