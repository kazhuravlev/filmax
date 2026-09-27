package com.filmax.feature.player.common

import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.playback.VoiceKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackResolutionTest {
    private val original = AudioMatchCandidate(lang = null, label = "1. Оригинал")
    private val russianDub = AudioMatchCandidate(lang = "rus", label = "2. Русский · Дубляж")
    private val russianVoiceover = AudioMatchCandidate(lang = "rus", label = "3. Русский · Многоголосый · BaibaKo")
    private val allAudio = listOf(original, russianDub, russianVoiceover)
    private val allVoiceKeys = listOf("||", "rus|Дубляж|", "rus|Многоголосый|BaibaKo").map(::VoiceKey)

    private val off = SubtitleOption.Off
    private val russianSubs = SubtitleOption.Track(label = "RUS #01", lang = "rus", groupIndex = 0, trackIndex = 0)
    private val englishSubs = SubtitleOption.Track(label = "ENG #02", lang = "eng", groupIndex = 0, trackIndex = 1)

    @Test
    fun `auto picks the first preset whose audio and subtitles both exist`() {
        val resolved = resolveTracks(allAudio, allVoiceKeys, listOf(off, russianSubs, englishSubs), null, null)
        assertEquals(TrackPreset.OriginalEnglishSubs, resolved.preset)
        assertEquals(0, resolved.audioIndex)
        assertEquals(englishSubs, resolved.subtitle)
        assertEquals(PresetSelection.Preset(TrackPreset.OriginalEnglishSubs), resolved.presetSelection)
    }

    @Test
    fun `auto skips a preset when its subtitles are missing`() {
        val resolved = resolveTracks(allAudio, allVoiceKeys, listOf(off, russianSubs), null, null)
        assertEquals(TrackPreset.OriginalRussianSubs, resolved.preset)
        assertEquals(0, resolved.audioIndex)
        assertEquals(russianSubs, resolved.subtitle)
    }

    @Test
    fun `auto falls through to russian dub when there are no subtitles`() {
        val resolved = resolveTracks(allAudio, allVoiceKeys, listOf(off), null, null)
        assertEquals(TrackPreset.RussianDub, resolved.preset)
        assertEquals(1, resolved.audioIndex)
        assertEquals(off, resolved.subtitle)
    }

    @Test
    fun `auto with nothing matching leaves the player default and subtitles off`() {
        val resolved = resolveTracks(listOf(original), listOf(VoiceKey("||")), listOf(off), null, null)
        assertNull(resolved.preset)
        assertNull(resolved.audioIndex)
        assertEquals(off, resolved.subtitle)
        assertEquals(PresetSelection.Auto, resolved.presetSelection)
    }

    @Test
    fun `global fixed preset is used for a title without its own memory`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, russianSubs, englishSubs),
            selection = null,
            globalPreset = TrackPreset.RussianDub,
        )
        assertEquals(TrackPreset.RussianDub, resolved.preset)
        assertEquals(1, resolved.audioIndex)
        assertEquals(off, resolved.subtitle)
    }

    @Test
    fun `title auto beats a global fixed preset`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, russianSubs, englishSubs),
            selection = TitleTracks.Preset(null),
            globalPreset = TrackPreset.RussianDub,
        )
        assertEquals(TrackPreset.OriginalEnglishSubs, resolved.preset)
    }

    @Test
    fun `title preset beats global auto`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, russianSubs, englishSubs),
            selection = TitleTracks.Preset(TrackPreset.OriginalRussianSubs),
            globalPreset = null,
        )
        assertEquals(TrackPreset.OriginalRussianSubs, resolved.preset)
        assertEquals(russianSubs, resolved.subtitle)
    }

    @Test
    fun `custom selection restores the saved voice and subtitle`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, russianSubs, englishSubs),
            selection = TitleTracks.Custom(
                voiceKey = VoiceKey("rus|Многоголосый|BaibaKo"),
                subtitleKey = russianSubs.preferenceKey(),
            ),
            globalPreset = null,
        )
        assertTrue(resolved.isCustom)
        assertNull(resolved.preset)
        assertEquals(2, resolved.audioIndex)
        assertEquals(russianSubs, resolved.subtitle)
        assertEquals(PresetSelection.Custom, resolved.presetSelection)
    }

    @Test
    fun `custom voice missing in this episode falls back to the preset audio`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, englishSubs),
            selection = TitleTracks.Custom(
                voiceKey = VoiceKey("rus|Дубляж|LostFilm"),
                subtitleKey = off.preferenceKey(),
            ),
            globalPreset = null,
        )
        assertTrue(resolved.isCustom)
        assertEquals(0, resolved.audioIndex)
        assertEquals(off, resolved.subtitle)
    }

    @Test
    fun `legacy custom without subtitles takes subtitles from the preset`() {
        val resolved = resolveTracks(
            candidates = allAudio,
            voiceKeys = allVoiceKeys,
            options = listOf(off, russianSubs, englishSubs),
            selection = TitleTracks.Custom(voiceKey = VoiceKey("rus|Дубляж|"), subtitleKey = null),
            globalPreset = null,
        )
        assertEquals(1, resolved.audioIndex)
        assertEquals(englishSubs, resolved.subtitle)
        assertFalse(resolved.preset != null)
    }
}
