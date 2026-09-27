package com.filmax.core.tv.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusCard(onClick = onClick, shape = TvMetrics.ChipShape, modifier = modifier) {
        Box(
            Modifier
                .clip(TvMetrics.ChipShape)
                .background(if (selected) TvAccent else TvSurfaceContainerHigh)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) TvOnAccent else TvOnSurfaceVariant,
            )
        }
    }
}

@Composable
fun TvRail(
    title: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = TvOnSurface,
            modifier = Modifier.padding(start = TvMetrics.SafeHorizontal, bottom = 12.dp),
        )
        LazyRow(
            modifier = Modifier.tvFocusGroup(),
            contentPadding = PaddingValues(
                start = TvMetrics.SafeHorizontal,
                end = TvMetrics.SafeHorizontal,
                top = TvMetrics.FocusInset,
                bottom = TvMetrics.FocusInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
            content = content,
        )
    }
}

@Composable
fun TvOverline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TvOnSurfaceVariant,
) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun TvMetaRow(
    parts: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        parts.filter { it.isNotBlank() }.forEach { part ->
            Text(part, style = MaterialTheme.typography.bodyLarge, color = TvOnSurfaceVariant)
        }
    }
}
