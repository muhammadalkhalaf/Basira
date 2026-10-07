package com.basira.core.logging

/**
 * Redaction-aware logging facade.
 *
 * Implementations must never be handed image bytes, recognized text, AI descriptions, or tokens.
 * Callers log only event names, typed error names, and numeric metadata. Release implementations
 * drop debug and info messages entirely.
 */
interface AppLogger {
    /**
     * Logs a diagnostic message that is only useful during development.
     *
     * @param tag short component name.
     * @param message log-safe message without personal content.
     */
    fun debug(tag: String, message: String)

    /**
     * Logs a notable but expected event.
     *
     * @param tag short component name.
     * @param message log-safe message without personal content.
     */
    fun info(tag: String, message: String)

    /**
     * Logs an unexpected condition.
     *
     * @param tag short component name.
     * @param message log-safe message without personal content.
     * @param throwable optional cause; only its class name is recorded in release builds.
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Logs a failure.
     *
     * @param tag short component name.
     * @param message log-safe message without personal content.
     * @param throwable optional cause; only its class name is recorded in release builds.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** Logger that discards everything; used by tests and as a safe default. */
object NoOpLogger : AppLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

/** Helpers that strip potentially sensitive values before they reach a log sink. */
object LogRedactor {

    private val bearerPattern = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val longTokenPattern = Regex("[A-Za-z0-9._~+/=-]{32,}")

    /**
     * Removes bearer tokens and long opaque strings from [message].
     *
     * @param message raw message that may accidentally contain a credential.
     * @return the message with credentials replaced by a placeholder.
     */
    fun redact(message: String): String =
        message.replace(bearerPattern, "Bearer <redacted>").replace(longTokenPattern, "<redacted>")

    /**
     * Describes text content by length only so that recognized text is never logged.
     *
     * @param text sensitive text such as a description or transcript.
     * @return a placeholder that only reveals the character count.
     */
    fun describeLength(text: String?): String = "<text:${text?.length ?: 0} chars>"
}
