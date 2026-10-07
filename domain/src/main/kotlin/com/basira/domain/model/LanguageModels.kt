package com.basira.domain.model

/**
 * Language the app is shown in. Speech, voice commands, and description requests always use the
 * same language as the UI, so the user never hears a language that differs from the screen.
 *
 * @property tag BCP-47 language tag.
 */
enum class AppLanguage(val tag: String) {
    /** Modern Standard Arabic. */
    ARABIC("ar"),

    /** English; also the fallback for system languages the app does not translate. */
    ENGLISH("en"),
    ;

    companion object {
        /** Language of the unqualified resources, shown when no system language is supported. */
        val FALLBACK: AppLanguage = ENGLISH

        /**
         * Resolves the language the app is shown in, mirroring Android resource resolution: an
         * explicit choice wins; otherwise the first supported system language, else [FALLBACK].
         *
         * @param preference the user's choice.
         * @param systemLanguageTags the system locale list, most preferred first.
         * @return the effective language.
         */
        fun resolve(preference: LanguagePreference, systemLanguageTags: List<String>): AppLanguage =
            preference.language
                ?: systemLanguageTags.firstNotNullOfOrNull(::fromTag)
                ?: FALLBACK

        /**
         * @param tag a BCP-47 tag such as `ar-SY` or `en-US`.
         * @return the supported language with the same primary subtag, or `null`.
         */
        fun fromTag(tag: String): AppLanguage? {
            val primary = tag.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.tag == primary }
        }
    }
}

/**
 * The user's language choice in Settings.
 *
 * @property language the fixed language, or `null` to follow the phone's language.
 */
enum class LanguagePreference(val language: AppLanguage?) {
    /** Follow the phone's language. */
    SYSTEM(null),

    /** Always Arabic. */
    ARABIC(AppLanguage.ARABIC),

    /** Always English. */
    ENGLISH(AppLanguage.ENGLISH),
    ;

    companion object {
        /**
         * @param language a fixed language, or `null` for the phone's language.
         * @return the matching preference.
         */
        fun of(language: AppLanguage?): LanguagePreference = entries.first { it.language == language }
    }
}
