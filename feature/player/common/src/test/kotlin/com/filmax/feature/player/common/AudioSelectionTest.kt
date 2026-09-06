package com.filmax.feature.player.common

import com.filmax.core.domain.playback.PlaybackSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioSelectionTest {

    private val original = AudioMatchCandidate(lang = null, label = "1. Оригинал")
    private val russianDub = AudioMatchCandidate(lang = "rus", label = "2. Русский · Дубляж")
    private val russianVoiceover = AudioMatchCandidate(lang = "rus", label = "3. Русский · Многоголосый · BaibaKo")
    private val english = AudioMatchCandidate(lang = "eng", label = "4. English")

    private val candidates = listOf(original, russianDub, russianVoiceover, english)

    @Test
    fun `off never overrides the player default`() {
        assertNull(resolveAudioGroupIndex(PlaybackSettings.AudioOff, candidates))
    }

    @Test
    fun `original matches a blank language track`() {
        assertEquals(0, resolveAudioGroupIndex(PlaybackSettings.AudioOriginal, candidates))
    }

    @Test
    fun `original matches a track labeled original even with a language code`() {
        val labeledOriginal = listOf(AudioMatchCandidate(lang = "rus", label = "1. Оригинал"), russianDub)
        assertEquals(0, resolveAudioGroupIndex(PlaybackSettings.AudioOriginal, labeledOriginal))
    }

    @Test
    fun `russian picks the first matching group when there are several`() {
        assertEquals(1, resolveAudioGroupIndex("Русский", candidates))
    }

    @Test
    fun `english picks the matching group`() {
        assertEquals(3, resolveAudioGroupIndex("English", candidates))
    }

    @Test
    fun `no match returns null so the player keeps its own default`() {
        assertNull(resolveAudioGroupIndex("Русский", listOf(original, english)))
    }

    @Test
    fun `unrecognized preference returns null`() {
        assertNull(resolveAudioGroupIndex("Українська", candidates))
    }
}
