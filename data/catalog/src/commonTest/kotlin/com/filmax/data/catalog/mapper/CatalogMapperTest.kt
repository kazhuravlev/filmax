package com.filmax.data.catalog.mapper

import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.cache.PrefetchProgress
import com.filmax.data.catalog.remote.dto.ItemDto
import com.filmax.data.catalog.remote.dto.PostersDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Регрессия на «прогрев картинок для каждого тайтла из любого списка» — раньше [ItemDto.toDomain]
 * ставил в очередь и маленький постер, и полноразмерный бэкдроп для КАЖДОГО тайтла (списки,
 * поиск, похожее, подборки), хотя бэкдроп реально показывается только в hero/«Продолжить» на
 * главной (см. `HomeScreenModel`). Это забивало 250 МБ дискового кэша Coil и вытесняло как раз те
 * маленькие постеры, что переиспользуются между экранами. Тесты ниже фиксируют: маппер прогревает
 * ТОЛЬКО маленький постер, а бэкдроп там больше не появляется.
 */
class CatalogMapperTest {

    private val originalPrefetcher = ImageDiscovery.prefetcher
    private val fakePrefetcher = FakeImagePrefetcher()

    @BeforeTest
    fun setUp() {
        // ImageDiscovery.prefetcher — общий mutable-глобал (@Volatile var), подставляем свой
        // фейк на время теста и обязательно возвращаем прежний, иначе один тест может «загрязнить»
        // состояние для другого (в этом модуле или даже в другом, если раннер их не изолирует).
        ImageDiscovery.prefetcher = fakePrefetcher
    }

    @AfterTest
    fun tearDown() {
        ImageDiscovery.prefetcher = originalPrefetcher
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
            key = ImageCacheKeys.poster(item.type.apiValue, item.id, ImageCacheKeys.SIZE_MEDIUM),
            url = "https://cdn.test/poster.jpg",
        )
        assertEquals(expected, fakePrefetcher.enqueued.single())
        assertTrue(fakePrefetcher.enqueued.none { it.key.endsWith(":${ImageCacheKeys.WALL}") })
        assertTrue(fakePrefetcher.enqueued.none { it.key.endsWith(":${ImageCacheKeys.SIZE_BIG}") })
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
    }

    private fun itemDto(posters: PostersDto) = ItemDto(id = 1, title = "Test item", posters = posters)

    private class FakeImagePrefetcher : ImagePrefetcher {
        val enqueued = mutableListOf<PrefetchImage>()
        override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
        override fun enqueue(images: List<PrefetchImage>) {
            enqueued += images
        }
    }
}
