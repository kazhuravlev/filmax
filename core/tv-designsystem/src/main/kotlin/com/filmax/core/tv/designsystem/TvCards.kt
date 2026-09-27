package com.filmax.core.tv.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class TvCardSize(val width: Dp, val height: Dp) {
    Continue(TvMetrics.ContinueWidth, TvMetrics.ContinueHeight),
    Episode(TvMetrics.EpisodeWidth, TvMetrics.EpisodeHeight),
}

@Suppress("LongParameterList")
@Composable
fun TvPosterCard(
    title: String,
    meta: String?,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TvMetrics.PosterWidth,
    height: Dp = TvMetrics.PosterHeight,
    imdbRating: String? = null,
    kinopoiskRating: String? = null,
    advert: Boolean = false,
    quality: String? = null,
    focusRequester: FocusRequester? = null,
    badgeContent: (@Composable RowScope.() -> Unit)? = null,
    posterContent: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)

    Column(
        modifier = modifier
            .width(width)
            .onFocusChanged { focused = it.hasFocus }
            .graphicsLayer { alpha = dim.value },
    ) {
        TvFocusCard(
            onClick = onClick,
            shape = TvMetrics.PosterShape,
            focusRequester = focusRequester,
            modifier = Modifier.size(width = width, height = height),
        ) {
            Box(Modifier.fillMaxSize().clip(TvMetrics.PosterShape)) {
                posterContent(posterUrl, Modifier.fillMaxSize())
                val hasRating = imdbRating != null || kinopoiskRating != null
                if (hasRating || badgeContent != null) {
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        TvRatingPill(imdbRating = imdbRating, kinopoiskRating = kinopoiskRating)
                        badgeContent?.let { content ->
                            Row(modifier = if (hasRating) Modifier.padding(top = 6.dp) else Modifier) {
                                content()
                            }
                        }
                    }
                }
                if (advert) {
                    TvAdvertBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                if (quality != null) {
                    TvQualityBadge(quality, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
            }
        }
        TvCardCaption(title = title, meta = meta, focused = focused)
    }
}

@Suppress("LongParameterList")
@Composable
fun TvProgressCard(
    title: String,
    meta: String?,
    posterUrl: String,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: TvCardSize = TvCardSize.Continue,
    focusRequester: FocusRequester? = null,
    posterContent: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)

    Column(
        modifier = modifier
            .width(size.width)
            .onFocusChanged { focused = it.hasFocus }
            .graphicsLayer { alpha = dim.value },
    ) {
        TvFocusCard(
            onClick = onClick,
            shape = TvMetrics.CardShape,
            focusRequester = focusRequester,
            modifier = Modifier.size(width = size.width, height = size.height),
        ) {
            Box(Modifier.fillMaxSize().clip(TvMetrics.CardShape)) {
                posterContent(posterUrl, Modifier.fillMaxSize())
                TvProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        TvCardCaption(title = title, meta = meta, focused = focused)
    }
}

@Composable
fun TvProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(TvAccent.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(TvAccent),
        )
    }
}

@Composable
fun TvRatingPill(imdbRating: String?, kinopoiskRating: String?, modifier: Modifier = Modifier) {
    if (imdbRating == null && kinopoiskRating == null) return
    Row(
        modifier
            .clip(TvMetrics.PosterShape)
            .background(TvSurface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (imdbRating != null) TvRatingSource(icon = ImdbLogo, value = imdbRating)
        if (kinopoiskRating != null) TvRatingSource(icon = KinopoiskLogo, value = kinopoiskRating)
    }
}

@Composable
private fun TvRatingSource(icon: ImageVector, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TvOnSurface,
            modifier = Modifier.size(12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = TvOnSurface,
        )
    }
}

@Composable
fun TvAdvertBadge(modifier: Modifier = Modifier) {
    Text(
        "Реклама",
        style = MaterialTheme.typography.labelSmall,
        color = TvOnSurface,
        modifier = modifier
            .clip(TvMetrics.PosterShape)
            .background(TvSurface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun TvQualityBadge(quality: String, modifier: Modifier = Modifier) {
    Text(
        quality,
        style = MaterialTheme.typography.labelSmall,
        color = TvOnSurface,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(TvMetrics.PosterShape)
            .background(TvSurface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun TvCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(TvError)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = TvSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val CaptionTopGap = 16.dp

@Composable
private fun TvCardCaption(title: String, meta: String?, focused: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = TvOnSurface,
        maxLines = 1,
        softWrap = false,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = CaptionTopGap)
            .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
    )
    if (!meta.isNullOrBlank()) {
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = TvOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

fun posterMeta(type: String?, year: Int): String? {
    val parts = buildList {
        if (!type.isNullOrBlank()) add(type)
        if (year > 0) add(year.toString())
    }
    return parts.joinToString(" · ").ifBlank { null }
}

fun gridPosterMeta(year: Int, genre: String?): String? {
    val parts = buildList {
        if (year > 0) add(year.toString())
        if (!genre.isNullOrBlank()) add(genre)
    }
    return parts.joinToString(" · ").ifBlank { null }
}

fun ratingLabel(raw: String?): String? = ratingLabel(raw?.toDoubleOrNull())

fun ratingLabel(value: Double?): String? =
    value?.takeIf { it > 0 }
        ?.let { String.format(Locale.US, "%.1f", it) }

fun qualityLabel(heightPx: Int): String? = when {
    heightPx <= 0 -> null
    heightPx >= QUALITY_4K_HEIGHT -> "4K"
    heightPx >= QUALITY_FHD_HEIGHT -> "FHD"
    heightPx >= QUALITY_HD_HEIGHT -> "HD"
    else -> "SD"
}

private const val QUALITY_HD_HEIGHT = 720
private const val QUALITY_FHD_HEIGHT = 1080
private const val QUALITY_4K_HEIGHT = 2160
