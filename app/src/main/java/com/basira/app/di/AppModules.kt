package com.basira.app.di

import com.basira.app.BuildConfig
import com.basira.app.core.AndroidLogger
import com.basira.app.core.BuildModes
import com.basira.app.core.DefaultDispatcherProvider
import com.basira.app.core.SystemClock
import com.basira.app.core.UuidRequestIdGenerator
import com.basira.app.data.connectivity.AndroidConnectivityObserver
import com.basira.app.data.glasses.AssetSampleImageSource
import com.basira.app.data.glasses.DatGlassesRepository
import com.basira.app.data.glasses.DatGlassesSetupLauncher
import com.basira.app.data.glasses.FakeGlassesRepository
import com.basira.app.data.glasses.FakeGlassesSetupLauncher
import com.basira.app.data.glasses.GlassesSetupLauncher
import com.basira.app.data.local.DataStoreDescriptionHistoryRepository
import com.basira.app.data.local.DataStoreSettingsRepository
import com.basira.app.data.local.FileCapturedImageArchive
import com.basira.app.data.phone.AndroidPhoneLocator
import com.basira.app.data.speech.AndroidFeedbackPlayer
import com.basira.app.data.speech.AndroidSpeechOutput
import com.basira.app.data.speech.ResourceAnnouncementTextProvider
import android.util.Base64
import com.basira.app.data.vision.FakeVisionAnalysisRepository
import com.basira.app.data.vision.gemini.GeminiApiService
import com.basira.app.data.vision.gemini.GeminiConfig
import com.basira.app.data.vision.gemini.GeminiErrorMapper
import com.basira.app.data.vision.gemini.GeminiPromptBuilder
import com.basira.app.data.vision.gemini.GeminiResponseParser
import com.basira.app.data.vision.gemini.GeminiVisionAnalysisRepository
import com.basira.app.data.vision.gemini.MisconfiguredVisionAnalysisRepository
import com.basira.app.data.vision.gemini.UploadCompletionInterceptor
import com.basira.app.data.voice.AndroidVoiceCommandRecognizer
import com.basira.core.coroutines.ApplicationScope
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.logging.AppLogger
import com.basira.core.retry.ExponentialBackoff
import com.basira.domain.assistant.AnnouncementTextProvider
import com.basira.domain.assistant.Announcer
import com.basira.domain.assistant.SpeakingAnnouncer
import com.basira.domain.repository.CapturedImageArchive
import com.basira.domain.repository.Clock
import com.basira.domain.repository.ConnectivityObserver
import com.basira.domain.repository.DescriptionHistoryRepository
import com.basira.domain.repository.FeedbackPlayer
import com.basira.domain.repository.GlassesRepository
import com.basira.domain.repository.PhoneLocator
import com.basira.domain.repository.RequestIdGenerator
import com.basira.domain.repository.SettingsRepository
import com.basira.domain.repository.SpeechOutput
import com.basira.domain.repository.VisionAnalysisRepository
import com.basira.domain.repository.VoiceCommandRecognizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Process-wide infrastructure. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Application-wide scope; it is never cancelled and runs on the main thread by default. */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** JSON configuration shared by Retrofit and error parsing. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/** Interface bindings for the data layer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider
    @Binds abstract fun logger(impl: AndroidLogger): AppLogger
    @Binds abstract fun clock(impl: SystemClock): Clock
    @Binds abstract fun requestIds(impl: UuidRequestIdGenerator): RequestIdGenerator
    @Binds abstract fun speech(impl: AndroidSpeechOutput): SpeechOutput
    @Binds abstract fun feedback(impl: AndroidFeedbackPlayer): FeedbackPlayer
    @Binds abstract fun connectivity(impl: AndroidConnectivityObserver): ConnectivityObserver
    @Binds abstract fun voice(impl: AndroidVoiceCommandRecognizer): VoiceCommandRecognizer
    @Binds abstract fun phoneLocator(impl: AndroidPhoneLocator): PhoneLocator
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun history(impl: DataStoreDescriptionHistoryRepository): DescriptionHistoryRepository
    @Binds abstract fun archive(impl: FileCapturedImageArchive): CapturedImageArchive
    @Binds abstract fun announcementTexts(impl: ResourceAnnouncementTextProvider): AnnouncementTextProvider
    @Binds abstract fun announcer(impl: SpeakingAnnouncer): Announcer
}

/** Selects the glasses implementation for the build mode (see app/build.gradle.kts). */
@Module
@InstallIn(SingletonComponent::class)
object GlassesModule {

    /** Bundled-sample simulation, created only when selected. */
    @Provides
    @Singleton
    fun provideFakeGlasses(images: AssetSampleImageSource): FakeGlassesRepository = FakeGlassesRepository(images)

    /** Active glasses repository. */
    @Provides
    @Singleton
    fun provideGlassesRepository(
        dat: Provider<DatGlassesRepository>,
        fake: Provider<FakeGlassesRepository>,
    ): GlassesRepository = if (BuildModes.glassesMode == "fake") fake.get() else dat.get()

    /** Activity-bound setup flows matching [provideGlassesRepository]. */
    @Provides
    fun provideSetupLauncher(
        dat: Provider<DatGlassesSetupLauncher>,
        fake: Provider<FakeGlassesRepository>,
    ): GlassesSetupLauncher = if (BuildModes.glassesMode == "fake") FakeGlassesSetupLauncher(fake.get()) else dat.get()
}

/**
 * Direct Gemini networking. EXPERIMENTAL PRIVATE BUILD: the API key comes from BuildConfig and is
 * extractable from the APK; see SECURITY.md.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Gemini configuration from the generated BuildConfig. */
    @Provides
    @Singleton
    fun provideGeminiConfig(): GeminiConfig = GeminiConfig(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = BuildConfig.GEMINI_MODEL.ifBlank { GeminiConfig.DEFAULT_MODEL },
        baseUrl = GeminiConfig.DEFAULT_BASE_URL,
    )

    /**
     * HTTP client with explicit time budgets. Debug builds log only BASIC request lines with the
     * key header redacted (never BODY: bodies contain images); release builds log nothing.
     */
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(UploadCompletionInterceptor())
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("x-goog-api-key")
                    },
                )
            }
        }
        .build()

    /** Error classifier. */
    @Provides
    fun provideErrorMapper(json: Json): GeminiErrorMapper = GeminiErrorMapper(json)

    /** Vision repository: offline fake, explicit misconfiguration, or Gemini directly. */
    @Provides
    @Singleton
    fun provideVisionRepository(
        config: GeminiConfig,
        client: OkHttpClient,
        json: Json,
        prompts: GeminiPromptBuilder,
        parser: GeminiResponseParser,
        errorMapper: GeminiErrorMapper,
        dispatchers: DispatcherProvider,
        logger: AppLogger,
    ): VisionAnalysisRepository {
        if (BuildModes.isFakeVision) return FakeVisionAnalysisRepository()
        if (!config.isConfigured) return MisconfiguredVisionAnalysisRepository()
        val retrofit = Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return GeminiVisionAnalysisRepository(
            api = retrofit.create(GeminiApiService::class.java),
            config = config,
            prompts = prompts,
            parser = parser,
            errorMapper = errorMapper,
            backoff = ExponentialBackoff(baseDelayMillis = 800, maxDelayMillis = 6_000, maxAttempts = 2),
            base64Encoder = { bytes -> Base64.encodeToString(bytes, Base64.NO_WRAP) },
            dispatchers = dispatchers,
            logger = logger,
        )
    }
}
