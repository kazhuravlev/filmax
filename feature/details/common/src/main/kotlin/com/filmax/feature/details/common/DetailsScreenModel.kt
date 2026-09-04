package com.filmax.feature.details.common

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.downloads.DownloadsRepository
import com.filmax.core.domain.downloads.model.DownloadedItem
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.favorites.model.toFavoriteItem
import com.filmax.core.domain.person.CastRepository
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.calculateContinuation
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.feature.details.common.navigation.DetailsRoute

// Экран деталей сводит воспроизведение, избранное, загрузки и подборки в одной модели —
// дробить её ради лимитов нельзя: состояние и обработчики читаются только вместе.
@Suppress("LongParameterList", "TooManyFunctions")
class DetailsScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val downloads: DownloadsRepository,
    private val favorites: FavoritesRepository,
    private val cast: CastRepository,
    private val user: UserRepository,
) : BaseScreenModel<DetailsState, DetailsSideEffect, DetailsEvent>(DetailsState()) {

    private val route = savedStateHandle.toRoute<DetailsRoute>()

    init {
        onFetchData()
        observeDownloadState()
        observeFavoriteState()
        loadBookmarkFolders()
    }

    override fun dispatch(event: DetailsEvent) {
        when (event) {
            DetailsEvent.ToggleFav -> toggleFav()
            DetailsEvent.ToggleDownload -> toggleDownload()
            DetailsEvent.ToggleWatching -> toggleWatching()
            is DetailsEvent.AddToFolder -> addToFolder(event.folderId)
            is DetailsEvent.CreateFolderAndAdd -> createFolderAndAdd(event.title)
        }
    }

    override fun onFetchData() {
        screenModelScope { _ ->
            val itemResult = catalog.getItemDetails(route.itemId)
            val similar = catalog.getSimilarItems(route.itemId).getOrNull().orEmpty()
            val history = watching.getHistory().getOrNull()?.firstOrNull { it.itemId == route.itemId }
            when (itemResult) {
                is RequestResult.Success -> {
                    updateState {
                        it.copy(
                            loading = false,
                            item = itemResult.data,
                            continuation = calculateContinuation(itemResult.data, history),
                            similar = similar,
                        )
                    }
                    // Down-sync: если на сервере фильм уже в watchlist — заносим в локальный кэш.
                    if (itemResult.data.inWatchlist) {
                        favorites.add(itemResult.data.toFavoriteItem())
                    }
                    loadCast(itemResult.data.imdbId)
                }

                is RequestResult.Error -> {
                    updateState { it.copy(loading = false, error = itemResult.message) }
                    showError(itemResult)
                }
            }
        }
    }

    /**
     * Фото актёров грузим отдельным запросом ПОСЛЕ показа тайтла: экран уже виден со строкой имён,
     * а фото «доезжают» без блокировки. Пустой ответ (нет ключа/совпадения) молча оставляет строку.
     */
    private fun loadCast(imdbId: String?) {
        screenModelScope { _ ->
            val members = cast.getCast(imdbId)
            if (members.isNotEmpty()) {
                updateState { it.copy(cast = members) }
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
            favorites.isFavorite(route.itemId).collect { favorite ->
                updateState { it.copy(isFav = favorite) }
            }
        }
    }

    private fun toggleFav() {
        val item = state.item ?: return
        screenModelScope {
            // Локальный кэш — источник состояния сердечка; сервер синхронизируем best-effort.
            favorites.toggle(item.toFavoriteItem())
            watching.toggleWatchlist(route.itemId)
        }
    }

    /** «Я смотрю»: отдельная от watchlist пометка тайтла (см. [DetailsEvent.ToggleWatching]). */
    private fun toggleWatching() {
        val item = state.item ?: return
        screenModelScope { watching.toggleWatched(item.id) }
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

    private fun loadBookmarkFolders() {
        screenModelScope { reloadBookmarkFolders() }
    }

    /**
     * Добавляет тайтл в выбранную подборку — независимо от «Буду смотреть».
     *
     * Сервер не проверяет уникальность в `bookmarks/{id}`: повторный клик по той же подборке
     * создавал бы там второй экземпляр тайтла. Проверяем несколько первых страниц перед
     * добавлением — тот же компромисс глубины сканирования, что и в
     * `FavoritesRepositoryImpl.refresh` ([FOLDER_SCAN_MAX_PAGES]), не полный обход ради клика.
     */
    private fun addToFolder(folderId: Int) {
        val item = state.item ?: return
        screenModelScope {
            if (!folderContainsItem(folderId, item.id)) {
                user.addToBookmark(item.id, folderId)
            }
            reloadBookmarkFolders()
        }
    }

    /** Создаёт подборку и сразу заносит в неё текущий тайтл — одно действие в диалоге выбора. */
    private fun createFolderAndAdd(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val item = state.item ?: return
        screenModelScope {
            // Свежесозданная подборка пуста — проверять уникальность здесь нечего.
            val created = user.createBookmarkFolder(trimmed).getOrNull() ?: return@screenModelScope
            user.addToBookmark(item.id, created.id)
            reloadBookmarkFolders()
        }
    }

    private suspend fun folderContainsItem(folderId: Int, itemId: Int): Boolean {
        var page = 1
        var found = false
        var hasMore = true
        while (!found && hasMore && page <= FOLDER_SCAN_MAX_PAGES) {
            val result = user.getBookmarkItems(folderId, page).getOrNull()
            found = result?.items?.any { it.id == itemId } == true
            hasMore = result?.pagination?.hasNextPage == true
            page++
        }
        return found
    }

    private suspend fun reloadBookmarkFolders() {
        val folders = user.getBookmarkFolders().getOrNull() ?: return
        updateState { it.copy(bookmarkFolders = folders) }
    }

    private companion object {
        /** Глубина сканирования подборки на дубликат перед добавлением — см. [folderContainsItem]. */
        const val FOLDER_SCAN_MAX_PAGES = 10
    }
}
