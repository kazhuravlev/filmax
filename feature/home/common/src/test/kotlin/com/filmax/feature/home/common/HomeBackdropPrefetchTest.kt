package com.filmax.feature.home.common

import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.catalog.model.Duration
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.Posters
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.WatchProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Бэкдроп прогревается точечно ТОЛЬКО для hero-тайтла и записей «Продолжить просмотр» (см.
 * `HomeScreenModel.onFetchData`) — эти тесты фиксируют, что ключ/url совпадают с тем, что реально
 * рисует `TvHomeScreen` (иначе прогрев впустую качал бы то, что экран потом попросит под другим
 * ключом), и что при отсутствии картинки функции честно возвращают null, а не мусорную запись.
 */
class HomeBackdropPrefetchTest {

    @Test
    fun `hero backdrop prefers wide and keys it as WALL`() {
        val item = item(wide = "https://example.com/wide.jpg", big = "https://example.com/big.jpg")

        val result = item.heroBackdropPrefetch()

        assertEquals(ImageCacheKeys.poster("movie", item.id, ImageCacheKeys.WALL), result?.key)
        assertEquals("https://example.com/wide.jpg", result?.url)
    }

    @Test
    fun `hero backdrop falls back to big and keys it as SIZE_BIG when there is no wide`() {
        val item = item(wide = null, big = "https://example.com/big.jpg")

        val result = item.heroBackdropPrefetch()

        assertEquals(ImageCacheKeys.poster("movie", item.id, ImageCacheKeys.SIZE_BIG), result?.key)
        assertEquals("https://example.com/big.jpg", result?.url)
    }

    @Test
    fun `hero backdrop is null when neither wide nor big is available`() {
        val item = item(wide = null, big = "")

        assertNull(item.heroBackdropPrefetch())
    }

    @Test
    fun `continuation backdrop keys as WALL and mirrors wideOrPoster`() {
        val continuation = continuation(item(wide = "https://example.com/wide.jpg", big = ""))

        val result = continuation.backdropPrefetch()

        assertEquals(ImageCacheKeys.poster("movie", continuation.itemId, ImageCacheKeys.WALL), result?.key)
        assertEquals(continuation.wideOrPoster, result?.url)
    }

    @Test
    fun `continuation backdrop is null when the item has no poster of any size`() {
        val continuation = continuation(item(wide = null, big = "", small = ""))

        assertNull(continuation.backdropPrefetch())
    }

    private fun item(wide: String?, big: String, small: String = "") = Item(
        id = 1,
        title = "Test item",
        type = ItemType.MOVIE,
        year = 2026,
        plot = "",
        director = "",
        cast = "",
        country = "",
        genres = emptyList(),
        rating = ItemRating(filmax = 0, filmaxPercentage = "", imdb = null, kinopoisk = null),
        posters = Posters(small = small, medium = "", big = big, wide = wide),
        duration = Duration(averageMinutes = null, totalMinutes = null),
        tracklist = emptyList(),
        trailer = null,
        inWatchlist = false,
        finished = false,
    )

    private fun continuation(item: Item) = Continuation(
        item = item,
        season = 0,
        videoId = 1,
        savedPositionSeconds = 0,
        isLastEpisode = false,
        isActualContinuation = true,
        progress = WatchProgress(status = 0, timeSeconds = 0, durationSeconds = null, videoId = 1, season = null),
    )
}
