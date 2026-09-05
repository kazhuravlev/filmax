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
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.calculateContinuation
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import com.filmax.feature.details.common.navigation.DetailsRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    /** Кэш реактивного флага «Буду смотреть» — не требует скана страниц, в отличие от прочих подборок. */
    private var isFav = false

    /** Id обычных подборок (без «Буду смотреть»), в которых найден тайтл — см. [scanMemberships]. */
    private var scannedMemberships: Set<Int> = emptySet()

    init {
        onFetchData()
        observeDownloadState()
        observeFavoriteState()
    }

    override fun dispatch(event: DetailsEvent) {
        when (event) {
            DetailsEvent.ToggleDownload -> toggleDownload()
            DetailsEvent.ToggleWatching -> toggleWatching()
            is DetailsEvent.ToggleFolder -> toggleFolder(event.folder)
            is DetailsEvent.CreateFolderAndAdd -> createFolderAndAdd(event.title)
        }
    }

    override fun onFetchData() {
        screenModelScope { _ ->
            val itemResult = catalog.getItemDetails(route.itemId)
            val similar = catalog.getSimilarItems(route.itemId).getOrNull().orEmpty()
            val history = findHistoryEntry()
            when (itemResult) {
                is RequestResult.Success -> {
                    updateState {
                        it.copy(
                            loading = false,
                            item = itemResult.data,
                            continuation = calculateContinuation(itemResult.data, history),
                            isWatching = itemResult.data.tracklist.any { track -> track.watchStatus != NOT_WATCHED },
                            similar = similar,
                        )
                    }
                    // Down-sync: если на сервере фильм уже в watchlist — заносим в локальный кэш.
                    if (itemResult.data.inWatchlist) {
                        favorites.add(itemResult.data.toFavoriteItem())
                    }
                    loadCast(itemResult.data.imdbId)
                    // Подборки грузим только теперь: скан принадлежности (см. scanMemberships)
                    // читает state.item, который до этого момента ещё null.
                    reloadBookmarkFolders()
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
                isFav = favorite
                updateFolderMemberships()
            }
        }
    }

    /** История ведётся по сериям — под текущий тайтл достаём только его запись. */
    private suspend fun findHistoryEntry() =
        watching.getHistory().getOrNull()?.firstOrNull { it.itemId == route.itemId }

    /**
     * «Я смотрю»: отдельная от watchlist пометка тайтла (см. [DetailsEvent.ToggleWatching]).
     * `watching/toggle` возвращает итоговое `watched` — используем его напрямую для кнопки,
     * а continuation всё равно пересчитываем: иначе «Продолжить»/прогресс на экране молча
     * оставались бы прежними, будто клик ни на что не повлиял.
     */
    private fun toggleWatching() {
        val item = state.item ?: return
        screenModelScope {
            val watched = watching.toggleWatched(item.id).getOrNull() ?: return@screenModelScope
            val history = findHistoryEntry()
            updateState { it.copy(isWatching = watched, continuation = calculateContinuation(item, history)) }
            // «Я смотрю»/история — это то же самое, что показывает «Я смотрю» в библиотеке.
            DataInvalidation.markDirty(DataDomain.WATCHING)
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

    /**
     * Добавляет или убирает тайтл из подборки — один и тот же диалог для «Буду смотреть» и для
     * любой другой подборки. «Буду смотреть» распознаём по названию (как и [FavoritesRepository]
     * внутри себя) и ведём через него же, чтобы не разъезжались локальный кэш и `in_watchlist` на
     * сервере. Остальные подборки — напрямую через [UserRepository].
     */
    private fun toggleFolder(folder: BookmarkFolder) {
        val item = state.item ?: return
        if (folder.title == FAVORITES_FOLDER_TITLE) {
            screenModelScope {
                favorites.toggle(item.toFavoriteItem())
                watching.toggleWatchlist(route.itemId)
                // «Буду смотреть» — это подписка, которая и формирует список «Я смотрю» в
                // библиотеке, и попутно обычная подборка в счётчиках «Подборок».
                DataInvalidation.markDirty(DataDomain.WATCHING, DataDomain.BOOKMARKS)
            }
            return
        }
        val alreadyIn = folder.id in scannedMemberships
        screenModelScope {
            if (alreadyIn) {
                user.removeFromBookmark(item.id, folder.id)
                scannedMemberships = scannedMemberships - folder.id
            } else {
                user.addToBookmark(item.id, folder.id)
                scannedMemberships = scannedMemberships + folder.id
            }
            updateFolderMemberships()
            reloadBookmarkFolders()
            DataInvalidation.markDirty(DataDomain.BOOKMARKS)
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
            scannedMemberships = scannedMemberships + created.id
            updateFolderMemberships()
            reloadBookmarkFolders()
            DataInvalidation.markDirty(DataDomain.BOOKMARKS)
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
        scanMemberships(folders)
    }

    /**
     * Принадлежность «Буду смотреть» уже известна реактивно ([isFav]) — сканируем только
     * остальные подборки, по одной странице на каждую параллельно (см. [folderContainsItem]).
     */
    private suspend fun scanMemberships(folders: List<BookmarkFolder>) {
        val item = state.item ?: return
        val toScan = folders.filter { it.title != FAVORITES_FOLDER_TITLE }
        scannedMemberships = coroutineScope {
            toScan.map { folder -> async { folder.id to folderContainsItem(folder.id, item.id) } }.awaitAll()
        }.filter { it.second }.map { it.first }.toSet()
        updateFolderMemberships()
    }

    private suspend fun updateFolderMemberships() {
        val favoritesFolderId = state.bookmarkFolders.firstOrNull { it.title == FAVORITES_FOLDER_TITLE }?.id
        val memberships = scannedMemberships + listOfNotNull(favoritesFolderId.takeIf { isFav })
        updateState { it.copy(folderMemberships = memberships) }
    }

    private companion object {
        /** Глубина сканирования подборки на дубликат перед добавлением — см. [folderContainsItem]. */
        const val FOLDER_SCAN_MAX_PAGES = 10

        /** То же название, что и [FavoritesRepository] использует для поиска/создания своей подборки. */
        const val FAVORITES_FOLDER_TITLE = "Буду смотреть"

        /** kino.watch `watching.status`: -1 — нет отметки о просмотре. */
        const val NOT_WATCHED = -1
    }
}
