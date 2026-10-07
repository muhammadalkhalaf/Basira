package com.basira.app

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.basira.app.core.AndroidLogger
import com.basira.app.core.DefaultDispatcherProvider
import com.basira.app.data.image.ImageProcessor
import com.basira.app.data.image.RawPhoto
import com.basira.app.data.vision.gemini.GeminiApiService
import com.basira.app.data.vision.gemini.GeminiConfig
import com.basira.app.data.vision.gemini.GeminiErrorMapper
import com.basira.app.data.vision.gemini.GeminiPromptBuilder
import com.basira.app.data.vision.gemini.GeminiResponseParser
import com.basira.app.data.vision.gemini.GeminiVisionAnalysisRepository
import com.basira.app.data.vision.gemini.UploadCompletionInterceptor
import com.basira.core.result.AppResult
import com.basira.core.retry.ExponentialBackoff
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.VisionAnalysisRequest
import com.basira.domain.model.VisionAnalysisResult
import com.basira.domain.model.Verbosity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Live smoke test against the real Gemini Interactions API using the key from the untracked
 * `local.properties` (via `BuildConfig`). Skipped when no key is configured. Each test sends one
 * synthetic sample image, so it costs real quota.
 */
@RunWith(AndroidJUnit4::class)
class GeminiLiveInstrumentedTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun repository(): GeminiVisionAnalysisRepository {
        val config = GeminiConfig(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL)
        assumeTrue("GEMINI_API_KEY not configured", config.isConfigured)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(UploadCompletionInterceptor())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val mapper = GeminiErrorMapper(json)
        return GeminiVisionAnalysisRepository(
            api = retrofit.create(GeminiApiService::class.java),
            config = config,
            prompts = GeminiPromptBuilder(),
            parser = GeminiResponseParser(mapper),
            errorMapper = mapper,
            backoff = ExponentialBackoff(800, 6_000, maxAttempts = 2),
            base64Encoder = { Base64.encodeToString(it, Base64.NO_WRAP) },
            dispatchers = DefaultDispatcherProvider(),
            logger = AndroidLogger(),
        )
    }

    private fun analyze(asset: String, mode: AnalysisMode): VisionAnalysisResult = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }
        val prepared = ImageProcessor(DefaultDispatcherProvider(), AndroidLogger()).process(RawPhoto.Encoded(bytes))
        val image = (prepared as AppResult.Success).value
        var uploaded = false
        val started = System.currentTimeMillis()
        val result = repository().analyze(VisionAnalysisRequest(image, mode, null, "ar", Verbosity.SHORT, "live-$asset")) { uploaded = true }
        Log.i(TAG, "mode=$mode uploaded=$uploaded ms=${System.currentTimeMillis() - started} result=$result")
        if (result is VisionAnalysisResult.Success) Log.i(TAG, "description(sample image)=${result.description}")
        result
    }

    @Test
    fun describesTheStairsSample() {
        val result = analyze("02_obstacle_stairs.jpg", AnalysisMode.SCENE_DESCRIPTION)
        assertTrue("Gemini failed: $result", result is VisionAnalysisResult.Success)
        assertTrue((result as VisionAnalysisResult.Success).description.any { it in '؀'..'ۿ' })
    }

    @Test
    fun readsTheExitSignSample() {
        val result = analyze("01_text_sign.jpg", AnalysisMode.READ_TEXT)
        assertTrue("Gemini failed: $result", result is VisionAnalysisResult.Success)
    }

    private companion object {
        const val TAG = "GeminiLiveTest"
    }
}
