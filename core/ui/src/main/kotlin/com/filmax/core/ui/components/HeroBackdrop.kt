package com.filmax.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.PosterSize
import com.filmax.core.domain.catalog.model.Item

@Composable
fun HeroBackdrop(
    item: Item,
    scrims: List<Brush>,
    modifier: Modifier = Modifier,
    posterUrl: String = item.posters.big,
    accentColor: Color = HeroBackdropAccent,
) {
    val size = if (posterUrl == item.posters.wide) PosterSize.Wall else PosterSize.Big
    Box(modifier) {
        PosterImage(
            url = posterUrl,
            contentDescription = item.title,
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(0.dp),
            accentColor = accentColor,
            cacheKey = ImageCacheKeys.poster(item.type, item.id, size),
        )
        scrims.forEach { brush ->
            Box(Modifier.matchParentSize().background(brush))
        }
    }
}

val HeroBackdropAccent: Color = Color(0xFFB4305A)
