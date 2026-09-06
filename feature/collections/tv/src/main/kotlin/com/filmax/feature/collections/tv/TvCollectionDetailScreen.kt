package com.filmax.feature.collections.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.tv.designsystem.ScrollToTopOnNavFocus
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.core.tv.designsystem.TvPosterCard
import com.filmax.core.tv.designsystem.TvPosterGrid
import com.filmax.core.tv.designsystem.TvServerRetryNotification
import com.filmax.core.tv.designsystem.TvSurfaceContainer
import com.filmax.core.tv.designsystem.posterMeta
import com.filmax.core.tv.designsystem.ratingLabel
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.ui.components.PosterImage
import com.filmax.feature.collections.common.CollectionDetailEvent
import com.filmax.feature.collections.common.CollectionDetailScreenModel
import org.koin.androidx.compose.koinViewModel

/** За сколько хвостовых рядов сетки до конца просить следующую страницу подборки. */
private const val LOAD_MORE_TAIL = 3

/**
 * TV-экран одной подборки: сетка постеров поверх общего [CollectionDetailScreenModel]
 * (itemId берётся из маршрута через SavedStateHandle).
 */
@Composable
fun TvCollectionDetailScreen(
    title: String,
    onOpenItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
    screenModel: CollectionDetailScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()
    val retryNotice by screenModel.collectServerRetryNoticeAsState()
    val focus = rememberTvScreenFocus()
    val gridState = rememberLazyGridState()
    ScrollToTopOnNavFocus(gridState)

    // Догрузка следующей страницы: подборки бывают крупнее одной страницы, а грид без хвостового
    // детектора обрезал бы их молча. derivedStateOf пересчитывается без рекомпозиции, дёргает её
    // только смена «пора/не пора»; повторные вызовы гасит идемпотентность модели.
    val loadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - LOAD_MORE_TAIL
        }
    }
    LaunchedEffect(loadMore) { if (loadMore) screenModel.dispatch(CollectionDetailEvent.LoadMore) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize().padding(top = TvMetrics.ContentTop)) {
            CollectionHeader(title)

            when {
                // Полноэкранный спиннер — только когда показать совсем нечего: с кэшем
                // (см. CollectionItemsCache) сетка красится сразу, а ревалидация идёт тихо.
                state.loading && state.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                else -> TvPosterGrid(
                    state = gridState,
                    modifier = focus.containerModifier,
                ) {
                    items(state.items, key = { item -> item.id }) { item ->
                        CollectionPoster(
                            item = item,
                            modifier = focus.item("collection:${item.id}"),
                            onClick = { onOpenItem(item.id) },
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "loading_more", span = { GridItemSpan(maxLineSpan) }) {
                            CollectionLoadingMore()
                        }
                    }
                }
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

/** Заголовок подборки над сеткой. */
@Composable
private fun CollectionHeader(title: String) {
    Column(Modifier.padding(horizontal = TvMetrics.SafeHorizontal)) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Подборка",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )
    }
}

/** Хвостовой индикатор догрузки страницы — невысокий, чтобы не дёргать сетку. */
@Composable
private fun CollectionLoadingMore() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TvOnSurfaceVariant, modifier = Modifier.size(28.dp))
    }
}

/** Карточка тайтла — общая для всего ТВ-приложения (ряды Главной, каталог, фильмография). */
@Composable
private fun CollectionPoster(item: Item, modifier: Modifier, onClick: () -> Unit) {
    TvPosterCard(
        title = item.title,
        meta = posterMeta(item.type.label(), item.year),
        posterUrl = item.posters.medium.ifEmpty { item.posters.big },
        onClick = onClick,
        width = TvMetrics.CompactPosterWidth,
        height = TvMetrics.CompactPosterHeight,
        imdbRating = ratingLabel(item.rating.imdb),
        kinopoiskRating = ratingLabel(item.rating.kinopoisk),
        advert = item.advert,
        modifier = modifier,
    ) { url, posterModifier ->
        PosterImage(
            url = url,
            contentDescription = item.title,
            modifier = posterModifier,
            shape = TvMetrics.PosterShape,
            // Плейсхолдер-градиент по умолчанию цветной; в монохроме под постером — поверхность.
            accentColor = TvSurfaceContainer,
            cacheKey = ImageCacheKeys.poster(item.type.apiValue, item.id, ImageCacheKeys.SIZE_MEDIUM),
        )
    }
}

/** Подпись типа в мете карточки — та же, что в рядах Главной и в каталоге. */
private fun ItemType.label(): String = when (this) {
    ItemType.MOVIE -> "Фильм"
    ItemType.SERIES -> "Сериал"
    ItemType.ANIME -> "Аниме"
    ItemType.DOCUMENTARY -> "Документальный"
    ItemType.TV -> "ТВ"
}
