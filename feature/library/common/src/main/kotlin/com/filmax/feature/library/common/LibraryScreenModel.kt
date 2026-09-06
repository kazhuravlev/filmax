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
                    watchLaterRail = watchingResult.watchLaterRail,
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
                    watchLaterRail = watchingResult.watchLaterRail
                        .preserveEmpty(current.watchLaterRail, watchingResult.error),
                    error = watchingResult.error,
                )
            }
            if (watchingResult.error != null) showServerRetryNotice()
        }
    }

    /**
     * «В процессе» и история — разные серверные источники и должны загружаться независимо.
     *
     * Ошибка [loadTitleDetails] намеренно НЕ попадает в общий [WatchingResult.error]: это
     * декоративное обогащение карточек (жанр/год/рейтинг), а не сами списки. Если сбой
     * привязан к конкретному тайтлу (например, он удалён/битый на сервере) — карточка просто
     * останется без обогащения, а не покажет баннер [showServerRetryNotice] поверх уже
     * загрузившихся и показывающих реальные данные списков.
     */
    private suspend fun loadWatchingSection(): WatchingResult = coroutineScope {
        val titlesDeferred = async { loadWatchingTitles() }
        val historyDeferred = async { watching.getHistory() }
        val watchLaterDeferred = async { loadWatchLaterCollectionItems() }
        val titles = titlesDeferred.await()
        val history = historyDeferred.await()
        val watchLaterAll = watchLaterDeferred.await()
        val historyItems = history.getOrNull().orEmpty()
        val watchingIds = titles.titles.mapTo(mutableSetOf(), WatchingItem::itemId)
        val remainingIds = (titles.titles.map(WatchingItem::itemId) + historyItems.map(WatchHistory::itemId))
            .distinct()
        val titleDetails = loadTitleDetails(remainingIds)
        WatchingResult(
            titles = titles.titles,
            history = historyItems,
            titleDetails = titleDetails,
            watchLaterRail = watchLaterAll.filter { it.id !in watchingIds },
            error = titles.error ?: firstErrorMessage(history),
        )
    }

    /**
     * Свимлейн «Буду смотреть» внизу «В процессе» — тайтлы одноимённой подборки за вычетом уже
     * показанного в [LibraryState.watching]. Поиска подборки по имени в API нет, поэтому страницы
     * [CatalogRepository.getCollections] перебираются вручную (конец — пустая страница, тот же
     * приём, что в HomeScreenModel.loadMoreCollections); дальше грузим все страницы её содержимого.
     * Любой сбой на этом пути — просто пустой рейл, а не баннер: раздел декоративный.
     */
    private suspend fun loadWatchLaterCollectionItems(): List<Item> {
        val collectionId = findCollectionIdByTitle(WATCH_LATER_COLLECTION_TITLE) ?: return emptyList()
        return loadAllCollectionItems(collectionId)
    }

    private suspend fun findCollectionIdByTitle(title: String): Int? {
        var page = FIRST_PAGE
        while (true) {
            val collections = catalog.getCollections(page).getOrNull()
            if (collections.isNullOrEmpty()) return null
            collections.firstOrNull { it.title == title }?.let { return it.id }
            page++
        }
    }

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
        val watchLaterRail: List<Item> = emptyList(),
        val error: String?,
    )

    /**
     * Эндпоинты `watching` не отдают год, жанры и рейтинги. Детали подгружаются ограниченно
     * параллельно: это сохраняет универсальную карточку, но не устраивает залп из десятков
     * одновременных запросов к серверу.
     *
     * Сбой по отдельному тайтлу (например, он удалён/битый на сервере) не считаем ошибкой
     * экрана: карточка просто останется без обогащения (жанр/год/рейтинг), а не покажет
     * баннер [showServerRetryNotice] — сам сбой уже ушёл в телеметрию через `safeRequest`
     * внутри `catalog.getItemDetails`.
     */
    private suspend fun loadTitleDetails(itemIds: List<Int>): Map<Int, Item> = coroutineScope {
        val limiter = Semaphore(PerformanceTuning.ForegroundDetailsConcurrency.LIBRARY_TITLE_DETAILS)
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

    /**
     * «В процессе» и «Подборки» — независимые источники: сбой подборок не должен подвешивать
     * баннер над «В процессе» (и наоборот), поэтому в общий [error] попадает только ошибка
     * [loadWatchingSection] — сбой подборок просто помечает раздел «грязным», следующий заход
     * в «Подборки» тихо перечитает список (см. [refreshIfDirty]).
     */
    override fun onFetchData() {
        screenModelScope {
            // Первый вызов после (пере)создания модели — `watching`/`lists` ещё пусты (холодный
            // старт либо повтор через retry() по пустому экрану). Берём то, что было при прошлом
            // успешном проходе ИЛИ что фоновый прогрев AppWarmup уже успел подложить в кэш (см.
            // LastValueCache.putIfAbsent) — так стартовый сегмент «В процессе» и список папок
            // отрисуются сразу вместо скелетона. Обычный фетч ниже всё равно идёт следом и красит
            // актуальные данные поверх, когда придут — затравка лишь мгновенная картинка на её время.
            val seed = if (state.watching.isEmpty() && state.lists.isEmpty()) snapshotCache.get() else null
            if (seed != null) {
                updateState { it.copy(loading = false, watching = seed.watching, lists = seed.folders) }
            }
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
                        watchLaterRail = watchingResult.watchLaterRail
                            .preserveEmpty(current.watchLaterRail, error),
                        lists = lists.getOrNull() ?: current.lists,
                        error = error,
                    )
                }
                if (lists is RequestResult.Error) DataInvalidation.markDirty(DataDomain.BOOKMARKS)
                if (error != null) showServerRetryNotice()
                // Кэш обновляем только когда ОБА независимых источника (секция «В процессе» и
                // список папок) реально ответили — частичный/ошибочный проход не должен затирать
                // последний хороший снимок, на который рассчитывает следующий холодный старт/прогрев.
                if (error == null && lists !is RequestResult.Error) snapshotCache.put(state.asSnapshot())
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
            if (result is RequestResult.Error) showServerRetryNotice()
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
            if (result is RequestResult.Error) showServerRetryNotice()
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
            if (result is RequestResult.Error) showServerRetryNotice()
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
            // Затронутые тайтлы — до оптимистичной очистки состояния ниже, иначе их id негде
            // будет взять. Кэш детали каждого из них хранит принадлежность к этой папке
            // (см. CatalogRepository.invalidateItemCache) — папки больше нет, кэш обязан узнать.
            val previewItems = state.folderPreviews[folderId]?.items.orEmpty()
            val openItems = state.openFolder?.takeIf { it.folder.id == folderId }?.items.orEmpty()
            val affectedItemIds = (previewItems + openItems).map { it.id }.toSet()
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
            affectedItemIds.forEach { catalog.invalidateItemCache(it) }
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
            catalog.invalidateItemCache(itemId)
            reloadFolders()
        }
    }

    /** Перечитывает список папок с сервера: id, счётчики и порядок — его зона ответственности. */
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

        /** Название подборки, чей свимлейн показывается внизу «В процессе». */
        const val WATCH_LATER_COLLECTION_TITLE = "Буду смотреть"
    }
}

private fun <T> List<T>.preserveEmpty(previous: List<T>, error: String?): List<T> =
    if (error != null && isEmpty()) previous else this

/** Единственные два значения `type`, которые понимает `watching/{type}` — общие для
 * [LibraryScreenModel] и [fetchLibrarySnapshot] (прогрев), поэтому вынесены на файл. */
private const val TYPE_MOVIES = "movies"
private const val TYPE_SERIALS = "serials"

/**
 * Тайтлы «в процессе» обоих типов параллельно — общая точка входа для [LibraryScreenModel] и
 * фонового прогрева [fetchLibrarySnapshot]: одинаковый вызов `watching/{movies|serials}`, чтобы
 * не разъезжаться при будущих правках API. private: используется только внутри этого файла —
 * наружу (в `AppWarmup` другого модуля) торчит только сам [fetchLibrarySnapshot].
 */
private suspend fun fetchWatchingTitles(watching: WatchingRepository): List<WatchingItem> = coroutineScope {
    val moviesDeferred = async { watching.getWatchingTitles(TYPE_MOVIES) }
    val serialsDeferred = async { watching.getWatchingTitles(TYPE_SERIALS) }
    moviesDeferred.await().getOrNull().orEmpty() + serialsDeferred.await().getOrNull().orEmpty()
}

/**
 * Последний успешно загруженный лёгкий снимок раздела «Моё» — офлайн-устойчивость и, отдельно,
 * затравка для фонового прогрева `AppWarmup` (см. [com.filmax.core.domain.common.LastValueCache]).
 *
 * Специально НЕ полное [LibraryState]: только то, что красит стартовый сегмент «В процессе»
 * (TV открывает его первым по умолчанию) и список папок для сегмента «Подборки» — история,
 * детали тайтлов, рейл «Буду смотреть» и превью папок сюда намеренно не входят, чтобы снимок
 * оставался маленьким. Пишется только когда оба независимых источника ([LibraryScreenModel.onFetchData])
 * реально ответили; читается один раз, как затравка, при (пере)создании модели.
 */
data class LibrarySnapshot(
    val watching: List<WatchingItem> = emptyList(),
    val folders: List<BookmarkFolder> = emptyList(),
)

private fun LibraryState.asSnapshot(): LibrarySnapshot = LibrarySnapshot(watching = watching, folders = lists)

/**
 * Собирает [LibrarySnapshot] из сети напрямую — используется ТОЛЬКО фоновым прогревом `AppWarmup`
 * из модуля `:app` (см. `app/warmup/AppWarmup.kt`), который кладёт результат через `putIfAbsent`,
 * если экран ещё ни разу не открывался в этой сессии процесса — отсюда публичная видимость
 * (не `internal`: `:app` — отдельный Gradle-модуль, `internal` был бы ему не виден). Сам
 * [LibraryScreenModel] в кэш этим путём не ходит: он собирает снимок из уже загруженного
 * [LibraryState] после своего полного прохода ([LibraryScreenModel.onFetchData]) — здесь же те же
 * самые репозиторные вызовы (тайтлы «в процессе» + папки), но без остальной механики экрана
 * (истории, деталей, рейла).
 */
suspend fun fetchLibrarySnapshot(
    watching: WatchingRepository,
    user: UserRepository,
): LibrarySnapshot = coroutineScope {
    val watchingDeferred = async { fetchWatchingTitles(watching) }
    val foldersDeferred = async { user.getBookmarkFolders().getOrNull().orEmpty() }
    LibrarySnapshot(watching = watchingDeferred.await(), folders = foldersDeferred.await())
}
