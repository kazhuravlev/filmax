package com.filmax.feature.player.tv

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.feature.player.common.AudioOption
import com.filmax.feature.player.common.NO_VALUE_CAPTION
import com.filmax.feature.player.common.PresetSelection
import com.filmax.feature.player.common.StreamQuality
import com.filmax.feature.player.common.SubtitleOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerChoicesTest {

    @Test
    fun `audio caption is the language code regardless of how the label spells it`() {
        assertEquals("rus", AudioOption("2. Русский · Дубляж", groupIndex = 1, lang = "rus").toChoice().shortValue)
        assertEquals("rus", AudioOption("2. RUS #02", groupIndex = 1, lang = "ru").toChoice().shortValue)
        assertEquals("eng", AudioOption("4. English", groupIndex = 3, lang = "eng").toChoice().shortValue)
    }

    @Test
    fun `audio without a language code is original`() {
        assertEquals("orig", AudioOption("1. Оригинал", groupIndex = 0, lang = null).toChoice().shortValue)
        assertEquals("orig", AudioOption("1. Оригинал", groupIndex = 0, lang = "").toChoice().shortValue)
    }

    @Test
    fun `unknown audio language falls back to its raw code not to a word from the label`() {
        assertEquals("be", AudioOption("3. Беларуская", groupIndex = 2, lang = "be").toChoice().shortValue)
    }

    @Test
    fun `subtitles off caption is a dash`() {
        assertEquals(NO_VALUE_CAPTION, SubtitleOption.Off.toChoice().shortValue)
        val english = SubtitleOption.Track("ENG #03", lang = "eng", groupIndex = 0, trackIndex = 2)
        assertEquals("eng", english.toChoice().shortValue)
    }

    @Test
    fun `quality caption maps frame height to the card badge`() {
        assertEquals("FHD", StreamQuality("1080p", listOf("u")).toChoice().shortValue)
        assertEquals("4K", StreamQuality("2160p", listOf("u")).toChoice().shortValue)
    }

    @Test
    fun `non numeric quality label is shown as is`() {
        assertEquals("auto", StreamQuality("auto", listOf("u")).toChoice().shortValue)
    }

    @Test
    fun `preset list and current preset selection produce the same choice`() {
        val preset = TrackPreset.OriginalEnglishSubs
        assertEquals(preset.toChoice(), PresetSelection.Preset(preset).toChoice())
        assertEquals((null as TrackPreset?).toChoice(), PresetSelection.Auto.toChoice())
        assertEquals(PlaybackSettings.PresetAuto, PresetSelection.Auto.toChoice().label)
    }

    @Test
    fun `custom preset selection is not in the options list so the cursor falls back to auto`() {
        val options = PlaybackSettings.presetOptions.map { it.toChoice() }
        assertEquals(-1, options.indexOf(PresetSelection.Custom.toChoice()))
    }
}
