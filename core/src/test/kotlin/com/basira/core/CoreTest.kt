package com.basira.core

import com.basira.core.error.AppError
import com.basira.core.error.RetryClassifier
import com.basira.core.error.UpdateTarget
import com.basira.core.logging.LogRedactor
import com.basira.core.retry.ExponentialBackoff
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryClassifierTest {

    @Test
    fun `only transport failures and 502 503 504 are retried automatically`() {
        assertTrue(RetryClassifier.isAutomaticallyRetryable(AppError.Transport))
        listOf(502, 503, 504).forEach { assertTrue(RetryClassifier.isAutomaticallyRetryable(AppError.ServerError(it))) }
        listOf(500, 501, 505).forEach { assertFalse(RetryClassifier.isAutomaticallyRetryable(AppError.ServerError(it))) }
    }

    @Test
    fun `auth, permission, validation, timeout and rate limit are never retried automatically`() {
        listOf(
            AppError.AuthenticationExpired,
            AppError.Forbidden,
            AppError.CameraPermissionDenied,
            AppError.InvalidImage,
            AppError.InvalidServerResponse,
            AppError.Timeout,
            AppError.RateLimited(1_000),
            AppError.RegistrationRequired,
        ).forEach { assertFalse(it.toString(), RetryClassifier.isAutomaticallyRetryable(it)) }
    }

    @Test
    fun `setup problems are not offered as a plain retry`() {
        assertFalse(RetryClassifier.isUserRetryable(AppError.CameraPermissionDenied))
        assertFalse(RetryClassifier.isUserRetryable(AppError.IncompatibleVersion(UpdateTarget.GLASSES_FIRMWARE)))
        assertFalse(RetryClassifier.isUserRetryable(AppError.Unexpected(fatal = true)))
        assertFalse(RetryClassifier.isUserRetryable(AppError.ApiKeyRejected))
        assertFalse(RetryClassifier.isUserRetryable(AppError.ServiceNotConfigured))
        assertFalse(RetryClassifier.isAutomaticallyRetryable(AppError.ContentBlocked))
        assertFalse(RetryClassifier.isAutomaticallyRetryable(AppError.QuotaExceeded))
        assertTrue(RetryClassifier.isUserRetryable(AppError.Timeout))
        assertTrue(RetryClassifier.isUserRetryable(AppError.Unexpected(fatal = false)))
    }
}

class ExponentialBackoffTest {

    @Test
    fun `delays grow exponentially and are capped without jitter`() {
        val backoff = ExponentialBackoff(baseDelayMillis = 500, maxDelayMillis = 3_000, maxAttempts = 5, jitter = false)
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 3_000L), (1..5).map { backoff.delayForAttempt(it) })
        assertNull(backoff.delayForAttempt(6))
        assertNull(backoff.delayForAttempt(0))
    }

    @Test
    fun `jittered delays stay within half and full ceiling`() {
        val backoff = ExponentialBackoff(1_000, 8_000, maxAttempts = 4, random = Random(7))
        repeat(50) {
            val delay = requireNotNull(backoff.delayForAttempt(3))
            assertTrue(delay in 2_000..4_000)
        }
    }
}

class LogRedactorTest {

    @Test
    fun `bearer tokens and long secrets are removed`() {
        val redacted = LogRedactor.redact("Authorization: Bearer abc.def.ghi failed for key FAKEFAKEFAKE0123456789fakefakefakefake")
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("FAKEFAKE"))
    }

    @Test
    fun `text is described by length only`() {
        assertEquals("<text:5 chars>", LogRedactor.describeLength("مرحبا"))
    }
}
