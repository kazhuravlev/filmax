package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.firstErrorMessage
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.getDedupedBookmarkItems
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Общая модель двух разделов держит по одному короткому обработчику на каждое MVI-событие.
// Дробить её на несколько классов ради лимита нельзя: логика закладок и истории связана общим
// состоянием и читается только вместе — отсюда осознанный Suppress.
@Suppress("TooManyFunctions")
class LibraryScreenModel(
    private val watching: WatchingRepository,
    private val user: UserRepository,
    private val favoritesRepo: FavoritesRepository,
    private val catalog: CatalogRepository,
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
            is LibraryEvent.Refresh -> {
                resetServerRetryCycle()
                refresh(event.section)
            }
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
            LibrarySection.WATCHING -> refreshWatching()
            LibrarySection.BOOKMARKS -> refreshBookmarks()
        }
    }

    /**
     * Возврат на экран: ScreenModel переживает уход в детали (стек навигации его не убивает),
     * поэтому по умолчанию ничего не делаем — то, что уже показано, остаётся как есть, без
     * спиннера и похода в сеть. Если же что-то в этом разделе поменяли на другом экране
     * (добавили в подборку, отметили «Я смотрю», сохранили прогресс) — тихо обновляем данные
     * в фоне и перерисовываем экран, когда они придут.
     */
    private fun refreshIfDirty(section: LibrarySection) {
        when (section) {
            LibrarySection.WATCHING ->
                if (DataInvalidation.consumeDirty(DataDomain.WATCHING)) refreshWatchingSilently()

            LibrarySection.BOOKMARKS ->
                if (DataInvalidation.consumeDirty(DataDomain.BOOKMARKS)) refreshBookmarksSilently()
        }
    }

    /** Как [refreshWatching], но без `loading` и без баннера при сбое — попытка невидима снаружи. */
    private fun refreshWatchingSilently() {
        screenModelScope {
            val watchingResult = loadWatchingSection()
            if (watchingResult.error != null) {
                // Не портим уже показанное сбойным пустым ответом и не теряем пометку:
                // следующий возврат на экран попробует обновиться ещё раз.
                DataInvalidation.markDirty(DataDomain.WATCHING)
                return@screenModelScope
            }
            updateState { current ->
                current.copy(
                    watching = watchingResult.titles,
                    history = watchingResult.history,
                    titleDetails = current.titleDetails + watchingResult.titleDetails,
                )
            }
        }
    }

    /** Как [refreshBookmarks], но без `loading` и без баннера при сбое — попытка невидима снаружи. */
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
            val watchingResult = loadWatchingSection()
            updateState { current ->
                current.copy(
                    loading = false,
                    watching = watchingResult.titles.preserveEmpty(current.watching, watchingResult.error),
                    history = watchingResult.history.preserveEmpty(current.history, watchingResult.error),
                    titleDetails = current.titleDetails + watchingResult.titleDetails,
                    error = watchingResult.error,
                )
            }
            if (watchingResult.error != null) scheduleServerRetry(::refreshWatching)
        }
    }

    /**
     * «В процессе» и история — разные серверные источники и должны загружаться независимо.
     *
     * Ошибка [loadTitleDetails] намеренно НЕ попадает в общий [WatchingResult.error]: это
     * декоративное обогащение карточек (жанр/год/рейтинг), а не сами списки. Если сбой
     * привязан к конкретному тайтлу (например, он удалён/битый на сервере), он не «лечится»
     * повтором и раньше вечно держал баннер [scheduleServerRetry] висящим — хотя списки уже
     * загрузились и показывают реальные данные.
     */
    private suspend fun loadWatchingSection(): WatchingResult = coroutineScope {
        val titlesDeferred = async { loadWatchingTitles() }
        val historyDeferred = async { watching.getHistory() }
        val titles = titlesDeferred.await()
        val history = historyDeferred.await()
        val historyItems = history.getOrNull().orEmpty()
        val remainingIds = (titles.titles.map(WatchingItem::itemId) + historyItems.map(WatchHistory::itemId))
            .distinct()
        val titleDetails = loadTitleDetails(remainingIds)
        WatchingResult(
            titles = titles.titles,
            history = historyItems,
            titleDetails = titleDetails,
            error = titles.error ?: firstErrorMessage(history),
        )
    }

    /** Тайтлы «в процессе» — родной прогресс `watching/{movies|serials}?subscribed=1`, оба типа параллельно. */
    private suspend fun loadWatchingTitles(): WatchingResult = coroutineScope {
        val moviesDeferred = async { watching.getWatchingTitles(TYPE_MOVIES) }
        val serialsDeferred = async { watching.getWatchingTitles(TYPE_SERIALS) }
        val movies = moviesDeferred.await()
        val serials = serialsDeferred.await()
        WatchingResult(
            titles = movies.getOrNull().orEmpty() + serials.getOrNull().orEmpty(),
            error = firstErrorMessage(movies, serials),
        )
    }

    private data class WatchingResult(
        val titles: List<WatchingItem>,
        val history: List<WatchHistory> = emptyList(),
        val titleDetails: Map<Int, Item> = emptyMap(),
        val error: String?,
    )

    /**
     * Эндпоинты `watching` не отдают год, жанры и рейтинги. Детали подгружаются ограниченно
     * параллельно: это сохраняет универсальную карточку, но не устраивает залп из десятков
     * одновременных запросов к серверу.
     *
     * Сбой по отдельному тайтлу (например, он удалён/битый на сервере) не считаем ошибкой
     * экрана: карточка просто останется без обогащения (жанр/год/рейтинг), а не подвесит
     * баннер [scheduleServerRetry] — сам сбой уже ушёл в телеметрию через `safeRequest`
     * внутри `catalog.getItemDetails`.
     */
    private suspend fun loadTitleDetails(itemIds: List<Int>): Map<Int, Item> = coroutineScope {
        val limiter = Semaphore(TITLE_DETAILS_CONCURRENCY)
        itemIds.distinct().map { itemId ->
            async { limiter.withPermit { catalog.getItemDetails(itemId).getOrNull() } }
        }.awaitAll().filterNotNull().associateBy(Item::id)
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
            if (error != null) scheduleServerRetry(::refreshBookmarks)
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

    /**
     * Неявная первая загрузка (не по действию пользователя) — в отличие от [refreshWatching]/
     * [refreshBookmarks], сбой ретраится тихо ([scheduleSilentServerRetry]): баннер «сервер не
     * вернул данные» на фоне уже показанных с прошлого раза данных только пугает, хотя через
     * 3 секунды тихий повтор чаще всего сам всё чинит (см. `scheduleSilentServerRetry`).
     *
     * «В процессе» и «Подборки» — независимые источники: сбой подборок не должен подвешивать
     * баннер над «В процессе» (и наоборот), поэтому в общий [error] попадает только ошибка
     * [loadWatchingSection] — сбой подборок просто помечает раздел «грязным», следующий заход
     * в «Подборки» тихо перечитает список (см. [refreshIfDirty]).
     */
    override fun onFetchData() {
        screenModelScope {
            coroutineScope {
                val watchingDeferred = async { loadWatchingSection() }
                val listsDeferred = async { user.getBookmarkFolders() }
                val watchingResult = watchingDeferred.await()
                val lists = listsDeferred.await()
                val error = watchingResult.error
                updateState { current ->
                    current.copy(
                        loading = false,
                        watching = watchingResult.titles.preserveEmpty(current.watching, error),
                        history = watchingResult.history.preserveEmpty(current.history, error),
                        titleDetails = current.titleDetails + watchingResult.titleDetails,
                        lists = lists.getOrNull() ?: current.lists,
                        error = error,
                    )
                }
                if (lists is RequestResult.Error) DataInvalidation.markDirty(DataDomain.BOOKMARKS)
                if (error != null) scheduleSilentServerRetry(::onFetchData)
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

    /**
     * Открывает подборку и грузит всё её содержимое разом.
     *
     * [UserRepository.getDedupedBookmarkItems] читает все страницы папки и чистит дубликаты
     * СЕРВЕРНОЙ связи `(folderId, id)`, прежде чем что-либо показать — папки-закладки личные и
     * небольшие, поэтому загрузка разом (а не по страницам, как раньше) — приемлемая цена за то,
     * что счётчик и список больше не расходятся из-за копившихся дублей; поэтому же
     * [loadMoreFolderItems] дальше не нужен ([OpenBookmarkFolder.endReached] сразу `true`).
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
            val result = user.getDedupedBookmarkItems(folder.id)
            val items = result.getOrNull()
            updateState { current ->
                val open = current.openFolder ?: return@updateState current
                // Пока грузили, подборку могли закрыть или открыть другую — чужой ответ не применяем.
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
            if (result is RequestResult.Error) scheduleServerRetry { openFolder(folder) }
        }
    }

    /** Загружает содержимое видимой подборки для плитки, не меняя экран на loader. */
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
                    // Если подборку успели открыть, тот же ответ — её содержимое целиком.
                    // Так обложки снаружи и тайтлы внутри имеют одинаковый серверный порядок.
                    openFolder = if (isOpenFolder && open.loading) {
                        items?.let { list -> list.toFolderPreview().toOpenFolder(folder) }
                            ?: open.copy(loading = false, error = firstErrorMessage(result))
                    } else {
                        open
                    },
                )
            }
            if (result is RequestResult.Error) scheduleServerRetry { loadFolderPreview(folder) }
        }
    }

    private fun closeFolder() {
        screenModelScope { _ -> updateState { it.copy(openFolder = null) } }
    }

    /**
     * Раньше догружала следующую страницу открытой папки. [openFolder] теперь читает подборку
     * целиком (см. его doc), поэтому [OpenBookmarkFolder.endReached] уже `true` сразу после
     * открытия и этот обработчик — no-op; оставлен, чтобы не трогать событие/UI, которое всё ещё
     * вызывает его по прокрутке к концу списка.
     */
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
            if (result is RequestResult.Error) scheduleServerRetry(::loadMoreFolderItems)
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
        val result = user.getBookmarkFolders()
        val folders = result.getOrNull()
        if (folders == null) {
            scheduleServerRetry { screenModelScope { reloadFolders() } }
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

    /** [getDedupedBookmarkItems] уже вернул полный, дедуплицированный список — страниц больше нет. */
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
        /** Первая страница содержимого папки (нумерация kino.watch — с единицы). */
        const val FIRST_PAGE = 1

        /** Единственные два значения `type`, которые понимает `watching/{type}`. */
        const val TYPE_MOVIES = "movies"
        const val TYPE_SERIALS = "serials"
        const val TITLE_DETAILS_CONCURRENCY = 4
    }
}

private fun <T> List<T>.preserveEmpty(previous: List<T>, error: String?): List<T> =
    if (error != null && isEmpty()) previous else this
