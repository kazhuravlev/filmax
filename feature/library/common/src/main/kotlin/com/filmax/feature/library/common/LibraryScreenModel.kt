package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.model.ItemPage
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.firstErrorMessage
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.ContinuationResolver
import com.filmax.core.presentation.BaseScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// Общая модель двух разделов держит по одному короткому обработчику на каждое MVI-событие.
// Дробить её на несколько классов ради лимита нельзя: логика закладок и истории связана общим
// состоянием и читается только вместе — отсюда осознанный Suppress.
@Suppress("TooManyFunctions")
class LibraryScreenModel(
    private val watching: WatchingRepository,
    private val continuations: ContinuationResolver,
    private val user: UserRepository,
    private val favoritesRepo: FavoritesRepository,
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
            LibrarySection.WATCHING -> refreshWatching()
            LibrarySection.BOOKMARKS -> refreshBookmarks()
        }
    }

    private fun refreshWatching() {
        screenModelScope {
            updateState { it.copy(loading = true, error = null) }
            val history = watching.getHistory()
            val resolvedContinuations = history.getOrNull()
                ?.let { continuations.resolve(it) }
                ?.filter { it.isActualContinuation }
                .orEmpty()
            updateState {
                it.copy(
                    loading = false,
                    history = history.getOrNull().orEmpty(),
                    continuations = resolvedContinuations,
                    error = firstErrorMessage(history),
                )
            }
        }
    }

    private fun refreshBookmarks() {
        val openedFolder = state.openFolder?.folder
        screenModelScope {
            updateState { current ->
                current.copy(
                    loading = openedFolder == null,
                    error = null,
                    folderPreviews = emptyMap(),
                    loadingFolderPreviews = emptySet(),
                    openFolder = current.openFolder?.copy(
                        loading = true,
                        loadingMore = false,
                        error = null,
                    ),
                )
            }
            val foldersResult = user.getBookmarkFolders()
            val itemsResult = openedFolder?.let { user.getBookmarkItems(it.id) }
            applyRefreshedBookmarks(openedFolder, foldersResult, itemsResult)
        }
    }

    private suspend fun applyRefreshedBookmarks(
        openedFolder: BookmarkFolder?,
        foldersResult: RequestResult<List<BookmarkFolder>>,
        itemsResult: RequestResult<ItemPage>?,
    ) {
        val folders = foldersResult.getOrNull()
        val refreshedPage = itemsResult?.getOrNull()
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
                refreshedPage?.toFolderPreview()?.toOpenFolder(folder)
                    ?: current.openFolder?.copy(folder = folder, loading = false, error = error)
            }
            current.copy(
                loading = false,
                lists = folders ?: current.lists,
                folderPreviews = if (refreshedFolder != null && refreshedPage != null) {
                    mapOf(refreshedFolder.id to refreshedPage.toFolderPreview())
                } else {
                    emptyMap()
                },
                openFolder = refreshedOpen,
                error = error,
            )
        }
    }

    override fun onFetchData() {
        screenModelScope {
            coroutineScope {
                val historyDeferred = async { watching.getHistory() }
                val listsDeferred = async { user.getBookmarkFolders() }
                val history = historyDeferred.await()
                val lists = listsDeferred.await()
                val resolvedContinuations = history.getOrNull()
                    ?.let { continuations.resolve(it) }
                    ?.filter { it.isActualContinuation }
                    .orEmpty()
                updateState {
                    it.copy(
                        loading = false,
                        history = history.getOrNull().orEmpty(),
                        continuations = resolvedContinuations,
                        lists = lists.getOrNull().orEmpty(),
                        error = firstErrorMessage(history, lists),
                    )
                }
            }
        }
    }

    private fun removeFromHistory(itemId: Int) {
        screenModelScope {
            watching.clearHistory(itemId)
            updateState { s ->
                s.copy(
                    history = s.history.filter { it.itemId != itemId },
                    continuations = s.continuations.filter { it.itemId != itemId },
                )
            }
        }
    }

    private fun clearHistory() {
        val ids = state.history.map { it.itemId }
        screenModelScope { _ ->
            ids.forEach { id -> watching.clearHistory(id) }
            updateState { it.copy(history = emptyList(), continuations = emptyList()) }
        }
    }

    /**
     * Открывает подборку и грузит первую страницу её содержимого.
     *
     * Кэшированное превью (если есть) используем только как мгновенную картинку вместо пустого
     * экрана на время запроса — не как повод пропустить запрос вовсе. Подборку могли изменить
     * с другого экрана (например, добавить тайтл из деталей), поэтому при каждом реальном
     * открытии подборки список пересобираем с сервера заново.
     */
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
            // В полёте уже может быть тот же запрос — от видимой плитки. Ждём его, а не дублируем.
            if (previewLoading) return@screenModelScope
            val result = user.getBookmarkItems(folder.id)
            val itemPage = result.getOrNull()
            updateState { current ->
                val open = current.openFolder ?: return@updateState current
                // Пока грузили, подборку могли закрыть или открыть другую — чужой ответ не применяем.
                if (open.folder.id != folder.id) return@updateState current
                current.copy(
                    openFolder = open.copy(
                        // distinctBy — та же страховка, что и в loadMoreFolderItems: дубликат id
                        // в первой же странице уронил бы LazyGrid по key.
                        items = itemPage?.items.orEmpty().distinctBy { it.id },
                        page = FIRST_PAGE,
                        loading = false,
                        endReached = itemPage?.pagination?.hasNextPage != true,
                        error = firstErrorMessage(result),
                    ),
                    folderPreviews = itemPage?.let { page ->
                        current.folderPreviews + (folder.id to page.toFolderPreview())
                    } ?: current.folderPreviews,
                    loadingFolderPreviews = current.loadingFolderPreviews - folder.id,
                )
            }
        }
    }

    /** Загружает начало видимой подборки для плитки, не меняя экран на loader. */
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
            val result = user.getBookmarkItems(folder.id)
            val itemPage = result.getOrNull()
            updateState { current ->
                val open = current.openFolder
                val isOpenFolder = open?.folder?.id == folder.id
                current.copy(
                    folderPreviews = itemPage?.let { page ->
                        current.folderPreviews + (folder.id to page.toFolderPreview())
                    } ?: current.folderPreviews,
                    loadingFolderPreviews = current.loadingFolderPreviews - folder.id,
                    // Если подборку успели открыть, тот же ответ — её первая страница.
                    // Так обложки снаружи и тайтлы внутри имеют одинаковый серверный порядок.
                    openFolder = if (isOpenFolder && open.loading) {
                        itemPage?.let { page -> page.toFolderPreview().toOpenFolder(folder) }
                            ?: open.copy(loading = false, error = firstErrorMessage(result))
                    } else {
                        open
                    },
                )
            }
        }
    }

    private fun closeFolder() {
        screenModelScope { _ -> updateState { it.copy(openFolder = null) } }
    }

    /** Догружает следующую страницу открытой папки. Вызывается, когда список подходит к концу. */
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
                        // Страницы kino.watch могут пересечься: дубликат id уронил бы LazyGrid по key.
                        items = (loaded.items + itemPage?.items.orEmpty()).distinctBy { it.id },
                        page = if (itemPage != null) nextPage else loaded.page,
                        loadingMore = false,
                        // Сбой страницы — не конец списка: следующая попытка повторит тот же запрос.
                        endReached = itemPage?.pagination?.hasNextPage?.not() ?: loaded.endReached,
                        error = firstErrorMessage(result),
                    ),
                )
            }
        }
    }

    /**
     * Создаёт папку и перечитывает список. Оптимистично добавить нельзя: id и порядок задаёт
     * сервер, а угаданный локально id сломал бы последующее открытие/удаление папки.
     */
    private fun createFolder(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        screenModelScope { _ ->
            user.createBookmarkFolder(trimmed)
            reloadFolders()
        }
    }

    /** Удаляет папку. Открытую — закрывает: содержимого у неё больше нет. */
    private fun deleteFolder(folderId: Int) {
        screenModelScope { _ ->
            // Оптимистично убираем плитку и выходим из папки, если удаляли именно открытую;
            // reloadFolders ниже сверит результат с сервером.
            updateState { current ->
                current.copy(
                    lists = current.lists.filter { it.id != folderId },
                    folderPreviews = current.folderPreviews - folderId,
                    loadingFolderPreviews = current.loadingFolderPreviews - folderId,
                    openFolder = current.openFolder?.takeIf { it.folder.id != folderId },
                )
            }
            user.deleteBookmarkFolder(folderId)
            reloadFolders()
        }
    }

    /**
     * Убирает тайтл из папки. Из открытой папки удаляем сразу (отклик мгновенный), затем
     * перечитываем список папок ради актуального счётчика на плитке. Заново тянуть содержимое
     * папки не станем: оно постраничное, и повторная загрузка первой страницы сбросила бы скролл.
     */
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
            reloadFolders()
        }
    }

    /** Перечитывает список папок с сервера: id, счётчики и порядок — его зона ответственности. */
    private suspend fun reloadFolders() {
        val folders = user.getBookmarkFolders().getOrNull() ?: return
        val folderIds = folders.mapTo(mutableSetOf()) { it.id }
        updateState { current ->
            current.copy(
                lists = folders,
                folderPreviews = current.folderPreviews.filterKeys { it in folderIds },
                loadingFolderPreviews = current.loadingFolderPreviews.intersect(folderIds),
            )
        }
    }

    private fun ItemPage.toFolderPreview(): BookmarkFolderPreview =
        BookmarkFolderPreview(items = items.distinctBy { it.id }, endReached = !pagination.hasNextPage)

    private fun BookmarkFolderPreview.toOpenFolder(folder: BookmarkFolder): OpenBookmarkFolder =
        OpenBookmarkFolder(
            folder = folder,
            items = items,
            page = FIRST_PAGE,
            loading = false,
            endReached = endReached,
        )

    private companion object {
        /** Первая страница содержимого папки (нумерация kino.watch — с единицы). */
        const val FIRST_PAGE = 1
    }
}
