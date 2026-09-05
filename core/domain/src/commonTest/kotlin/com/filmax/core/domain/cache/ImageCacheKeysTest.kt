package com.filmax.core.domain.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ImageCacheKeysTest {

    @Test
    fun `poster key has stable entityType colon entityId colon size shape`() {
        assertEquals("movie:123:poster_medium", ImageCacheKeys.poster("movie", 123, ImageCacheKeys.SIZE_MEDIUM))
        assertEquals("serial:7:wall", ImageCacheKeys.poster("serial", 7, ImageCacheKeys.WALL))
    }

    /**
     * Постер и бэкдроп одного тайтла должны жить в кэше как РАЗНЫЕ записи — иначе прогрев одного
     * вытеснит/подменит другой. Это ровно то различие, на котором держится разделение
     * `CatalogMapper.posterPrefetchImages` (только SIZE_MEDIUM) и `HomeScreenModel`'s точечный
     * прогрев бэкдропа (WALL/SIZE_BIG) для одного и того же тайтла.
     */
    @Test
    fun `different sizes of the same item produce different keys`() {
        val keys = listOf(
            ImageCacheKeys.SIZE_SMALL,
            ImageCacheKeys.SIZE_MEDIUM,
            ImageCacheKeys.SIZE_BIG,
            ImageCacheKeys.WALL,
        ).map { size -> ImageCacheKeys.poster("movie", 42, size) }

        assertEquals(keys.size, keys.toSet().size, "each size must yield a distinct key for the same item")
    }

    @Test
    fun `different items never collide on the same key`() {
        val a = ImageCacheKeys.poster("movie", 1, ImageCacheKeys.SIZE_MEDIUM)
        val b = ImageCacheKeys.poster("movie", 2, ImageCacheKeys.SIZE_MEDIUM)
        assertNotEquals(a, b)
    }

    @Test
    fun `actor photo key normalizes case and surrounding whitespace`() {
        assertEquals(ImageCacheKeys.actorPhoto("Tom Hardy"), ImageCacheKeys.actorPhoto(" tom hardy "))
        assertEquals("actor:tom hardy:photo", ImageCacheKeys.actorPhoto("Tom Hardy"))
    }

    @Test
    fun `collection poster key has stable shape`() {
        assertEquals("collection:99:poster_medium", ImageCacheKeys.collectionPoster(99, ImageCacheKeys.SIZE_MEDIUM))
    }

    @Test
    fun `episode thumbnail key has stable shape`() {
        assertEquals("track:5:thumb", ImageCacheKeys.episodeThumbnail(5))
    }
}
