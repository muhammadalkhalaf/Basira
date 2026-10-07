package com.basira.domain.repository

import com.basira.domain.model.CapturedImage
import com.basira.domain.model.SceneDescription
import com.basira.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

/** Persisted, non-sensitive user preferences. */
interface SettingsRepository {

    /** Current settings; emits again after every update. */
    val settings: Flow<UserSettings>

    /**
     * Atomically updates the settings.
     *
     * @param transform function producing the new settings from the current ones.
     */
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

/** Optional on-device history of description text. Disabled by default. */
interface DescriptionHistoryRepository {

    /** Stored descriptions, newest first. */
    val history: Flow<List<SceneDescription>>

    /**
     * Stores [description] when history is enabled; otherwise does nothing.
     *
     * @param description description to store.
     */
    suspend fun add(description: SceneDescription)

    /** Permanently deletes all stored descriptions. */
    suspend fun clear()
}

/** Optional on-device archive of captured images. Requires an explicit opt-in. */
interface CapturedImageArchive {

    /**
     * Saves [image] when the user opted in; otherwise does nothing.
     *
     * @param image image to save.
     * @param requestId identifier used to name the file.
     */
    suspend fun saveIfEnabled(image: CapturedImage, requestId: String)

    /** Permanently deletes every saved image. */
    suspend fun clear()

    /** Returns the number of saved images. */
    suspend fun count(): Int
}
