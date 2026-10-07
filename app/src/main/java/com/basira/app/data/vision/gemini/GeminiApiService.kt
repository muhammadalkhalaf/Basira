package com.basira.app.data.vision.gemini

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag

/** Retrofit definition of the Gemini Interactions endpoint. Retrofit types stay in the data layer. */
interface GeminiApiService {

    /**
     * Sends an image-understanding request directly to the Gemini API.
     *
     * The key travels only in the `x-goog-api-key` header, never in the URL. `Api-Revision` selects
     * the `steps` response schema used by the official REST examples.
     *
     * @param apiKey Gemini API key compiled into this experimental build.
     * @param request structured Gemini interaction request.
     * @param uploadListener notified once the request body was fully written (OkHttp request tag).
     * @return the raw HTTP response; the body is the Gemini `Interaction` on 2xx.
     */
    @Headers("Api-Revision: $API_REVISION")
    @POST("v1beta/interactions")
    suspend fun createInteraction(
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiInteractionRequest,
        @Tag uploadListener: UploadCompletionListener,
    ): Response<GeminiInteractionResponse>

    /** Constants. */
    companion object {
        /** Interactions API schema revision from the official migration guide. */
        const val API_REVISION: String = "2026-05-20"
    }
}
