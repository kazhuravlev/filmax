package com.filmax.feature.player.common

import com.filmax.core.domain.playback.AudioPreference
import com.filmax.core.domain.playback.TrackLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioSelectionTest {
    private val original = AudioMatchCandidate(lang = null, label = "1. Оригинал")
    private val russianDub = AudioMatchCandidate(lang = "rus", label = "2. Русский · Дубляж")
    private val russianVoiceover = AudioMatchCandidate(lang = "rus", label = "3. Русский · Многоголосый · BaibaKo")
    private val english = AudioMatchCandidate(lang = "eng", label = "4. English")

    private val candidates = listOf(original, russianDub, russianVoiceover, english)

    private val russian = AudioPreference.Language(TrackLanguage.Russian)
    private val englishPreference = AudioPreference.Language(TrackLanguage.English)

    @Test
    fun `original matches a blank language track`() {
        assertEquals(0, resolveAudioGroupIndex(AudioPreference.Original, candidates))
    }

    @Test
    fun `original matches a track labeled original even with a language code`() {
        val labeledOriginal = listOf(AudioMatchCandidate(lang = "rus", label = "1. Оригинал"), russianDub)
        assertEquals(0, resolveAudioGroupIndex(AudioPreference.Original, labeledOriginal))
    }

    @Test
    fun `russian picks the first matching group when there are several`() {
        assertEquals(1, resolveAudioGroupIndex(russian, candidates))
    }

    @Test
    fun `english picks the matching group`() {
        assertEquals(3, resolveAudioGroupIndex(englishPreference, candidates))
    }

    @Test
    fun `exact two letter code matches without a language word in the label`() {
        val terse = listOf(AudioMatchCandidate(lang = "ru", label = "1. #01"))
        assertEquals(0, resolveAudioGroupIndex(russian, terse))
    }

    @Test
    fun `no match returns null so the player keeps its own default`() {
        assertNull(resolveAudioGroupIndex(russian, listOf(original, english)))
    }

    @Test
    fun `language hidden inside another word never matches`() {
        val belarusian = listOf(AudioMatchCandidate(lang = "be", label = "1. Беларуская"))
        val bengali = listOf(AudioMatchCandidate(lang = "bn", label = "1. Bengali"))
        assertNull(resolveAudioGroupIndex(russian, belarusian))
        assertNull(resolveAudioGroupIndex(englishPreference, bengali))
    }

    @Test
    fun `language at the start of a word in the label matches`() {
        val labeledOnly = listOf(
            AudioMatchCandidate(lang = null, label = "1. Оригинал"),
            AudioMatchCandidate(lang = "", label = "2. англ. дубляж"),
        )
        assertEquals(1, resolveAudioGroupIndex(englishPreference, labeledOnly))
    }
}
