package com.basira.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.basira.core.coroutines.DispatcherProvider
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.CapturedImage
import com.basira.domain.model.Confidence
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.UserSettings
import com.basira.domain.model.Verbosity
import com.basira.domain.repository.CapturedImageArchive
import com.basira.domain.repository.DescriptionHistoryRepository
import com.basira.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history")

/** [SettingsRepository] backed by Preferences DataStore (non-sensitive values only). */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val errorReporter: ErrorReporter,
) : SettingsRepository {

    override val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch {
            if (it !is IOException) throw it
            errorReporter.reportStorage("settings.read", ErrorSeverity.ERROR, "defaults_used", throwable = it)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map(::toSettings)

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.settingsDataStore.edit { preferences ->
            val next = transform(toSettings(preferences))
            preferences[VERBOSITY] = next.verbosity.name
            preferences[CONSENT] = next.consentAccepted
            preferences[ONBOARDING] = next.onboardingCompleted
            preferences[SAVE_HISTORY] = next.saveHistory
            preferences[SAVE_IMAGES] = next.saveImages
            preferences[MEDIA_BUTTON] = next.mediaButtonEnabled
            preferences[SPEECH_RATE] = next.speechRate.coerceIn(MIN_RATE, MAX_RATE)
        }
    }

    private fun toSettings(preferences: Preferences) = UserSettings(
        verbosity = preferences[VERBOSITY]?.let(::readVerbosity) ?: Verbosity.SHORT,
        consentAccepted = preferences[CONSENT] ?: false,
        onboardingCompleted = preferences[ONBOARDING] ?: false,
        saveHistory = preferences[SAVE_HISTORY] ?: false,
        saveImages = preferences[SAVE_IMAGES] ?: false,
        mediaButtonEnabled = preferences[MEDIA_BUTTON] ?: false,
        speechRate = preferences[SPEECH_RATE] ?: 1.0f,
    )

    private fun readVerbosity(stored: String): Verbosity? =
        Verbosity.entries.firstOrNull { it.name == stored }
            ?: run {
                errorReporter.reportUnreadableValue("settings.verbosity", stored)
                null
            }

    private companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        val VERBOSITY = stringPreferencesKey("verbosity")
        val CONSENT = booleanPreferencesKey("consent_accepted")
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val SAVE_IMAGES = booleanPreferencesKey("save_images")
        val MEDIA_BUTTON = booleanPreferencesKey("media_button")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
    }
}

/** Serialized form of a history entry. */
@Serializable
private data class HistoryEntry(
    val requestId: String,
    val text: String,
    val confidence: String,
    val mode: String,
    val targetObject: String? = null,
    val createdAtMillis: Long,
)

/**
 * Optional, opt-in history of description text kept in app-private storage and excluded from
 * backup. Nothing is stored unless [UserSettings.saveHistory] is on; disabling it deletes the history.
 */
@Singleton
class DataStoreDescriptionHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val errorReporter: ErrorReporter,
) : DescriptionHistoryRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryEntry.serializer())

    override val history: Flow<List<SceneDescription>> = context.historyDataStore.data
        .catch {
            if (it !is IOException) throw it
            errorReporter.reportStorage("history.read", ErrorSeverity.ERROR, "history_shown_empty", throwable = it)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { preferences -> decode(preferences[ENTRIES]).map(::toDomain) }

    override suspend fun add(description: SceneDescription) {
        if (!settings.settings.first().saveHistory) return
        context.historyDataStore.edit { preferences ->
            val updated = (listOf(toEntry(description)) + decode(preferences[ENTRIES])).take(MAX_ENTRIES)
            preferences[ENTRIES] = json.encodeToString(serializer, updated)
        }
    }

    override suspend fun clear() {
        context.historyDataStore.edit { it.clear() }
    }

    private fun decode(raw: String?): List<HistoryEntry> = try {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, raw)
    } catch (e: SerializationException) {
        errorReporter.reportStorage("history.decode", ErrorSeverity.ERROR, "history_discarded", throwable = e)
        emptyList()
    }

    private fun toEntry(description: SceneDescription) = HistoryEntry(
        requestId = description.requestId,
        text = description.text,
        confidence = description.confidence.name,
        mode = description.mode.name,
        targetObject = description.targetObject,
        createdAtMillis = description.createdAtMillis,
    )

    private fun toDomain(entry: HistoryEntry) = SceneDescription(
        requestId = entry.requestId,
        text = entry.text,
        confidence = Confidence.entries.firstOrNull { it.name == entry.confidence }
            ?: Confidence.UNKNOWN.also { errorReporter.reportUnreadableValue("history.confidence", entry.confidence) },
        warnings = emptyList(),
        processingTimeMillis = null,
        mode = AnalysisMode.entries.firstOrNull { it.name == entry.mode }
            ?: AnalysisMode.SCENE_DESCRIPTION.also { errorReporter.reportUnreadableValue("history.mode", entry.mode) },
        targetObject = entry.targetObject,
        createdAtMillis = entry.createdAtMillis,
    )

    private companion object {
        const val MAX_ENTRIES = 20
        val ENTRIES = stringPreferencesKey("entries")
    }
}

/**
 * Optional archive of captured images in app-private storage (`files/saved_images`), excluded from
 * backup. Images are written only after the user explicitly opted in.
 */
@Singleton
class FileCapturedImageArchive @Inject constructor(
    @param:ApplicationContext context: Context,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val errorReporter: ErrorReporter,
) : CapturedImageArchive {

    private val directory = File(context.filesDir, "saved_images")

    override suspend fun saveIfEnabled(image: CapturedImage, requestId: String) {
        if (!settings.settings.first().saveImages) return
        withContext(dispatchers.io) {
            try {
                directory.mkdirs()
                val safeName = requestId.filter { it.isLetterOrDigit() || it == '-' }.take(64)
                File(directory, "$safeName.jpg").writeBytes(image.jpegBytes)
            } catch (e: IOException) {
                errorReporter.reportStorage("imageArchive.save", ErrorSeverity.WARNING, "image_not_saved", throwable = e)
            }
        }
    }

    override suspend fun clear() {
        withContext(dispatchers.io) { directory.deleteRecursively() }
    }

    override suspend fun count(): Int = withContext(dispatchers.io) { directory.listFiles()?.size ?: 0 }
}

/** Reports a local store that could not be read or written. */
private fun ErrorReporter.reportStorage(
    operation: String,
    severity: ErrorSeverity,
    outcome: String,
    message: String? = null,
    throwable: Throwable? = null,
) = report(
    ErrorReport(
        domain = ErrorDomain.STORAGE,
        operation = operation,
        severity = severity,
        outcome = outcome,
        message = message,
        throwable = throwable,
    ),
)

/** Reports a stored enum name this build no longer knows; the default is used instead. */
private fun ErrorReporter.reportUnreadableValue(operation: String, stored: String) =
    reportStorage(operation, ErrorSeverity.WARNING, "default_used", message = "Unknown stored value: $stored")
