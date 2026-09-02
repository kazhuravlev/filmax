package com.filmax.feature.player.common

import com.filmax.core.domain.playback.PlaybackSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleSelectionTest {

    private val off = SubtitleOption(PlaybackSettings.SubtitleOff, null)
    private val russianForced = SubtitleOption(
        label = "RUS #01 Forced",
        lang = "rus",
        groupIndex = 0,
        trackIndex = 0,
        isForced = true,
    )
    private val russianFull = SubtitleOption(
        label = "RUS #02",
        lang = "rus",
        groupIndex = 0,
        trackIndex = 1,
    )
    private val english = SubtitleOption(
        label = "ENG #03",
        lang = "eng",
        groupIndex = 0,
        trackIndex = 2,
    )

    private val options = listOf(off, russianForced, russianFull, english)

    @Test
    fun `legacy russian language chooses non forced subtitle`() {
        assertEquals(russianFull, resolveSubtitleOption(options, "rus"))
        assertEquals(russianFull, resolveSubtitleOption(options, "Русский"))
    }

    @Test
    fun `saved subtitle track keeps the selected russian rendition`() {
        assertEquals(
            russianFull,
            resolveSubtitleOption(options, russianFull.preferenceKey()),
        )
    }

    @Test
    fun `explicit forced track remains selectable`() {
        assertEquals(
            russianForced,
            resolveSubtitleOption(options, russianForced.preferenceKey()),
        )
    }
}
