package com.filmax.core.domain.cache

import kotlin.concurrent.Volatile

/**
 * Очередь фоновой докачки статической информации о тайтле ([ItemDetailsCache]) по голому id —
 * для мест, где тайтл известен только по ссылке (id/название/постер из «лёгкого» ответа), а
 * полных полей (жанры/рейтинги/трейлер и т.п.) сервер для этого эндпоинта не отдаёт. Обрабатывает
 * очередь последовательно — как и `ImagePrefetcher`, аккуратно, без параллельного залпа запросов.
 */
fun interface ItemDetailsPrefetcher {
    fun enqueue(itemIds: List<Int>)
}

/**
 * Точка обнаружения вне DI-графа: любой маппер «лёгкого» ответа сообщает сюда id тайтла без
 * DI-инъекции — аналогично [ImageDiscovery]/[com.filmax.core.domain.common.ErrorReporting].
 * Источники: `WatchingItemDto`/`HistoryEntryDto` в data:watching (история, «в процессе») и
 * `ItemDto.toDomain()` в data:catalog — она шлёт сюда тайтлы БЕЗ треклиста (списки/поиск/похожее/
 * подборки отдают их так), потому что такой ответ она сама сознательно не кэширует (см. её
 * комментарий про `ItemDetailsCacheAccess`) — не слать их сюда значило бы, что эти тайтлы никогда
 * не получат полных деталей в фоне. Тайтлы, у которых треклист УЖЕ есть (полный `items/{id}`),
 * сюда посылать не нужно — они и так закэшированы напрямую; сама реализация к тому же пропускает
 * id, уже свежие в кэше.
 */
object ItemDiscovery {
    @Volatile
    var prefetcher: ItemDetailsPrefetcher = ItemDetailsPrefetcher {}

    fun discovered(itemId: Int) {
        prefetcher.enqueue(listOf(itemId))
    }

    fun discovered(itemIds: List<Int>) {
        if (itemIds.isNotEmpty()) prefetcher.enqueue(itemIds)
    }
}
