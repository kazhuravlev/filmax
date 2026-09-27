package com.filmax.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.filmax.core.designsystem.ShapePoster
import com.filmax.core.ui.cache.CacheableImage
import com.filmax.core.ui.cache.proxiedImageUrl

val LocalImageProxyEnabled = compositionLocalOf { false }

@Suppress("LongParameterList")
@Composable
fun PosterImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ShapePoster,
    accentColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    cacheKey: String? = null,
) {
    val placeholder = remember(accentColor) { posterPlaceholderBrush(accentColor) }
    val proxyEnabled = LocalImageProxyEnabled.current
    val effectiveUrl = remember(url, proxyEnabled) { proxiedImageUrl(url, proxyEnabled) }
    val model = remember(cacheKey, effectiveUrl) {
        if (cacheKey != null) CacheableImage(key = cacheKey, url = effectiveUrl) else effectiveUrl
    }
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(shape).background(placeholder), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onState = { state -> failed = state is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(
                Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(BrokenPosterIconSize),
            )
        }
    }
}

private val BrokenPosterIconSize = 28.dp

private val PlaceholderBottomColor = Color(0xFF0F0F0F)

private const val PLACEHOLDER_GRADIENT_END_X = 200f
private const val PLACEHOLDER_GRADIENT_END_Y = 600f

private fun posterPlaceholderBrush(accentColor: Color): Brush =
    Brush.linearGradient(
        colors = listOf(accentColor.copy(alpha = 0.7f), PlaceholderBottomColor),
        start = Offset(0f, 0f),
        end = Offset(PLACEHOLDER_GRADIENT_END_X, PLACEHOLDER_GRADIENT_END_Y),
    )

@Composable
fun GradientPosterPlaceholder(accentColor: Color, modifier: Modifier = Modifier) {
    val placeholder = remember(accentColor) { posterPlaceholderBrush(accentColor) }
    Box(modifier = modifier.background(placeholder))
}
