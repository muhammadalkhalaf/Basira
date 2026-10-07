package com.basira.app.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards that every English base string has an Arabic translation and the reverse. */
class LocalizationTest {

    private fun keys(path: String): Set<String> {
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `Arabic and English resources define the same keys`() {
        val english = keys("src/main/res/values/strings.xml")
        val arabic = keys("src/main/res/values-ar/strings.xml")
        assertTrue("Missing in values-ar: ${english - arabic}", (english - arabic).isEmpty())
        assertTrue("Missing in values: ${arabic - english}", (arabic - english).isEmpty())
    }
}
