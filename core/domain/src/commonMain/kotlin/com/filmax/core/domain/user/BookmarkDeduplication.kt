package com.filmax.core.domain.user

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.common.safeRequest

private const val BOOKMARK_SCAN_MAX_PAGES = 10

suspend fun UserRepository.isItemInBookmark(
    itemId: Int,
    folderId: Int,
    maxPages: Int = BOOKMARK_SCAN_MAX_PAGES,
): Boolean {
    getItemBookmarkFolderIds(itemId).getOrNull()?.let { return folderId in it }
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
