package com.filmax.feature.player.common

import com.filmax.core.domain.playback.SubtitleKey
import com.filmax.core.domain.playback.SubtitlePreference
import com.filmax.core.domain.playback.TrackLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleSelectionTest {

    private val off = SubtitleOption.Off
    private val russianForced = SubtitleOption.Track(
        label = "RUS #01 Forced",
        lang = "rus",
        groupIndex = 0,
        trackIndex = 0,
        isForced = true,
    )
    private val russianFull = SubtitleOption.Track(
        label = "RUS #02",
        lang = "rus",
        groupIndex = 0,
        trackIndex = 1,
    )
    private val english = SubtitleOption.Track(
        label = "ENG #03",
        lang = "eng",
        groupIndex = 0,
        trackIndex = 2,
    )

    private val options = listOf(off, russianForced, russianFull, english)

    private val russian = SubtitleSelection.ByLanguage(TrackLanguage.Russian)

    @Test
    fun `legacy language keys parse into a language selection`() {
        assertEquals(russian, SubtitleSelection.parse(SubtitleKey("rus")))
        assertEquals(russian, SubtitleSelection.parse(SubtitleKey("ru")))
        assertEquals(russian, SubtitleSelection.parse(SubtitleKey("Русский")))
    }

    @Test
    fun `off key parses into off and the off option produces the off key`() {
        assertEquals(SubtitleSelection.Off, SubtitleSelection.parse(off.preferenceKey()))
        assertEquals(SubtitleSelection.Off, SubtitleSelection.of(SubtitlePreference.Off))
    }

    @Test
    fun `saved track key round trips through parse`() {
        assertEquals(
            SubtitleSelection.SavedTrack(language = "rus", label = "RUS #02"),
            SubtitleSelection.parse(russianFull.preferenceKey()),
        )
    }

    @Test
    fun `unknown raw key is kept as a legacy label`() {
        assertEquals(SubtitleSelection.LegacyLabel("ENG #03"), SubtitleSelection.parse(SubtitleKey("ENG #03")))
        assertEquals(english, resolveSubtitleOption(options, SubtitleSelection.LegacyLabel("ENG #03")))
    }

    @Test
    fun `legacy russian language chooses non forced subtitle`() {
        assertEquals(russianFull, resolveSubtitleOption(options, SubtitleSelection.parse(SubtitleKey("rus"))))
    }

    @Test
    fun `saved subtitle track keeps the selected russian rendition`() {
        assertEquals(
            russianFull,
            resolveSubtitleOption(options, SubtitleSelection.parse(russianFull.preferenceKey())),
        )
    }

    @Test
    fun `explicit forced track remains selectable`() {
        assertEquals(
            russianForced,
            resolveSubtitleOption(options, SubtitleSelection.parse(russianForced.preferenceKey())),
        )
    }

    @Test
    fun `global default russian picks first non forced russian track`() {
        assertEquals(russianFull, resolveSubtitleOption(options, russian))
    }

    @Test
    fun `global default english picks the english track`() {
        assertEquals(english, resolveSubtitleOption(options, SubtitleSelection.ByLanguage(TrackLanguage.English)))
    }

    @Test
    fun `global default off always disables subtitles`() {
        assertEquals(off, resolveSubtitleOption(options, SubtitleSelection.Off))
    }

    @Test
    fun `no match on a language without tracks falls back to off`() {
        assertEquals(off, resolveSubtitleOption(options, SubtitleSelection.ByLanguage(TrackLanguage.Ukrainian)))
    }

    @Test
    fun `only forced track available never gets auto picked by language default`() {
        val forcedOnly = listOf(off, russianForced)
        assertEquals(off, resolveSubtitleOption(forcedOnly, russian))
    }

    @Test
    fun `track without a manifest language still round trips through its saved key`() {
        val unlabeledLanguage = SubtitleOption.Track(label = "#04", lang = null, groupIndex = 0, trackIndex = 3)
        val withUnlabeled = options + unlabeledLanguage
        assertEquals(
            SubtitleSelection.SavedTrack(language = "", label = "#04"),
            SubtitleSelection.parse(unlabeledLanguage.preferenceKey()),
        )
        assertEquals(
            unlabeledLanguage,
            resolveSubtitleOption(withUnlabeled, SubtitleSelection.parse(unlabeledLanguage.preferenceKey())),
        )
    }

    @Test
    fun `short codes come from the typed language and never from the label`() {
        assertEquals("rus", russianFull.shortCode)
        assertEquals(NO_VALUE_CAPTION, off.shortCode)
        val japanese = SubtitleOption.Track(label = "Japanese", lang = "JPN", groupIndex = 0, trackIndex = 0)
        assertEquals("jpn", japanese.shortCode)
        assertEquals(
            SubtitleOption.Track.UNKNOWN_LANGUAGE_SHORT_CODE,
            SubtitleOption.Track(label = "#04", lang = null, groupIndex = 0, trackIndex = 3).shortCode,
        )
    }
}
