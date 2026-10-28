package com.filmax.feature.details.common

import com.filmax.core.domain.catalog.model.Duration
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.catalog.model.Posters
import com.filmax.core.domain.catalog.model.WatchStatus
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.WatchProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayLabelTest {
    @Test
    fun `label hides the episode while the continuation is still being resolved`() {
        val first = episode(season = 1, number = 1)

        val label = playLabel(continuation = null, resume = null, target = first, resolving = true)

        assertEquals("Смотреть", label)
    }

    @Test
    fun `resolving wins over an already known continuation`() {
        val track = episode(season = 2, number = 5)
        val continuation = continuation(track, position = 754, actual = true)

        val label = playLabel(continuation, resume = track, target = track, resolving = true)

        assertEquals("Смотреть", label)
    }

    @Test
    fun `actual continuation shows position and episode tag`() {
        val track = episode(season = 2, number = 5)
        val continuation = continuation(track, position = 754, actual = true)

        val label = playLabel(continuation, resume = track, target = track)

        assertEquals("Продолжить с 12:34 · S2E5", label)
    }

    @Test
    fun `next episode from the start has no position`() {
        val track = episode(season = 3, number = 1)
        val continuation = continuation(track, position = 0, actual = true)

        val label = playLabel(continuation, resume = track, target = track)

        assertEquals("Продолжить · S3E1", label)
    }

    @Test
    fun `finished series falls back to the target episode`() {
        val first = episode(season = 1, number = 1)
        val continuation = continuation(episode(season = 1, number = 8), position = 0, actual = false)

        val label = playLabel(continuation, resume = null, target = first)

        assertEquals("Смотреть · S1E1", label)
    }

    @Test
    fun `movie without continuation is plain watch`() {
        assertEquals("Смотреть", playLabel(continuation = null, resume = null, target = null))
    }

    @Test
    fun `resume position over an hour includes hours`() {
        assertEquals("1:02:05", formatResumePosition(3_725))
        assertEquals("0:09", formatResumePosition(9))
    }

    private fun continuation(track: MediaTrack, position: Int, actual: Boolean) = Continuation(
        item = series(track),
        season = track.seasonNumber,
        videoId = track.number,
        savedPositionSeconds = position,
        isLastEpisode = false,
        isActualContinuation = actual,
        progress = WatchProgress(
            status = if (actual) WatchStatus.InProgress else WatchStatus.Finished,
            timeSeconds = position,
            durationSeconds = track.durationSeconds,
            videoId = track.number,
            season = track.seasonNumber,
        ),
    )

    private fun series(vararg tracks: MediaTrack) = Item(
        id = 1,
        title = "Test series",
        type = ItemType.SERIES,
        year = 2026,
        plot = "",
        director = "",
        cast = "",
        country = "",
        genres = emptyList(),
        rating = ItemRating(filmax = 0, filmaxPercentage = "", imdb = null, kinopoisk = null),
        posters = Posters(small = "", medium = "", big = "", wide = null),
        duration = Duration(averageMinutes = null, totalMinutes = null),
        tracklist = tracks.toList(),
        trailer = null,
        inWatchlist = false,
        finished = false,
    )

    private fun episode(season: Int, number: Int) = MediaTrack(
        id = season * 100 + number,
        number = number,
        seasonNumber = season,
        title = "S${season}E$number",
        thumbnail = "",
        durationSeconds = 1_200,
        files = emptyList(),
        audios = emptyList(),
        subtitles = emptyList(),
    )
}
