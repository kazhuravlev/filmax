package com.filmax.data.catalog

import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.ItemDetailsPrefetcher
import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.data.catalog.mapper.itemCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * Сам результат [CatalogRepository.getItemDetails] не используется — важен побочный эффект:
 * `ItemDto.toDomain()` кладёt тайтл в [ItemDetailsCache]. [fetchIfMissing] заранее проверяет кэш,
 * чтобы не тратить сеть на то, что и так уже свежее (например, тайтл только что открывали).
 */
internal class ItemDetailsPrefetcherImpl(
    private val catalog: CatalogRepository,
    private val itemCache: ItemDetailsCache,
) : ItemDetailsPrefetcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Int>(capacity = Channel.UNLIMITED)
    private val queuedIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    init {
        ItemDiscovery.prefetcher = this
        scope.launch {
            for (id in channel) {
                runCatching { withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchIfMissing(id) } }
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

    private suspend fun fetchIfMissing(id: Int) {
        if (itemCache.get(itemCacheKey(id)) != null) return
        catalog.getItemDetails(id)
    }
}
