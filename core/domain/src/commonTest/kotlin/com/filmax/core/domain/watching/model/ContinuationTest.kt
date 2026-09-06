package com.filmax.core.domain.watching.model

import com.filmax.core.domain.catalog.model.Duration
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.catalog.model.Posters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContinuationTest {
    @Test
    fun `last episode with 60 seconds remaining is not continuation`() {
        val item = series(
            episode(season = 2, number = 1),
            episode(season = 2, number = 2, watchStatus = 1),
        )

        val result = calculateContinuation(item, history(season = 2, video = 2, time = 1_140, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isLastEpisode)
        assertFalse(result.isActualContinuation)
    }

    @Test
    fun `last episode above finish threshold remains continuation`() {
        val item = series(episode(season = 1, number = 2), episode(season = 1, number = 1))

        val result = calculateContinuation(item, history(season = 1, video = 2, time = 1_100, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isLastEpisode)
        assertTrue(result.isActualContinuation)
    }

    @Test
    fun `episode with next episode continues even near its end`() {
        val item = series(episode(season = 1, number = 1), episode(season = 1, number = 2))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 1_140, duration = 1_200))

        assertNotNull(result)
        assertFalse(result.isLastEpisode)
        assertTrue(result.isActualContinuation)
    }

    @Test
    fun `completed series without next episode has no continuation`() {
        val item = series(episode(season = 1, number = 1, watchedSeconds = 1_200, watchStatus = 1))

        val result = calculateContinuation(item)

        assertNotNull(result)
        assertTrue(result.isLastEpisode)
        assertFalse(result.isActualContinuation)
    }

    @Test
    fun `continuation preserves history position regardless of finished watch status`() {
        val item = series(episode(season = 1, number = 1, watchStatus = 1))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 600, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(600, result.savedPositionSeconds)
        assertEquals(600, result.progress.timeSeconds)
    }

    @Test
    fun `mismatched history does not resume a different episode`() {
        val item = series(episode(season = 1, number = 1, watchedSeconds = 600, watchStatus = 0))

        val result = calculateContinuation(item, history(season = 1, video = 99, time = 600, duration = 1_200))

        assertEquals(null, result)
    }

    @Test
    fun `progress uses track duration when history omits it`() {
        val item = series(episode(season = 1, number = 1, watchStatus = 1))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 600, duration = 0))

        assertNotNull(result)
        assertEquals(1_200, result.progress.durationSeconds)
    }

    @Test
    fun `movie resumes its first track when history omits video number`() {
        val item = series(episode(season = 0, number = 0)).copy(type = ItemType.MOVIE)

        val result = calculateContinuation(
            item,
            history(season = null, video = null, time = 2_405, duration = 7_200),
        )

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(2_405, result.savedPositionSeconds)
    }

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

    private fun episode(
        season: Int,
        number: Int,
        watchedSeconds: Int = 0,
        watchStatus: Int = -1,
    ) = MediaTrack(
        id = season * 100 + number,
        number = number,
        seasonNumber = season,
        title = "S${season}E$number",
        thumbnail = "",
        durationSeconds = 1_200,
        files = emptyList(),
        audios = emptyList(),
        subtitles = emptyList(),
        watchedSeconds = watchedSeconds,
        watchStatus = watchStatus,
    )

    private fun history(season: Int?, video: Int?, time: Int, duration: Int) = WatchHistory(
        itemId = 1,
        title = "Test series",
        posterSmall = null,
        progress = WatchProgress(
            status = 0,
            timeSeconds = time,
            durationSeconds = duration,
            videoId = video,
            season = season,
        ),
    )
}
