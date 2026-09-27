package com.filmax.core.domain.watching.model

import com.filmax.core.domain.catalog.model.Duration
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.catalog.model.Posters
import com.filmax.core.domain.catalog.model.WatchStatus
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
            episode(season = 2, number = 2, watchStatus = WatchStatus.Finished),
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
    fun `episode stopped on credits advances to next episode from start`() {
        val item = series(episode(season = 1, number = 1), episode(season = 1, number = 2))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 1_140, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(1, result.season)
        assertEquals(2, result.videoId)
        assertEquals(0, result.savedPositionSeconds)
        assertTrue(result.isLastEpisode)
    }

    @Test
    fun `episode above finish threshold with next episode resumes in place`() {
        val item = series(episode(season = 1, number = 1), episode(season = 1, number = 2))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 1_000, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(1, result.videoId)
        assertEquals(1_000, result.savedPositionSeconds)
        assertFalse(result.isLastEpisode)
    }

    @Test
    fun `server finished status advances past history position to next unwatched episode`() {
        val item = series(
            episode(season = 1, number = 1, watchStatus = WatchStatus.Finished),
            episode(season = 1, number = 2, watchStatus = WatchStatus.Finished),
            episode(season = 2, number = 1),
        )

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 600, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(2, result.season)
        assertEquals(1, result.videoId)
        assertEquals(0, result.savedPositionSeconds)
    }

    @Test
    fun `next episode already in progress keeps its own position`() {
        val item = series(
            episode(season = 1, number = 1, watchStatus = WatchStatus.Finished),
            episode(season = 1, number = 2, watchedSeconds = 300, watchStatus = WatchStatus.InProgress),
        )

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 1_190, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(2, result.videoId)
        assertEquals(300, result.savedPositionSeconds)
    }

    @Test
    fun `next episode card uses its own thumbnail instead of finished episode frame`() {
        val item = series(episode(season = 1, number = 1), episode(season = 1, number = 2))
        val entry = history(season = 1, video = 1, time = 1_190, duration = 1_200)
            .copy(episodeThumbnail = "frame-of-e1.jpg")

        val result = calculateContinuation(item, entry)

        assertNotNull(result)
        assertEquals("thumb-S1E2.jpg", result.wideOrPoster)
    }

    @Test
    fun `without history finished tracks advance to first unwatched episode`() {
        val item = series(
            episode(season = 1, number = 1, watchedSeconds = 1_200, watchStatus = WatchStatus.Finished),
            episode(season = 1, number = 2),
        )

        val result = calculateContinuation(item)

        assertNotNull(result)
        assertTrue(result.isActualContinuation)
        assertEquals(2, result.videoId)
        assertEquals(0, result.savedPositionSeconds)
    }

    @Test
    fun `movie stopped on credits is not continuation`() {
        val item = series(episode(season = 0, number = 1)).copy(type = ItemType.MOVIE)

        val result = calculateContinuation(item, history(season = null, video = 1, time = 7_150, duration = 7_200))

        assertNotNull(result)
        assertFalse(result.isActualContinuation)
    }

    @Test
    fun `completed series without next episode has no continuation`() {
        val item = series(episode(season = 1, number = 1, watchedSeconds = 1_200, watchStatus = WatchStatus.Finished))

        val result = calculateContinuation(item)

        assertNotNull(result)
        assertTrue(result.isLastEpisode)
        assertFalse(result.isActualContinuation)
    }

    @Test
    fun `finished last episode is not continuation even with history position`() {
        val item = series(episode(season = 1, number = 1, watchStatus = WatchStatus.Finished))

        val result = calculateContinuation(item, history(season = 1, video = 1, time = 600, duration = 1_200))

        assertNotNull(result)
        assertTrue(result.isLastEpisode)
        assertFalse(result.isActualContinuation)
    }

    @Test
    fun `mismatched history does not resume a different episode`() {
        val item = series(episode(season = 1, number = 1, watchedSeconds = 600, watchStatus = WatchStatus.InProgress))

        val result = calculateContinuation(item, history(season = 1, video = 99, time = 600, duration = 1_200))

        assertEquals(null, result)
    }

    @Test
    fun `progress uses track duration when history omits it`() {
        val item = series(episode(season = 1, number = 1, watchStatus = WatchStatus.Finished))

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
        watchStatus: WatchStatus = WatchStatus.NotStarted,
    ) = MediaTrack(
        id = season * 100 + number,
        number = number,
        seasonNumber = season,
        title = "S${season}E$number",
        thumbnail = "thumb-S${season}E$number.jpg",
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
            status = WatchStatus.InProgress,
            timeSeconds = time,
            durationSeconds = duration,
            videoId = video,
            season = season,
        ),
    )
}
