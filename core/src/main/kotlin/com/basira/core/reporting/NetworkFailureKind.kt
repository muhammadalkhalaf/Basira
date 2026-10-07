package com.basira.core.reporting

/**
 * Names why an HTTP call did not deliver an answer, and says who has to fix it.
 *
 * A single `IOException` covers a phone in a lift, a backend that is down, and an expired
 * certificate, and those need three different fixes. Splitting them here is what makes the network
 * reports in Crashlytics actionable instead of one large bucket.
 *
 * **The connection is never reported.** Every kind that describes the link between the phone and the
 * backend is left out on purpose: no network, a name that did not resolve, a connection that was
 * refused, dropped or never opened, and a call the app cancelled itself. None of those is a defect
 * anybody can fix, and on a mobile network there are enough of them to bury everything that matters.
 * They still leave a breadcrumb, so they explain the failures reported around them.
 *
 * @property key value written to the `http.kind` Crashlytics key.
 * @property severity what this kind costs the user when nothing else is known about the call.
 * @property reportable `true` when the backend (or the app) is answerable for this kind, which is the
 *   same as saying it is worth a Crashlytics report.
 */
enum class NetworkFailureKind(val key: String, val severity: ErrorSeverity, val reportable: Boolean) {

    /** The phone has no usable network, so no call could have succeeded. */
    OFFLINE("offline", ErrorSeverity.WARNING, false),

    /** The user or the lifecycle cancelled the call while it was still running. */
    CANCELED("canceled", ErrorSeverity.WARNING, false),

    /** The connection could not be opened in time: a weak network, the request never reached Gemini. */
    CONNECT_TIMEOUT("connect_timeout", ErrorSeverity.WARNING, false),

    /** The host name did not resolve: no DNS, a captive portal, or a network that is on paper only. */
    DNS_FAILURE("dns_failure", ErrorSeverity.WARNING, false),

    /** The connection was refused or never reached the host. */
    CONNECTION_FAILED("connection_failed", ErrorSeverity.WARNING, false),

    /** The connection was established and then dropped. */
    CONNECTION_LOST("connection_lost", ErrorSeverity.WARNING, false),

    /** The call failed on the connection in a way the classifier does not name yet. */
    TRANSPORT_FAILURE("transport_failure", ErrorSeverity.WARNING, false),

    /** The backend took the request and did not finish answering in time. */
    READ_TIMEOUT("read_timeout", ErrorSeverity.ERROR, true),

    /** The whole call ran past the client budget, however the time was spent. */
    CALL_TIMEOUT("call_timeout", ErrorSeverity.ERROR, true),

    /** The TLS handshake failed, which is nearly always a certificate that could not be trusted. */
    TLS_FAILURE("tls_failure", ErrorSeverity.ERROR, true),

    /** 401: the key is missing or was rejected. */
    HTTP_UNAUTHORIZED("http_unauthorized", ErrorSeverity.ERROR, true),

    /** 403: the key is not allowed to call this API. */
    HTTP_FORBIDDEN("http_forbidden", ErrorSeverity.ERROR, true),

    /** 404: the endpoint or the configured model does not exist. */
    HTTP_NOT_FOUND("http_not_found", ErrorSeverity.ERROR, true),

    /** 429: the app is calling too often or the quota is spent. */
    HTTP_RATE_LIMITED("http_rate_limited", ErrorSeverity.ERROR, true),

    /** Any other 4xx: the app sent something the backend refused. */
    HTTP_CLIENT_ERROR("http_client_error", ErrorSeverity.ERROR, true),

    /** 5xx: the backend failed. */
    HTTP_SERVER_ERROR("http_server_error", ErrorSeverity.ERROR, true),

    /** The answer arrived but did not match the contract, so it could not be read. */
    MALFORMED_RESPONSE("malformed_response", ErrorSeverity.ERROR, true),

    /** The app built a request it could not send, so the call never happened. */
    MALFORMED_REQUEST("malformed_request", ErrorSeverity.ERROR, true),

    /** The call failed without ever touching the connection, so the app or the contract broke. */
    UNKNOWN("unknown", ErrorSeverity.ERROR, true),
}
