package com.basira.domain.repository

import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult

/**
 * Provider-independent image analysis.
 *
 * The production implementation calls the Gemini API directly from the phone. The interface is
 * provider-independent, so another legally available vision model can replace Gemini without
 * changing the domain or UI layers.
 */
interface VisionAnalysisRepository {

    /**
     * Analyzes one image.
     *
     * At most one request is in flight at a time. Cancelling the calling coroutine cancels the HTTP
     * call. Transient connection failures and HTTP 502/503/504 are retried with bounded backoff;
     * nothing else is retried automatically.
     *
     * @param request the image and analysis parameters.
     * @param onUploadComplete invoked once the image bytes were fully sent, so callers can switch from
     * "uploading" to "analyzing" feedback.
     * @return a structurally validated result or a typed failure.
     */
    suspend fun analyze(
        request: VisionAnalysisRequest,
        onUploadComplete: () -> Unit = {},
    ): VisionAnalysisResult
}
