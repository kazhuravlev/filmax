package com.filmax.data.catalog

import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.cache.TitleBackgroundFetcher
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.network.networkJson
import com.filmax.data.catalog.mapper.itemCacheKey
import com.filmax.data.catalog.mapper.posterPrefetchImages
import com.filmax.data.catalog.mapper.toDomainOnly
import com.filmax.data.catalog.remote.dto.ItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val FETCH_TIMEOUT_MS = 15_000L

/**
 * Докачивает `items/{id}` в фоне для тайтлов, известных пока только по голой ссылке (см.
 * [ItemDiscovery]) — «В процессе», история и т.п. отдают id/название/постер без жанров, рейтинга,
 * трейлера. Очередь строго последовательная (один [Channel], одна корутина-читатель) — как и
 * `ImagePrefetcherImpl`: фоновая докачка не должна соревноваться за сеть с активным контентом
 * (в том числе с воспроизведением видео).
 *
 * Единый конвейер на id, СТРОГО по порядку: сначала детали тайтла, потом его постер.
 *  - Кэш-промах: [CatalogRepository.getItemDetails] сходит в сеть, а её `ItemDto.toDomain()`
 *    сам кладёт результат в [ItemDetailsCache] и сам же заявляет постер в [ImageDiscovery] —
 *    второй раз звать её тут не нужно.
 *  - Кэш-хит: сеть не нужна, но `toDomain()` в этой ветке не вызывается вовсе — значит, и заявку
 *    на постер до сих пор никто не делал. Раньше на этом мы теряли постер молча: если он с тех
 *    пор вымылся из дискового кэша Coil (LRU), тайтл с деталями, но без постера, так и оставался
 *    без него. Теперь досылаем заявку явно.
 *
 * Гонку «пользователь открыл тайтл ровно в момент, когда та же очередь его же и качает» решает
 * не эта очередь, а [CatalogRepository.getItemDetails] — обе стороны зовут один и тот же метод,
 * и `CatalogRepositoryImpl` схлопывает совпадающие по id запросы в один сетевой вызов с общим
 * результатом (см. её doc). Здесь достаточно не гнать по сети то, что уже свежее в кэше.
 *
 * [BackgroundFetchSettings.enabled] проверяем первым делом на каждый id: выключенная фоновая
 * загрузка — это «совсем ничего не делаем», даже кэш-хитовую ветку без единого похода в сеть,
 * а не «делаем только то, что бесплатно».
 */
internal class TitleBackgroundFetcherImpl(
    private val catalog: CatalogRepository,
    private val itemCache: ItemDetailsCache,
    private val backgroundFetch: BackgroundFetchSettings,
) : TitleBackgroundFetcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Int>(capacity = Channel.UNLIMITED)
    private val queuedIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    init {
        ItemDiscovery.prefetcher = this
        scope.launch {
            for (id in channel) {
                runCatching { withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchThenPrefetchPoster(id) } }
                queuedIds.remove(id)
            }
        }
    }

    override fun enqueue(itemIds: List<Int>) {
        for (id in itemIds) {
            // add() возвращает false, если id уже в очереди/обрабатывается — не дублируем.
            if (queuedIds.add(id)) {
                channel.trySend(id)
            }
        }
    }

    private suspend fun fetchThenPrefetchPoster(id: Int) {
        if (!backgroundFetch.enabled.value) return
        val cached = itemCache.get(itemCacheKey(id))
        if (cached != null) {
            val item = networkJson.decodeFromString<ItemDto>(cached).toDomainOnly()
            ImageDiscovery.discovered(item.posterPrefetchImages())
            return
        }
        // toDomain() внутри уже сам заявит постер в ImageDiscovery — отдельно звать не нужно.
        catalog.getItemDetails(id).getOrNull()
    }
}
