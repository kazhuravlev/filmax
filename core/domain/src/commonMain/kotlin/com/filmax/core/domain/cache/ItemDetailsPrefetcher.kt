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
 * Точка обнаружения вне DI-графа: любой маппер «лёгкого» ответа (`WatchingItemDto`,
 * `HistoryEntryDto` в data:watching и т.п.) сообщает сюда id тайтла без DI-инъекции — аналогично
 * [ImageDiscovery]/[com.filmax.core.domain.common.ErrorReporting]. Тайтлы с уже полными данными
 * (прошедшие через `ItemDto.toDomain()`, см. `ItemDetailsCacheAccess`) сюда слать не нужно —
 * они и так закэшированы; сама реализация к тому же пропускает id, уже свежие в кэше.
 *
 * НАМЕРЕННО не источник для `ItemDto.toDomain()` (data:catalog) — та мапит КАЖДЫЙ тайтл КАЖДОГО
 * спискового ответа (все ряды главной, каталог, поиск, похожее, подборки); слать их сюда означало
 * бы полный `items/{id}` запрос и запись в [ItemDetailsCacheAccess] (persist на диск, без потолка
 * размера) на каждую карточку, когда-либо промелькнувшую в любом списке — обычный скролл раздувал
 * файл кэша до неработоспособности, переживающей даже перезапуск приложения (было и исправлено).
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
