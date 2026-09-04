package com.filmax.core.tv.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Единая сетка постеров — каталог, подборка, «Продолжить», «История»: везде одна и та же
 * сетка, чтобы не разъезжался ни размер карточки, ни ритм колонок между экранами.
 *
 * Число колонок следует из ширины карточки ([TvMetrics.CompactPosterWidth]) и доступного
 * места: при безопасной ширине экрана это ровно пять постеров в ряд. Карточки внутри обязаны
 * использовать тот же размер — иначе колонки и сами карточки разъедутся. Нижний safe-area
 * сохраняет подпись последнего ряда и рамку сфокусированной карточки внутри экрана.
 */
@Composable
fun TvPosterGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.FixedSize(TvMetrics.CompactPosterWidth),
        state = state,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
        verticalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
        contentPadding = PaddingValues(
            start = TvMetrics.SafeHorizontal,
            end = TvMetrics.SafeHorizontal,
            top = TvMetrics.FocusInset,
            bottom = TvMetrics.SafeVertical + TvMetrics.FocusInset,
        ),
        content = content,
    )
}
