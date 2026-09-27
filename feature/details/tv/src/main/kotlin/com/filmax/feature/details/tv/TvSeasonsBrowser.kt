@file:OptIn(ExperimentalFoundationApi::class)

package com.filmax.feature.details.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.catalog.model.WatchStatus
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvFocusCard
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnAccent
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.core.tv.designsystem.TvOverline
import com.filmax.core.tv.designsystem.TvScreenFocus
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.tv.designsystem.TvSurfaceContainerHigh
import com.filmax.core.tv.designsystem.TvSurfaceContainerHighest
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.tv.designsystem.tvFocusGroup
import com.filmax.core.ui.components.PosterImage

private val ColumnGap = 8.dp

private val BrowserStart = 40.dp

private const val EPISODE_COLUMN_WIDTH_FACTOR = 2f

private val RowGap = 8.dp

private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 10.dp

private val ProgressBarHeight = 2.dp

private val PreviewMaxWidth = 480.dp

private const val PREVIEW_ASPECT_RATIO = 16f / 9f

private const val SELECTED_SUBTITLE_ALPHA = 0.7f

private const val SECONDS_IN_MINUTE = 60

@Stable
internal data class SeasonsBrowserContent(
    val title: String,
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    val resumeId: Int?,
    val resumePositionSeconds: Int,
)

@Composable
internal fun TvSeasonsBrowserDialog(
    content: SeasonsBrowserContent,
    onPlay: (season: Int, videoId: Int, resumePositionSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SeasonsBrowser(
            content = content,
            onPlay = { episode ->
                val position = if (episode.id == content.resumeId) content.resumePositionSeconds else 0
                onPlay(episode.seasonNumber, episode.number, position)
            },
        )
    }
}

@Composable
private fun SeasonsBrowser(content: SeasonsBrowserContent, onPlay: (MediaTrack) -> Unit) {
    val seasons = content.seasons
    val multiSeason = seasons.size > 1
    var seasonIndex by rememberSaveable { mutableIntStateOf(0) }
    var previewId by rememberSaveable { mutableStateOf<Int?>(null) }
    val episodes = seasons.getOrNull(seasonIndex)?.second.orEmpty()
    val preview = episodes.firstOrNull { it.id == previewId } ?: episodes.firstOrNull()

    val startAt = if (multiSeason) {
        seasonKey(seasons.first().first)
    } else {
        episodes.firstOrNull()?.let { episodeKey(it.id) }
    }
    val focus = rememberTvScreenFocus(startAt = startAt)
    val episodesColumn = remember { FocusRequester() }

    val rowStyle = rowLabelStyle()
    val seasonWidth = rememberColumnWidth(remember(seasons) { seasons.map { seasonLabel(it.first) } }, rowStyle)
    val episodeWidth = rememberColumnWidth(
        remember(seasons) { seasons.flatMap { it.second }.map { episodeLabel(it.number) }.distinct() },
        rowStyle,
    ) * EPISODE_COLUMN_WIDTH_FACTOR

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvSurface)
            .then(focus.containerModifier)
            .padding(
                start = BrowserStart,
                end = TvMetrics.SafeHorizontal,
                top = TvMetrics.SafeVertical,
                bottom = TvMetrics.SafeVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(ColumnGap),
    ) {
        if (multiSeason) {
            SeasonsColumn(
                seasons = seasons,
                selectedIndex = seasonIndex,
                width = seasonWidth,
                focus = focus,
                onSelect = { seasonIndex = it },
                onEnterEpisodes = { runCatching { episodesColumn.requestFocus() } },
            )
        }
        EpisodesColumn(
            spec = EpisodesColumnSpec(
                episodes = episodes,
                seasonIndex = seasonIndex,
                width = episodeWidth,
                resumeId = content.resumeId,
            ),
            focus = focus,
            columnRequester = episodesColumn,
            onPreview = { previewId = it },
            onPlay = onPlay,
        )
        PreviewPane(seriesTitle = content.title, episode = preview, modifier = Modifier.weight(1f))
    }
}

@Suppress("LongParameterList")
@Composable
private fun SeasonsColumn(
    seasons: List<Pair<Int, List<MediaTrack>>>,
    selectedIndex: Int,
    width: Dp,
    focus: TvScreenFocus,
    onSelect: (Int) -> Unit,
    onEnterEpisodes: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.width(width).fillMaxHeight().tvFocusGroup(),
        contentPadding = PaddingValues(TvMetrics.FocusInset),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        itemsIndexed(seasons, key = { _, season -> season.first }) { index, season ->
            BrowserRow(
                look = RowLook(label = seasonLabel(season.first), selected = index == selectedIndex),
                onClick = onEnterEpisodes,
                modifier = focus
                    .item(seasonKey(season.first))
                    .onFocusChanged { if (it.isFocused) onSelect(index) },
            )
        }
    }
}

private data class EpisodesColumnSpec(
    val episodes: List<MediaTrack>,
    val seasonIndex: Int,
    val width: Dp,
    val resumeId: Int?,
)

@Composable
private fun EpisodesColumn(
    spec: EpisodesColumnSpec,
    focus: TvScreenFocus,
    columnRequester: FocusRequester,
    onPreview: (Int) -> Unit,
    onPlay: (MediaTrack) -> Unit,
) {
    key(spec.seasonIndex) {
        LazyColumn(
            modifier = Modifier
                .width(spec.width)
                .fillMaxHeight()
                .focusRequester(columnRequester)
                .tvFocusGroup(),
            contentPadding = PaddingValues(TvMetrics.FocusInset),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            items(spec.episodes, key = { episode -> episode.id }) { episode ->
                BrowserRow(
                    look = RowLook(
                        label = episodeLabel(episode.number),
                        subtitle = episode.title.takeIf { it.isNotBlank() },
                        selected = episode.id == spec.resumeId,
                        progress = episodeProgress(episode),
                    ),
                    onClick = { onPlay(episode) },
                    modifier = focus
                        .item(episodeKey(episode.id))
                        .onFocusChanged { if (it.isFocused) onPreview(episode.id) },
                )
            }
        }
    }
}

private data class RowLook(
    val label: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val progress: Float = 0f,
)

@Composable
private fun BrowserRow(look: RowLook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    TvFocusCard(
        onClick = onClick,
        shape = TvMetrics.ButtonShape,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(TvMetrics.ButtonShape)
                .background(if (look.selected) TvAccent else TvSurfaceContainerHigh)
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        ) {
            Text(
                look.label,
                style = rowLabelStyle(),
                color = if (look.selected) TvOnAccent else TvOnSurface,
                maxLines = 1,
                softWrap = false,
            )
            look.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (look.selected) TvOnAccent.copy(alpha = SELECTED_SUBTITLE_ALPHA) else TvOnSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                    ),
                )
            }
            if (look.progress > 0f) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(ProgressBarHeight)
                        .background(
                            if (look.selected) {
                                TvOnAccent.copy(alpha = SELECTED_SUBTITLE_ALPHA)
                            } else {
                                TvSurfaceContainerHighest
                            },
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(look.progress)
                            .fillMaxHeight()
                            .background(if (look.selected) TvOnAccent else TvOnSurface),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(seriesTitle: String, episode: MediaTrack?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().padding(vertical = TvMetrics.FocusInset)) {
        TvOverline(seriesTitle)
        if (episode == null) return
        if (episode.thumbnail.isNotBlank()) {
            PosterImage(
                url = episode.thumbnail,
                contentDescription = episode.title,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .widthIn(max = PreviewMaxWidth)
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT_RATIO),
                shape = TvMetrics.CardShape,
                accentColor = TvSurfaceContainerHigh,
                cacheKey = ImageCacheKeys.episodeThumbnail(episode.id),
            )
        }
        Text(
            episodeTag(episode),
            style = MaterialTheme.typography.labelLarge,
            color = TvOnSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (episode.title.isNotBlank()) {
            Text(
                episode.title,
                style = MaterialTheme.typography.titleLarge,
                color = TvOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        previewMeta(episode)?.let { meta ->
            Text(
                meta,
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun rowLabelStyle(): TextStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)

@Composable
private fun rememberColumnWidth(labels: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, density) {
        val widestPx = labels.maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
        with(density) { widestPx.toDp() } + 1.dp + RowHorizontalPadding * 2 + TvMetrics.FocusInset * 2
    }
}

private fun seasonKey(number: Int) = "season:$number"
private fun episodeKey(id: Int) = "episode:$id"

private fun seasonLabel(number: Int): String = if (number > 0) "Сезон $number" else "Серии"

private fun episodeLabel(number: Int): String = "Серия $number"

private fun episodeTag(episode: MediaTrack): String = if (episode.seasonNumber > 0) {
    "${seasonLabel(episode.seasonNumber)} · ${episodeLabel(episode.number)}"
} else {
    episodeLabel(episode.number)
}

private fun episodeProgress(episode: MediaTrack): Float = when {
    episode.watchStatus == WatchStatus.Finished -> 1f
    episode.durationSeconds > 0 -> (episode.watchedSeconds.toFloat() / episode.durationSeconds).coerceIn(0f, 1f)
    else -> 0f
}

private fun previewMeta(episode: MediaTrack): String? = buildList {
    episode.durationSeconds.takeIf { it > 0 }?.let { add("${it / SECONDS_IN_MINUTE} мин") }
    when {
        episode.watchStatus == WatchStatus.Finished -> add("Досмотрена")
        episode.watchedSeconds > 0 -> add("Остановились на ${formatResumePosition(episode.watchedSeconds)}")
    }
}.joinToString(" · ").ifBlank { null }
