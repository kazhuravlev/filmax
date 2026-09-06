package com.filmax.feature.collections.common

import com.filmax.core.domain.catalog.model.Item

/**
 * Кэш последней просмотренной страницы подборки в памяти, keyed по `collectionId`. Экран
 * подборки — новый [CollectionDetailScreenModel] на каждый переход (в отличие от
 * `LibraryScreenModel`, который живёт дольше своего экрана и хранит `folderPreviews` прямо в
 * себе), поэтому без общего кэша повторное открытие ТОЙ ЖЕ подборки всегда показывало бы
 * полноэкранный спиннер, даже если её только что смотрели.
 *
 * Процесс-широкий синглтон, не пул на ScreenModel — тот же приём, что и в
 * [com.filmax.core.presentation.DataInvalidation]: экраны живут в разных ScreenModel-инстансах
 * и не видят состояние друг друга иначе.
 *
 * Кэш — только «мгновенная картинка» на время повторного запроса, а не повод его пропустить:
 * подборку могли изменить с другого экрана, поэтому первая страница всё равно тихо перечитывается
 * с сервера при каждом реальном открытии (см. [CollectionDetailScreenModel.onFetchData]).
 */
object CollectionItemsCache {
    private val cache = mutableMapOf<Int, List<Item>>()

    @Synchronized
    fun get(collectionId: Int): List<Item>? = cache[collectionId]

    @Synchronized
    fun put(collectionId: Int, items: List<Item>) {
        cache[collectionId] = items
    }
}
