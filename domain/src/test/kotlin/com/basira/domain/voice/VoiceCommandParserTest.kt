package com.basira.domain.voice

import com.basira.domain.model.Verbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {

    private val parser = VoiceCommandParser()

    @Test
    fun `describe phrases in standard Arabic and Levantine map to Describe`() {
        assertEquals(VoiceCommand.Describe(), parser.parse("صِف ما أمامي"))
        assertEquals(VoiceCommand.Describe(), parser.parse("شو في قدامي؟"))
        assertEquals(VoiceCommand.Describe(), parser.parse("Describe"))
    }

    @Test
    fun `detailed request sets verbosity override`() {
        assertEquals(VoiceCommand.Describe(Verbosity.DETAILED), parser.parse("وصف مفصل من فضلك"))
        assertEquals(VoiceCommand.Describe(Verbosity.SHORT), parser.parse("صف باختصار"))
    }

    @Test
    fun `read text tolerates hamza and taa marbuta variants`() {
        assertEquals(VoiceCommand.ReadText, parser.parse("اقرأ النص"))
        assertEquals(VoiceCommand.ReadText, parser.parse("إقرا"))
        assertEquals(VoiceCommand.ReadText, parser.parse("قراءة"))
    }

    @Test
    fun `find object extracts the target`() {
        assertEquals(VoiceCommand.FindObject("المفاتيح"), parser.parse("ابحث عن المفاتيح"))
        assertEquals(VoiceCommand.FindObject("الكاس"), parser.parse("دوّر على الكاس"))
        assertEquals(VoiceCommand.FindObject("my keys"), parser.parse("find my keys"))
    }

    @Test
    fun `find object without target falls back to other commands`() {
        assertNull(parser.parse("ابحث عن"))
    }

    @Test
    fun `control commands`() {
        assertEquals(VoiceCommand.Repeat, parser.parse("أعد"))
        assertEquals(VoiceCommand.StopSpeaking, parser.parse("اسكت"))
        assertEquals(VoiceCommand.Cancel, parser.parse("إلغاء"))
        assertEquals(VoiceCommand.Currency, parser.parse("كم هذه العملة"))
        assertEquals(VoiceCommand.Status, parser.parse("ما الحالة"))
    }

    @Test
    fun `unrelated speech is not understood`() {
        assertNull(parser.parse("مرحبا كيف حالك"))
        assertNull(parser.parse("   "))
    }

    @Test
    fun `normalize removes diacritics and unifies letters`() {
        assertEquals("اقرا الرساله", parser.normalize("اقْرَأْ الرِّسالة"))
    }
}
