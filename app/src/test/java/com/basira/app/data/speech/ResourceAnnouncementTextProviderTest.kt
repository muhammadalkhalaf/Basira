package com.basira.app.data.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.basira.app.data.accessibility.AccessibilityStateProvider
import com.basira.domain.assistant.Announcement
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.Confidence
import com.basira.domain.model.SceneDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class ResourceAnnouncementTextProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ResourceAnnouncementTextProvider(context, AccessibilityStateProvider(context))
    private val arabicLetters = Regex("[\\u0600-\\u06FF]")

    @Test
    @Config(qualifiers = "en")
    fun `spoken text is Arabic even when the UI language is English`() {
        val text = provider.textFor(Announcement.Status(AssistantPhase.Offline))!!
        assertTrue(text, arabicLetters.containsMatchIn(text))
        assertTrue(text.contains("لا يوجد اتصال بالإنترنت"))
    }

    @Test
    fun `ready status mentions the phone speaker route`() {
        val text = provider.textFor(Announcement.Status(AssistantPhase.Ready, route = AudioRoute.PHONE_SPEAKER))!!
        assertTrue(text.contains("سماعة الهاتف"))
    }

    @Test
    fun `low confidence descriptions are prefixed with uncertainty and warnings follow`() {
        val description = SceneDescription("r", "ربما كرسي أمامك", Confidence.LOW, listOf("الصورة معتمة"), null, AnalysisMode.SCENE_DESCRIPTION, null, 0)
        val text = provider.textFor(Announcement.Description(description, repeated = false))!!
        assertEquals("لست متأكداً. ربما كرسي أمامك ملاحظة: الصورة معتمة", text)
    }

    @Test
    fun `find announcement names the object`() {
        assertEquals("أبحث عن المفاتيح.", provider.textFor(Announcement.CaptureStarted(AnalysisMode.FIND_OBJECT, "المفاتيح")))
    }
}
