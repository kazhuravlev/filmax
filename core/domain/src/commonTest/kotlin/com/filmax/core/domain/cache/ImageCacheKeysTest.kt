package com.filmax.core.domain.cache

import com.filmax.core.domain.catalog.model.ItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ImageCacheKeysTest {
    @Test
    fun `poster key has stable entityType colon entityId colon size shape`() {
        assertEquals("movie:123:poster_medium", ImageCacheKeys.poster(ItemType.MOVIE, 123, PosterSize.Medium))
        assertEquals("serial:7:wall", ImageCacheKeys.poster(ItemType.SERIES, 7, PosterSize.Wall))
    }

    @Test
    fun `different sizes of the same item produce different keys`() {
        val keys = PosterSize.entries.map { size -> ImageCacheKeys.poster(ItemType.MOVIE, 42, size) }

        assertEquals(keys.size, keys.toSet().size, "each size must yield a distinct key for the same item")
    }

    @Test
    fun `different items never collide on the same key`() {
        val a = ImageCacheKeys.poster(ItemType.MOVIE, 1, PosterSize.Medium)
        val b = ImageCacheKeys.poster(ItemType.MOVIE, 2, PosterSize.Medium)
        assertNotEquals(a, b)
    }

    @Test
    fun `different item types never collide on the same key`() {
        val movie = ImageCacheKeys.poster(ItemType.MOVIE, 1, PosterSize.Medium)
        val series = ImageCacheKeys.poster(ItemType.SERIES, 1, PosterSize.Medium)
        assertNotEquals(movie, series)
    }

    @Test
    fun `actor photo key normalizes case and surrounding whitespace`() {
        assertEquals(ImageCacheKeys.actorPhoto("Tom Hardy"), ImageCacheKeys.actorPhoto(" tom hardy "))
        assertEquals("actor:tom hardy:photo", ImageCacheKeys.actorPhoto("Tom Hardy"))
    }

    @Test
    fun `collection poster key has stable shape`() {
        assertEquals("collection:99:poster_medium", ImageCacheKeys.collectionPoster(99, PosterSize.Medium))
    }

    @Test
    fun `episode thumbnail key has stable shape`() {
        assertEquals("track:5:thumb", ImageCacheKeys.episodeThumbnail(5))
    }
}
