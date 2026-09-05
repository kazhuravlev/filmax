package com.filmax.core.domain.user

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.common.safeRequest

/** Потолок страниц, которые сканируем в папке-закладке — тот же, что и у существующих читателей. */
private const val BOOKMARK_SCAN_MAX_PAGES = 10

/**
 * Проверяет по СЕРВЕРУ (не по локальному кэшу), есть ли [itemId] в папке [folderId] — использовать
 * перед [UserRepository.addToBookmark], чтобы не плодить дубликаты, когда локальное состояние
 * (StateFlow, `scannedMemberships`) отстало от реальности: переустановка, другое устройство, ручной
 * вызов API в обход приложения.
 */
suspend fun UserRepository.isItemInBookmark(
    itemId: Int,
    folderId: Int,
    maxPages: Int = BOOKMARK_SCAN_MAX_PAGES,
): Boolean {
    var page = 1
    var found = false
    var hasMore = true
    while (!found && hasMore && page <= maxPages) {
        val result = getBookmarkItems(folderId, page).getOrNull()
        found = result?.items?.any { it.id == itemId } == true
        hasMore = result != null && result.items.isNotEmpty() && result.pagination.hasNextPage
        page++
    }
    return found
}

/**
 * Читает ВСЕ страницы папки [folderId] и убирает дубликаты — не только из показанного списка, но
 * и с сервера: kino.watch способен накопить несколько связей `(folderId, itemId)` в одной папке
 * (двойной клик, гонка, ручной вызов API), и без этой чистки счётчик папки растёт бесконечно, даже
 * если экран показывает опрятный список через `distinctBy`.
 *
 * Для каждого id с более чем одним вхождением: сначала [UserRepository.removeFromBookmark] (у
 * kino.watch это удаление связи по `(folderId, itemId)` целиком — снимет разом все копии, а не
 * одну лишнюю), затем ОДИН [UserRepository.addToBookmark] — так в папке гарантированно остаётся
 * ровно одна связь. Порядок важен: сначала чистим сервер, только потом возвращаем список — вызывающий
 * код должен показать пользователю уже дедуплицированные данные, а не сырые с дублями.
 */
suspend fun UserRepository.getDedupedBookmarkItems(
    folderId: Int,
    maxPages: Int = BOOKMARK_SCAN_MAX_PAGES,
): RequestResult<List<Item>> = safeRequest {
    val collected = mutableListOf<Item>()
    var page = 1
    var hasMore = true
    while (hasMore && page <= maxPages) {
        val pageResult = getBookmarkItems(folderId, page).getOrNull()
        val items = pageResult?.items.orEmpty()
        collected += items
        hasMore = pageResult != null && items.isNotEmpty() && page < pageResult.pagination.total
        page++
    }
    val seen = mutableSetOf<Int>()
    val unique = mutableListOf<Item>()
    val duplicateIds = mutableSetOf<Int>()
    for (item in collected) {
        if (seen.add(item.id)) unique += item else duplicateIds += item.id
    }
    duplicateIds.forEach { id ->
        removeFromBookmark(id, folderId)
        addToBookmark(id, folderId)
    }
    unique
}
