@file:Suppress("TooManyFunctions")
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import com.filmax.core.domain.cache.PosterSize
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
import com.filmax.core.tv.designsystem.qualityLabel
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

private val SkeletonInfoPanelWidth = 420.dp

private const val HERO_INFO_SCRIM_ALPHA = 0.72f

private val ReadableTextWidth = 760.dp

private val ContentBottomPadding = 70.dp

private const val CONTENT_START_INDEX = 1

private const val FRAMES_BEFORE_STATE_SCROLL = 2

private val PersonChipShape = TvMetrics.CardShape
private val PersonChipWidth = 120.dp
private val PersonChipHeight = 188.dp
private val PersonChipNameHeight = 40.dp
private val PersonChipGap = 12.dp

private val HeroPosterWidth = 140.dp
private val HeroPosterHeight = 210.dp

private const val EPISODES_TITLE = "Эпизоды"

private const val HERO_KEY_PREFIX = "hero:"

private const val HERO_PLAY_KEY = "hero:play"

private const val HERO_SEASONS_KEY = "hero:seasons"

private const val MOVIE_VIDEO_ID = -1
private const val NO_RESUME_POSITION = 0

private const val NO_SEASON = -1

private const val MAX_META_GENRES = 2
private const val SECONDS_IN_MINUTE = 60
private const val MINUTES_IN_HOUR = 60

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
                    onPrefetchEpisodeThumbnails = {
                        screenModel.dispatch(DetailsEvent.PrefetchEpisodeThumbnails)
                    },
                    awaitContinuation = screenModel::awaitContinuation,
                ),
            )
        }
    }
}

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
                SkeletonBlock(
                    width = SkeletonInfoPanelWidth,
                    height = SkeletonInfoPanelHeight,
                    shape = TvMetrics.PanelShape,
                )
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

data class TvDetailsNav(
    val onPlay: (itemId: Int, season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    val onOpenItem: (Int) -> Unit,
    val onOpenPerson: (name: String, isDirector: Boolean) -> Unit,
    val onPlayTrailer: (url: String, title: String) -> Unit,
)

private data class DetailsActions(
    val onPlay: (season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    val onToggleWantToWatch: () -> Unit,
    val onOpenItem: (Int) -> Unit,
    val onOpenPerson: (name: String, isDirector: Boolean) -> Unit,
    val onPlayTrailer: (url: String, title: String) -> Unit,
    val onToggleFolder: (BookmarkFolder) -> Unit,
    val onCreateFolder: (title: String) -> Unit,
    val onPrefetchPlayback: () -> Unit,
    val onPrefetchEpisodeThumbnails: () -> Unit,
    val awaitContinuation: suspend () -> Continuation?,
)

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
    var selectedSeason by rememberSaveable(item.id) { mutableIntStateOf(series?.resumeSeasonIndex ?: 0) }
    val episodes = series?.seasons?.getOrNull(selectedSeason)?.second.orEmpty()
    var seasonsOpen by rememberSaveable(item.id) { mutableStateOf(false) }

    val focus = rememberTvScreenFocus(startAt = HERO_PLAY_KEY)

    val target = series?.let { it.resume ?: episodes.firstOrNull() ?: item.tracklist.firstOrNull() }
    val trailerUrl = item.trailer?.url?.takeIf { it.startsWith("http") }
    val people = remember(cast, item.cast) { resolveCast(cast, item.cast) }
    val directors = remember(item.director) { resolveDirectors(item.director) }

    val listState = rememberLazyListState()
    val playScope = rememberCoroutineScope()
    val contentFocused = remember {
        mutableStateOf(focus.initialReturnTarget?.startsWith(HERO_KEY_PREFIX) == false)
    }
    val onHeroFocusChanged = rememberHeroFocusScroller(listState, contentFocused)

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
            contentPadding = PaddingValues(bottom = ContentBottomPadding),
        ) {
            item(key = "hero") {
                DetailsHero(
                    item = item,
                    series = series,
                    hasAnyFolder = folderMemberships.isNotEmpty(),
                    playback = HeroPlayback(
                        playModifier = focus.item(HERO_PLAY_KEY),
                        playLabel = remember(continuation, series?.resume, target) {
                            playLabel(continuation, series?.resume, target)
                        },
                        onPlay = {
                            playScope.launch {
                                val resolved = actions.awaitContinuation()
                                    ?.takeIf { it.isActualContinuation }
                                if (series == null) {
                                    actions.onPlay(
                                        NO_SEASON,
                                        MOVIE_VIDEO_ID,
                                        resolved?.savedPositionSeconds ?: NO_RESUME_POSITION,
                                    )
                                } else {
                                    val resolvedResume = resolved
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
                        onOpenSeasons = series?.takeIf { it.seasons.isNotEmpty() }?.let {
                            {
                                seasonsOpen = true
                                actions.onPrefetchEpisodeThumbnails()
                            }
                        },
                        seasonsModifier = focus.item(HERO_SEASONS_KEY),
                        seasonsLabel = if ((series?.seasons?.size ?: 0) > 1) "Сезоны и серии" else "Серии",
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

    if (seasonsOpen && series != null) {
        TvSeasonsBrowserDialog(
            content = SeasonsBrowserContent(
                title = item.title,
                seasons = series.seasons,
                resumeId = series.resume?.id,
                resumePositionSeconds = continuation
                    ?.takeIf { it.isActualContinuation }
                    ?.savedPositionSeconds
                    ?: NO_RESUME_POSITION,
            ),
            onPlay = actions.onPlay,
            onDismiss = { seasonsOpen = false },
        )
    }
}

@Composable
private fun rememberHeroFocusScroller(
    listState: LazyListState,
    contentFocused: MutableState<Boolean>,
): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    var heroHadFocus by remember { mutableStateOf(false) }

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

private val NoFocusScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

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

private data class HeroPlayback(
    val playModifier: Modifier,
    val onPlay: () -> Unit,
    val playLabel: String,
    val onOpenFolderPicker: () -> Unit,
    val onOpenSeasons: (() -> Unit)? = null,
    val seasonsModifier: Modifier = Modifier,
    val seasonsLabel: String = "Сезоны и серии",
    val onToggleWantToWatch: () -> Unit,
    val isWantToWatch: Boolean,
    val showWantToWatch: Boolean,
    val onHeroFocusChanged: (Boolean) -> Unit,
    val onTrailer: (() -> Unit)? = null,
    val onPrefetchPlayback: () -> Unit = {},
)

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
            .heightIn(min = TvMetrics.DetailsHeroHeight + TvMetrics.ContentTop),
    ) {
        HeroBackdrop(
            item = item,
            scrims = heroScrims(),
            modifier = Modifier.matchParentSize(),
            posterUrl = item.posters.wide ?: item.posters.big,
            accentColor = TvSurfaceContainerHigh,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = TvMetrics.SafeHorizontal,
                    end = TvMetrics.SafeHorizontal,
                    top = TvMetrics.ContentTop,
                    bottom = 22.dp,
                ),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = TvOnSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HeroPoster(item)
                Column {
                    HeroInfoPanel(item = item, series = series)
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
        )
        RatingsRow(
            rating = item.rating,
            views = item.views,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun HeroPoster(item: Item) {
    PosterImage(
        url = item.posters.medium.ifEmpty { item.posters.big },
        contentDescription = item.title,
        modifier = Modifier.width(HeroPosterWidth).height(HeroPosterHeight),
        shape = TvMetrics.PosterShape,
        accentColor = TvSurfaceContainerHigh,
        cacheKey = ImageCacheKeys.poster(item.type, item.id, PosterSize.Medium),
    )
}

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
                modifier = playback.playModifier
                    .onFocusChanged { if (it.hasFocus) playback.onPrefetchPlayback() },
            )
            playback.onOpenSeasons?.let { onOpenSeasons ->
                TvButton(
                    text = playback.seasonsLabel,
                    onClick = onOpenSeasons,
                    primary = false,
                    leadingIcon = Icons.AutoMirrored.Filled.ViewList,
                    modifier = playback.seasonsModifier,
                )
            }
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
            TvButton(
                text = if (hasAnyFolder) "В подборках" else "Добавить в подборку",
                onClick = playback.onOpenFolderPicker,
                primary = false,
                leadingIcon = if (hasAnyFolder) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                leadingIconTint = if (hasAnyFolder) TvError else null,
            )
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

@Composable
private fun RatingsRow(rating: ItemRating, views: Int, modifier: Modifier = Modifier) {
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

@Composable
private fun DetailsAbout(item: Item) {
    if (item.plot.isNotBlank()) {
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
                people.forEach { member ->
                    TvPersonChip(member = member, onClick = { onOpenPerson(member.name) })
                }
            }
        }
    }
}

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

private data class EpisodesSection(
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    val episodes: List<MediaTrack>,
    val resumeId: Int?,
    val selectedSeason: Int,
    val onSelectSeason: (Int) -> Unit,
    val onPlayEpisode: (season: Int, videoId: Int) -> Unit,
    val focus: TvScreenFocus,
)

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

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = TvOnSurface,
        modifier = modifier.padding(start = TvMetrics.SafeHorizontal, bottom = 12.dp),
    )
}

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
                    modifier = focus.item("episode:${episode.id}"),
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

@Stable
private class TvFolderPickerUi {
    var pickerOpen by mutableStateOf(false)
    var creatingFolder by mutableStateOf(false)
}

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
                    quality = qualityLabel(railItem.quality),
                ) { url, modifier ->
                    PosterImage(
                        url = url,
                        contentDescription = railItem.title,
                        modifier = modifier,
                        shape = TvMetrics.PosterShape,
                        accentColor = TvSurfaceContainerHigh,
                        cacheKey = ImageCacheKeys.poster(
                            railItem.type,
                            railItem.id,
                            PosterSize.Medium,
                        ),
                    )
                }
            }
        }
    }
}

private const val SIMILAR_SKELETON_COUNT = 6

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

private fun metaParts(item: Item, series: SeriesData?): List<String> = buildList {
    if (item.year > 0) add(item.year.toString())
    volumeLabel(item, series)?.let { add(it) }
    if (item.country.isNotBlank()) add(item.country)
    if (item.genres.isNotEmpty()) {
        add(item.genres.take(MAX_META_GENRES).joinToString(", ") { it.title })
    }
}

private fun playLabel(
    continuation: Continuation?,
    resume: MediaTrack?,
    target: MediaTrack?,
): String = when {
    continuation?.isActualContinuation == true -> buildString {
        append("Продолжить")
        continuation.savedPositionSeconds.takeIf { it > 0 }?.let { append(" с ${formatResumePosition(it)}") }
        resume?.let { append(" · ${episodeTag(it)}") }
    }
    target != null -> "Смотреть · ${episodeTag(target)}"
    else -> "Смотреть"
}

internal fun formatResumePosition(positionSeconds: Int): String {
    val totalMinutes = positionSeconds / SECONDS_IN_MINUTE
    val seconds = positionSeconds % SECONDS_IN_MINUTE
    if (totalMinutes < MINUTES_IN_HOUR) return "$totalMinutes:${seconds.twoDigits()}"

    val hours = totalMinutes / MINUTES_IN_HOUR
    val minutes = totalMinutes % MINUTES_IN_HOUR
    return "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(length = 2, padChar = '0')

private fun episodeTag(track: MediaTrack): String =
    if (track.seasonNumber > 0) "S${track.seasonNumber}E${track.number}" else "Серия ${track.number}"

private fun episodeMeta(episode: MediaTrack): String? = buildList {
    if (episode.title.isNotBlank()) add("Серия ${episode.number}")
    episode.durationSeconds.takeIf { it > 0 }?.let { add("${it / SECONDS_IN_MINUTE} мин") }
}.joinToString(" · ").ifBlank { null }
