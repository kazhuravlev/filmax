package com.filmax.feature.details.common

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.downloads.DownloadsRepository
import com.filmax.core.domain.downloads.model.DownloadedItem
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.favorites.model.toFavoriteItem
import com.filmax.core.domain.person.CastRepository
import com.filmax.core.domain.search.SearchRepository
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.isItemInBookmark
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.calculateContinuation
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import com.filmax.feature.details.common.navigation.DetailsRoute
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("LongParameterList", "TooManyFunctions")
class DetailsScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val downloads: DownloadsRepository,
    private val favorites: FavoritesRepository,
    private val cast: CastRepository,
    private val user: UserRepository,
    private val search: SearchRepository,
) : BaseScreenModel<DetailsState, DetailsSideEffect, DetailsEvent>(DetailsState()) {
    private val route = savedStateHandle.toRoute<DetailsRoute>()

    private var isInFavoritesFolder = false

    private var scannedMemberships: Set<Int> = emptySet()

    private var playbackPrefetched = false

    private var episodeThumbnailsWarmed = false

    private var continuationJob: Deferred<Continuation?>? = null

    init {
        onFetchData()
        observeDownloadState()
        observeFavoriteState()
    }

    override fun dispatch(event: DetailsEvent) {
        when (event) {
            DetailsEvent.ToggleDownload -> toggleDownload()
            DetailsEvent.ToggleWantToWatch -> toggleWantToWatch()
            is DetailsEvent.ToggleFolder -> toggleFolder(event.folder)
            is DetailsEvent.CreateFolderAndAdd -> createFolderAndAdd(event.title)
            DetailsEvent.PrefetchPlayback -> prefetchPlayback()
            DetailsEvent.PrefetchEpisodeThumbnails -> prefetchEpisodeThumbnails()
        }
    }

    private fun prefetchEpisodeThumbnails() {
        if (episodeThumbnailsWarmed) return
        val item = state.item ?: return
        episodeThumbnailsWarmed = true
        val images = item.tracklist
            .filter { it.thumbnail.isNotBlank() }
            .map { track -> PrefetchImage(key = ImageCacheKeys.episodeThumbnail(track.id), url = track.thumbnail) }
        ImageDiscovery.warm(images)
    }

    private fun prefetchPlayback() {
        if (playbackPrefetched) return
        val item = state.item ?: return
        playbackPrefetched = true
        screenModelScope { _ -> catalog.getItemDetails(item.id, forceRefresh = true) }
    }

    override fun onFetchData() {
        screenModelScope { _ -> reloadBookmarkFolders() }
        screenModelScope { _ ->
            catalog.getCachedItemDetails(route.itemId)?.let { preview ->
                updateState {
                    it.copy(loading = false, item = preview, isWantToWatch = preview.inWatchlist)
                }
            }
            when (val itemResult = catalog.getItemDetails(route.itemId)) {
                is RequestResult.Success -> {
                    val item = itemResult.data
                    updateState { it.copy(loading = false, item = item, isWantToWatch = item.inWatchlist) }
                    prefetchCastPhotos(item.cast, item.director)
                    loadCast(item.imdbId)
                    loadDirectorFilms(item)
                    loadSimilar()
                    loadContinuation(item)
                }

                is RequestResult.Error -> {
                    updateState { it.copy(loading = false, error = itemResult.message) }
                    showError(itemResult)
                }
            }
        }
    }

    private fun loadSimilar() {
        screenModelScope { _ ->
            updateState { it.copy(similarLoading = true) }
            val similar = catalog.getSimilarItems(route.itemId).getOrNull().orEmpty()
            updateState { it.copy(similar = similar, similarLoading = false) }
        }
    }

    private fun loadContinuation(item: Item) {
        continuationJob = screenModelScope.async {
            val fromItem = runCatching { calculateContinuation(item) }.getOrNull()
            updateState { it.copy(continuation = fromItem, continuationLoading = true) }
            val continuation = runCatching { calculateContinuation(item, findHistoryEntry()) }.getOrNull()
            updateState { it.copy(continuation = continuation, continuationLoading = false) }
            continuation
        }
    }

    suspend fun awaitContinuation(): Continuation? {
        val pending = continuationJob ?: return state.continuation
        return withTimeoutOrNull(CONTINUATION_AWAIT_TIMEOUT_MS) { pending.await() } ?: state.continuation
    }

    private fun prefetchCastPhotos(vararg rawNames: String) {
        val images = rawNames.asSequence()
            .flatMap { it.split(",").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { name -> PrefetchImage(key = ImageCacheKeys.actorPhoto(name), url = actorPhotoUrl(name)) }
            .toList()
        ImageDiscovery.discovered(images)
    }

    private fun loadCast(imdbId: String?) {
        screenModelScope { _ ->
            val members = cast.getCast(imdbId)
            if (members.isNotEmpty()) {
                updateState { it.copy(cast = members) }
            }
        }
    }

    private fun loadDirectorFilms(item: Item) {
        val director = item.director.substringBefore(",").trim().takeIf { it.isNotBlank() } ?: return
        screenModelScope { _ ->
            val films = search.searchByDirector(director).getOrNull().orEmpty().filterNot { it.id == item.id }
            if (films.isNotEmpty()) {
                updateState { it.copy(directorFilms = films) }
            }
        }
    }

    private fun observeDownloadState() {
        screenModelScope {
            downloads.isDownloaded(route.itemId).collect { downloaded ->
                updateState { it.copy(isDownloaded = downloaded) }
            }
        }
    }

    private fun observeFavoriteState() {
        screenModelScope {
            favorites.isFavorite(route.itemId).collect { fav ->
                isInFavoritesFolder = fav
                updateFolderMemberships()
            }
        }
    }

    private suspend fun findHistoryEntry() =
        watching.getHistory().getOrNull()?.firstOrNull { it.itemId == route.itemId }

    private fun toggleWantToWatch() {
        val item = state.item ?: return
        screenModelScope {
            val optimistic = !state.isWantToWatch
            updateState { it.copy(isWantToWatch = optimistic) }
            watching.toggleWatchlist(item.id).getOrNull()?.let { isWantToWatch ->
                updateState { it.copy(isWantToWatch = isWantToWatch) }
            }
            DataInvalidation.markDirty(DataDomain.WATCHING)
            catalog.invalidateItemCache(item.id)
        }
    }

    private fun toggleDownload() {
        val item = state.item ?: return
        screenModelScope {
            if (state.isDownloaded) {
                downloads.remove(item.id)
            } else {
                downloads.add(
                    DownloadedItem(
                        id = item.id,
                        title = item.title,
                        posterSmall = item.posters.medium.ifBlank { item.posters.small },
                        year = item.year,
                        durationMinutes = item.duration.averageMinutes?.toInt() ?: 0,
                    ),
                )
            }
        }
    }

    private fun toggleFolder(folder: BookmarkFolder) {
        val item = state.item ?: return
        if (folder.title == FAVORITES_FOLDER_TITLE) {
            screenModelScope { toggleFavoritesFolder(item) }
            return
        }
        val alreadyIn = folder.id in scannedMemberships
        screenModelScope {
            if (alreadyIn) {
                user.removeFromBookmark(item.id, folder.id)
                scannedMemberships = scannedMemberships - folder.id
            } else {
                if (!user.isItemInBookmark(item.id, folder.id)) {
                    user.addToBookmark(item.id, folder.id)
                }
                scannedMemberships = scannedMemberships + folder.id
            }
            updateFolderMemberships()
            reloadBookmarkFolders()
            DataInvalidation.markDirty(DataDomain.BOOKMARKS)
            catalog.invalidateItemCache(item.id)
        }
    }

    private suspend fun toggleFavoritesFolder(item: Item) {
        favorites.toggle(item.toFavoriteItem())
        updateFolderMemberships()
        reloadBookmarkFolders()
        DataInvalidation.markDirty(DataDomain.BOOKMARKS)
        catalog.invalidateItemCache(item.id)
    }

    private fun createFolderAndAdd(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val item = state.item ?: return
        screenModelScope {
            val created = user.createBookmarkFolder(trimmed).getOrNull() ?: return@screenModelScope
            user.addToBookmark(item.id, created.id)
            scannedMemberships = scannedMemberships + created.id
            updateFolderMemberships()
            reloadBookmarkFolders()
            DataInvalidation.markDirty(DataDomain.BOOKMARKS)
            catalog.invalidateItemCache(item.id)
        }
    }

    private suspend fun folderContainsItem(folderId: Int, itemId: Int): Boolean =
        user.isItemInBookmark(itemId, folderId, FOLDER_SCAN_MAX_PAGES)

    private suspend fun reloadBookmarkFolders() = coroutineScope {
        val membershipsDeferred = async { user.getItemBookmarkFolderIds(route.itemId).getOrNull() }
        val folders = user.getBookmarkFolders().getOrNull() ?: return@coroutineScope
        updateState { it.copy(bookmarkFolders = folders) }
        scanMemberships(folders, membershipsDeferred.await())
    }

    private suspend fun scanMemberships(folders: List<BookmarkFolder>, serverMemberships: Set<Int>?) {
        val toScan = folders.filter { it.title != FAVORITES_FOLDER_TITLE }
        scannedMemberships = if (serverMemberships != null) {
            toScan.mapTo(mutableSetOf()) { it.id }.intersect(serverMemberships)
        } else {
            coroutineScope {
                toScan.map { folder -> async { folder.id to folderContainsItem(folder.id, route.itemId) } }
                    .awaitAll()
            }.filter { it.second }.map { it.first }.toSet()
        }
        updateFolderMemberships()
    }

    private suspend fun updateFolderMemberships() {
        val favoritesFolderId = state.bookmarkFolders.firstOrNull { it.title == FAVORITES_FOLDER_TITLE }?.id
        val memberships = scannedMemberships + listOfNotNull(favoritesFolderId.takeIf { isInFavoritesFolder })
        updateState { it.copy(folderMemberships = memberships) }
    }

    private companion object {
        const val FOLDER_SCAN_MAX_PAGES = 10

        const val FAVORITES_FOLDER_TITLE = "Буду смотреть"

        const val CONTINUATION_AWAIT_TIMEOUT_MS = 4_000L
    }
}
