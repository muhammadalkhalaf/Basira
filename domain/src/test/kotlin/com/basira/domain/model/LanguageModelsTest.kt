package com.basira.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageModelsTest {

    @Test
    fun `an explicit choice wins over the phone language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(LanguagePreference.ENGLISH, listOf("ar-SY")))
        assertEquals(AppLanguage.ARABIC, AppLanguage.resolve(LanguagePreference.ARABIC, listOf("en-US")))
    }

    @Test
    fun `system choice uses the first supported phone language`() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.resolve(LanguagePreference.SYSTEM, listOf("tr-TR", "ar-SY", "en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(LanguagePreference.SYSTEM, listOf("en-GB", "ar")))
    }

    @Test
    fun `unsupported phone languages fall back to English like the unqualified resources`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(LanguagePreference.SYSTEM, listOf("tr-TR")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(LanguagePreference.SYSTEM, emptyList()))
    }

    @Test
    fun `tags are matched on the primary subtag`() {
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromTag("ar_EG"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("EN-us"))
        assertNull(AppLanguage.fromTag("fr"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.of(null))
        assertEquals(LanguagePreference.ARABIC, LanguagePreference.of(AppLanguage.ARABIC))
    }
}
