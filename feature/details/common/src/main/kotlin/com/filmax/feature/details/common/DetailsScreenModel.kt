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
    private val search: SearchRepository,
) : BaseScreenModel<DetailsState, DetailsSideEffect, DetailsEvent>(DetailsState()) {

    private val route = savedStateHandle.toRoute<DetailsRoute>()

    /** Состояние строки «Буду смотреть» В ДИАЛОГЕ подборок — настоящая подборка
     * [FavoritesRepository], отдельно от кнопки hero (см. [toggleWantToWatch]). */
    private var isInFavoritesFolder = false

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

    /**
     * Сам тайтл — единственное, что держит спиннер: он же почти всегда кэш-хит
     * (`CatalogRepositoryImpl.getItemDetails`), и экран должен открыться МОМЕНТАЛЬНО, если данные
     * уже есть. «Похожее» и историю (для continuation) раньше ждали здесь же, в одном `Triple` —
     * оба всегда идут в сеть по-настоящему (не кэшируются), и держали спиннер лишние 1-2 секунды
     * даже когда сам тайтл уже был на экране готов. Теперь оба — независимые [screenModelScope],
     * которые доливают своё поверх уже открытого экрана (см. [loadSimilar]/[loadContinuation]),
     * не блокируя [DetailsState.loading].
     */
    override fun onFetchData() {
        screenModelScope { _ ->
            when (val itemResult = catalog.getItemDetails(route.itemId)) {
                is RequestResult.Success -> {
                    val item = itemResult.data
                    updateState { it.copy(loading = false, item = item, isWantToWatch = item.inWatchlist) }
                    // Down-sync: если на сервере фильм уже в watchlist — заносим в локальный кэш.
                    if (item.inWatchlist) {
                        favorites.add(item.toFavoriteItem())
                    }
                    // Постеры (item/similar) уже ушли в фоновую закачку из ItemDto.toDomain() — тут
                    // только фото актёров и режиссёра, угаданные из сырых строк cast/director: их
                    // эта функция не знает, а строим мы их именно здесь (actorPhotoUrl).
                    prefetchCastPhotos(item.cast, item.director)
                    loadCast(item.imdbId)
                    loadDirectorFilms(item)
                    loadSimilar()
                    loadContinuation(item)
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

    /** «Похожее» — отдельным запросом (всегда реальная сеть, не кэшируется), не блокируя показ
     * самого тайтла. [DetailsState.similarLoading] держит скелетон ряда, пока не пришёл ответ. */
    private fun loadSimilar() {
        screenModelScope { _ ->
            updateState { it.copy(similarLoading = true) }
            val similar = catalog.getSimilarItems(route.itemId).getOrNull().orEmpty()
            updateState { it.copy(similar = similar, similarLoading = false) }
        }
    }

    /** История (для continuation — «Продолжить · SxEy» на кнопке hero) — отдельным запросом, не
     * блокируя показ самого тайтла. До ответа кнопка играет разумный дефолт (первый недосмотренный
     * эпизод сезона либо первый вовсе, см. `target` в TvDetailsScreen) и тихо обновляется, если
     * найдётся реальный прогресс. */
    private fun loadContinuation(item: Item) {
        screenModelScope { _ ->
            val history = findHistoryEntry()
            updateState { it.copy(continuation = calculateContinuation(item, history)) }
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
            favorites.isFavorite(route.itemId).collect { fav ->
                isInFavoritesFolder = fav
                updateFolderMemberships()
            }
        }
    }

    /** История ведётся по сериям — под текущий тайтл достаём только его запись. */
    private suspend fun findHistoryEntry() =
        watching.getHistory().getOrNull()?.firstOrNull { it.itemId == route.itemId }

    /** «Буду смотреть» — кнопка hero-блока: нативный `watching/togglewatchlist`, без своей логики. */
    private fun toggleWantToWatch() {
        val item = state.item ?: return
        screenModelScope {
            watching.toggleWatchlist(item.id).getOrNull()?.let { isWantToWatch ->
                updateState { it.copy(isWantToWatch = isWantToWatch) }
            }
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
     * любой другой подборки. «Буду смотреть» распознаём по названию и делегируем в
     * [toggleFavoritesFolder] — это НАСТОЯЩАЯ подборка [FavoritesRepository], не имеющая отношения
     * к кнопке hero (см. [toggleWantToWatch]); остальные подборки — напрямую через [UserRepository].
     */
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

    /** Строка «Буду смотреть» В ДИАЛОГЕ подборок — настоящая подборка [FavoritesRepository]:
     * через её собственный `toggle()`, а не напрямую `user.addToBookmark`/`removeFromBookmark`,
     * чтобы локальный кэш репозитория не разошёлся с реальностью. */
    private suspend fun toggleFavoritesFolder(item: Item) {
        favorites.toggle(item.toFavoriteItem())
        updateFolderMemberships()
        reloadBookmarkFolders()
        DataInvalidation.markDirty(DataDomain.BOOKMARKS)
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
        updateState { it.copy(bookmarkFolders = folders) }
        scanMemberships(folders)
    }

    /**
     * Принадлежность «Буду смотреть» уже известна реактивно ([isInFavoritesFolder]) — сканируем
     * только остальные подборки, по одной странице на каждую параллельно (см. [folderContainsItem]).
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
        val memberships = scannedMemberships + listOfNotNull(favoritesFolderId.takeIf { isInFavoritesFolder })
        updateState { it.copy(folderMemberships = memberships) }
    }

    private companion object {
        /** Глубина сканирования подборки на дубликат перед добавлением — см. [folderContainsItem]. */
        const val FOLDER_SCAN_MAX_PAGES = 10

        /** То же название, что и [FavoritesRepository] использует для поиска/создания своей подборки. */
        const val FAVORITES_FOLDER_TITLE = "Буду смотреть"
    }
}
