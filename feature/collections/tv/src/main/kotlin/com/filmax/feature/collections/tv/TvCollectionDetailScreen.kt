package com.filmax.feature.collections.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvPosterCard
import com.filmax.core.tv.designsystem.TvPosterGrid
import com.filmax.core.tv.designsystem.TvSurfaceContainer
import com.filmax.core.tv.designsystem.posterMeta
import com.filmax.core.tv.designsystem.ratingLabel
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.ui.components.PosterImage
import com.filmax.feature.collections.common.CollectionDetailScreenModel
import org.koin.androidx.compose.koinViewModel

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
    val focus = rememberTvScreenFocus()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = TvMetrics.ContentTop),
    ) {
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

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            else -> TvPosterGrid(
                modifier = focus.containerModifier,
            ) {
                items(state.items, key = { item -> item.id }) { item ->
                    CollectionPoster(
                        item = item,
                        modifier = focus.item("collection:${item.id}"),
                        onClick = { onOpenItem(item.id) },
                    )
                }
            }
        }
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
        rating = ratingLabel(item.rating.external),
        modifier = modifier,
    ) { url, posterModifier ->
        PosterImage(
            url = url,
            contentDescription = item.title,
            modifier = posterModifier,
            shape = TvMetrics.PosterShape,
            // Плейсхолдер-градиент по умолчанию цветной; в монохроме под постером — поверхность.
            accentColor = TvSurfaceContainer,
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
