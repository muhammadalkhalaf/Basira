package com.basira.app.data.vision.gemini

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** Called once when a request body has been fully written to the connection. */
fun interface UploadCompletionListener {
    /** The image upload finished; the server is now processing. */
    fun onUploadComplete()
}

/**
 * Application interceptor that wraps the body of requests tagged with an [UploadCompletionListener]
 * (Retrofit `@Tag`) so the UI can switch from "sending" to "analyzing".
 */
class UploadCompletionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val listener = request.tag(UploadCompletionListener::class.java) ?: return chain.proceed(request)
        val body = request.body ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().method(request.method, NotifyingBody(body, listener)).build())
    }

    /** Delegating body that notifies after writing. */
    private class NotifyingBody(
        private val delegate: RequestBody,
        private val listener: UploadCompletionListener,
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            delegate.writeTo(sink)
            listener.onUploadComplete()
        }
    }
}
