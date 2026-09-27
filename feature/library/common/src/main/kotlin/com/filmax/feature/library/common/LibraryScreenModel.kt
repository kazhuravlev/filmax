package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.firstErrorMessage
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.tuning.PerformanceTuning
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.getDedupedBookmarkItems
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem
import com.filmax.core.domain.watching.model.WatchingListType
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Suppress("TooManyFunctions")
class LibraryScreenModel(
    private val watching: WatchingRepository,
    private val user: UserRepository,
    private val favoritesRepo: FavoritesRepository,
    private val catalog: CatalogRepository,
    private val snapshotCache: LastValueCache<LibrarySnapshot>,
) : BaseScreenModel<LibraryState, LibrarySideEffect, LibraryEvent>(LibraryState()) {
    init {
        onFetchData()
        observeFavorites()
    }

    private fun observeFavorites() {
        screenModelScope {
            favoritesRepo.favorites.collect { items ->
                updateState { it.copy(favorites = items) }
            }
        }
    }

    override fun dispatch(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Refresh -> refresh(event.section)
            is LibraryEvent.RefreshIfDirty -> refreshIfDirty(event.section)
            is LibraryEvent.RemoveFromHistory -> removeFromHistory(event.itemId)
            LibraryEvent.ClearHistory -> clearHistory()
            is LibraryEvent.OpenFolder -> openFolder(event.folder)
            is LibraryEvent.LoadFolderPreview -> loadFolderPreview(event.folder)
            LibraryEvent.CloseFolder -> closeFolder()
            LibraryEvent.LoadMoreFolderItems -> loadMoreFolderItems()
            is LibraryEvent.CreateFolder -> createFolder(event.title)
            is LibraryEvent.DeleteFolder -> deleteFolder(event.folderId)
            is LibraryEvent.RemoveItemFromFolder ->
                removeItemFromFolder(event.itemId, event.folderId)
        }
    }

    private fun refresh(section: LibrarySection) {
        when (section) {
            LibrarySection.WATCHING, LibrarySection.HISTORY -> refreshWatching()
            LibrarySection.BOOKMARKS -> refreshBookmarks()
        }
    }

    private fun refreshIfDirty(section: LibrarySection) {
        when (section) {
            LibrarySection.WATCHING, LibrarySection.HISTORY ->
                if (DataInvalidation.consumeDirty(DataDomain.WATCHING)) refreshWatchingSilently()

            LibrarySection.BOOKMARKS ->
                if (DataInvalidation.consumeDirty(DataDomain.BOOKMARKS)) refreshBookmarksSilently()
        }
    }

    private fun refreshWatchingSilently() {
        screenModelScope {
            val titles = loadWatchingTitlesPhase()
            if (titles.error != null) {
                DataInvalidation.markDirty(DataDomain.WATCHING)
                return@screenModelScope
            }
            updateState { current ->
                current.copy(
                    watching = titles.titles,
                    titleDetails = current.titleDetails + titles.titleDetails,
                )
            }
            val historyError = loadWatchingTailPhase(titles.titles)
            if (historyError != null) DataInvalidation.markDirty(DataDomain.WATCHING)
        }
    }

    private fun refreshBookmarksSilently() {
        screenModelScope {
            val folders = user.getBookmarkFolders().getOrNull()
            if (folders == null) {
                DataInvalidation.markDirty(DataDomain.BOOKMARKS)
                return@screenModelScope
            }
            val folderIds = folders.mapTo(mutableSetOf()) { it.id }
            updateState { current ->
                current.copy(
                    lists = folders,
                    folderPreviews = current.folderPreviews.filterKeys { it in folderIds },
                    loadingFolderPreviews = current.loadingFolderPreviews.intersect(folderIds),
                )
            }
        }
    }

    private fun refreshWatching() {
        screenModelScope {
            updateState { it.copy(loading = true, error = null) }
            val titles = loadWatchingTitlesPhase()
            applyWatchingTitles(titles)
            val historyError = loadWatchingTailPhase(titles.titles, forceRefreshHistory = true)
            val error = titles.error ?: historyError
            if (error != null) {
                updateState { it.copy(error = error) }
                showServerRetryNotice()
            }
        }
    }

    private suspend fun applyWatchingTitles(titles: WatchingResult) {
        updateState { current ->
            current.copy(
                loading = false,
                watching = titles.titles.preserveEmpty(current.watching, titles.error),
                titleDetails = current.titleDetails + titles.titleDetails,
                error = titles.error,
            )
        }
    }

    private suspend fun loadWatchingTitlesPhase(): WatchingResult {
        val titles = loadWatchingTitles()
        val cached = readCachedTitleDetails(titles.titles.map(WatchingItem::itemId))
        return titles.copy(titleDetails = cached)
    }

    private suspend fun loadWatchingTailPhase(
        titles: List<WatchingItem>,
        forceRefreshHistory: Boolean = false,
    ): String? = coroutineScope {
        val watchingIds = titles.mapTo(mutableSetOf(), WatchingItem::itemId)
        val detailsJob = launch { loadTitleDetails(titles.map(WatchingItem::itemId)) }
        launch {
            val rail = loadWatchLaterCollectionItems().filter { it.id !in watchingIds }
            updateState { it.copy(watchLaterRail = rail) }
        }
        val historyResult = watching.getHistory(forceRefresh = forceRefreshHistory)
        val history = historyResult.getOrNull()
        if (history != null) {
            val historyIds = history.map(WatchHistory::itemId)
            val cached = readCachedTitleDetails(historyIds)
            updateState { current ->
                current.copy(history = history, titleDetails = current.titleDetails + cached)
            }
            detailsJob.join()
            loadTitleDetails(historyIds)
        }
        firstErrorMessage(historyResult)
    }

    private suspend fun loadWatchLaterCollectionItems(): List<Item> {
        val collectionId = resolveWatchLaterCollectionId() ?: return emptyList()
        return loadAllCollectionItems(collectionId)
    }

    private suspend fun resolveWatchLaterCollectionId(): Int? {
        watchLaterLookup?.let { return it.collectionId }
        var found: Int? = null
        var networkFailed = false
        var morePages = true
        var page = FIRST_PAGE
        while (found == null && morePages && !networkFailed) {
            val collections = catalog.getCollections(page).getOrNull()
            networkFailed = collections == null
            found = collections?.firstOrNull { it.title == WATCH_LATER_COLLECTION_TITLE }?.id
            page++
            morePages = !collections.isNullOrEmpty() && page <= WATCH_LATER_MAX_COLLECTION_PAGES
        }
        if (!networkFailed) watchLaterLookup = WatchLaterLookup(found)
        return found
    }

    private class WatchLaterLookup(val collectionId: Int?)

    private var watchLaterLookup: WatchLaterLookup? = null

    private suspend fun loadAllCollectionItems(collectionId: Int): List<Item> {
        val items = mutableListOf<Item>()
        var page = FIRST_PAGE
        while (true) {
            val result = catalog.getCollectionItems(collectionId, page).getOrNull() ?: break
            items += result.items
            if (!result.pagination.hasNextPage) break
            page++
        }
        return items.distinctBy { it.id }
    }

    private suspend fun loadWatchingTitles(): WatchingResult = coroutineScope {
        val moviesDeferred = async { watching.getWatchingTitles(WatchingListType.Movies) }
        val serialsDeferred = async { watching.getWatchingTitles(WatchingListType.Serials) }
        val movies = moviesDeferred.await()
        val serials = serialsDeferred.await()
        WatchingResult(
            titles = movies.getOrNull().orEmpty() + serials.getOrNull().orEmpty(),
            error = firstErrorMessage(movies, serials),
        )
    }

    private data class WatchingResult(
        val titles: List<WatchingItem>,
        val titleDetails: Map<Int, Item> = emptyMap(),
        val error: String?,
    )

    private suspend fun readCachedTitleDetails(itemIds: List<Int>): Map<Int, Item> = coroutineScope {
        itemIds.distinct()
            .filter { it !in state.titleDetails }
            .map { itemId -> async { catalog.getCachedItemDetails(itemId) } }
            .awaitAll()
            .filterNotNull()
            .associateBy(Item::id)
    }

    private suspend fun loadTitleDetails(itemIds: List<Int>) = coroutineScope {
        val limiter = Semaphore(PerformanceTuning.ForegroundDetailsConcurrency.LIBRARY_TITLE_DETAILS)
        itemIds.distinct()
            .filter { it !in state.titleDetails }
            .map { itemId ->
                launch {
                    val item = limiter.withPermit { catalog.getItemDetails(itemId).getOrNull() } ?: return@launch
                    updateState { it.copy(titleDetails = it.titleDetails + (itemId to item)) }
                }
            }
            .joinAll()
    }

    private fun refreshBookmarks() {
        val openedFolder = state.openFolder?.folder
        screenModelScope {
            updateState { current ->
                current.copy(
                    loading = openedFolder == null,
                    error = null,
                    folderPreviews = current.folderPreviews,
                    loadingFolderPreviews = emptySet(),
                    openFolder = current.openFolder?.copy(
                        loading = true,
                        loadingMore = false,
                        error = null,
                    ),
                )
            }
            val foldersResult = user.getBookmarkFolders()
            val itemsResult = openedFolder?.let { user.getDedupedBookmarkItems(it.id) }
            val error = applyRefreshedBookmarks(openedFolder, foldersResult, itemsResult)
            if (error != null) showServerRetryNotice()
        }
    }

    private suspend fun applyRefreshedBookmarks(
        openedFolder: BookmarkFolder?,
        foldersResult: RequestResult<List<BookmarkFolder>>,
        itemsResult: RequestResult<List<Item>>?,
    ): String? {
        val folders = foldersResult.getOrNull()
        val refreshedItems = itemsResult?.getOrNull()
        val error = if (itemsResult == null) {
            firstErrorMessage(foldersResult)
        } else {
            firstErrorMessage(foldersResult, itemsResult)
        }
        updateState { current ->
            val refreshedFolder = openedFolder?.let { opened ->
                folders?.firstOrNull { it.id == opened.id } ?: opened.takeIf { folders == null }
            }
            val refreshedOpen = refreshedFolder?.let { folder ->
                refreshedItems?.toFolderPreview()?.toOpenFolder(folder)
                    ?: current.openFolder?.copy(folder = folder, loading = false, error = error)
            }
            current.copy(
                loading = false,
                lists = folders ?: current.lists,
                folderPreviews = when {
                    error != null -> current.folderPreviews
                    refreshedFolder != null && refreshedItems != null ->
                        mapOf(refreshedFolder.id to refreshedItems.toFolderPreview())
                    else -> emptyMap()
                },
                openFolder = refreshedOpen,
                error = error,
            )
        }
        return error
    }

    override fun onFetchData() {
        screenModelScope {
            val seed = if (state.watching.isEmpty() && state.lists.isEmpty()) snapshotCache.get() else null
            if (seed != null) {
                updateState { it.copy(loading = false, watching = seed.watching, lists = seed.folders) }
            }
            coroutineScope {
                val listsDeferred = async { user.getBookmarkFolders() }
                val titles = loadWatchingTitlesPhase()
                applyWatchingTitles(titles)
                val lists = listsDeferred.await()
                updateState { current -> current.copy(lists = lists.getOrNull() ?: current.lists) }
                if (lists is RequestResult.Error) DataInvalidation.markDirty(DataDomain.BOOKMARKS)
                if (titles.error == null && lists !is RequestResult.Error) snapshotCache.put(state.asSnapshot())
                val historyError = loadWatchingTailPhase(titles.titles)
                val error = titles.error ?: historyError
                if (error != null) {
                    updateState { it.copy(error = error) }
                    showServerRetryNotice()
                }
            }
        }
    }

    private fun removeFromHistory(itemId: Int) {
        screenModelScope {
            watching.clearHistory(itemId)
            updateState { current ->
                val watchingItems = current.watching.filter { it.itemId != itemId }
                val historyItems = current.history.filter { it.itemId != itemId }
                val remainingIds = (watchingItems.map { it.itemId } + historyItems.map { it.itemId }).toSet()
                current.copy(
                    watching = watchingItems,
                    history = historyItems,
                    titleDetails = current.titleDetails.filterKeys { it in remainingIds },
                )
            }
        }
    }

    private fun clearHistory() {
        val ids = (state.watching.map { it.itemId } + state.history.map { it.itemId }).distinct()
        screenModelScope { _ ->
            ids.forEach { id -> watching.clearHistory(id) }
            updateState { it.copy(watching = emptyList(), history = emptyList(), titleDetails = emptyMap()) }
        }
    }

    private fun openFolder(folder: BookmarkFolder) {
        val preview = state.folderPreviews[folder.id]
        val previewLoading = folder.id in state.loadingFolderPreviews
        screenModelScope { _ ->
            updateState { current ->
                current.copy(
                    openFolder = preview?.toOpenFolder(folder) ?: OpenBookmarkFolder(folder = folder),
                    loadingFolderPreviews = current.loadingFolderPreviews + folder.id,
                )
            }
            if (previewLoading) return@screenModelScope
            val result = user.getDedupedBookmarkItems(folder.id)
            val items = result.getOrNull()
            updateState { current ->
                val open = current.openFolder ?: return@updateState current
                if (open.folder.id != folder.id) return@updateState current
                current.copy(
                    openFolder = open.copy(
                        items = items ?: open.items,
                        page = if (items != null) FIRST_PAGE else open.page,
                        loading = false,
                        endReached = items != null || open.endReached,
                        error = firstErrorMessage(result),
                    ),
                    folderPreviews = items?.let { list ->
                        current.folderPreviews + (folder.id to list.toFolderPreview())
                    } ?: current.folderPreviews,
                    loadingFolderPreviews = current.loadingFolderPreviews - folder.id,
                )
            }
            if (result is RequestResult.Error) showServerRetryNotice()
        }
    }

    private fun loadFolderPreview(folder: BookmarkFolder) {
        if (folder.count == 0 ||
            folder.id in state.folderPreviews ||
            folder.id in state.loadingFolderPreviews
        ) {
            return
        }

        screenModelScope { _ ->
            updateState { current ->
                current.copy(loadingFolderPreviews = current.loadingFolderPreviews + folder.id)
            }
            val result = user.getDedupedBookmarkItems(folder.id)
            val items = result.getOrNull()
            updateState { current ->
                val open = current.openFolder
                val isOpenFolder = open?.folder?.id == folder.id
                current.copy(
                    folderPreviews = items?.let { list ->
                        current.folderPreviews + (folder.id to list.toFolderPreview())
                    } ?: current.folderPreviews,
                    loadingFolderPreviews = current.loadingFolderPreviews - folder.id,
                    openFolder = if (isOpenFolder && open.loading) {
                        items?.let { list -> list.toFolderPreview().toOpenFolder(folder) }
                            ?: open.copy(loading = false, error = firstErrorMessage(result))
                    } else {
                        open
                    },
                )
            }
            if (result is RequestResult.Error) showServerRetryNotice()
        }
    }

    private fun closeFolder() {
        screenModelScope { _ -> updateState { it.copy(openFolder = null) } }
    }

    private fun loadMoreFolderItems() {
        val open = state.openFolder ?: return
        if (open.loading || open.loadingMore || open.endReached) return
        val nextPage = open.page + 1
        screenModelScope { _ ->
            updateState { current -> current.copy(openFolder = current.openFolder?.copy(loadingMore = true)) }
            val result = user.getBookmarkItems(open.folder.id, nextPage)
            val itemPage = result.getOrNull()
            updateState { current ->
                val loaded = current.openFolder ?: return@updateState current
                if (loaded.folder.id != open.folder.id) return@updateState current
                current.copy(
                    openFolder = loaded.copy(
                        items = (loaded.items + itemPage?.items.orEmpty()).distinctBy { it.id },
                        page = if (itemPage != null) nextPage else loaded.page,
                        loadingMore = false,
                        endReached = itemPage?.pagination?.hasNextPage?.not() ?: loaded.endReached,
                        error = firstErrorMessage(result),
                    ),
                )
            }
            if (result is RequestResult.Error) showServerRetryNotice()
        }
    }

    private fun createFolder(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        screenModelScope { _ ->
            user.createBookmarkFolder(trimmed)
            reloadFolders()
        }
    }

    private fun deleteFolder(folderId: Int) {
        screenModelScope { _ ->
            val previewItems = state.folderPreviews[folderId]?.items.orEmpty()
            val openItems = state.openFolder?.takeIf { it.folder.id == folderId }?.items.orEmpty()
            val affectedItemIds = (previewItems + openItems).map { it.id }.toSet()
            updateState { current ->
                current.copy(
                    lists = current.lists.filter { it.id != folderId },
                    folderPreviews = current.folderPreviews - folderId,
                    loadingFolderPreviews = current.loadingFolderPreviews - folderId,
                    openFolder = current.openFolder?.takeIf { it.folder.id != folderId },
                )
            }
            user.deleteBookmarkFolder(folderId)
            affectedItemIds.forEach { catalog.invalidateItemCache(it) }
            reloadFolders()
        }
    }

    private fun removeItemFromFolder(itemId: Int, folderId: Int) {
        screenModelScope { _ ->
            updateState { current ->
                val open = current.openFolder ?: return@updateState current
                if (open.folder.id != folderId) return@updateState current
                val preview = current.folderPreviews[folderId]
                current.copy(
                    openFolder = open.copy(items = open.items.filter { it.id != itemId }),
                    folderPreviews = if (preview != null) {
                        val trimmed = preview.copy(items = preview.items.filter { it.id != itemId })
                        current.folderPreviews + (folderId to trimmed)
                    } else {
                        current.folderPreviews
                    },
                )
            }
            user.removeFromBookmark(itemId, folderId)
            catalog.invalidateItemCache(itemId)
            reloadFolders()
        }
    }

    private suspend fun reloadFolders() {
        val result = user.getBookmarkFolders()
        val folders = result.getOrNull()
        if (folders == null) {
            showServerRetryNotice()
            return
        }
        val folderIds = folders.mapTo(mutableSetOf()) { it.id }
        updateState { current ->
            current.copy(
                lists = folders,
                folderPreviews = current.folderPreviews.filterKeys { it in folderIds },
                loadingFolderPreviews = current.loadingFolderPreviews.intersect(folderIds),
            )
        }
    }

    private fun List<Item>.toFolderPreview(): BookmarkFolderPreview =
        BookmarkFolderPreview(items = this, endReached = true)

    private fun BookmarkFolderPreview.toOpenFolder(folder: BookmarkFolder): OpenBookmarkFolder =
        OpenBookmarkFolder(
            folder = folder,
            items = items,
            page = FIRST_PAGE,
            loading = false,
            endReached = endReached,
        )

    private companion object {
        const val FIRST_PAGE = 1

        const val WATCH_LATER_COLLECTION_TITLE = "Буду смотреть"

        const val WATCH_LATER_MAX_COLLECTION_PAGES = 10
    }
}

private fun <T> List<T>.preserveEmpty(previous: List<T>, error: String?): List<T> =
    if (error != null && isEmpty()) previous else this

private suspend fun fetchWatchingTitles(watching: WatchingRepository): List<WatchingItem> = coroutineScope {
    val moviesDeferred = async { watching.getWatchingTitles(WatchingListType.Movies) }
    val serialsDeferred = async { watching.getWatchingTitles(WatchingListType.Serials) }
    moviesDeferred.await().getOrNull().orEmpty() + serialsDeferred.await().getOrNull().orEmpty()
}

data class LibrarySnapshot(
    val watching: List<WatchingItem> = emptyList(),
    val folders: List<BookmarkFolder> = emptyList(),
)

private fun LibraryState.asSnapshot(): LibrarySnapshot = LibrarySnapshot(watching = watching, folders = lists)

suspend fun fetchLibrarySnapshot(
    watching: WatchingRepository,
    user: UserRepository,
): LibrarySnapshot = coroutineScope {
    val watchingDeferred = async { fetchWatchingTitles(watching) }
    val foldersDeferred = async { user.getBookmarkFolders().getOrNull().orEmpty() }
    LibrarySnapshot(watching = watchingDeferred.await(), folders = foldersDeferred.await())
}
