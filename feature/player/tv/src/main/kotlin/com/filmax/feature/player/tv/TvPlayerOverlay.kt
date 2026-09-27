package com.filmax.feature.player.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvFocus
import com.filmax.core.tv.designsystem.TvFocusCard
import com.filmax.core.tv.designsystem.TvFocusHalo
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnAccent
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceDim
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.core.tv.designsystem.TvOverline
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.tv.designsystem.TvSurfaceContainerHighest
import com.filmax.feature.player.common.NO_VALUE_CAPTION
import com.filmax.feature.player.common.formatPlayerTime
import kotlin.math.roundToInt

internal fun Modifier.playerPanel(): Modifier = this
    .clip(TvMetrics.PanelShape)
    .background(PlayerControlBackground)
    .border(1.dp, TvSurfaceContainerHighest.copy(alpha = PANEL_ALPHA), TvMetrics.PanelShape)

@Composable
internal fun CircleBox(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier
            .requiredSize(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun PlayerOverlay(
    ui: TvPlayerUiState,
    menu: PlayerActions,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to TvSurface.copy(alpha = 0.45f),
                    0.45f to TvSurface.copy(alpha = 0f),
                    0.70f to TvSurface.copy(alpha = 0.25f),
                    1f to TvSurface.copy(alpha = 0.85f),
                )
            ),
    ) {
        PlayerTopBar(title = title, subtitle = subtitle, modifier = Modifier.align(Alignment.TopStart))

        ui.seekLabel?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.headlineMedium.copy(
                    shadow = Shadow(color = TvFocusHalo, offset = Offset(0f, 2f), blurRadius = 20f),
                ),
                color = TvAccent,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        PlayerTransport(ui = ui, menu = menu, modifier = Modifier.align(Alignment.BottomCenter))

        ui.submenu?.let { category ->
            SettingsPopover(
                action = category,
                menu = menu,
                cursor = ui.submenuCursor,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (ui.episodesOpen) {
            menu.episodes?.let { panel ->
                EpisodesPanel(
                    panel = panel,
                    seasonCursor = ui.episodesSeasonCursor,
                    episodeCursor = ui.episodesCursor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = 20.dp),
                )
            }
        }
    }
}

@Composable
internal fun AutoNextCard(label: String, seconds: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = AutoNextCardMaxWidth)
            .playerPanel()
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = TvOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Автостарт через $seconds с · OK — сейчас · «Назад» — отмена",
            style = MaterialTheme.typography.labelSmall,
            color = TvOnSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun SubscriptionCard(modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = SubscriptionCardMaxWidth)
            .playerPanel()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Нужна подписка",
            style = MaterialTheme.typography.titleMedium,
            color = TvOnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            "Просмотр доступен только с активной подпиской — оформите её в аккаунте kino.watch",
            style = MaterialTheme.typography.bodySmall,
            color = TvOnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PlayerTopBar(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = TvMetrics.SafeHorizontal)
            .padding(top = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = TvOnSurface)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerTransport(ui: TvPlayerUiState, menu: PlayerActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = TvMetrics.SafeHorizontal)
            .padding(bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Scrubber(
            positionMs = if (ui.isScrubbing) ui.scrubTargetMs else ui.positionMs,
            durationMs = ui.durationMs,
            active = ui.mode == PlayerMode.Progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TransportHints(
                isPlaying = ui.isPlaying,
                focused = ui.mode == PlayerMode.Transport,
                episodeNav = if (menu.hasPreviousEpisode || menu.hasNextEpisode) {
                    EpisodeNavHints(
                        hasPrevious = menu.hasPreviousEpisode,
                        hasNext = menu.hasNextEpisode,
                        active = ui.mode == PlayerMode.EpisodeNav,
                        selected = ui.episodeNavArrow,
                    )
                } else {
                    null
                },
            )
            SettingsGrid(ui = ui, menu = menu)
        }
    }
}

@Composable
private fun Scrubber(positionMs: Long, durationMs: Long, active: Boolean, modifier: Modifier = Modifier) {
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            formatPlayerTime(positionMs),
            style = timeStyle,
            color = TvOnSurface,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        ScrubTrack(
            fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            active = active,
        )
        Text(
            formatPlayerTime(durationMs),
            style = timeStyle,
            color = TvOnSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp),
        )
    }
}

@Composable
private fun RowScope.ScrubTrack(fraction: Float, active: Boolean) {
    val trackHeight by animateDpAsState(if (active) ScrubTrackHeightActive else ScrubTrackHeight, label = "scrubTrack")
    val thumbSize by animateDpAsState(if (active) ScrubThumbActive else ScrubThumb, label = "scrubThumb")
    val haloSize by animateDpAsState(if (active) ScrubThumbHaloActive else ScrubThumbHalo, label = "scrubHalo")
    BoxWithConstraints(
        Modifier
            .weight(1f)
            .height(ScrubThumbHaloActive + ScrubFocusRingExtra),
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxWidth.toPx() }

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(TvAccent.copy(alpha = 0.2f)),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(CircleShape)
                .background(TvAccent),
        )
        val ringSize = haloSize + ScrubFocusRingExtra
        val ringPx = with(density) { ringSize.toPx() }
        CircleBox(
            size = ringSize,
            color = if (active) TvFocus else TvFocus.copy(alpha = 0f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((fraction * trackPx - ringPx / 2f).roundToInt(), 0) },
        ) {
            CircleBox(size = haloSize, color = TvFocusHalo) {
                CircleBox(size = thumbSize, color = TvAccent)
            }
        }
    }
}

internal data class EpisodeNavHints(
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val active: Boolean,
    val selected: EpisodeNavArrow,
)

@Composable
private fun TransportHints(
    isPlaying: Boolean,
    focused: Boolean,
    episodeNav: EpisodeNavHints?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircleBox(size = PauseFocusOuter, color = if (focused) TvFocus else TvFocus.copy(alpha = 0f)) {
                CircleBox(size = PauseFocusInner, color = if (focused) TvFocusHalo else TvFocusHalo.copy(alpha = 0f)) {
                    CircleBox(size = PauseButtonSize, color = TvAccent) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = TvOnAccent,
                            modifier = Modifier.size(PauseIconSize),
                        )
                    }
                }
            }
            if (episodeNav != null) {
                Row(
                    modifier = Modifier.padding(top = EpisodeNavTopGap),
                    horizontalArrangement = Arrangement.spacedBy(EpisodeNavGap),
                ) {
                    EpisodeNavButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Предыдущая серия",
                        enabled = episodeNav.hasPrevious,
                        focused = episodeNav.active && episodeNav.selected == EpisodeNavArrow.Previous,
                    )
                    EpisodeNavButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Следующая серия",
                        enabled = episodeNav.hasNext,
                        focused = episodeNav.active && episodeNav.selected == EpisodeNavArrow.Next,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeNavButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    focused: Boolean,
) {
    CircleBox(
        size = EpisodeNavFocusOuter,
        color = if (focused) TvFocus else TvFocus.copy(alpha = 0f),
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
    ) {
        CircleBox(size = EpisodeNavFocusInner, color = if (focused) TvFocusHalo else TvFocusHalo.copy(alpha = 0f)) {
            CircleBox(size = EpisodeNavButtonSize, color = PlayerControlBackground) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = TvOnSurface,
                    modifier = Modifier.size(EpisodeNavIconSize),
                )
            }
        }
    }
}

@Composable
private fun SettingsGrid(ui: TvPlayerUiState, menu: PlayerActions, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(SettingsGridGap),
        verticalAlignment = Alignment.Top,
    ) {
        menu.items.chunked(SETTINGS_GRID_ROWS).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(SettingsGridGap)) {
                column.forEach { action ->
                    val index = menu.items.indexOf(action)
                    SettingsButton(
                        action = action,
                        value = action.buttonValue(menu),
                        selected = ui.mode == PlayerMode.Settings && index == ui.settingsCursor,
                        enabled = menu.isEnabled(action),
                        onClick = {
                            ui.settingsCursor = index
                            ui.activate(action, menu)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsButton(
    action: SettingsAction,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusCard(
        onClick = { if (enabled) onClick() },
        shape = TvMetrics.ChipShape,
        modifier = Modifier
            .width(SettingsButtonWidth)
            .heightIn(min = SettingsButtonHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .focusProperties { canFocus = false },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsButtonHeight)
                .clip(TvMetrics.ChipShape)
                .background(if (selected) TvAccent else PlayerControlBackground)
                .padding(horizontal = 10.dp, vertical = SettingsButtonVerticalPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                action.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) TvOnAccent else TvOnSurfaceDim,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) TvOnAccent else TvOnSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun SettingsAction.buttonValue(menu: PlayerActions): String = when (this) {
    SettingsAction.Audio, SettingsAction.Subtitle ->
        menu.selected(this)?.shortValue?.uppercase() ?: NO_VALUE_CAPTION
    SettingsAction.Preset, SettingsAction.Quality, SettingsAction.Speed ->
        menu.selected(this)?.label ?: NO_VALUE_CAPTION
    SettingsAction.Episodes -> "Выбрать"
    SettingsAction.NextEpisode -> "Далее"
}

@Composable
internal fun SettingsPopover(
    action: SettingsAction,
    menu: PlayerActions,
    cursor: Int,
    modifier: Modifier = Modifier,
) {
    val options = menu.options(action)
    val current = menu.selected(action)
    val listState = rememberLazyListState()
    val rowHeightPx = with(LocalDensity.current) { PopoverRowHeight.roundToPx() }
    LaunchedEffect(listState, cursor) { listState.keepCursorVisible(cursor, rowHeightPx) }
    Column(
        modifier
            .fillMaxWidth(POPOVER_WIDTH_FRACTION)
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * POPOVER_MAX_HEIGHT_FRACTION)
            .playerPanel()
            .padding(12.dp),
    ) {
        TvOverline(action.label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
            items(options.size) { index ->
                val option = options[index]
                SettingsRow(label = option.label, highlighted = index == cursor, current = option == current)
            }
        }
    }
}

private suspend fun LazyListState.keepCursorVisible(cursor: Int, rowHeightPx: Int) {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) {
        scrollToItem(cursor)
        return
    }
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val row = info.visibleItemsInfo.firstOrNull { it.index == cursor }
    when {
        row == null && cursor < info.visibleItemsInfo.first().index -> animateScrollToItem(cursor)
        row == null -> animateScrollToItem(cursor, scrollOffset = rowHeightPx - viewport)
        row.offset < info.viewportStartOffset -> animateScrollToItem(cursor)
        row.offset + row.size > info.viewportEndOffset ->
            animateScrollToItem(cursor, scrollOffset = rowHeightPx - viewport)
    }
}

@Composable
private fun SettingsRow(label: String, highlighted: Boolean, current: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PopoverRowHeight)
            .clip(MaterialTheme.shapes.small)
            .background(if (highlighted) TvAccent else TvSurface.copy(alpha = 0f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (highlighted) TvOnAccent else TvOnSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = if (highlighted) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (highlighted) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )
        if (current) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = if (highlighted) TvOnAccent else TvAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private const val PANEL_ALPHA = 0.85f

private val PlayerControlBackground = Color(0xA6000000)

private const val POPOVER_WIDTH_FRACTION = 0.25f
private const val POPOVER_MAX_HEIGHT_FRACTION = 0.6f
private val PopoverRowHeight = 40.dp
private val AutoNextCardMaxWidth = 460.dp
private val SubscriptionCardMaxWidth = 480.dp

private val ScrubTrackHeight = 6.dp
private val ScrubTrackHeightActive = 9.dp
private val ScrubThumb = 15.dp
private val ScrubThumbActive = 24.dp
private val ScrubThumbHalo = 24.dp
private val ScrubThumbHaloActive = 38.dp

private val ScrubFocusRingExtra = 6.dp

private val PauseButtonSize = 34.dp
private val PauseFocusOuter = 42.dp
private val PauseFocusInner = 38.dp
private val PauseIconSize = 16.dp

private val EpisodeNavButtonSize = 24.dp
private val EpisodeNavFocusOuter = 30.dp
private val EpisodeNavFocusInner = 27.dp
private val EpisodeNavIconSize = 13.dp
private val EpisodeNavTopGap = 6.dp
private val EpisodeNavGap = 8.dp

private val SettingsGridGap = 6.dp
private val SettingsButtonWidth = 136.dp

private val SettingsButtonHeight = 38.dp
private val SettingsButtonVerticalPadding = 2.dp
