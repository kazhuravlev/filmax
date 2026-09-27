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
            LibrarySection.WATCHING, LibrarySection.HISTORY -> refreshWatching()
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
            LibrarySection.WATCHING, LibrarySection.HISTORY ->
                if (DataInvalidation.consumeDirty(DataDomain.WATCHING)) refreshWatchingSilently()

            LibrarySection.BOOKMARKS ->
                if (DataInvalidation.consumeDirty(DataDomain.BOOKMARKS)) refreshBookmarksSilently()
        }
    }

    /** Как [refreshWatching], но без `loading` и без баннера при сбое — попытка невидима снаружи. */
    private fun refreshWatchingSilently() {
        screenModelScope {
            val titles = loadWatchingTitlesPhase()
            if (titles.error != null) {
                // Не портим уже показанное сбойным пустым ответом и не теряем пометку:
                // следующий возврат на экран попробует обновиться ещё раз.
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

    /** Явное обновление по действию пользователя — историю читаем мимо кэша репозитория. */
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

    /**
     * Первый такт «В процессе»: сам список тайтлов — и только он — снимает спиннер. Раньше экран
     * ждал ещё историю (десяток последовательных страниц), обход всех страниц публичных подборок
     * ради рейла «Буду смотреть» и догрузку деталей КАЖДОГО тайтла из сети — и «Я смотрю», самый
     * посещаемый пункт меню, открывался секундами, хотя список приходит за два быстрых запроса.
     * Всё остальное — [loadWatchingTailPhase], поверх уже показанного экрана.
     */
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

    /**
     * Тайтлы «в процессе» плюс то, что о них УЖЕ лежит в локальном кэше деталей (preview или полный
     * ответ — для карточки хватает и preview: год/жанры/рейтинг). Локальное чтение — миллисекунды,
     * поэтому карточки с первого же кадра выходят с метаданными, а не «голыми» до ответа сети.
     */
    private suspend fun loadWatchingTitlesPhase(): WatchingResult {
        val titles = loadWatchingTitles()
        val cached = readCachedTitleDetails(titles.titles.map(WatchingItem::itemId))
        return titles.copy(titleDetails = cached)
    }

    /**
     * Второй такт «В процессе» — три независимые фоновые ветки поверх уже показанного списка:
     * история (сегмент «История»), рейл «Буду смотреть» и сетевая догрузка деталей тех карточек,
     * которых не оказалось в кэше. Каждая ветка красит своё в state сама, как только ответит.
     * Возвращает ошибку истории (единственная из трёх, о которой стоит сообщить): детали и рейл —
     * декоративные, их сбой карточку не ломает (см. doc [loadTitleDetails]).
     */
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
            // Детали истории — после деталей «В процессе»: тот сегмент открыт по умолчанию.
            detailsJob.join()
            loadTitleDetails(historyIds)
        }
        firstErrorMessage(historyResult)
    }

    /**
     * Свимлейн «Буду смотреть» внизу «В процессе» — тайтлы одноимённой подборки за вычетом уже
     * показанного в [LibraryState.watching]. Поиска подборки по имени в API нет, поэтому страницы
     * [CatalogRepository.getCollections] перебираются вручную (конец — пустая страница, тот же
     * приём, что в HomeScreenModel.loadMoreCollections); дальше грузим все страницы её содержимого.
     * Любой сбой на этом пути — просто пустой рейл, а не баннер: раздел декоративный.
     */
    private suspend fun loadWatchLaterCollectionItems(): List<Item> {
        val collectionId = resolveWatchLaterCollectionId() ?: return emptyList()
        return loadAllCollectionItems(collectionId)
    }

    /**
     * Id подборки «Буду смотреть» ищется по имени обходом страниц публичных подборок — дорого,
     * поэтому результат (в т.ч. «такой подборки нет») запоминается на жизнь модели: каждое
     * обновление раздела раньше повторяло весь обход заново. Сетевой сбой посреди обхода НЕ
     * запоминаем — следующая попытка честно поищет ещё раз. Потолок страниц — предохранитель от
     * бесконечного листания каталога подборок ради одного рейла.
     */
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

    /** Результат поиска подборки «Буду смотреть» — см. [resolveWatchLaterCollectionId]. */
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

    /** Тайтлы «в процессе» — родной прогресс `watching/{movies|serials}?subscribed=1`, оба типа параллельно. */
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

    /** Что о тайтлах уже знает локальный кэш деталей — без сети, параллельно, только промахи пропускаем. */
    private suspend fun readCachedTitleDetails(itemIds: List<Int>): Map<Int, Item> = coroutineScope {
        itemIds.distinct()
            .filter { it !in state.titleDetails }
            .map { itemId -> async { catalog.getCachedItemDetails(itemId) } }
            .awaitAll()
            .filterNotNull()
            .associateBy(Item::id)
    }

    /**
     * Эндпоинты `watching` не отдают год, жанры и рейтинги. Детали подгружаются ограниченно
     * параллельно: это сохраняет универсальную карточку, но не устраивает залп из десятков
     * одновременных запросов к серверу. Каждый ответ красится в state сразу, не дожидаясь
     * соседей — карточки дозаполняются по одной, а не все разом в конце. Тайтлы, чьи детали уже
     * есть в state (из кэша или прошлого прохода), сеть не трогают.
     *
     * Сбой по отдельному тайтлу (например, он удалён/битый на сервере) не считаем ошибкой
     * экрана: карточка просто останется без обогащения (жанр/год/рейтинг), а не покажет
     * баннер [showServerRetryNotice] — сам сбой уже ушёл в телеметрию через `safeRequest`
     * внутри `catalog.getItemDetails`.
     */
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

    /**
     * «В процессе» и «Подборки» — независимые источники: сбой подборок не должен подвешивать
     * баннер над «В процессе» (и наоборот), поэтому в общий [error] попадает только ошибка
     * [loadWatchingTitlesPhase]/истории — сбой подборок просто помечает раздел «грязным», следующий
     * заход в «Подборки» тихо перечитает список (см. [refreshIfDirty]).
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
                val listsDeferred = async { user.getBookmarkFolders() }
                val titles = loadWatchingTitlesPhase()
                applyWatchingTitles(titles)
                val lists = listsDeferred.await()
                updateState { current -> current.copy(lists = lists.getOrNull() ?: current.lists) }
                if (lists is RequestResult.Error) DataInvalidation.markDirty(DataDomain.BOOKMARKS)
                // Кэш обновляем только когда ОБА независимых источника (секция «В процессе» и
                // список папок) реально ответили — частичный/ошибочный проход не должен затирать
                // последний хороший снимок, на который рассчитывает следующий холодный старт/прогрев.
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

        /** Потолок страниц публичных подборок при поиске [WATCH_LATER_COLLECTION_TITLE] по имени. */
        const val WATCH_LATER_MAX_COLLECTION_PAGES = 10
    }
}

private fun <T> List<T>.preserveEmpty(previous: List<T>, error: String?): List<T> =
    if (error != null && isEmpty()) previous else this

/** Единственные два значения `type`, которые понимает `watching/{type}` — общие для
 * [LibraryScreenModel] и [fetchLibrarySnapshot] (прогрев), поэтому вынесены на файл. */

/**
 * Тайтлы «в процессе» обоих типов параллельно — общая точка входа для [LibraryScreenModel] и
 * фонового прогрева [fetchLibrarySnapshot]: одинаковый вызов `watching/{movies|serials}`, чтобы
 * не разъезжаться при будущих правках API. private: используется только внутри этого файла —
 * наружу (в `AppWarmup` другого модуля) торчит только сам [fetchLibrarySnapshot].
 */
private suspend fun fetchWatchingTitles(watching: WatchingRepository): List<WatchingItem> = coroutineScope {
    val moviesDeferred = async { watching.getWatchingTitles(WatchingListType.Movies) }
    val serialsDeferred = async { watching.getWatchingTitles(WatchingListType.Serials) }
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
