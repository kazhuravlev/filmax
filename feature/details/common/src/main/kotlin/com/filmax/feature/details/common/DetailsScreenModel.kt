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
import com.filmax.core.domain.watching.WatchingNowRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.calculateContinuation
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import com.filmax.feature.details.common.navigation.DetailsRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

// Экран деталей сводит воспроизведение, избранное, загрузки и подборки в одной модели —
// дробить её ради лимитов нельзя: состояние и обработчики читаются только вместе.
@Suppress("LongParameterList", "TooManyFunctions")
class DetailsScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val downloads: DownloadsRepository,
    private val favorites: FavoritesRepository,
    private val watchingNow: WatchingNowRepository,
    private val cast: CastRepository,
    private val user: UserRepository,
    private val search: SearchRepository,
) : BaseScreenModel<DetailsState, DetailsSideEffect, DetailsEvent>(DetailsState()) {

    private val route = savedStateHandle.toRoute<DetailsRoute>()

    /**
     * Кэш реактивного флага строки «Буду смотреть» в диалоге — не требует скана страниц, в
     * отличие от прочих подборок. Совмещает два независимых сигнала (см. [observeFavoriteState]):
     * нативный watchlist (сериалы) и подборку «Watching Now» (фильмы, см. [WatchingNowRepository]).
     */
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
            DetailsEvent.ToggleWantToWatch -> toggleWantToWatch()
            is DetailsEvent.ToggleFolder -> toggleFolder(event.folder)
            is DetailsEvent.CreateFolderAndAdd -> createFolderAndAdd(event.title)
        }
    }

    override fun onFetchData() {
        screenModelScope { _ ->
            // Три независимых запроса — параллельно, а не по очереди: без этого кэш-хит по
            // деталям тайтла (см. CatalogRepositoryImpl.getItemDetails) не давал бы никакого
            // выигрыша в скорости — «похожее» и историю всё равно ждали бы одно за другим.
            val (itemResult, similarResult, history) = coroutineScope {
                val itemDeferred = async { catalog.getItemDetails(route.itemId) }
                val similarDeferred = async { catalog.getSimilarItems(route.itemId) }
                val historyDeferred = async { findHistoryEntry() }
                Triple(itemDeferred.await(), similarDeferred.await(), historyDeferred.await())
            }
            val similar = similarResult.getOrNull().orEmpty()
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
                    // Постеры (itemResult.data/similar) уже ушли в фоновую закачку из
                    // ItemDto.toDomain() — тут только фото актёров и режиссёра, угаданные из сырых
                    // строк cast/director: их эта функция не знает, а строим мы их именно здесь
                    // (actorPhotoUrl).
                    prefetchCastPhotos(itemResult.data.cast, itemResult.data.director)
                    loadCast(itemResult.data.imdbId)
                    loadDirectorFilms(itemResult.data)
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
     * Угаданные фото актёров и режиссёра (см. [actorPhotoUrl]) ставим в фоновую очередь сразу по
     * сырым строкам `cast`/`director` — не дожидаясь ответа TMDB ([loadCast]) и тем более того,
     * что пользователь долистает до соответствующего ряда: к этому моменту угаданные картинки,
     * скорее всего, уже в кэше.
     */
    private fun prefetchCastPhotos(vararg rawNames: String) {
        val images = rawNames.asSequence()
            .flatMap { it.split(",").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { name -> PrefetchImage(key = ImageCacheKeys.actorPhoto(name), url = actorPhotoUrl(name)) }
            .toList()
        ImageDiscovery.discovered(images)
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

    /**
     * «От режиссёра»: другие тайтлы того же человека, поиском по имени. Только ПЕРВОЕ имя из
     * `item.director` — тот же приём, что и у клика по чипу режиссёра ([resolveDirectors]):
     * kino.watch ищет по одному имени, а не по всей строке соавторов. Отдельным запросом ПОСЛЕ
     * показа тайтла — как и [loadCast], это украшение, а не основа экрана.
     */
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
            combine(favorites.isFavorite(route.itemId), watchingNow.isMember(route.itemId)) { fav, inWatchingNow ->
                fav || inWatchingNow
            }.collect { active ->
                isFav = active
                updateFolderMemberships()
            }
        }
    }

    /** История ведётся по сериям — под текущий тайтл достаём только его запись. */
    private suspend fun findHistoryEntry() =
        watching.getHistory().getOrNull()?.firstOrNull { it.itemId == route.itemId }

    /**
     * «Буду смотреть» — кнопка hero-блока: тот же тоггл, что и выбор строки «Буду смотреть» в
     * диалоге подборок (см. [toggleFolder]), но в один клик, без диалога.
     */
    private fun toggleWantToWatch() {
        val item = state.item ?: return
        screenModelScope { toggleWantToWatchFolder(item) }
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
     * внутри себя) и делегируем в [toggleWantToWatchFolder]; остальные подборки — напрямую через
     * [UserRepository].
     */
    private fun toggleFolder(folder: BookmarkFolder) {
        val item = state.item ?: return
        if (folder.title == FAVORITES_FOLDER_TITLE) {
            screenModelScope { toggleWantToWatchFolder(item) }
            return
        }
        val alreadyIn = folder.id in scannedMemberships
        screenModelScope {
            if (alreadyIn) {
                user.removeFromBookmark(item.id, folder.id)
                scannedMemberships = scannedMemberships - folder.id
            } else {
                // Свежая проверка по серверу, а не только по [scannedMemberships]: локальный скан
                // мог устареть (другое устройство, прямой вызов API) — повторный addToBookmark на
                // уже существующую связь и есть источник дублей в подборке.
                if (!user.isItemInBookmark(item.id, folder.id)) {
                    user.addToBookmark(item.id, folder.id)
                }
                scannedMemberships = scannedMemberships + folder.id
            }
            updateFolderMemberships()
            reloadBookmarkFolders()
            DataInvalidation.markDirty(DataDomain.BOOKMARKS)
        }
    }

    /**
     * «Буду смотреть»: общая логика для hero-кнопки ([toggleWantToWatch]) и строки диалога
     * подборок ([toggleFolder]) — один тот же тоггл, два способа его вызвать.
     *
     * У kino.watch `watching/togglewatchlist` реально работает только для сериалов — для фильмов
     * сервер не даёт ручного способа попасть в «Я смотрю» вовсе (проверено запросами к боевому
     * API: список фильмов там наполняется только реальным прогрессом просмотра). Поэтому строку
     * «Буду смотреть» ведём в [WatchingNowRepository] всегда (это и есть наша подборка-костыль
     * «Watching Now», которую страница «Я смотрю» подмешивает третьим источником), а нативный
     * watchlist — ДОПОЛНИТЕЛЬНО, только для сериалов, чтобы не терять серверный флаг там, где он
     * реально что-то значит.
     *
     * Три источника ([watchingNow], [favorites], нативный watchlist сервера) держат один и тот же
     * флаг независимо, и их `toggle()` переворачивает КАЖДЫЙ как есть, а не выставляет в конкретное
     * значение. Если источники разошлись между собой (например, у сериала уже стоит нативный
     * watchlist, но ещё не проставлен локальный [favorites]), слепой вызов `toggle()` на каждом по
     * отдельности уводил их в РАЗНЫЕ стороны: один включался, другой выключался — и агрегат
     * [isFav] (`favorites || watchingNow`) после клика оставался тем же самым true, то есть
     * сериал было НЕВОЗМОЖНО убрать из «Буду смотреть» с этого экрана. Поэтому считаем ОДНУ
     * целевую цель (обратное текущему [isFav]) и подводим к ней каждый источник отдельно — трогаем
     * только те, что этой цели ещё не достигли.
     */
    private suspend fun toggleWantToWatchFolder(item: Item) {
        val target = !isFav
        if (watchingNow.isMember(item.id).first() != target) {
            watchingNow.toggle(item)
        }
        if (item.isSeries()) {
            if (favorites.isFavorite(item.id).first() != target) {
                favorites.toggle(item.toFavoriteItem())
            }
            if (item.inWatchlist != target) {
                val result = watching.toggleWatchlist(route.itemId).getOrNull() ?: target
                updateState { it.copy(item = it.item?.copy(inWatchlist = result)) }
            }
        }
        // «Буду смотреть» — это подписка/подборка, которая и формирует список «Я смотрю»
        // в библиотеке, и попутно обычная подборка в счётчиках «Подборок».
        DataInvalidation.markDirty(DataDomain.WATCHING, DataDomain.BOOKMARKS)
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

    private suspend fun folderContainsItem(folderId: Int, itemId: Int): Boolean =
        user.isItemInBookmark(itemId, folderId, FOLDER_SCAN_MAX_PAGES)

    private suspend fun reloadBookmarkFolders() {
        val folders = user.getBookmarkFolders().getOrNull() ?: return
        // «Watching Now» — служебная папка-костыль (см. WatchingNowRepository), в диалоге выбора
        // подборок её не показываем: пользователь работает с ней только через «Буду смотреть».
        updateState { it.copy(bookmarkFolders = folders.filterNot { it.title == WATCHING_NOW_FOLDER_TITLE }) }
        scanMemberships(folders)
    }

    /**
     * Принадлежность «Буду смотреть» уже известна реактивно ([isFav]) — сканируем только
     * остальные подборки, по одной странице на каждую параллельно (см. [folderContainsItem]).
     */
    private suspend fun scanMemberships(folders: List<BookmarkFolder>) {
        val item = state.item ?: return
        val toScan = folders.filter { it.title != FAVORITES_FOLDER_TITLE && it.title != WATCHING_NOW_FOLDER_TITLE }
        scannedMemberships = coroutineScope {
            toScan.map { folder -> async { folder.id to folderContainsItem(folder.id, item.id) } }.awaitAll()
        }.filter { it.second }.map { it.first }.toSet()
        updateFolderMemberships()
    }

    private suspend fun updateFolderMemberships() {
        val favoritesFolderId = state.bookmarkFolders.firstOrNull { it.title == FAVORITES_FOLDER_TITLE }?.id
        val memberships = scannedMemberships + listOfNotNull(favoritesFolderId.takeIf { isFav })
        updateState { it.copy(folderMemberships = memberships, isWantToWatch = isFav) }
    }

    private companion object {
        /** Глубина сканирования подборки на дубликат перед добавлением — см. [folderContainsItem]. */
        const val FOLDER_SCAN_MAX_PAGES = 10

        /** То же название, что и [FavoritesRepository] использует для поиска/создания своей подборки. */
        const val FAVORITES_FOLDER_TITLE = "Буду смотреть"

        /** То же название, что и [WatchingNowRepository] использует для поиска/создания своей подборки. */
        const val WATCHING_NOW_FOLDER_TITLE = "Watching Now"
    }
}
