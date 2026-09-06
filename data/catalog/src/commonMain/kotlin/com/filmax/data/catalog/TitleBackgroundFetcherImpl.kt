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

/**
 * Докачивает `items/{id}` в фоне для тайтлов, известных пока только по голой ссылке (см.
 * [ItemDiscovery]) — раньше это были только «В процессе»/история (отдают id/название/постер без
 * жанров, рейтинга, трейлера), теперь источник — ЛЮБОЙ тайтл, когда-либо прошедший через список,
 * поиск, похожее или подборку (см. `CatalogMapper.toDomain()`). Очередь строго последовательная
 * (один [Channel], одна корутина-читатель) — как и `ImagePrefetcherImpl`: фоновая докачка не
 * должна соревноваться за сеть с активным контентом (в том числе с воспроизведением видео).
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
 *
 * Очередь ограничена [PerformanceTuning.BackgroundQueues.MAX_QUEUED_TITLE_IDS] элементами
 * (drop-newest): при переполнении новый id не попадает ни в [queuedIds], ни в [channel] — как и
 * в `ImagePrefetcherImpl`, дропаем именно новые элементы, чтобы ключ не застревал в множестве
 * без шанса когда-либо обработаться.
 *
 * Перед КАЖДЫМ id ждём, пока [ImagePrefetchThrottle.shouldThrottle] не станет false — иначе
 * запрос деталей и последующее декодирование постера соревнуются с UI-потоком за сеть/CPU прямо
 * во время активного использования приложения (см. `ImagePrefetcherImpl`, тот же приём).
 */
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
            // Списковый ответ уже содержит почти всю карточку. Кладём её сразу, до ожидания
            // throttle/очереди, но только в пустой слот — полный items/{id} важнее preview.
            rememberPreview(item, itemCache)

            // Переполнение — дропаем новый id, не трогая queuedIds (см. doc класса).
            if (queuedIds.size >= PerformanceTuning.BackgroundQueues.MAX_QUEUED_TITLE_IDS) continue
            // add() возвращает false, если id уже в очереди/обрабатывается — не дублируем.
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
        // Preview из списка полезен для мгновенного отображения, но не завершает фоновую
        // работу: videos/seasons и остальные detail-only поля всё ещё нужно догрузить.
        if (cachedDto?.hasFullDetails == true) return
        // toDomain() внутри уже сам заявит постер в ImageDiscovery — отдельно звать не нужно.
        // Иначе ActivityTrackingPlugin считает этот же запрос пользовательской активностью и
        // после КАЖДОГО JSON блокирует следующий id ещё на полный cooldown.
        catalog.getItemDetails(id, isBackground = true).getOrNull()
    }
}

internal fun rememberPreview(item: DiscoveredTitle, itemCache: ItemDetailsCache) {
    item.previewJson?.let { json -> itemCache.rememberIfAbsent(itemCacheKey(item.id), json) }
}
