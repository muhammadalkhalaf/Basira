package com.basira.core.reporting

/**
 * Says how much a failure cost the user, which is what decides whether a report is worth acting on.
 *
 * Crashlytics has no severity of its own, so this is reported as the `error.severity` key and is
 * what the dashboard filters and alerts are built on.
 *
 * @property key value written to the `error.severity` Crashlytics key.
 */
enum class ErrorSeverity(val key: String) {

    /** The app recovered on its own and the user either noticed nothing or got a fallback. */
    WARNING("warning"),

    /** The user heard an error or lost a feature for this attempt. The flow continued. */
    ERROR("error"),

    /** The flow could not continue: the glasses or the service could not start at all. */
    CRITICAL("critical"),
}
