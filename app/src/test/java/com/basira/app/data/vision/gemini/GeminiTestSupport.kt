package com.basira.app.data.vision.gemini

import com.basira.app.controllers.reporting.ErrorReportingInterceptor
import com.basira.app.testutil.RecordingLogger
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.NoOpErrorReporter
import com.basira.core.retry.ExponentialBackoff
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Fake key used only by tests; never a real credential. */
const val TEST_API_KEY: String = "test-key-not-real-0000"

/** Same JSON configuration as production. */
val testJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Dispatchers that run inline. */
val inlineDispatchers: DispatcherProvider = object : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
}

/** Builds a repository against [server] exactly like production DI, with fast backoff. */
fun geminiRepository(
    server: MockWebServer,
    apiKey: String = TEST_API_KEY,
    readTimeoutMillis: Long = 5_000,
    logger: RecordingLogger = RecordingLogger(),
    totalBudgetMillis: Long = 30_000,
    errorReporter: ErrorReporter = NoOpErrorReporter,
): GeminiVisionAnalysisRepository {
    val client = OkHttpClient.Builder()
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(ErrorReportingInterceptor("gemini", errorReporter))
        .addInterceptor(UploadCompletionInterceptor())
        .build()
    val config = GeminiConfig(apiKey = apiKey, model = "gemini-3.5-flash-lite", baseUrl = server.url("/").toString())
    val retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
    val mapper = GeminiErrorMapper(testJson, errorReporter)
    return GeminiVisionAnalysisRepository(
        api = retrofit.create(GeminiApiService::class.java),
        config = config,
        prompts = GeminiPromptBuilder(),
        parser = GeminiResponseParser(mapper, errorReporter),
        errorMapper = mapper,
        backoff = ExponentialBackoff(baseDelayMillis = 1, maxDelayMillis = 2, maxAttempts = 2, jitter = false),
        base64Encoder = { Base64.getEncoder().encodeToString(it) },
        dispatchers = inlineDispatchers,
        logger = logger,
        errorReporter = errorReporter,
        totalBudgetMillis = totalBudgetMillis,
    )
}

/** Structured answer text as the model would return it. */
fun answerJson(description: String = "درج أمامك على بعد مترين", confidence: String = "HIGH", warnings: List<String> = emptyList()): String =
    buildJsonObject {
        put("description", description)
        put("confidence", confidence)
        put("warnings", kotlinx.serialization.json.JsonArray(warnings.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

/** A completed interaction whose `model_output` step contains [text]. */
fun interactionBody(text: String = answerJson(), status: String = "completed"): String = buildJsonObject {
    put("id", "int_test")
    put("object", "interaction")
    put("status", status)
    put(
        "steps",
        kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject { put("type", "thought") })
            add(
                buildJsonObject {
                    put("type", "model_output")
                    put("content", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                },
            )
        },
    )
}.toString()

/** 2xx JSON response. */
fun okResponse(body: String = interactionBody()): MockResponse =
    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

/** Error response with the official `{"error":{"code","message"}}` envelope. */
fun errorResponse(httpCode: Int, code: String, message: String = "details", retryAfter: String? = null): MockResponse =
    MockResponse.Builder()
        .code(httpCode)
        .addHeader("Content-Type", "application/json")
        .apply { retryAfter?.let { addHeader("Retry-After", it) } }
        .body("""{"error":{"code":"$code","message":"$message"}}""")
        .build()
