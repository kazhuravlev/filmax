package com.filmax.feature.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.PosterSize
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.tv.designsystem.RefreshOnTopNavReselect
import com.filmax.core.tv.designsystem.ScrollToTopOnNavFocus
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvButton
import com.filmax.core.tv.designsystem.TvErrorState
import com.filmax.core.tv.designsystem.TvMetaRow
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOverline
import com.filmax.core.tv.designsystem.TvPosterCard
import com.filmax.core.tv.designsystem.TvProgressCard
import com.filmax.core.tv.designsystem.TvRail
import com.filmax.core.tv.designsystem.TvScreenFocus
import com.filmax.core.tv.designsystem.TvServerRetryNotification
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.tv.designsystem.TvSurfaceContainerHigh
import com.filmax.core.tv.designsystem.posterMeta
import com.filmax.core.tv.designsystem.qualityLabel
import com.filmax.core.tv.designsystem.ratingLabel
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.ui.components.GradientPosterPlaceholder
import com.filmax.core.ui.components.PosterImage
import com.filmax.core.ui.components.appErrorText
import com.filmax.core.ui.components.continueMeta
import com.filmax.core.ui.components.durationLabel
import com.filmax.core.ui.components.posterUrl
import com.filmax.feature.home.common.HomeEvent
import com.filmax.feature.home.common.HomeRow
import com.filmax.feature.home.common.HomeScreenModel
import com.filmax.feature.home.common.HomeState
import org.koin.androidx.compose.koinViewModel

@Composable
fun TvHomeScreen(
    onOpenItem: (Int) -> Unit,
    onPlay: (itemId: Int, season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    onOpenCollection: (id: Int, title: String) -> Unit,
    modifier: Modifier = Modifier,
    screenModel: HomeScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()
    RefreshOnTopNavReselect { screenModel.dispatch(HomeEvent.Load) }
    val offline by screenModel.collectOfflineBannerAsState()
    val appError by screenModel.collectErrorAsState()
    val retryNotice by screenModel.collectServerRetryNoticeAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvSurface),
    ) {
        val error = appError
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvAccent)
            }

            error != null && state.isEmpty -> {
                val text = appErrorText(error)
                TvErrorState(
                    title = text.title,
                    message = text.message,
                    onRetry = screenModel::retry,
                )
            }

            else -> {
                val actions = remember(screenModel, onOpenItem, onPlay, onOpenCollection) {
                    TvHomeActions(
                        onOpenItem = onOpenItem,
                        onPlay = onPlay,
                        onOpenCollection = onOpenCollection,
                        onReload = { screenModel.dispatch(HomeEvent.Load) },
                        onLoadMoreRow = { id -> screenModel.dispatch(HomeEvent.LoadMoreRow(id)) },
                    )
                }
                TvHomeContent(
                    state = state,
                    offline = offline,
                    actions = actions,
                )
            }
        }
        TvServerRetryNotification(
            visible = retryNotice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = TvMetrics.SafeVertical),
        )
    }
}

private data class TvHomeActions(
    val onOpenItem: (Int) -> Unit,
    val onPlay: (itemId: Int, season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    val onOpenCollection: (id: Int, title: String) -> Unit,
    val onReload: () -> Unit,
    val onLoadMoreRow: (String) -> Unit,
)

@Composable
private fun TvHomeContent(
    state: HomeState,
    offline: Boolean,
    actions: TvHomeActions,
) {
    val listState = rememberLazyListState()
    ScrollToTopOnNavFocus(listState)
    val focus = rememberTvScreenFocus()

    val filteredCollections = remember(state.rows) {
        state.rows.filterIsInstance<HomeRow.Collections>()
            .associate { row -> row.id to row.paging.items.filter { it.posterUrl() != null } }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().then(focus.containerModifier),
        contentPadding = PaddingValues(
            top = TvMetrics.ContentTop,
            bottom = TvMetrics.SafeVertical + TvMetrics.FocusInset,
        ),
        verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
    ) {
        if (offline) {
            item(key = "offline") { TvOfflineBanner(onReload = actions.onReload) }
        }
        val hero = state.hero
        when {
            hero != null -> item(key = "hero") {
                TvHero(
                    item = hero,
                    onPlay = { actions.onPlay(hero.id, NO_SEASON, NO_VIDEO_ID, NO_RESUME_POSITION) },
                    onDetails = { actions.onOpenItem(hero.id) },
                    focus = focus,
                )
            }
            state.heroLoading -> item(key = "hero-skeleton") { TvHeroSkeleton() }
        }
        tvRails(state = state, actions = actions, focus = focus, filteredCollections = filteredCollections)
    }
}

private fun LazyListScope.tvRails(
    state: HomeState,
    actions: TvHomeActions,
    focus: TvScreenFocus,
    filteredCollections: Map<String, List<Collection>>,
) {
    state.rows.forEach { row ->
        if (row.isEmpty) return@forEach
        if (!row.hasContent) {
            item(key = "${row.id}-skeleton") { TvRailSkeleton(title = row.title) }
            return@forEach
        }
        when (row) {
            is HomeRow.Continue -> tvContinueRail(row, actions, focus)
            is HomeRow.Titles -> tvPosterRail(row, actions, focus)
            is HomeRow.Collections -> tvCollectionsRail(row, actions, focus, filteredCollections[row.id].orEmpty())
        }
    }
}

private val HomeRow.hasContent: Boolean
    get() = when (this) {
        is HomeRow.Continue -> entries.isNotEmpty()
        is HomeRow.Titles -> paging.items.isNotEmpty()
        is HomeRow.Collections -> paging.items.isNotEmpty()
    }

private fun LazyListScope.tvContinueRail(row: HomeRow.Continue, actions: TvHomeActions, focus: TvScreenFocus) {
    val onPlay = actions.onPlay
    item(key = row.id) {
        TvRail(title = row.title) {
            items(row.entries, key = { entry -> entry.itemId }) { entry ->
                TvContinueCard(
                    history = entry,
                    modifier = focus.item(returnKey(row.id, entry.itemId)),
                    onClick = {
                        onPlay(entry.itemId, entry.season, entry.videoId, entry.savedPositionSeconds)
                    },
                )
            }
        }
    }
}

private fun LazyListScope.tvPosterRail(row: HomeRow.Titles, actions: TvHomeActions, focus: TvScreenFocus) {
    val railItems = row.paging.items
    item(key = row.id) {
        TvRail(title = row.title) {
            itemsIndexed(railItems, key = { _, catalogItem -> catalogItem.id }) { index, catalogItem ->
                if (index == railItems.lastIndex) {
                    LaunchedEffect(railItems.size) { actions.onLoadMoreRow(row.id) }
                }
                TvHomePosterCard(
                    item = catalogItem,
                    onClick = { actions.onOpenItem(catalogItem.id) },
                    modifier = focus.item(returnKey(row.id, catalogItem.id)),
                )
            }
            if (row.paging.loadingMore) {
                item(key = "${row.id}-loading-more") {
                    TvRailLoadingMoreCard(
                        width = TvMetrics.PosterWidth,
                        height = TvMetrics.PosterHeight,
                        shape = TvMetrics.PosterShape,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.tvCollectionsRail(
    row: HomeRow.Collections,
    actions: TvHomeActions,
    focus: TvScreenFocus,
    withPoster: List<Collection>,
) {
    if (withPoster.isEmpty()) return
    item(key = row.id) {
        TvRail(title = row.title) {
            itemsIndexed(withPoster, key = { _, collection -> collection.id }) { index, collection ->
                if (index == withPoster.lastIndex) {
                    LaunchedEffect(withPoster.size) { actions.onLoadMoreRow(row.id) }
                }
                TvCollectionCard(
                    collection = collection,
                    onClick = { actions.onOpenCollection(collection.id, collection.title) },
                    modifier = focus.item(returnKey(row.id, collection.id)),
                )
            }
            if (row.paging.loadingMore) {
                item(key = "${row.id}-loading-more") {
                    TvRailLoadingMoreCard(
                        width = TvMetrics.PosterWidth,
                        height = TvMetrics.PosterHeight,
                        shape = TvMetrics.PosterShape,
                    )
                }
            }
        }
    }
}

private const val SKELETON_CARD_COUNT = 6

@Composable
private fun TvRailSkeleton(title: String) {
    TvRail(title = title) {
        items(SKELETON_CARD_COUNT) {
            GradientPosterPlaceholder(
                accentColor = TvSurfaceContainerHigh,
                modifier = Modifier
                    .size(width = TvMetrics.PosterWidth, height = TvMetrics.PosterHeight)
                    .clip(TvMetrics.PosterShape),
            )
        }
    }
}

@Composable
private fun TvRailLoadingMoreCard(width: Dp, height: Dp, shape: Shape) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(TvSurfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = TvAccent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun TvHeroSkeleton() {
    GradientPosterPlaceholder(
        accentColor = TvSurfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .height(TvMetrics.HeroHeight),
    )
}

// ── Hero ──────────────────────────────────────────────────────────────────

private val HeroContentWidth = 520.dp
private val HeroContentBottom = 26.dp

private val HeroScrimHorizontal = Brush.horizontalGradient(
    0.00f to TvSurface.copy(alpha = 0.94f),
    0.17f to TvSurface.copy(alpha = 0.89f),
    0.34f to TvSurface.copy(alpha = 0.82f),
    0.48f to TvSurface.copy(alpha = 0.60f),
    0.62f to TvSurface.copy(alpha = 0.35f),
    0.80f to TvSurface.copy(alpha = 0.16f),
    1.00f to TvSurface.copy(alpha = 0.05f),
)

private val HeroScrimVertical = Brush.verticalGradient(
    0.00f to TvSurface.copy(alpha = 0f),
    0.60f to TvSurface.copy(alpha = 0f),
    0.72f to TvSurface.copy(alpha = 0.22f),
    0.84f to TvSurface.copy(alpha = 0.52f),
    0.92f to TvSurface.copy(alpha = 0.74f),
    1.00f to TvSurface.copy(alpha = 0.90f),
)

@Composable
private fun TvHero(
    item: Item,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    focus: TvScreenFocus,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvMetrics.HeroHeight),
    ) {
        PosterImage(
            url = item.posters.wide ?: item.posters.big,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            accentColor = TvSurfaceContainerHigh,
            cacheKey = ImageCacheKeys.poster(
                item.type,
                item.id,
                if (item.posters.wide != null) PosterSize.Wall else PosterSize.Big,
            ),
        )
        Box(Modifier.fillMaxSize().background(HeroScrimHorizontal))
        Box(Modifier.fillMaxSize().background(HeroScrimVertical))

        TvHeroOverlay(item = item, onPlay = onPlay, onDetails = onDetails, focus = focus)
    }
}

@Composable
private fun BoxScope.TvHeroOverlay(
    item: Item,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    focus: TvScreenFocus,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = TvMetrics.SafeHorizontal, bottom = HeroContentBottom)
            .width(HeroContentWidth),
    ) {
        TvOverline("Выбор редакции")
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.displayMedium,
            color = TvOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        TvHeroMeta(item)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Смотреть", onClick = onPlay, modifier = focus.item(HERO_PLAY_KEY))
            TvButton(
                "Подробнее",
                onClick = onDetails,
                modifier = focus.item(HERO_DETAILS_KEY),
                primary = false,
            )
        }
    }
}

@Composable
private fun TvHeroMeta(item: Item) {
    val parts = remember(item) {
        buildList {
            item.genres.take(MAX_HERO_GENRES).joinToString(" · ") { it.title }
                .takeIf { it.isNotBlank() }
                ?.let { add(it) }
            if (item.year > 0) add(item.year.toString())
            item.duration.averageMinutes?.toInt()?.takeIf { it > 0 }?.let { add(durationLabel(it)) }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ratingLabel(item.rating.kinopoisk)?.let { rating ->
            Text(
                "$rating КП",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TvOnSurface,
            )
        }
        if (parts.isNotEmpty()) TvMetaRow(parts)
    }
}

@Composable
private fun TvHomePosterCard(item: Item, onClick: () -> Unit, modifier: Modifier) {
    TvPosterCard(
        modifier = modifier,
        title = item.title,
        meta = posterMeta(type = item.type.label(), year = item.year),
        posterUrl = item.posters.medium.ifEmpty { item.posters.big },
        imdbRating = ratingLabel(item.rating.imdb),
        kinopoiskRating = ratingLabel(item.rating.kinopoisk),
        advert = item.advert,
        quality = qualityLabel(item.quality),
        onClick = onClick,
        posterContent = { url, posterModifier ->
            PosterImage(
                url = url,
                contentDescription = item.title,
                modifier = posterModifier,
                shape = TvMetrics.PosterShape,
                accentColor = TvSurfaceContainerHigh,
                cacheKey = ImageCacheKeys.poster(item.type, item.id, PosterSize.Medium),
            )
        },
    )
}

@Composable
private fun TvCollectionCard(collection: Collection, onClick: () -> Unit, modifier: Modifier) {
    TvPosterCard(
        modifier = modifier,
        title = collection.title,
        meta = null,
        posterUrl = collection.posterUrl().orEmpty(),
        onClick = onClick,
        posterContent = { url, posterModifier ->
            PosterImage(
                url = url,
                contentDescription = collection.title,
                modifier = posterModifier,
                shape = TvMetrics.PosterShape,
                accentColor = TvSurfaceContainerHigh,
                cacheKey = ImageCacheKeys.collectionPoster(collection.id, PosterSize.Medium),
            )
        },
    )
}

@Composable
private fun TvContinueCard(history: Continuation, onClick: () -> Unit, modifier: Modifier) {
    TvProgressCard(
        modifier = modifier,
        title = history.title,
        meta = continueMeta(history.progress),
        posterUrl = history.wideOrPoster,
        progress = history.progress.fraction,
        onClick = onClick,
        posterContent = { url, posterModifier ->
            PosterImage(
                url = url,
                contentDescription = history.title,
                modifier = posterModifier,
                shape = TvMetrics.CardShape,
                accentColor = TvSurfaceContainerHigh,
                cacheKey = ImageCacheKeys.poster(history.item.type, history.itemId, PosterSize.Wall),
            )
        },
    )
}

private const val NO_RESUME_POSITION = 0

@Composable
private fun TvOfflineBanner(onReload: () -> Unit) {
    TvButton(
        text = "Нет сети — показаны сохранённые данные. Нажмите, чтобы повторить",
        onClick = onReload,
        primary = false,
        leadingIcon = Icons.Filled.CloudOff,
        modifier = Modifier.padding(horizontal = TvMetrics.SafeHorizontal),
    )
}

private fun returnKey(row: String, itemId: Int): String = "$row:$itemId"

private const val HERO_PLAY_KEY = "hero:play"
private const val HERO_DETAILS_KEY = "hero:details"

private const val NO_VIDEO_ID = -1

private const val NO_SEASON = -1

private const val MAX_HERO_GENRES = 3

private fun ItemType.label(): String = when (this) {
    ItemType.MOVIE -> "Фильм"
    ItemType.SERIES -> "Сериал"
    ItemType.ANIME -> "Аниме"
    ItemType.DOCUMENTARY -> "Документальный"
    ItemType.TV -> "ТВ"
}
