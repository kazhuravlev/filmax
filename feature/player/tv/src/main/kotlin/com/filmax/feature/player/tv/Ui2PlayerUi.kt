package com.filmax.feature.player.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.feature.player.common.NO_VALUE_CAPTION
import com.filmax.feature.player.common.formatPlayerTime

internal object Ui2PlayerUi : TvPlayerUi {
    @Composable
    override fun Content(session: TvPlayerSession, modifier: Modifier) {
        val ui = remember(session.player) { Ui2PlayerUiState(session.player) }
        PlayerEffects(ui = ui, session = session)
        BackHandler { if (!ui.back()) session.onBack() }
        PlayerFrame(ui = ui, session = session, modifier = modifier) {
            Ui2Overlay(ui = ui, session = session)
        }
    }
}

internal enum class Ui2Zone { Scrubber, Controls }

internal sealed interface Ui2Control {
    data object PlayPause : Ui2Control

    data object Rewind : Ui2Control

    data object Forward : Ui2Control

    data class Action(val action: SettingsAction) : Ui2Control
}

internal fun ui2Controls(menu: PlayerActions): List<Ui2Control> = buildList {
    add(Ui2Control.PlayPause)
    add(Ui2Control.Rewind)
    add(Ui2Control.Forward)
    UI2_ACTION_ORDER.forEach { action ->
        if (action in menu.items && menu.isEnabled(action)) add(Ui2Control.Action(action))
    }
}

private val UI2_ACTION_ORDER = listOf(
    SettingsAction.NextEpisode,
    SettingsAction.Episodes,
    SettingsAction.Audio,
    SettingsAction.Subtitle,
    SettingsAction.Quality,
    SettingsAction.Preset,
)

@Suppress("TooManyFunctions")
@Stable
internal class Ui2PlayerUiState(player: Player) : BasePlayerUiState(player) {
    var zone by mutableStateOf(Ui2Zone.Controls)
    var controlCursor by mutableIntStateOf(0)

    override val idleHidesOverlay: Boolean
        get() = visible && isPlaying

    override fun hideOverlay() {
        super.hideOverlay()
        zone = Ui2Zone.Controls
        controlCursor = 0
    }

    override fun onKey(key: Key, menu: PlayerActions): Boolean = when {
        autoNextVisible && submenu == null && !episodesOpen && key.isOk() -> {
            acceptAutoNext(menu)
            true
        }
        episodesOpen -> onEpisodesKey(key, menu)
        submenu != null -> onSubmenuKey(key, menu)
        !visible -> onHiddenKey(key)
        zone == Ui2Zone.Scrubber -> onScrubberKey(key)
        else -> onControlsKey(key, menu)
    }

    private fun onHiddenKey(key: Key): Boolean {
        when (key) {
            Key.DirectionLeft, Key.MediaRewind -> {
                zone = Ui2Zone.Scrubber
                scrub(-1)
            }
            Key.DirectionRight, Key.MediaFastForward -> {
                zone = Ui2Zone.Scrubber
                scrub(1)
            }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                focusPlayPause()
                togglePlay()
            }
            Key.DirectionUp, Key.DirectionDown -> {
                focusPlayPause()
                touch()
            }
            else -> return false
        }
        return true
    }

    private fun onScrubberKey(key: Key): Boolean {
        when (key) {
            Key.DirectionLeft, Key.MediaRewind -> scrub(-1)
            Key.DirectionRight, Key.MediaFastForward -> scrub(1)
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> togglePlay()
            Key.DirectionDown -> {
                focusPlayPause()
                touch()
            }
            Key.DirectionUp -> touch()
            else -> return false
        }
        return true
    }

    private fun onControlsKey(key: Key, menu: PlayerActions): Boolean {
        val controls = ui2Controls(menu)
        when (key) {
            Key.DirectionLeft -> controlCursor = (controlCursor - 1).coerceAtLeast(0)
            Key.DirectionRight -> controlCursor = (controlCursor + 1).coerceAtMost(controls.lastIndex)
            Key.DirectionUp -> zone = Ui2Zone.Scrubber
            Key.DirectionDown -> Unit
            Key.DirectionCenter, Key.Enter -> controls.getOrNull(controlCursor)?.let { activate(it, menu) }
            Key.MediaPlayPause -> togglePlay()
            Key.MediaRewind -> seekBy(-SKIP_STEP_MS)
            Key.MediaFastForward -> seekBy(SKIP_STEP_MS)
            else -> return false
        }
        touch()
        return true
    }

    fun activate(control: Ui2Control, menu: PlayerActions) {
        when (control) {
            Ui2Control.PlayPause -> togglePlay()
            Ui2Control.Rewind -> seekBy(-SKIP_STEP_MS)
            Ui2Control.Forward -> seekBy(SKIP_STEP_MS)
            is Ui2Control.Action -> when (control.action) {
                SettingsAction.NextEpisode -> menu.onNextEpisode()
                SettingsAction.Episodes -> openEpisodes(menu)
                SettingsAction.Preset, SettingsAction.Quality, SettingsAction.Audio, SettingsAction.Subtitle,
                SettingsAction.Speed,
                -> openSubmenu(control.action, menu)
            }
        }
        touch()
    }

    private fun focusPlayPause() {
        zone = Ui2Zone.Controls
        controlCursor = 0
    }

    private fun Key.isOk(): Boolean = this == Key.DirectionCenter || this == Key.Enter
}

@Composable
private fun Ui2Overlay(ui: Ui2PlayerUiState, session: TvPlayerSession) {
    val menu = session.menu
    Box(
        Modifier
            .fillMaxSize()
            .background(if (ui.isPlaying) Color.Transparent else PausedDim)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    GRADIENT_START to Color.Transparent,
                    1f to BottomShade,
                ),
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = TvMetrics.SafeHorizontal)
                .padding(bottom = BottomInset),
        ) {
            Text(
                session.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.subtitle.isNotBlank()) {
                Text(
                    session.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!ui.isPlaying && session.description.isNotBlank()) {
                Text(
                    session.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(DESCRIPTION_WIDTH_FRACTION),
                )
            }
            Ui2Scrubber(ui = ui, modifier = Modifier.padding(top = 14.dp))
            Ui2ControlsRow(ui = ui, menu = menu, modifier = Modifier.padding(top = 6.dp))
        }

        Ui2Panels(ui = ui, menu = menu)
    }
}

@Composable
private fun BoxScope.Ui2Panels(ui: Ui2PlayerUiState, menu: PlayerActions) {
    ui.submenu?.let { category ->
        SettingsPopover(
            action = category,
            menu = menu,
            cursor = ui.submenuCursor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = TvMetrics.SafeHorizontal, bottom = PopoverBottom),
        )
    }
    if (ui.episodesOpen) {
        menu.episodes?.let { panel ->
            EpisodesPanel(
                panel = panel,
                seasonCursor = ui.episodesSeasonCursor,
                episodeCursor = ui.episodesCursor,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = TvMetrics.SafeHorizontal)
                    .padding(vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun Ui2Scrubber(ui: Ui2PlayerUiState, modifier: Modifier = Modifier) {
    val focused = ui.zone == Ui2Zone.Scrubber
    val position = if (ui.isScrubbing) ui.scrubTargetMs else ui.positionMs
    val duration = ui.durationMs
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (ui.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(BubbleHeight + TrackAreaHeight),
        ) {
            val trackWidth = maxWidth
            val thumbCenter = trackWidth * fraction

            if (ui.isScrubbing) {
                val bubbleX = (thumbCenter - BubbleWidth / 2).coerceIn(0.dp, trackWidth - BubbleWidth)
                Text(
                    formatPlayerTime(position),
                    style = timeStyle,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(x = bubbleX)
                        .width(BubbleWidth)
                        .clip(MaterialTheme.shapes.small)
                        .background(BubbleBackground)
                        .padding(vertical = 4.dp),
                )
            }
            Ui2Track(
                fraction = fraction,
                buffered = buffered,
                thumbCenter = thumbCenter,
                focused = focused,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(TrackAreaHeight),
            )
        }
        Text(
            formatPlayerTime((duration - position).coerceAtLeast(0L)),
            style = timeStyle,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier
                .padding(start = 16.dp, bottom = (TrackAreaHeight - 16.dp) / 2)
                .widthIn(min = 56.dp),
        )
    }
}

@Composable
private fun Ui2Track(
    fraction: Float,
    buffered: Float,
    thumbCenter: Dp,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackHeight = if (focused) TrackFocused else Track
    val thumbSize = if (focused) ThumbFocused else Thumb
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(TrackBackground),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(buffered)
                .height(trackHeight)
                .clip(CircleShape)
                .background(BufferedBackground),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(CircleShape)
                .background(Ui2Accent),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbCenter - thumbSize / 2)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Ui2Accent)
                .border(if (focused) ThumbRing else 0.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun Ui2ControlsRow(ui: Ui2PlayerUiState, menu: PlayerActions, modifier: Modifier = Modifier) {
    val controls = ui2Controls(menu)
    val focusedIndex = ui.controlCursor.coerceIn(0, controls.lastIndex.coerceAtLeast(0))
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ButtonGap),
    ) {
        controls.forEachIndexed { index, control ->
            Ui2ControlSlot(
                icon = control.icon(ui.isPlaying),
                contentDescription = control.label(menu, ui.isPlaying),
                caption = control.caption(menu),
                focused = ui.zone == Ui2Zone.Controls && index == focusedIndex,
            )
            if (control == Ui2Control.Forward) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Ui2ControlSlot(icon: ImageVector, contentDescription: String, caption: String?, focused: Boolean) {
    Column(Modifier.width(ButtonSize), horizontalAlignment = Alignment.CenterHorizontally) {
        Ui2Button(icon = icon, contentDescription = contentDescription, focused = focused)
        if (caption == null) {
            Spacer(Modifier.padding(top = CaptionGap).height(CaptionHeight))
        } else {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = CaptionColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = CaptionGap)
                    .heightIn(min = CaptionHeight),
            )
        }
    }
}

@Composable
private fun Ui2Button(icon: ImageVector, contentDescription: String, focused: Boolean) {
    Box(
        Modifier
            .size(ButtonSize)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(IconSize),
        )
    }
}

private fun Ui2Control.icon(isPlaying: Boolean): ImageVector = when (this) {
    Ui2Control.PlayPause -> if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
    Ui2Control.Rewind -> Icons.Filled.Replay10
    Ui2Control.Forward -> Icons.Filled.Forward10
    is Ui2Control.Action -> when (action) {
        SettingsAction.NextEpisode -> Icons.Filled.SkipNext
        SettingsAction.Episodes -> Icons.AutoMirrored.Filled.ViewList
        SettingsAction.Audio -> Icons.Filled.Audiotrack
        SettingsAction.Subtitle -> Icons.Filled.Subtitles
        SettingsAction.Speed -> Icons.Filled.Speed
        SettingsAction.Quality -> Icons.Filled.HighQuality
        SettingsAction.Preset -> Icons.Filled.Tune
    }
}

private fun Ui2Control.label(menu: PlayerActions, isPlaying: Boolean): String = when (this) {
    Ui2Control.PlayPause -> if (isPlaying) "Пауза" else "Смотреть"
    Ui2Control.Rewind -> "Назад 10 с"
    Ui2Control.Forward -> "Вперёд 10 с"
    is Ui2Control.Action -> {
        val value = menu.selected(action)?.label
        if (value.isNullOrBlank()) action.label else "${action.label} · $value"
    }
}

private fun Ui2Control.caption(menu: PlayerActions): String? = when (this) {
    Ui2Control.PlayPause, Ui2Control.Rewind, Ui2Control.Forward -> null
    is Ui2Control.Action -> when (action) {
        SettingsAction.Audio, SettingsAction.Subtitle, SettingsAction.Quality ->
            menu.selected(action)?.shortValue ?: NO_VALUE_CAPTION
        SettingsAction.Speed, SettingsAction.Preset, SettingsAction.Episodes, SettingsAction.NextEpisode -> null
    }
}

private const val SKIP_STEP_MS = 10_000L

private const val GRADIENT_START = 0.5f

private val Ui2Accent = Color(0xFFE50914)
private val PausedDim = Color(0x59000000)
private val BottomShade = Color(0xE6000000)
private val TrackBackground = Color(0x59FFFFFF)
private val BufferedBackground = Color(0x40FFFFFF)

private const val DESCRIPTION_WIDTH_FRACTION = 0.55f
private val BubbleBackground = Color(0xCC000000)

private val BottomInset = 36.dp
private val Track = 4.dp
private val TrackFocused = 6.dp
private val TrackAreaHeight = 24.dp
private val Thumb = 14.dp
private val ThumbFocused = 20.dp
private val ThumbRing = 3.dp
private val BubbleWidth = 76.dp
private val BubbleHeight = 30.dp
private val ButtonSize = 44.dp
private val IconSize = 26.dp
private val ButtonGap = 10.dp
private val CaptionHeight = 16.dp
private val CaptionGap = 2.dp

private val CaptionColor = Color(0xCCFFFFFF)

private val PopoverBottom = BottomInset + ButtonSize + CaptionGap + CaptionHeight + 12.dp
