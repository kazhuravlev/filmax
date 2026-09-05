// Экран деталей составной: hero с действиями, сезоны с эпизодами и ряд похожих. Каждая часть —
// свой composable, и это правильное дробление; растаскивать их по файлам значило бы разорвать
// один экран на куски, которые читаются только вместе.
@file:Suppress("TooManyFunctions")
// BringIntoViewSpec: единственный способ выключить фокус-прокрутку полотна в hero-стейте.
@file:OptIn(ExperimentalFoundationApi::class)

package com.filmax.feature.details.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.filmax.core.tv.designsystem.TvSuccess
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

/** Ширина текстового блока в hero (макет: 600dp из 960) — правее лежит открытый бэкдроп. */
private val HeroTextWidth = 600.dp

/** Максимальная ширина описания и строки состава: длинная строка на 3 метрах не читается. */
private val ReadableTextWidth = 760.dp

/** Отступ снизу единого полотна: рамке фокуса последнего ряда нужно место. */
private val ContentBottomPadding = 70.dp

/** Индекс элемента «описание» в полотне: сюда полотно едет, когда фокус уходит с кнопок вниз. */
private const val CONTENT_START_INDEX = 1

/** Сколько кадров пропустить перед прокруткой к стейту (см. [rememberHeroFocusScroller]). */
private const val FRAMES_BEFORE_STATE_SCROLL = 2

/** Ширина карточки актёра и диаметр круглого аватара в ряду «Актёры» (крупнее мобильных — 10-foot UI). */
private val TvActorCardWidth = 120.dp
private val TvActorAvatarSize = 104.dp

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
            state.loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )

            item != null -> DetailsContent(
                item = item,
                similar = state.similar,
                cast = state.cast,
                continuation = state.continuation,
                isWatching = state.isWatching,
                bookmarkFolders = state.bookmarkFolders,
                folderMemberships = state.folderMemberships,
                actions = DetailsActions(
                    onPlay = { season, videoId, resumePositionSeconds ->
                        nav.onPlay(item.id, season, videoId, resumePositionSeconds)
                    },
                    onToggleWatching = { screenModel.dispatch(DetailsEvent.ToggleWatching) },
                    onOpenItem = nav.onOpenItem,
                    onOpenPerson = nav.onOpenPerson,
                    onPlayTrailer = nav.onPlayTrailer,
                    onToggleFolder = { folder -> screenModel.dispatch(DetailsEvent.ToggleFolder(folder)) },
                    onCreateFolder = { title -> screenModel.dispatch(DetailsEvent.CreateFolderAndAdd(title)) },
                ),
            )
        }
    }
}

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
    /** «Я смотрю» — отдельная от подборок пометка, без читаемого состояния (см. DetailsEvent). */
    val onToggleWatching: () -> Unit,
    val onOpenItem: (Int) -> Unit,
    val onOpenPerson: (name: String, isDirector: Boolean) -> Unit,
    val onPlayTrailer: (url: String, title: String) -> Unit,
    /** Добавить тайтл в подборку или убрать из неё — состояние решает сам экран. */
    val onToggleFolder: (BookmarkFolder) -> Unit,
    /** Создать подборку и сразу занести в неё тайтл — из того же диалога выбора. */
    val onCreateFolder: (title: String) -> Unit,
)

// Экран собирает hero, все секции полотна и диалог выбора подборки в одном месте — раскладывать
// его по отдельным composable ради лимитов значило бы разорвать код, который читается только
// вместе (см. заголовок файла). Тот же осознанный Suppress, что и для класса-модели экрана.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun DetailsContent(
    item: Item,
    similar: List<Item>,
    cast: List<CastMember>,
    continuation: Continuation?,
    isWatching: Boolean,
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
    var selectedSeason by remember(item.id) { mutableIntStateOf(series?.resumeSeasonIndex ?: 0) }
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
    // false = стейт hero (открытие экрана), true = фокус ушёл в контент. Пока полотно в стейте
    // hero, фокус-прокрутка (bringIntoView) выключена ПОЛНОСТЬЮ: именно она давала подскролл к
    // середине при открытии — стартовый requestFocus на «Смотреть» уезжал раньше раскладки.
    val contentFocused = remember { mutableStateOf(false) }
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
            contentPadding = PaddingValues(top = TvMetrics.SafeVertical, bottom = ContentBottomPadding),
        ) {
            item(key = "hero") {
                DetailsHero(
                    item = item,
                    series = series,
                    hasAnyFolder = folderMemberships.isNotEmpty(),
                    playback = HeroPlayback(
                        playModifier = focus.item(HERO_PLAY_KEY),
                        // Фильм играется целиком (videoId = -1), сериал — конкретной серией. Сериал
                        // без серий играть нечем — кнопка молчит. В плеер уходят НОМЕР серии и
                        // СЕЗОН: номер уникален только внутри сезона.
                        onPlay = {
                            if (series == null) {
                                actions.onPlay(NO_SEASON, MOVIE_VIDEO_ID, NO_RESUME_POSITION)
                            } else {
                                target?.let {
                                    val position = continuation
                                        ?.takeIf { candidate ->
                                            candidate.isActualContinuation &&
                                                candidate.season == it.seasonNumber && candidate.videoId == it.number
                                        }
                                        ?.savedPositionSeconds
                                        ?: NO_RESUME_POSITION
                                    actions.onPlay(it.seasonNumber, it.number, position)
                                }
                            }
                        },
                        onOpenFolderPicker = { folderPicker.pickerOpen = true },
                        onToggleWatching = actions.onToggleWatching,
                        isWatching = isWatching,
                        onHeroFocusChanged = onHeroFocusChanged,
                        onTrailer = trailerUrl?.let { ::playTrailer },
                    ),
                )
            }
            detailsSections(
                data = DetailsSectionsData(item, similar, people, directors, series, episodes, selectedSeason),
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
    val people: List<CastMember>,
    val directors: List<CastMember>,
    val series: SeriesData?,
    val episodes: List<MediaTrack>,
    val selectedSeason: Int,
)

/** Секции полотна под hero: описание, актёры, режиссёр, эпизоды, «Похожее». */
private fun LazyListScope.detailsSections(
    data: DetailsSectionsData,
    actions: DetailsActions,
    onSelectSeason: (Int) -> Unit,
    focus: TvScreenFocus,
) {
    item(key = "about") { DetailsAbout(data.item) }
    if (data.people.isNotEmpty()) {
        castRail(people = data.people, onOpenPerson = actions.onOpenPerson)
    }
    if (data.directors.isNotEmpty()) {
        directorSection(directors = data.directors, onOpenPerson = actions.onOpenPerson)
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
    if (data.similar.isNotEmpty()) {
        similarRail(similar = data.similar, onOpenItem = actions.onOpenItem)
    }
}

// ─────────────────────────────────── Hero ───────────────────────────────────

/** Фокус и действия кнопок hero — группой (detekt LongParameterList). */
private data class HeroPlayback(
    val playModifier: Modifier,
    val onPlay: () -> Unit,
    /** Открыть диалог выбора подборки — единственная кнопка «Добавить в подборку» / «В подборках». */
    val onOpenFolderPicker: () -> Unit,
    /** «Я смотрю» — отдельная пометка тайтла, см. [DetailsActions.onToggleWatching]. */
    val onToggleWatching: () -> Unit,
    /** Текущее состояние пометки «Я смотрю» — см. [DetailsState.isWatching]. */
    val isWatching: Boolean,
    /** Фокус зашёл на кнопки hero или ушёл с них — экран переключает стейт полотна. */
    val onHeroFocusChanged: (Boolean) -> Unit,
    /** null — у тайтла нет играбельного трейлера, кнопки нет. */
    val onTrailer: (() -> Unit)? = null,
)

/**
 * Hero: бэкдроп во всю ширину, текстовый блок прижат к низу слева (вариант A макета).
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
            .height(TvMetrics.DetailsHeroHeight),
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
                .align(Alignment.BottomStart)
                .padding(start = TvMetrics.SafeHorizontal, bottom = 22.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.displaySmall,
                color = TvOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(HeroTextWidth),
            )
            TvMetaRow(
                parts = remember(item, series) { metaParts(item, series) },
                modifier = Modifier
                    .width(HeroTextWidth)
                    .padding(top = 11.dp),
            )
            RatingsRow(
                rating = item.rating,
                views = item.views,
                modifier = Modifier
                    .width(HeroTextWidth)
                    .padding(top = 9.dp),
            )
            // Ряд кнопок не зажат в HeroTextWidth: с трейлером их четыре, и в 600dp они не
            // помещаются — последняя кнопка обрезалась бы почти до одной иконки.
            HeroButtons(
                hasAnyFolder = hasAnyFolder,
                resume = series?.resume,
                playback = playback,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
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

@Composable
private fun HeroButtons(
    hasAnyFolder: Boolean,
    resume: MediaTrack?,
    playback: HeroPlayback,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.onFocusChanged { playback.onHeroFocusChanged(it.hasFocus) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvButton(
            text = playLabel(resume),
            onClick = playback.onPlay,
            leadingIcon = Icons.Filled.PlayArrow,
            modifier = playback.playModifier,
        )
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
        // Отдельная от подборок пометка (см. DetailsEvent.ToggleWatching): зелёный залитый глаз —
        // тайтл отмечен «Я смотрю», белый контурный — ещё нет («Хочу посмотреть»).
        TvButton(
            text = if (playback.isWatching) "Я смотрю" else "Хочу посмотреть",
            onClick = playback.onToggleWatching,
            primary = false,
            leadingIcon = if (playback.isWatching) Icons.Filled.Visibility else Icons.Outlined.RemoveRedEye,
            leadingIconTint = if (playback.isWatching) TvSuccess else null,
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
        Text(
            item.plot,
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = TvMetrics.SafeHorizontal, end = TvMetrics.SafeHorizontal, top = 22.dp)
                .widthIn(max = ReadableTextWidth),
        )
    }
}

// ─────────────────────────────── Актёры и режиссёр ────────────────────────────

/**
 * Ряд актёров карточками с круглым аватаром. Фото приходят из TMDB ([DetailsState.cast]); пока
 * их нет — те же карточки с инициалами (имена всегда есть от kino.watch). Каждая карточка ведёт в
 * фильмографию человека, поэтому каст на TV наконец фокусируемый и кликабельный, а не мёртвая строка.
 */
private fun LazyListScope.castRail(people: List<CastMember>, onOpenPerson: (String, Boolean) -> Unit) {
    item(key = "cast") {
        TvRail(title = "Актёры", modifier = Modifier.padding(top = 24.dp)) {
            // Без key: имена в составе могут повторяться, позиционного ключа достаточно.
            items(people) { member ->
                TvActorCard(
                    member = member,
                    onClick = { onOpenPerson(member.name, false) },
                )
            }
        }
    }
}

/**
 * Ряд режиссёров — та же карточка, что и у актёров (см. [castRail]): у kino.watch `director` тоже
 * строка имён через запятую, а не одно значение, поэтому раньше весь список садился в один чип.
 */
private fun LazyListScope.directorSection(directors: List<CastMember>, onOpenPerson: (String, Boolean) -> Unit) {
    item(key = "director") {
        TvRail(title = "Режиссёр", modifier = Modifier.padding(top = 24.dp)) {
            items(directors) { member ->
                TvActorCard(
                    member = member,
                    onClick = { onOpenPerson(member.name, true) },
                )
            }
        }
    }
}

/** Карточка актёра: круглый аватар (фото TMDB или инициалы) + имя. Фокус/скейл — как у медиа-карточек. */
@Composable
private fun TvActorCard(member: CastMember, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)
    Column(
        modifier = Modifier
            .width(TvActorCardWidth)
            .onFocusChanged { focused = it.hasFocus }
            .alpha(dim),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvFocusCard(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.size(TvActorAvatarSize),
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(TvSurfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                val photo = member.photoUrl
                // Фото из TMDB надёжное, а угаданное по MD5 имени (kino.watch CDN) — нет: часть
                // ссылок честно 404. Здесь нужен именно `AsyncImage`, а не общий `PosterImage`:
                // тот при ошибке загрузки рисует значок «фото нет» (правильно для настоящих
                // постеров), а для угаданного аватара актёра лучше молча откатиться на инициалы.
                // Ключ — photoUrl: при переиспользовании карточки в ряду флаг сбрасывается.
                var loadFailed by remember(member.photoUrl) { mutableStateOf(false) }
                if (photo != null && !loadFailed) {
                    val proxyEnabled by koinInject<ImageProxyRepository>().enabled.collectAsState()
                    val model = remember(photo, proxyEnabled) {
                        CacheableImage(
                            key = ImageCacheKeys.actorPhoto(member.name),
                            url = proxiedImageUrl(photo, proxyEnabled),
                        )
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state -> loadFailed = state is AsyncImagePainter.State.Error },
                    )
                } else {
                    Text(
                        initials(member.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = TvOnSurfaceVariant,
                    )
                }
            }
        }
        Text(
            member.name,
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
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

// ─────────────────────────────────── Похожее ─────────────────────────────────

private fun LazyListScope.similarRail(similar: List<Item>, onOpenItem: (Int) -> Unit) {
    item(key = "similar") {
        TvRail(title = "Похожее", modifier = Modifier.padding(top = 26.dp)) {
            items(similar, key = { simItem -> simItem.id }) { simItem ->
                TvPosterCard(
                    title = simItem.title,
                    meta = posterMeta(typeLabel(simItem.type), simItem.year),
                    posterUrl = simItem.posters.medium.ifEmpty { simItem.posters.big },
                    onClick = { onOpenItem(simItem.id) },
                    imdbRating = ratingLabel(simItem.rating.imdb),
                    kinopoiskRating = ratingLabel(simItem.rating.kinopoisk),
                    advert = simItem.advert,
                ) { url, modifier ->
                    PosterImage(
                        url = url,
                        contentDescription = simItem.title,
                        modifier = modifier,
                        shape = TvMetrics.PosterShape,
                        accentColor = TvSurfaceContainerHigh,
                        cacheKey = ImageCacheKeys.poster(simItem.type.apiValue, simItem.id, ImageCacheKeys.SIZE_MEDIUM),
                    )
                }
            }
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

/** «Продолжить · S2E5» — сериал с недосмотренной серией; иначе «Смотреть». */
private fun playLabel(resume: MediaTrack?): String = when {
    resume == null -> "Смотреть"
    resume.seasonNumber > 0 -> "Продолжить · S${resume.seasonNumber}E${resume.number}"
    else -> "Продолжить · Серия ${resume.number}"
}

/** Мета карточки серии: «Серия 3 · 45 мин». Номер опускаем, если он уже стал заголовком. */
private fun episodeMeta(episode: MediaTrack): String? = buildList {
    if (episode.title.isNotBlank()) add("Серия ${episode.number}")
    episode.durationSeconds.takeIf { it > 0 }?.let { add("${it / SECONDS_IN_MINUTE} мин") }
}.joinToString(" · ").ifBlank { null }
