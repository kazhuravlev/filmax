package com.filmax.data.catalog

import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.DiscoveredTitle
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.cache.PrefetchProgress
import com.filmax.core.domain.cache.TitleBackgroundFetcher
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.tuning.PerformanceTuning
import com.filmax.core.network.networkJson
import com.filmax.data.catalog.mapper.hasFullDetails
import com.filmax.data.catalog.mapper.itemCacheKey
import com.filmax.data.catalog.mapper.posterPrefetchImages
import com.filmax.data.catalog.mapper.toDomainOnly
import com.filmax.data.catalog.remote.dto.ItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class TitleBackgroundFetcherImpl(
    private val catalog: CatalogRepository,
    private val itemCache: ItemDetailsCache,
    private val backgroundFetch: BackgroundFetchSettings,
) : TitleBackgroundFetcher {
    private val progressState = MutableStateFlow(PrefetchProgress())
    override val progress: StateFlow<PrefetchProgress> = progressState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Int>(capacity = Channel.UNLIMITED)
    private val queuedIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    init {
        ItemDiscovery.prefetcher = this
        scope.launch {
            for (id in channel) {
                while (ImagePrefetchThrottle.shouldThrottle) {
                    delay(PerformanceTuning.BackgroundThrottle.THROTTLE_POLL_INTERVAL_MS)
                }
                runCatching {
                    withTimeoutOrNull(PerformanceTuning.BackgroundQueues.TITLE_DETAILS_FETCH_TIMEOUT_MS) {
                        fetchThenPrefetchPoster(id)
                    }
                }
                queuedIds.remove(id)
                progressState.update {
                    it.copy(downloaded = it.downloaded + 1, remaining = queuedIds.size)
                }
            }
        }
    }

    override fun enqueue(items: List<DiscoveredTitle>) {
        for (item in items) {
            rememberPreview(item, itemCache)

            if (queuedIds.size >= PerformanceTuning.BackgroundQueues.MAX_QUEUED_TITLE_IDS) continue
            if (queuedIds.add(item.id)) {
                channel.trySend(item.id)
                progressState.update { it.copy(remaining = queuedIds.size) }
            }
        }
    }

    private suspend fun fetchThenPrefetchPoster(id: Int) {
        if (!backgroundFetch.enabled.value) return
        val cached = itemCache.get(itemCacheKey(id))
        val cachedDto = cached?.let { networkJson.decodeFromString<ItemDto>(it) }
        cachedDto?.let { dto ->
            val item = dto.toDomainOnly()
            ImageDiscovery.discovered(item.posterPrefetchImages())
        }
        if (cachedDto?.hasFullDetails == true) return
        catalog.getItemDetails(id, isBackground = true).getOrNull()
    }
}

internal fun rememberPreview(item: DiscoveredTitle, itemCache: ItemDetailsCache) {
    item.previewJson?.let { json -> itemCache.rememberIfAbsent(itemCacheKey(item.id), json) }
}
