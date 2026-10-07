package com.basira.domain.repository

import com.basira.domain.model.AppLanguage
import com.basira.domain.model.LanguagePreference
import kotlinx.coroutines.flow.StateFlow

/** The app language: the user's choice and the language that results from it. */
interface AppLanguageRepository {

    /** Language the UI is shown in; speech, voice commands, and descriptions follow it. */
    val language: StateFlow<AppLanguage>

    /** The user's choice; [LanguagePreference.SYSTEM] follows the phone's language. */
    val preference: StateFlow<LanguagePreference>

    /**
     * Applies and persists a new choice. The visible screens are recreated in the new language.
     *
     * @param preference the new choice.
     */
    fun setPreference(preference: LanguagePreference)
}
