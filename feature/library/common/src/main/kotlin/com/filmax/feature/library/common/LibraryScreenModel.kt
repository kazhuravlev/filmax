package com.filmax.feature.library.common

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
            is LibraryEvent.RemoveFromHistory -> removeFromHistory(event.itemId)
            LibraryEvent.ClearHistory -> clearHistory()
            is LibraryEvent.OpenFolder -> openFolder(event.folder)
            LibraryEvent.CloseFolder -> closeFolder()
            LibraryEvent.LoadMoreFolderItems -> loadMoreFolderItems()
            is LibraryEvent.CreateFolder -> createFolder(event.title)
            is LibraryEvent.DeleteFolder -> deleteFolder(event.folderId)
            is LibraryEvent.RemoveItemFromFolder ->
                removeItemFromFolder(event.itemId, event.folderId)
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

    /** Открывает папку-закладку и грузит первую страницу её содержимого. */
    private fun openFolder(folder: BookmarkFolder) {
        screenModelScope { _ ->
            updateState { it.copy(openFolder = OpenBookmarkFolder(folder = folder)) }
            val result = user.getBookmarkItems(folder.id)
            val itemPage = result.getOrNull()
            updateState { current ->
                val open = current.openFolder ?: return@updateState current
                // Пока грузили, папку могли закрыть или открыть другую — чужой ответ не применяем.
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
                current.copy(openFolder = open.copy(items = open.items.filter { it.id != itemId }))
            }
            user.removeFromBookmark(itemId, folderId)
            reloadFolders()
        }
    }

    /** Перечитывает список папок с сервера: id, счётчики и порядок — его зона ответственности. */
    private suspend fun reloadFolders() {
        val folders = user.getBookmarkFolders().getOrNull() ?: return
        updateState { it.copy(lists = folders) }
    }

    private companion object {
        /** Первая страница содержимого папки (нумерация kino.watch — с единицы). */
        const val FIRST_PAGE = 1
    }
}
