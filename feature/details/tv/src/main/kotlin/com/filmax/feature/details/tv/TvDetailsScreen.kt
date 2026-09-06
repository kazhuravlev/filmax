// Экран деталей составной: hero с действиями, сезоны с эпизодами и ряд похожих. Каждая часть —
// свой composable, и это правильное дробление; растаскивать их по файлам значило бы разорвать
// один экран на куски, которые читаются только вместе.
@file:Suppress("TooManyFunctions")
// BringIntoViewSpec: единственный способ выключить фокус-прокрутку полотна в hero-стейте.
@file:OptIn(ExperimentalFoundationApi::class)

package com.filmax.feature.details.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageProxyRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.person.CastMember
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvButton
import com.filmax.core.tv.designsystem.TvCardSize
import com.filmax.core.tv.designsystem.TvChip
import com.filmax.core.tv.designsystem.TvError
import com.filmax.core.tv.designsystem.TvFocusCard
import com.filmax.core.tv.designsystem.TvMetaRow
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.core.tv.designsystem.TvPosterCard
import com.filmax.core.tv.designsystem.TvProgressCard
import com.filmax.core.tv.designsystem.TvRail
import com.filmax.core.tv.designsystem.TvScreenFocus
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.tv.designsystem.TvSurfaceContainer
import com.filmax.core.tv.designsystem.TvSurfaceContainerHigh
import com.filmax.core.tv.designsystem.TvSurfaceContainerHighest
import com.filmax.core.tv.designsystem.posterMeta
import com.filmax.core.tv.designsystem.ratingLabel
import com.filmax.core.tv.designsystem.rememberDimAlpha
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.tv.designsystem.tvFocusGroup
import com.filmax.core.ui.cache.CacheableImage
import com.filmax.core.ui.cache.proxiedImageUrl
import com.filmax.core.ui.components.GradientPosterPlaceholder
import com.filmax.core.ui.components.HeroBackdrop
import com.filmax.core.ui.components.PosterImage
import com.filmax.feature.details.common.DetailsEvent
import com.filmax.feature.details.common.DetailsScreenModel
import com.filmax.feature.details.common.SeriesData
import com.filmax.feature.details.common.calculateSeriesData
import com.filmax.feature.details.common.initials
import com.filmax.feature.details.common.isSeries
import com.filmax.feature.details.common.resolveCast
import com.filmax.feature.details.common.resolveDirectors
import com.filmax.feature.details.common.typeLabel
import com.filmax.feature.details.common.viewsLabel
import com.filmax.feature.details.common.volumeLabel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Ширина меты/рейтинга в hero: уже заголовка — рядом теперь постер (см. [HeroPoster]). */
private val HeroInfoWidth = 420.dp

/** Непрозрачность чёрной подложки под метой/рейтингами — та же, что у [TvRatingPill]. */
private const val HERO_INFO_SCRIM_ALPHA = 0.72f

/** Максимальная ширина описания и строки состава: длинная строка на 3 метрах не читается. */
private val ReadableTextWidth = 760.dp

/** Отступ снизу единого полотна: рамке фокуса последнего ряда нужно место. */
private val ContentBottomPadding = 70.dp

/** Индекс элемента «описание» в полотне: сюда полотно едет, когда фокус уходит с кнопок вниз. */
private const val CONTENT_START_INDEX = 1

/** Сколько кадров пропустить перед прокруткой к стейту (см. [rememberHeroFocusScroller]). */
private const val FRAMES_BEFORE_STATE_SCROLL = 2

/**
 * Чип человека (актёр/режиссёр, см. [TvPersonChip]): портретное фото сверху, имя под ним.
 * Форма — та же, что и у плашки в целом ([PersonChipShape]): скругление не отдельное для фото,
 * а общее. Ширина и высота ФИКСИРОВАНЫ и одинаковы у всех чипов ряда — иначе имена разной
 * длины давали чипы вразнобой. Имя, которое не влезает в [PersonChipWidth], не обрезается
 * навсегда: бегущей строкой оно едет при фокусе (см. [TvPersonChip]).
 */
private val PersonChipShape = TvMetrics.CardShape
private val PersonChipWidth = 120.dp
private val PersonChipHeight = 188.dp
private val PersonChipNameHeight = 40.dp
private val PersonChipGap = 12.dp

/** Размер постера-обложки в hero (см. [HeroPoster]) — компактнее каталожного, рядом с бэкдропом. */
private val HeroPosterWidth = 140.dp
private val HeroPosterHeight = 210.dp

private const val EPISODES_TITLE = "Эпизоды"

/** Ключ фокуса кнопки «Смотреть»: стартовая цель экрана. */
private const val HERO_PLAY_KEY = "hero:play"

/** Фильм играется целиком, без выбора дорожки: плеер ждёт videoId = -1. */
private const val MOVIE_VIDEO_ID = -1
private const val NO_RESUME_POSITION = 0

/** «Сезона нет» — фильм или сезон неизвестен (PlayerRoute.season = -1). */
private const val NO_SEASON = -1

private const val MAX_META_GENRES = 2
private const val SECONDS_IN_MINUTE = 60

/**
 * TV-Детали. Фильм и сериал — один вертикальный поток: hero, описание, эпизоды (сериал),
 * «Похожее». Поверх общего [DetailsScreenModel] (itemId берётся из маршрута через SavedStateHandle).
 */
@Composable
fun TvDetailsScreen(
    nav: TvDetailsNav,
    modifier: Modifier = Modifier,
    screenModel: DetailsScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()
    val item = state.item

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.loading -> TvDetailsSkeleton()

            item != null -> DetailsContent(
                item = item,
                similar = state.similar,
                similarLoading = state.similarLoading,
                directorFilms = state.directorFilms,
                cast = state.cast,
                continuation = state.continuation,
                isWantToWatch = state.isWantToWatch,
                bookmarkFolders = state.bookmarkFolders,
                folderMemberships = state.folderMemberships,
                actions = DetailsActions(
                    onPlay = { season, videoId, resumePositionSeconds ->
                        nav.onPlay(item.id, season, videoId, resumePositionSeconds)
                    },
                    onToggleWantToWatch = { screenModel.dispatch(DetailsEvent.ToggleWantToWatch) },
                    onOpenItem = nav.onOpenItem,
                    onOpenPerson = nav.onOpenPerson,
                    onPlayTrailer = nav.onPlayTrailer,
                    onToggleFolder = { folder -> screenModel.dispatch(DetailsEvent.ToggleFolder(folder)) },
                    onCreateFolder = { title -> screenModel.dispatch(DetailsEvent.CreateFolderAndAdd(title)) },
                    onPrefetchPlayback = { screenModel.dispatch(DetailsEvent.PrefetchPlayback) },
                    awaitContinuation = screenModel::awaitContinuation,
                ),
            )
        }
    }
}

/**
 * Скелетон холодного промаха кэша (`state.loading` без ещё загруженного `item`) — редкий путь:
 * тайтл почти всегда уже лежит в `ItemDetailsCache` и экран открывается мгновенно (см. doc
 * `DetailsScreenModel.onFetchData`). Раньше здесь был голый `CircularProgressIndicator` на чёрном
 * фоне — грубое приближение раскладки [DetailsHero] тем же визуальным языком, что и
 * [PosterRailSkeleton]/`TvRailSkeleton` (главная): статичные градиентные плашки
 * ([GradientPosterPlaceholder]) без shimmer-анимации (см. её обоснование в `PosterImage.kt` —
 * десятки одновременных shimmer-анимаций на ТВ роняли FPS).
 */
@Composable
private fun TvDetailsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = TvMetrics.SafeHorizontal,
                end = TvMetrics.SafeHorizontal,
                top = TvMetrics.ContentTop,
            ),
    ) {
        SkeletonBlock(width = SkeletonTitleWidth, height = SkeletonTitleHeight, shape = TvMetrics.ButtonShape)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SkeletonBlock(width = HeroPosterWidth, height = HeroPosterHeight, shape = TvMetrics.PosterShape)
            Column {
                SkeletonBlock(width = HeroInfoWidth, height = SkeletonInfoPanelHeight, shape = TvMetrics.PanelShape)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBlock(
                        width = SkeletonPlayButtonWidth,
                        height = SkeletonButtonHeight,
                        shape = TvMetrics.ButtonShape,
                    )
                    SkeletonBlock(
                        width = SkeletonSecondaryButtonWidth,
                        height = SkeletonButtonHeight,
                        shape = TvMetrics.ButtonShape,
                    )
                }
                Spacer(Modifier.height(10.dp))
                SkeletonBlock(
                    width = SkeletonSecondaryButtonWidth,
                    height = SkeletonButtonHeight,
                    shape = TvMetrics.ButtonShape,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        SkeletonBlock(width = ReadableTextWidth, height = SkeletonTextLineHeight, shape = SkeletonTextShape)
        Spacer(Modifier.height(10.dp))
        SkeletonBlock(width = ReadableTextWidth, height = SkeletonTextLineHeight, shape = SkeletonTextShape)
        Spacer(Modifier.height(10.dp))
        SkeletonBlock(width = SkeletonShortTextLineWidth, height = SkeletonTextLineHeight, shape = SkeletonTextShape)
    }
}

/** Одна плашка скелетона — та же статичная градиентная заглушка, что и у постеров/рядов. */
@Composable
private fun SkeletonBlock(width: Dp, height: Dp, shape: Shape) {
    GradientPosterPlaceholder(
        accentColor = TvSurfaceContainerHigh,
        modifier = Modifier.width(width).height(height).clip(shape),
    )
}

private val SkeletonTitleWidth = 360.dp
private val SkeletonTitleHeight = 32.dp
private val SkeletonInfoPanelHeight = 76.dp
private val SkeletonButtonHeight = 44.dp
private val SkeletonPlayButtonWidth = 170.dp
private val SkeletonSecondaryButtonWidth = 190.dp
private val SkeletonTextLineHeight = 16.dp
private val SkeletonShortTextLineWidth = 420.dp
private val SkeletonTextShape = RoundedCornerShape(4.dp)

/**
 * Навигация TV-деталей — группой (detekt LongParameterList): входной composable иначе набирает
 * больше шести параметров.
 */
data class TvDetailsNav(
    val onPlay: (itemId: Int, season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    val onOpenItem: (Int) -> Unit,
    /** Тап по актёру/режиссёру -> его фильмография (isDirector различает запрос к API). */
    val onOpenPerson: (name: String, isDirector: Boolean) -> Unit,
    /** Играть трейлер: прямой HLS-url и заголовок. */
    val onPlayTrailer: (url: String, title: String) -> Unit,
)

/** Действия экрана — группой, чтобы не раздувать списки параметров у вложенных секций. */
private data class DetailsActions(
    /** [season] ≤ 0 — фильм/сезон неизвестен; номер видео уникален только внутри сезона. */
    val onPlay: (season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    /** «Буду смотреть» — нативный watchlist kino.watch. */
    val onToggleWantToWatch: () -> Unit,
    val onOpenItem: (Int) -> Unit,
    val onOpenPerson: (name: String, isDirector: Boolean) -> Unit,
    val onPlayTrailer: (url: String, title: String) -> Unit,
    /** Добавить тайтл в подборку или убрать из неё — состояние решает сам экран. */
    val onToggleFolder: (BookmarkFolder) -> Unit,
    /** Создать подборку и сразу занести в неё тайтл — из того же диалога выбора. */
    val onCreateFolder: (title: String) -> Unit,
    /** Фокус зашёл на кнопку «Смотреть» — см. [DetailsEvent.PrefetchPlayback]. */
    val onPrefetchPlayback: () -> Unit,
    /** Ждёт настоящий ответ continuation, если он ещё грузится — см. `DetailsScreenModel.awaitContinuation`. */
    val awaitContinuation: suspend () -> Continuation?,
)

// Экран собирает hero, все секции полотна и диалог выбора подборки в одном месте — раскладывать
// его по отдельным composable ради лимитов значило бы разорвать код, который читается только
// вместе (см. заголовок файла). Тот же осознанный Suppress, что и для класса-модели экрана.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun DetailsContent(
    item: Item,
    similar: List<Item>,
    similarLoading: Boolean,
    directorFilms: List<Item>,
    cast: List<CastMember>,
    continuation: Continuation?,
    isWantToWatch: Boolean,
    bookmarkFolders: List<BookmarkFolder>,
    folderMemberships: Set<Int>,
    actions: DetailsActions,
) {
    val folderPicker = remember { TvFolderPickerUi() }
    val series = remember(item, continuation) {
        if (item.isSeries()) calculateSeriesData(item.tracklist, continuation) else null
    }
    // Селектор стартует на сезоне недосмотренной серии, а не на первом: продолжают чаще, чем
    // начинают заново.
    //
    // rememberSaveable, а не remember: тот же класс бага, что уже чинили для contentFocused
    // выше (см. её комментарий) — уход в плеер/фильмографию и обратно пересоздаёт композицию
    // с нуля, и обычный remember тихо сбрасывал вручную выбранный сезон на resumeSeasonIndex,
    // а восстановление фокуса на ключ "episode:${id}" переставало находить карточку — она
    // просто не рендерилась, потому что список серий откатывался на другой сезон.
    var selectedSeason by rememberSaveable(item.id) { mutableIntStateOf(series?.resumeSeasonIndex ?: 0) }
    val episodes = series?.seasons?.getOrNull(selectedSeason)?.second.orEmpty()

    // Первый заход открывает экран на «Смотреть», возврат из плеера — на серии, с которой ушли.
    // И то, и другое — одна цель фокуса, поэтому и механизм один: два конкурирующих реквеста в
    // одном кадре давали то кнопку, то серию, и ряд серий выглядел мёртвым (отсюда «иногда»).
    val focus = rememberTvScreenFocus(startAt = HERO_PLAY_KEY)

    // Кнопка играет недосмотренную серию, иначе первую серию ВЫБРАННОГО сезона (у фильма дорожка
    // не выбирается вовсе).
    val target = series?.let { it.resume ?: episodes.firstOrNull() ?: item.tracklist.firstOrNull() }
    // Трейлер показываем, только если url — играбельный http(s) (kino.watch отдаёт прямой HLS).
    val trailerUrl = item.trailer?.url?.takeIf { it.startsWith("http") }
    // Актёры карточками: фото из TMDB, если доехали; иначе — имена из строки kino.watch.
    val people = remember(cast, item.cast) { resolveCast(cast, item.cast) }
    // Режиссёр(ы) той же карточкой: у kino.watch это тоже строка имён через запятую.
    val directors = remember(item.director) { resolveDirectors(item.director) }

    val listState = rememberLazyListState()
    // Для onPlay ниже: ожидание continuation (см. её doc) — suspend, а обработчик клика — нет.
    val playScope = rememberCoroutineScope()
    // false = стейт hero (открытие экрана), true = фокус ушёл в контент. Пока полотно в стейте
    // hero, фокус-прокрутка (bringIntoView) выключена ПОЛНОСТЬЮ: именно она давала подскролл к
    // середине при открытии — стартовый requestFocus на «Смотреть» уезжал раньше раскладки.
    //
    // Начальное значение — не всегда false: если экран восстанавливает фокус сразу вглубь
    // контента (например, на карточку серии, на которой стояли до перехода к режиссёру и
    // обратно), фокус в hero-кнопки в этой композиции вообще не заходит — rememberHeroFocusScroller
    // никогда не срабатывает, и NoFocusScroll остаётся включённым навсегда, ломая bringIntoView
    // (в т.ч. подскролл) во всех рядах контента, включая «Похожее». Поэтому стартуем сразу в
    // нужном стейте, а не ждём живого перехода фокуса через шапку.
    val contentFocused = remember {
        mutableStateOf(focus.initialReturnTarget != null && focus.initialReturnTarget != HERO_PLAY_KEY)
    }
    val onHeroFocusChanged = rememberHeroFocusScroller(listState, contentFocused)

    // Локальная функция вместо лямбды-в-лямбде (ktlint Wrapping): у тайтла без трейлера кнопки нет.
    fun playTrailer() {
        trailerUrl?.let { url -> actions.onPlayTrailer(url, "Трейлер · ${item.title}") }
    }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides
            if (contentFocused.value) LocalBringIntoViewSpec.current else NoFocusScroll,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(focus.containerModifier),
            // Без top-инсета: бэкдроп hero — первый элемент полотна — должен упираться в настоящий
            // верхний край экрана и уходить под плавающий TvTopNavBar, а не начинаться под ним.
            // Клиренс под таб-бар отъезжает внутрь DetailsHero — на текстовую колонку, а не сюда.
            contentPadding = PaddingValues(bottom = ContentBottomPadding),
        ) {
            item(key = "hero") {
                DetailsHero(
                    item = item,
                    series = series,
                    hasAnyFolder = folderMemberships.isNotEmpty(),
                    playback = HeroPlayback(
                        playModifier = focus.item(HERO_PLAY_KEY),
                        playLabel = remember(series?.resume, target) { playLabel(series?.resume, target) },
                        // Фильм играется целиком (videoId = -1), сериал — конкретной серией. Сериал
                        // без серий играть нечем — кнопка молчит. В плеер уходят НОМЕР серии и
                        // СЕЗОН: номер уникален только внутри сезона.
                        onPlay = {
                            if (series == null) {
                                actions.onPlay(NO_SEASON, MOVIE_VIDEO_ID, NO_RESUME_POSITION)
                            } else {
                                playScope.launch {
                                    // ГОНКА (см. doc DetailsScreenModel.loadContinuation): пока
                                    // continuation не пришла, `continuation`/`target` в этой
                                    // композиции — временный дефолт (null / первая серия сезона),
                                    // и без ожидания настоящего ответа серия сыграла бы с нуля,
                                    // хотя прогресс уже есть — «потерял моё место». Ждём реальный
                                    // ответ (с таймаутом на случай медленного сервера) и пересчитываем
                                    // серию/позицию по НЕМУ, а не по тому, что успело попасть в кадр.
                                    val resolved = actions.awaitContinuation()
                                    val resolvedResume = resolved
                                        ?.takeIf { it.isActualContinuation }
                                        ?.let { c ->
                                            item.tracklist.firstOrNull { track ->
                                                track.seasonNumber == c.season && track.number == c.videoId
                                            }
                                        }
                                    val chosen = resolvedResume ?: target
                                    chosen?.let {
                                        val position = resolved
                                            ?.takeIf { candidate ->
                                                candidate.isActualContinuation &&
                                                    candidate.season == it.seasonNumber &&
                                                    candidate.videoId == it.number
                                            }
                                            ?.savedPositionSeconds
                                            ?: NO_RESUME_POSITION
                                        actions.onPlay(it.seasonNumber, it.number, position)
                                    }
                                }
                            }
                        },
                        onOpenFolderPicker = { folderPicker.pickerOpen = true },
                        onToggleWantToWatch = actions.onToggleWantToWatch,
                        isWantToWatch = isWantToWatch,
                        showWantToWatch = item.isSeries(),
                        onHeroFocusChanged = onHeroFocusChanged,
                        onTrailer = trailerUrl?.let { ::playTrailer },
                        onPrefetchPlayback = actions.onPrefetchPlayback,
                    ),
                )
            }
            detailsSections(
                data = DetailsSectionsData(
                    item = item,
                    similar = similar,
                    similarLoading = similarLoading,
                    directorFilms = directorFilms,
                    people = people,
                    directors = directors,
                    series = series,
                    episodes = episodes,
                    selectedSeason = selectedSeason,
                ),
                actions = actions,
                onSelectSeason = { selectedSeason = it },
                focus = focus,
            )
        }
    }

    TvFolderPickerHost(
        ui = folderPicker,
        folders = bookmarkFolders,
        memberships = folderMemberships,
        onSelectFolder = { folder -> actions.onToggleFolder(folder) },
        onCreateFolder = { title -> actions.onCreateFolder(title) },
    )
}

/**
 * Переключатель двух стейтов полотна по фокусу кнопок hero. Стейт 1: фокус на кнопках —
 * полотно к началу, hero виден целиком (плюс описание под ним). Стейт 2: фокус ушёл с кнопок
 * вниз — полотно едет к описанию, hero скрывается прокруткой. Всё это ОДИН LazyColumn:
 * ничего не накладывается и не режется. Начальная композиция (фокуса ещё не было) — не выход.
 */
@Composable
private fun rememberHeroFocusScroller(
    listState: LazyListState,
    contentFocused: MutableState<Boolean>,
): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    var heroHadFocus by remember { mutableStateOf(false) }

    // Прокрутка к стейту — через кадр: смена фокуса в этом же кадре запускает системный
    // bringIntoView, и без паузы он перехватывал бы нашу прокрутку (полотно застревало на
    // полпути, верх постера оставался срезанным). Более поздний вызов забирает scroll-мьютекс
    // списка себе — поэтому пропускаем кадры и едем к цели последними.
    fun scrollAfterFrame(targetIndex: Int) {
        scope.launch {
            repeat(FRAMES_BEFORE_STATE_SCROLL) { withFrameNanos { } }
            listState.animateScrollToItem(targetIndex)
        }
    }

    return { focused ->
        if (focused) {
            heroHadFocus = true
            contentFocused.value = false
            scrollAfterFrame(0)
        } else if (heroHadFocus) {
            heroHadFocus = false
            contentFocused.value = true
            scrollAfterFrame(CONTENT_START_INDEX)
        }
    }
}

/**
 * Спека «не скроллить»: пока полотно в стейте hero, любой bringIntoView от фокуса гасится —
 * позицией полотна управляет только [rememberHeroFocusScroller]. Включается обратно, когда
 * фокус уходит в контент: там штатная фокус-прокрутка нужна для глубоких рядов.
 */
private val NoFocusScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Данные секций полотна под hero — группой (detekt LongParameterList). */
private data class DetailsSectionsData(
    val item: Item,
    val similar: List<Item>,
    val similarLoading: Boolean,
    val directorFilms: List<Item>,
    val people: List<CastMember>,
    val directors: List<CastMember>,
    val series: SeriesData?,
    val episodes: List<MediaTrack>,
    val selectedSeason: Int,
)

/**
 * Секции полотна под hero: описание, режиссёр (+ «От режиссёра»), актёры, эпизоды, «Похожее».
 * Режиссёр и его фильмография — первыми среди состава и рядов похожего: это то, ради чего люди
 * чаще всего открывают карточку конкретного человека, а не разгребают весь состав сначала.
 */
private fun LazyListScope.detailsSections(
    data: DetailsSectionsData,
    actions: DetailsActions,
    onSelectSeason: (Int) -> Unit,
    focus: TvScreenFocus,
) {
    item(key = "about") { DetailsAbout(data.item) }
    if (data.directors.isNotEmpty()) {
        peopleSection(
            key = "directors",
            title = if (data.directors.size > 1) "Режиссёры" else "Режиссёр",
            people = data.directors,
            onOpenPerson = { name -> actions.onOpenPerson(name, true) },
        )
    }
    if (data.directorFilms.isNotEmpty()) {
        posterRail(
            key = "director-films",
            title = "От режиссёра",
            items = data.directorFilms,
            onOpenItem = actions.onOpenItem,
        )
    }
    if (data.people.isNotEmpty()) {
        peopleSection(
            key = "cast",
            title = "В ролях",
            people = data.people,
            onOpenPerson = { name -> actions.onOpenPerson(name, false) },
        )
    }
    if (data.episodes.isNotEmpty()) {
        episodesSection(
            EpisodesSection(
                seasons = data.series?.seasons.orEmpty(),
                episodes = data.episodes,
                resumeId = data.series?.resume?.id,
                selectedSeason = data.selectedSeason,
                onSelectSeason = onSelectSeason,
                onPlayEpisode = { season, videoId -> actions.onPlay(season, videoId, NO_RESUME_POSITION) },
                focus = focus,
            )
        )
    }
    if (data.similarLoading) {
        item(key = "similar-skeleton") { PosterRailSkeleton(title = "Похожее") }
    } else if (data.similar.isNotEmpty()) {
        posterRail(key = "similar", title = "Похожее", items = data.similar, onOpenItem = actions.onOpenItem)
    }
}

// ─────────────────────────────────── Hero ───────────────────────────────────

/** Фокус и действия кнопок hero — группой (detekt LongParameterList). */
private data class HeroPlayback(
    val playModifier: Modifier,
    val onPlay: () -> Unit,
    /** Текст кнопки «Смотреть» — уже с сезоном/серией, если применимо, см. [playLabel]. */
    val playLabel: String,
    /** Открыть диалог выбора подборки — единственная кнопка «Добавить в подборку» / «В подборках». */
    val onOpenFolderPicker: () -> Unit,
    /** «Буду смотреть», см. [DetailsActions.onToggleWantToWatch]. */
    val onToggleWantToWatch: () -> Unit,
    /** Текущее состояние «Буду смотреть» — см. [DetailsState.isWantToWatch]. */
    val isWantToWatch: Boolean,
    /** У kino.watch `watching/togglewatchlist` добавляет в «Я смотрю» только сериалы — у фильма
     * кнопки нет вовсе, а не задизейблена. */
    val showWantToWatch: Boolean,
    /** Фокус зашёл на кнопки hero или ушёл с них — экран переключает стейт полотна. */
    val onHeroFocusChanged: (Boolean) -> Unit,
    /** null — у тайтла нет играбельного трейлера, кнопки нет. */
    val onTrailer: (() -> Unit)? = null,
    /** Фокус зашёл именно на «Смотреть» — см. [DetailsEvent.PrefetchPlayback]. */
    val onPrefetchPlayback: () -> Unit = {},
)

/**
 * Hero: бэкдроп во всю ширину, название сверху слева (а не над кнопками внизу — рядом с постером
 * и составом теперь просится подпись экрана, а не заголовок-плакат), под ним постер 2:3 и рядом с
 * ним мета/рейтинг/кнопки.
 *
 * Высота фиксированная: hero — первый элемент единого полотна и скрывается обычной прокруткой,
 * когда фокус уходит в контент, а не сжимается поверх него. Так постер всегда либо виден
 * целиком, либо честно уезжает вверх — ничего не режется.
 */
@Composable
private fun DetailsHero(
    item: Item,
    series: SeriesData?,
    hasAnyFolder: Boolean,
    playback: HeroPlayback,
) {
    Box(
        Modifier
            .fillMaxWidth()
            // Бэкдроп растёт вверх на высоту таб-бара (см. LazyColumn выше): сам кадр честно
            // упирается в верхний край экрана и уходит под плавающий TvTopNavBar, а текстовая
            // колонка ниже отступает от него на ContentTop — её высота (и раскладка внутри)
            // от этого не меняется, просто сдвинута вниз на ту же величину, что раньше давал
            // top-инсет списка.
            .height(TvMetrics.DetailsHeroHeight + TvMetrics.ContentTop),
    ) {
        HeroBackdrop(
            item = item,
            scrims = heroScrims(),
            modifier = Modifier.fillMaxSize(),
            posterUrl = item.posters.wide ?: item.posters.big,
            // Заглушка постера — нейтральная поверхность: цвет на экране только у самого кадра.
            accentColor = TvSurfaceContainerHigh,
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = TvMetrics.SafeHorizontal,
                    end = TvMetrics.SafeHorizontal,
                    top = TvMetrics.ContentTop,
                    bottom = 22.dp,
                ),
        ) {
            // Заголовок всегда виден целиком: без maxLines/ellipsis (обрезать нельзя) и без
            // marquee (не единственный фокусируемый элемент кадра, гонять текст туда-сюда
            // назойливо). Не влезает в строку — переносится: fillMaxWidth на всю ширину кадра.
            Text(
                item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = TvOnSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.weight(1f).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HeroPoster(item)
                Column {
                    HeroInfoPanel(item = item, series = series)
                    // Кнопки НЕ зажаты в HeroInfoWidth: в узкой колонке подборки/«хочу
                    // посмотреть» обрезались бы почти до одной иконки.
                    HeroButtons(
                        hasAnyFolder = hasAnyFolder,
                        playback = playback,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Год/жанр и рейтинги на полупрозрачной чёрной подложке: бэкдроп под ними — постер тайтла,
 * часто пёстрый, и текст без неё местами терялся.
 */
@Composable
private fun HeroInfoPanel(item: Item, series: SeriesData?) {
    Column(
        modifier = Modifier
            .clip(TvMetrics.PanelShape)
            .background(TvSurface.copy(alpha = HERO_INFO_SCRIM_ALPHA))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        TvMetaRow(
            parts = remember(item, series) { metaParts(item, series) },
            modifier = Modifier.width(HeroInfoWidth),
        )
        RatingsRow(
            rating = item.rating,
            views = item.views,
            modifier = Modifier
                .width(HeroInfoWidth)
                .padding(top = 9.dp),
        )
    }
}

/** Обложка 2:3 рядом с бэкдропом — «обычный постер» тайтла, а не только широкий фон hero. */
@Composable
private fun HeroPoster(item: Item) {
    PosterImage(
        url = item.posters.medium.ifEmpty { item.posters.big },
        contentDescription = item.title,
        modifier = Modifier.width(HeroPosterWidth).height(HeroPosterHeight),
        shape = TvMetrics.PosterShape,
        accentColor = TvSurfaceContainerHigh,
        cacheKey = ImageCacheKeys.poster(item.type.apiValue, item.id, ImageCacheKeys.SIZE_MEDIUM),
    )
}

/**
 * Скримы hero. Стопы длинные и с промежуточными точками: в монохроме переход серого в серый
 * на коротком отрезке полосит (бандинг), а уход в прозрачность берём как `TvSurface` с нулевой
 * альфой — интерполяция в `Color.Transparent` тянет RGB к чёрному и даёт грязный «хвост».
 */
@Composable
private fun heroScrims(): List<Brush> = remember {
    listOf(
        Brush.horizontalGradient(
            0f to TvSurface.copy(alpha = 0.95f),
            0.40f to TvSurface.copy(alpha = 0.72f),
            0.72f to TvSurface.copy(alpha = 0.20f),
            1f to TvSurface.copy(alpha = 0f),
        ),
        Brush.verticalGradient(
            0f to TvSurface.copy(alpha = 0f),
            0.22f to TvSurface.copy(alpha = 0f),
            0.60f to TvSurface.copy(alpha = 0.35f),
            1f to TvSurface.copy(alpha = 0.98f),
        ),
    )
}

/**
 * Кнопки hero — двумя рядами: сверху «Смотреть»/«Трейлер» (собственно воспроизведение), под
 * ними — наши пометки тайтла (подборки, «Хочу посмотреть»). `onFocusChanged` висит на общем
 * контейнере: фокус в любом из двух рядов одинаково держит полотно в стейте hero.
 */
@Composable
private fun HeroButtons(
    hasAnyFolder: Boolean,
    playback: HeroPlayback,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.onFocusChanged { playback.onHeroFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton(
                text = playback.playLabel,
                onClick = playback.onPlay,
                leadingIcon = Icons.Filled.PlayArrow,
                // onFocusChanged именно здесь, а не на общем onHeroFocusChanged контейнера ниже:
                // прогрев нужен только когда фокус реально на «Смотреть», а не на любой кнопке hero.
                modifier = playback.playModifier
                    .onFocusChanged { if (it.hasFocus) playback.onPrefetchPlayback() },
            )
            playback.onTrailer?.let { onTrailer ->
                TvButton(
                    text = "Трейлер",
                    onClick = onTrailer,
                    primary = false,
                    leadingIcon = Icons.Filled.Movie,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Единственная кнопка подборок: тайтл либо нигде не сохранён, либо уже в одной или
            // нескольких (включая «Буду смотреть» — для сервера это обычная подборка). Красная
            // заливка иконки — сигнал «уже добавлено», клик всегда открывает диалог выбора.
            TvButton(
                text = if (hasAnyFolder) "В подборках" else "Добавить в подборку",
                onClick = playback.onOpenFolderPicker,
                primary = false,
                leadingIcon = if (hasAnyFolder) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                leadingIconTint = if (hasAnyFolder) TvError else null,
            )
            // «Буду смотреть»: нативный watchlist kino.watch, переключается в один клик
            // (см. DetailsEvent.ToggleWantToWatch) — та же красная заливка, что и у кнопки
            // подборок, сигнализирует «уже добавлено». Только у сериалов (см. [showWantToWatch]).
            if (playback.showWantToWatch) {
                TvButton(
                    text = if (playback.isWantToWatch) "Буду смотреть" else "Хочу посмотреть",
                    onClick = playback.onToggleWantToWatch,
                    primary = false,
                    leadingIcon = if (playback.isWantToWatch) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    leadingIconTint = if (playback.isWantToWatch) TvError else null,
                )
            }
        }
    }
}

/**
 * КП и IMDb показываем РАЗДЕЛЬНО: `rating.external` усредняет их, а расхождение оценок — это
 * и есть причина смотреть обе. Цветового кодирования нет: в монохроме оценку несёт число.
 * Число просмотров — тот же формат пилюли, третьим элементом ряда.
 */
@Composable
private fun RatingsRow(rating: ItemRating, views: Int, modifier: Modifier = Modifier) {
    // ratingLabel режет «0» (у kino.watch это «оценки нет») и приводит «8.312» к одному знаку.
    val sources = remember(rating, views) {
        buildList {
            ratingLabel(rating.kinopoisk)?.let { add(it to "КиноПоиск") }
            ratingLabel(rating.imdb)?.let { add(it to "IMDb") }
            viewsLabel(views)?.let { add(it to "просмотров") }
        }
    }
    if (sources.isEmpty()) return

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        sources.forEachIndexed { index, (value, source) ->
            if (index > 0) {
                Box(
                    Modifier
                        .size(width = 1.dp, height = 14.dp)
                        .background(TvSurfaceContainerHighest),
                )
            }
            RatingValue(value = value, source = source)
        }
    }
}

@Composable
private fun RatingValue(value: String, source: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = TvOnSurface)
        Text(source, style = MaterialTheme.typography.bodyLarge, color = TvOnSurfaceVariant)
    }
}

// ─────────────────────────── Описание и состав ──────────────────────────────

@Composable
private fun DetailsAbout(item: Item) {
    if (item.plot.isNotBlank()) {
        // Без maxLines/ellipsis: это единственное место, где показан полный синопсис — резать
        // его до 4 строк означало обрезать описание у большинства тайтлов на полуслове.
        Text(
            item.plot,
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceVariant,
            modifier = Modifier
                .padding(start = TvMetrics.SafeHorizontal, end = TvMetrics.SafeHorizontal, top = 22.dp)
                .widthIn(max = ReadableTextWidth),
        )
    }
}

// ─────────────────────────────── Актёры и режиссёр ────────────────────────────

/**
 * Ряд людей (актёры ИЛИ режиссёры — один и тот же компонент для обоих, только заголовок и цель
 * клика отличаются): портретные чипы одинакового размера ([TvPersonChip]), с переносом на новую
 * строку ([FlowRow]) — весь состав виден сразу, без горизонтального скролла ряда.
 */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.peopleSection(
    key: String,
    title: String,
    people: List<CastMember>,
    onOpenPerson: (String) -> Unit,
) {
    item(key = key) {
        Column(Modifier.padding(top = 24.dp)) {
            SectionTitle(title)
            FlowRow(
                modifier = Modifier
                    .tvFocusGroup()
                    .padding(start = TvMetrics.SafeHorizontal, end = TvMetrics.SafeHorizontal),
                horizontalArrangement = Arrangement.spacedBy(PersonChipGap),
                verticalArrangement = Arrangement.spacedBy(PersonChipGap),
            ) {
                // Без key: имена в составе могут повторяться, позиционного ключа достаточно.
                people.forEach { member ->
                    TvPersonChip(member = member, onClick = { onOpenPerson(member.name) })
                }
            }
        }
    }
}

/**
 * Чип человека: портретное фото сверху (TMDB/угаданное или инициалы), имя под ним одной строкой.
 * Карточка — фиксированного размера ([PersonChipWidth] × [PersonChipHeight]), одинакового у всех
 * чипов ряда. Фото занимает всё, что остаётся над именем (`weight(1f)` в колонке) — то есть
 * 100% высоты своей области, по центру, с обрезкой по бокам ([ContentScale.Crop] на более узкий,
 * чем у фото, контейнер). Имя не обрезается навсегда многоточием: при фокусе на чипе запускается
 * бегущая строка — так виден весь текст без роста карточки.
 */
@Composable
private fun TvPersonChip(member: CastMember, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)
    TvFocusCard(
        onClick = onClick,
        shape = PersonChipShape,
        modifier = Modifier
            .size(width = PersonChipWidth, height = PersonChipHeight)
            .onFocusChanged { focused = it.hasFocus }
            // Читаем dim.value в drawing-фазе (graphicsLayer), не в composition (alpha()):
            // иначе каждый кадр анимации затухания рекомпозирует весь чип — см. TvComponents.kt.
            .graphicsLayer { alpha = dim.value },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(PersonChipShape)
                .background(TvSurfaceContainerHigh),
        ) {
            PersonPhoto(member = member, modifier = Modifier.fillMaxWidth().weight(1f))
            Box(
                modifier = Modifier.fillMaxWidth().height(PersonChipNameHeight).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                    ),
                )
            }
        }
    }
}

/**
 * Портретное фото чипа: фото (TMDB надёжное, угаданное по MD5 имени на kino.watch CDN — нет,
 * часть ссылок честно 404) или инициалы. Скругление — то же [PersonChipShape], что и у плашки
 * целиком: отдельной формы у фото нет. Здесь нужен именно `AsyncImage`, а не общий `PosterImage`:
 * тот при ошибке загрузки рисует значок «фото нет» (правильно для настоящих постеров), а для
 * угаданного аватара лучше молча откатиться на инициалы. Ключ ремембера — photoUrl: при
 * переиспользовании чипа в ряду флаг ошибки сбрасывается.
 */
@Composable
private fun PersonPhoto(member: CastMember, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(PersonChipShape).background(TvSurfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val photo = member.photoUrl
        var loadFailed by remember(member.photoUrl) { mutableStateOf(false) }
        if (photo != null && !loadFailed) {
            val proxyEnabled by koinInject<ImageProxyRepository>().enabled.collectAsState()
            val model = remember(photo, proxyEnabled) {
                CacheableImage(key = ImageCacheKeys.actorPhoto(member.name), url = proxiedImageUrl(photo, proxyEnabled))
            }
            AsyncImage(
                model = model,
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state -> loadFailed = state is AsyncImagePainter.State.Error },
            )
        } else {
            Text(initials(member.name), style = MaterialTheme.typography.titleMedium, color = TvOnSurfaceVariant)
        }
    }
}

// ────────────────────────────── Эпизоды сериала ──────────────────────────────

/** Данные и действия секции эпизодов — группой (detekt LongParameterList). */
private data class EpisodesSection(
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    val episodes: List<MediaTrack>,
    val resumeId: Int?,
    val selectedSeason: Int,
    val onSelectSeason: (Int) -> Unit,
    val onPlayEpisode: (season: Int, videoId: Int) -> Unit,
    val focus: TvScreenFocus,
)

/**
 * Секция эпизодов: заголовок → чипы сезонов → ряд карточек серий.
 *
 * Чипы — горизонтальный ряд, а не FlowRow с переносом: у сериала на 8+ сезонов перенос забирал
 * под чипы половину экрана. Чипы и карточки — разные ряды LazyColumn, поэтому «вниз» с чипов
 * ведёт в серии, а не прыгает через них.
 */
private fun LazyListScope.episodesSection(section: EpisodesSection) {
    if (section.seasons.size > 1) {
        item(key = "seasons") {
            TvRail(title = EPISODES_TITLE, modifier = Modifier.padding(top = 24.dp)) {
                itemsIndexed(section.seasons, key = { _, season -> season.first }) { index, season ->
                    val number = season.first
                    TvChip(
                        label = if (number > 0) "Сезон $number" else "Серии",
                        selected = index == section.selectedSeason,
                        onClick = { section.onSelectSeason(index) },
                    )
                }
            }
        }
    } else {
        // Один сезон — селектор не нужен, но заголовок секции остаётся.
        item(key = "episodes-title") {
            SectionTitle(EPISODES_TITLE, Modifier.padding(top = 24.dp))
        }
    }

    item(key = "episodes") {
        EpisodesRow(
            episodes = section.episodes,
            resumeId = section.resumeId,
            selectedSeason = section.selectedSeason,
            onPlay = section.onPlayEpisode,
            focus = section.focus,
        )
    }
}

/** Заголовок секции, когда над рядом нет чипов (TvRail рисует заголовок вплотную к своему ряду). */
@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = TvOnSurface,
        modifier = modifier.padding(start = TvMetrics.SafeHorizontal, bottom = 12.dp),
    )
}

/**
 * Ряд серий. Свой LazyRow, а не [TvRail]: заголовок «Эпизоды» стоит над чипами сезонов, а
 * TvRail жёстко ставит заголовок над своим рядом. Отступы и группа фокуса — как у TvRail.
 *
 * Ряд ПЕРЕСОЗДАЁТСЯ на каждый сезон (`key`), а не переиспользует один LazyListState. Соседний
 * сезон — это другой набор данных: другие ключи и другая длина. Общий стейт тащил в него скролл
 * прошлого сезона и — главное — удержанный (pinned) фокусом элемент: при следующем размещении
 * ряд ставил его вторым проходом и Compose падал с «Place was called on a node which was placed
 * already». Ловилось так: посмотреть серию → вернуться на карточку → полистать сезоны и серии
 * (Crashlytics 1.7.1, реальный ТВ-бокс). Свежий стейт не тащит ни скролла, ни пинов, и сброс
 * скролла к началу больше не нужен отдельным эффектом.
 */
@Composable
private fun EpisodesRow(
    episodes: List<MediaTrack>,
    resumeId: Int?,
    selectedSeason: Int,
    onPlay: (season: Int, videoId: Int) -> Unit,
    focus: TvScreenFocus,
) {
    key(selectedSeason) {
        LazyRow(
            state = rememberLazyListState(),
            modifier = Modifier.tvFocusGroup(),
            contentPadding = PaddingValues(
                start = TvMetrics.SafeHorizontal,
                end = TvMetrics.SafeHorizontal,
                top = TvMetrics.FocusInset,
                bottom = TvMetrics.FocusInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
        ) {
            items(episodes, key = { episode -> episode.id }) { episode ->
                EpisodeCard(
                    episode = episode,
                    isResume = episode.id == resumeId,
                    // Возврат из плеера ставит фокус обратно на эту серию.
                    modifier = focus.item("episode:${episode.id}"),
                    // Плееру нужны номер серии (API `video`) и сезон, а не id трека.
                    onClick = { onPlay(episode.seasonNumber, episode.number) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: MediaTrack,
    isResume: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val progress = if (episode.durationSeconds > 0) {
        episode.watchedSeconds.toFloat() / episode.durationSeconds
    } else {
        0f
    }
    TvProgressCard(
        title = episode.title.ifBlank { "Серия ${episode.number}" },
        meta = episodeMeta(episode),
        posterUrl = episode.thumbnail,
        progress = progress,
        onClick = onClick,
        modifier = modifier,
        size = TvCardSize.Episode,
    ) { url, posterModifier ->
        EpisodeThumb(url = url, episode = episode, isResume = isResume, modifier = posterModifier)
    }
}

/**
 * Превью серии: кадр, а если его нет — крупный номер серии (у kino.watch thumbnail часто пустой,
 * и пустая плитка не отличима от соседней).
 */
@Composable
private fun EpisodeThumb(url: String, episode: MediaTrack, isResume: Boolean, modifier: Modifier) {
    Box(modifier.background(TvSurfaceContainer), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            PosterImage(
                url = url,
                contentDescription = episode.title,
                modifier = Modifier.fillMaxSize(),
                shape = TvMetrics.CardShape,
                accentColor = TvSurfaceContainerHigh,
                cacheKey = ImageCacheKeys.episodeThumbnail(episode.id),
            )
        } else {
            Text(
                "${episode.number}",
                style = MaterialTheme.typography.headlineMedium,
                color = TvOnSurfaceVariant,
            )
        }
        if (isResume) {
            // Явный бейдж вместо слова «продолжить» в строке меты: в ряду из десятка одинаковых
            // плиток текстовый признак не находится взглядом.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(TvMetrics.PosterShape)
                    .background(TvSurface.copy(alpha = 0.78f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("Продолжить", style = MaterialTheme.typography.labelSmall, color = TvOnSurface)
            }
        }
    }
}

// ─────────────────────────── Выбор подборки ──────────────────────────────

/** Стейт диалогов выбора подборки: живёт в [DetailsContent], меняется кнопкой подборок и диалогами. */
@Stable
private class TvFolderPickerUi {
    var pickerOpen by mutableStateOf(false)
    var creatingFolder by mutableStateOf(false)
}

/** Рисует активный диалог выбора подборки и переводит выбор в события экрана. */
@Composable
private fun TvFolderPickerHost(
    ui: TvFolderPickerUi,
    folders: List<BookmarkFolder>,
    memberships: Set<Int>,
    onSelectFolder: (BookmarkFolder) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    if (ui.pickerOpen) {
        TvFolderPickerDialog(
            folders = folders,
            memberships = memberships,
            onSelectFolder = { folder ->
                onSelectFolder(folder)
                ui.pickerOpen = false
            },
            onNewFolder = {
                ui.pickerOpen = false
                ui.creatingFolder = true
            },
            onDismiss = { ui.pickerOpen = false },
        )
    }
    if (ui.creatingFolder) {
        TvCreateBookmarkFolderDialog(
            onConfirm = { title ->
                onCreateFolder(title)
                ui.creatingFolder = false
            },
            onDismiss = { ui.creatingFolder = false },
        )
    }
}

/**
 * Список ВСЕХ подборок пользователя, включая «Буду смотреть» — единая точка выбора, куда
 * добавить тайтл или откуда его убрать. Уже содержащие тайтл подборки отмечены красной залитой
 * иконкой закладки, остальные — белым контуром. «Новая подборка» всегда последней строкой, даже
 * если подборок ещё нет вовсе.
 */
@Composable
private fun TvFolderPickerDialog(
    folders: List<BookmarkFolder>,
    memberships: Set<Int>,
    onSelectFolder: (BookmarkFolder) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = FolderDialogWidth)
                .clip(TvMetrics.PanelShape)
                .background(TvSurfaceContainer)
                .padding(24.dp),
        ) {
            Text("Добавить в подборку", style = MaterialTheme.typography.titleLarge, color = TvOnSurface)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEachIndexed { index, folder ->
                    val inFolder = folder.id in memberships
                    TvFolderPickerRow(
                        title = folder.title,
                        subtitle = bookmarkCountLabel(folder.count),
                        icon = if (inFolder) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        iconTint = if (inFolder) TvError else TvOnSurface,
                        focusRequester = if (index == 0) firstRowFocus else null,
                        onClick = { onSelectFolder(folder) },
                    )
                }
                TvFolderPickerRow(
                    title = "Новая подборка",
                    subtitle = null,
                    icon = Icons.Filled.CreateNewFolder,
                    iconTint = TvOnSurface,
                    focusRequester = if (folders.isEmpty()) firstRowFocus else null,
                    onClick = onNewFolder,
                )
            }
        }
    }
}

/** Строка диалога: иконка (белый контур — не добавлено, красная заливка — уже в подборке), название, счётчик. */
@Composable
private fun TvFolderPickerRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    iconTint: Color,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    TvFocusCard(
        onClick = onClick,
        shape = TvMetrics.ButtonShape,
        focusRequester = focusRequester,
        modifier = Modifier.fillMaxWidth().height(FolderRowHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(TvMetrics.ButtonShape)
                .background(TvSurfaceContainerHigh)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TvOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TvOnSurfaceVariant)
                }
            }
        }
    }
}

/** Диалог названия новой подборки. По подтверждению подборка создаётся и тайтл сразу в неё добавляется. */
@Composable
private fun TvCreateBookmarkFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = FolderDialogWidth)
                .clip(TvMetrics.PanelShape)
                .background(TvSurfaceContainer)
                .padding(28.dp),
        ) {
            Text("Новая подборка", style = MaterialTheme.typography.titleLarge, color = TvOnSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "Введите название пультом",
                style = MaterialTheme.typography.bodyMedium,
                color = TvOnSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TvMetrics.ButtonShape)
                    .background(TvSurfaceContainerHigh)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (name.isEmpty()) {
                    Text(
                        "Название подборки",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TvOnSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvOnSurface),
                    cursorBrush = SolidColor(TvAccent),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(text = "Создать и добавить", onClick = { onConfirm(name) })
                TvButton(text = "Отмена", onClick = onDismiss, primary = false)
            }
        }
    }
}

/** «3 тайтла» / «1 тайтл» / «5 тайтлов» — подпись счётчика под названием подборки. */
private fun bookmarkCountLabel(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "тайтлов"
        count % 10 == 1 -> "тайтл"
        count % 10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    return "$count $word"
}

private val FolderDialogWidth = 420.dp
private val FolderRowHeight = 56.dp

// ────────────────────────── Похожее / От режиссёра ───────────────────────────

/** Ряд постеров тайтлов — общий для «От режиссёра» и «Похожего»: карточка та же, что и в каталоге. */
private fun LazyListScope.posterRail(
    key: String,
    title: String,
    items: List<Item>,
    onOpenItem: (Int) -> Unit,
) {
    item(key = key) {
        TvRail(title = title, modifier = Modifier.padding(top = 26.dp)) {
            items(items, key = { railItem -> railItem.id }) { railItem ->
                TvPosterCard(
                    title = railItem.title,
                    meta = posterMeta(typeLabel(railItem.type), railItem.year),
                    posterUrl = railItem.posters.medium.ifEmpty { railItem.posters.big },
                    onClick = { onOpenItem(railItem.id) },
                    imdbRating = ratingLabel(railItem.rating.imdb),
                    kinopoiskRating = ratingLabel(railItem.rating.kinopoisk),
                    advert = railItem.advert,
                ) { url, modifier ->
                    PosterImage(
                        url = url,
                        contentDescription = railItem.title,
                        modifier = modifier,
                        shape = TvMetrics.PosterShape,
                        accentColor = TvSurfaceContainerHigh,
                        cacheKey = ImageCacheKeys.poster(
                            railItem.type.apiValue,
                            railItem.id,
                            ImageCacheKeys.SIZE_MEDIUM,
                        ),
                    )
                }
            }
        }
    }
}

/** Сколько карточек-заглушек рисовать, пока «Похожее» ещё грузится. */
private const val SIMILAR_SKELETON_COUNT = 6

/**
 * «Похожее», пока не пришёл ответ: заголовок уже на месте, вместо карточек — статичные
 * градиентные плейсхолдеры ([GradientPosterPlaceholder], тот же, что и под непрогруженным
 * постером). Без shimmer-анимации — см. её обоснование в `PosterImage.kt` (десятки одновременных
 * shimmer-анимаций на ТВ роняли FPS).
 */
@Composable
private fun PosterRailSkeleton(title: String) {
    TvRail(title = title, modifier = Modifier.padding(top = 26.dp)) {
        items(SIMILAR_SKELETON_COUNT) {
            GradientPosterPlaceholder(
                accentColor = TvSurfaceContainerHigh,
                modifier = Modifier
                    .size(width = TvMetrics.PosterWidth, height = TvMetrics.PosterHeight)
                    .clip(TvMetrics.PosterShape),
            )
        }
    }
}

// ───────────────────────────── Производные данные ────────────────────────────
// Чистые производные сериала и подписи меты общие с mobile — см. details.common.DetailsFormat.

/** Мета-строка hero: год · объём/длительность · страна · жанры. Пустые части выпадают. */
private fun metaParts(item: Item, series: SeriesData?): List<String> = buildList {
    if (item.year > 0) add(item.year.toString())
    volumeLabel(item, series)?.let { add(it) }
    if (item.country.isNotBlank()) add(item.country)
    if (item.genres.isNotEmpty()) {
        add(item.genres.take(MAX_META_GENRES).joinToString(", ") { it.title })
    }
}

/**
 * «Продолжить · S2E5» — сериал с недосмотренной серией; «Смотреть · S1E1» — сериал без
 * continuation (кнопка всё равно сыграет конкретную серию — первую недосмотренную сезона или
 * первую серию вовсе, см. `target` в [DetailsContent]); «Смотреть» — фильм, где сезона/серии нет.
 */
private fun playLabel(resume: MediaTrack?, target: MediaTrack?): String = when {
    resume != null -> "Продолжить · ${episodeTag(resume)}"
    target != null -> "Смотреть · ${episodeTag(target)}"
    else -> "Смотреть"
}

private fun episodeTag(track: MediaTrack): String =
    if (track.seasonNumber > 0) "S${track.seasonNumber}E${track.number}" else "Серия ${track.number}"

/** Мета карточки серии: «Серия 3 · 45 мин». Номер опускаем, если он уже стал заголовком. */
private fun episodeMeta(episode: MediaTrack): String? = buildList {
    if (episode.title.isNotBlank()) add("Серия ${episode.number}")
    episode.durationSeconds.takeIf { it > 0 }?.let { add("${it / SECONDS_IN_MINUTE} мин") }
}.joinToString(" · ").ifBlank { null }
