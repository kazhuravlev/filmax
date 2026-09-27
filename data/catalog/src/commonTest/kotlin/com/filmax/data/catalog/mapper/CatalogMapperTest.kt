package com.filmax.data.catalog.mapper

import com.filmax.core.domain.cache.DiscoveredTitle
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.cache.PosterSize
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.cache.PrefetchProgress
import com.filmax.core.domain.cache.TitleBackgroundFetcher
import com.filmax.core.network.networkJson
import com.filmax.data.catalog.remote.dto.ItemDto
import com.filmax.data.catalog.remote.dto.PostersDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogMapperTest {
    private val originalPrefetcher = ImageDiscovery.prefetcher
    private val originalTitlePrefetcher = ItemDiscovery.prefetcher
    private val fakePrefetcher = FakeImagePrefetcher()
    private val fakeTitlePrefetcher = FakeTitleBackgroundFetcher()

    @BeforeTest
    fun setUp() {
        ImageDiscovery.prefetcher = fakePrefetcher
        ItemDiscovery.prefetcher = fakeTitlePrefetcher
    }

    @AfterTest
    fun tearDown() {
        ImageDiscovery.prefetcher = originalPrefetcher
        ItemDiscovery.prefetcher = originalTitlePrefetcher
    }

    @Test
    fun `toDomain enqueues only the medium poster, never the backdrop`() {
        val dto = itemDto(
            posters = PostersDto(
                small = "",
                medium = "https://cdn.test/poster.jpg",
                big = "https://cdn.test/big.jpg",
                wide = "https://cdn.test/wide.jpg",
            ),
        )

        val item = dto.toDomain()

        assertEquals(1, fakePrefetcher.enqueued.size, "backdrop must not be prefetched from the generic mapper")
        val expected = PrefetchImage(
            key = ImageCacheKeys.poster(item.type, item.id, PosterSize.Medium),
            url = "https://cdn.test/poster.jpg",
        )
        assertEquals(expected, fakePrefetcher.enqueued.single())
        val wall = ImageCacheKeys.poster(item.type, item.id, PosterSize.Wall)
        val big = ImageCacheKeys.poster(item.type, item.id, PosterSize.Big)
        assertTrue(fakePrefetcher.enqueued.none { it.key == wall })
        assertTrue(fakePrefetcher.enqueued.none { it.key == big })
    }

    @Test
    fun `toDomain enqueues nothing when medium poster is blank`() {
        val dto = itemDto(
            posters = PostersDto(small = "", medium = "", big = "https://cdn.test/big.jpg", wide = null),
        )

        dto.toDomain()

        assertTrue(fakePrefetcher.enqueued.isEmpty())
    }

    @Test
    fun `toDomainOnly (cache-hit path) never triggers prefetch discovery`() {
        val dto = itemDto(
            posters = PostersDto(small = "", medium = "https://cdn.test/poster.jpg", big = "", wide = null),
        )

        dto.toDomainOnly()

        val reason = "repeat views of an already-cached list must not re-trigger prefetch"
        assertTrue(fakePrefetcher.enqueued.isEmpty(), reason)
        assertTrue(fakeTitlePrefetcher.enqueued.isEmpty(), reason)
    }

    @Test
    fun `list item forwards the complete available dto as cache preview`() {
        val dto = ItemDto(
            id = 42,
            title = "List title",
            type = "serial",
            year = 2026,
            plot = "Already available plot",
            director = "Director",
            cast = "Actor",
            imdbRating = 8.2,
            kinopoiskRating = 7.9,
            quality = 2160,
            posters = PostersDto(
                small = "https://cdn.test/small.jpg",
                medium = "https://cdn.test/medium.jpg",
                big = "https://cdn.test/big.jpg",
                wide = "https://cdn.test/wide.jpg",
            ),
        )

        dto.toDomain()

        val discovered = fakeTitlePrefetcher.enqueued.single()
        assertEquals(dto.id, discovered.id)
        assertEquals(dto, networkJson.decodeFromString<ItemDto>(discovered.previewJson.orEmpty()))
    }

    private fun itemDto(posters: PostersDto) = ItemDto(id = 1, title = "Test item", posters = posters)

    private class FakeImagePrefetcher : ImagePrefetcher {
        val enqueued = mutableListOf<PrefetchImage>()
        override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
        override fun enqueue(images: List<PrefetchImage>) {
            enqueued += images
        }
    }

    private class FakeTitleBackgroundFetcher : TitleBackgroundFetcher {
        val enqueued = mutableListOf<DiscoveredTitle>()
        override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
        override fun enqueue(items: List<DiscoveredTitle>) {
            enqueued += items
        }
    }
}
