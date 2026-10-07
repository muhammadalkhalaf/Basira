package com.basira.domain.voice

import com.basira.domain.model.Verbosity
import javax.inject.Inject

/** Commands that can be spoken during an active, user-initiated voice session. */
sealed interface VoiceCommand {
    /**
     * Describe the scene.
     *
     * @property verbosityOverride verbosity requested in the utterance, if any.
     */
    data class Describe(val verbosityOverride: Verbosity? = null) : VoiceCommand

    /** Read visible text. */
    data object ReadText : VoiceCommand

    /**
     * Look for an object.
     *
     * @property target the object name as spoken.
     */
    data class FindObject(val target: String) : VoiceCommand

    /** Identify a banknote. */
    data object Currency : VoiceCommand

    /** Repeat the last description. */
    data object Repeat : VoiceCommand

    /** Stop speaking. */
    data object StopSpeaking : VoiceCommand

    /** Cancel the running operation. */
    data object Cancel : VoiceCommand

    /** Announce the current status. */
    data object Status : VoiceCommand
}

/**
 * Maps a free-form Arabic (Modern Standard and common Levantine phrasing) or English transcript to
 * a [VoiceCommand].
 *
 * Arabic text is normalized before matching: diacritics and tatweel are removed and letter variants
 * (alef forms, alef maqsura, taa marbuta) are unified, so recognizer spelling differences do not
 * matter.
 */
class VoiceCommandParser @Inject constructor() {

    /**
     * Parses [transcript].
     *
     * @param transcript recognizer output.
     * @return the recognized command, or `null` when nothing matched.
     */
    fun parse(transcript: String): VoiceCommand? {
        val text = normalize(transcript)
        if (text.isEmpty()) return null
        findTarget(text)?.let { return VoiceCommand.FindObject(it) }
        return when {
            containsAny(text, cancelWords) -> VoiceCommand.Cancel
            containsAny(text, stopWords) -> VoiceCommand.StopSpeaking
            containsAny(text, repeatWords) -> VoiceCommand.Repeat
            containsAny(text, statusWords) -> VoiceCommand.Status
            containsAny(text, currencyWords) -> VoiceCommand.Currency
            containsAny(text, readWords) -> VoiceCommand.ReadText
            containsAny(text, describeWords) -> VoiceCommand.Describe(
                verbosityOverride = when {
                    containsAny(text, detailedWords) -> Verbosity.DETAILED
                    containsAny(text, shortWords) -> Verbosity.SHORT
                    else -> null
                },
            )
            else -> null
        }
    }

    private fun findTarget(text: String): String? {
        for (prefix in findPrefixes) {
            val index = text.indexOf(prefix)
            if (index >= 0 && (index == 0 || text[index - 1] == ' ')) {
                val target = text.substring(index + prefix.length).trim()
                if (target.isNotEmpty()) return target
            }
        }
        return null
    }

    private fun containsAny(text: String, words: List<String>): Boolean {
        val padded = " $text "
        return words.any { padded.contains(" $it ") || padded.contains(" $it") && it.length >= 4 }
    }

    /**
     * Normalizes Arabic and Latin text for keyword matching.
     *
     * @param raw recognizer output.
     * @return lower-case text with unified Arabic letter forms and single spaces.
     */
    fun normalize(raw: String): String = normalizeText(raw)

    private companion object {
        val diacritics = Regex("[\\u064B-\\u0652\\u0670]")
        val punctuation = Regex("[\\p{Punct}،؟؛«»]")
        val alefVariants = Regex("[إأآٱ]")
        val whitespace = Regex("\\s+")

        /** Removes diacritics and tatweel, unifies letter variants, strips punctuation. */
        fun normalizeText(raw: String): String = raw
            .lowercase()
            .replace(diacritics, "")
            .replace('\u0640', ' ')
            .replace(alefVariants, "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(punctuation, " ")
            .replace(whitespace, " ")
            .trim()

        /** Normalizes every keyword so it matches normalized transcripts. */
        fun List<String>.normalized(): List<String> = map(::normalizeText)

        // Phrases are stored in normalized form (see normalize()).
        val findPrefixes = listOf(
            "ابحث عن", "ابحثي عن", "دور على", "دور ع", "دوري على", "فتش عن", "وين ال", "اين ال",
            "find the", "find", "where is the", "where is", "look for",
        ).normalized()
        val cancelWords = listOf("الغاء", "الغي", "الغ", "cancel").normalized()
        val stopWords = listOf("توقف", "اسكت", "اسكتي", "قف", "كفى", "خلص", "stop", "quiet").normalized()
        val repeatWords = listOf("اعد", "اعيد", "كرر", "عيد", "مره ثانيه", "repeat", "again").normalized()
        val statusWords = listOf("الحاله", "حاله", "وضع النظاره", "status").normalized()
        val currencyWords = listOf("عمله", "العمله", "مصاري", "فلوس", "نقود", "ورقه نقديه", "currency", "money", "banknote").normalized()
        val readWords = listOf("اقرا", "اقري", "قراءه", "النص", "مكتوب", "read", "text").normalized()
        val describeWords = listOf(
            "صف", "صفي", "وصف", "ماذا امامي", "ما امامي", "شو قدامي", "شو في قدامي", "ماذا يوجد",
            "describe", "what is in front", "whats in front",
        ).normalized()
        val detailedWords = listOf("مفصل", "بالتفصيل", "تفصيلي", "detailed", "detail").normalized()
        val shortWords = listOf("مختصر", "باختصار", "قصير", "short", "brief").normalized()
    }
}
