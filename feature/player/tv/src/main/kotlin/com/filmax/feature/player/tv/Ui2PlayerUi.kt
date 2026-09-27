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

/**
 * UI 2 ([com.filmax.core.domain.playback.PlayerUi.Ui2]) — привычная большинству раскладка
 * стриминговых плееров: внизу кадра название, полоса прокрутки с остатком времени справа и ряд
 * круглых кнопок под ней — транспорт слева (пауза, ±10 с), действия справа (следующая серия,
 * серии, аудио, субтитры, скорость, качество, пресет). Под кнопками выбора — их текущее
 * значение мелко («rus», «Выкл», «1×», «1080p»), всегда, не только под курсором: что выбрано,
 * видно без открытия селектора. Выбор — в поповере над рядом справа, серии — панелью.
 *
 * Аудиодорожка — отдельная кнопка рядом с субтитрами: у kino.watch озвучек несколько, и «Аудио
 * и субтитры» одним пунктом было бы тесно.
 */
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

/** Что ведёт пульт: полоса прокрутки или ряд кнопок под ней. */
internal enum class Ui2Zone { Scrubber, Controls }

/** Кнопка нижнего ряда: транспорт или действие из сетки настроек сессии. */
internal sealed interface Ui2Control {
    data object PlayPause : Ui2Control

    data object Rewind : Ui2Control

    data object Forward : Ui2Control

    data class Action(val action: SettingsAction) : Ui2Control
}

/**
 * Ряд кнопок: транспорт слева, действия справа. Недоступные категории (одна дорожка, одно
 * качество) в ряду не показываются вовсе: чего нет, того нет.
 * Порядок фиксирован здесь, а не в [PlayerActions.items]: тот задаёт сетку UI 1.
 */
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

/**
 * Раскладка пульта UI 2:
 *  - Оверлей скрыт: ◄/► — сразу перемотка с полосой под курсором, OK — пауза/воспроизведение,
 *    ▲/▼ — просто показать оверлей с курсором на паузе.
 *  - Полоса: ◄/► — перемотка с разгоном, OK — пауза/воспроизведение, ▼ — в ряд кнопок.
 *  - Ряд кнопок: ◄/► — по кнопкам, ▲ — на полосу, OK — действие кнопки.
 *  - Поповер и панель серий забирают ввод целиком (см. [BasePlayerUiState]).
 */
// Каркас тот же, что у UI 1: обработчики зон и есть API раскладки.
@Suppress("TooManyFunctions")
@Stable
internal class Ui2PlayerUiState(player: Player) : BasePlayerUiState(player) {

    var zone by mutableStateOf(Ui2Zone.Controls)
    var controlCursor by mutableIntStateOf(0)

    /** На паузе оверлей остаётся: экран паузы с синопсисом — это и есть состояние, а не бездействие. */
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

    /** Оверлей скрыт: первое нажатие и показывает его, и уже что-то делает. */
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
            // Медиа-клавиши пульта работают из любой точки ряда, не только с кнопки под курсором.
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

    /** Точка входа в ряд — всегда пауза, первая кнопка слева. */
    private fun focusPlayPause() {
        zone = Ui2Zone.Controls
        controlCursor = 0
    }

    private fun Key.isOk(): Boolean = this == Key.DirectionCenter || this == Key.Enter
}

// ── Оверлей ──────────────────────────────────────────────────────────────────

@Composable
private fun Ui2Overlay(ui: Ui2PlayerUiState, session: TvPlayerSession) {
    val menu = session.menu
    Box(
        Modifier
            .fillMaxSize()
            // На паузе притемняем весь кадр, при воспроизведении — только низ под текстом.
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
            // Экран паузы: синопсис под названием, пока стоим. При воспроизведении он лишний —
            // текст на кадре должен исчезать вместе с оверлеем, а не жить на нём.
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

/**
 * Поповер — над рядом кнопок справа, где стоят сами действия; панель серий — по центру справа:
 * она высокая, над рядом ей не поместиться.
 */
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

/**
 * Полоса прокрутки: красная заливка на полупрозрачном треке, thumb растёт под курсором, справа —
 * остаток времени. Во время перемотки над thumb'ом всплывает время, куда попадём.
 */
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

/**
 * Трек, докачанный участок ([buffered], светлее трека), заливка и thumb; [thumbCenter] — уже
 * посчитанная от ширины трека координата центра.
 */
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

/**
 * Ряд кнопок: транспорт слева, действия прижаты вправо. Каждая кнопка вместе со своей подписью —
 * одна колонка ([Ui2ControlSlot]) шириной ровно в кнопку: подпись не может уехать от кнопки,
 * а место под неё зарезервировано у всех, чтобы ряд не прыгал, когда подпись появляется.
 * Скорости в ряду нет вовсе: на ТВ ей не пользуются, а кнопка занимала место.
 */
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
            // Транспорт слева, действия прижаты к правому краю — ровно под правый край полосы.
            if (control == Ui2Control.Forward) Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Кнопка и подпись под ней в одном контейнере. Подпись — мелким шрифтом по центру кнопки
 * (см. [caption]); null — подписи у кнопки нет (транспорт, серии, пресет), но место под неё
 * всё равно зарезервировано, чтобы ряд не прыгал. Длинная подпись обрезается многоточием, а
 * при увеличенном системном шрифте строка подрастает, а не режет текст.
 */
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

/** Круглая кнопка: под курсором — белый круг с чёрной иконкой, иначе белая иконка на кадре. */
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

/** Описание кнопки для accessibility: у действий с выбором — вместе с текущим значением. */
private fun Ui2Control.label(menu: PlayerActions, isPlaying: Boolean): String = when (this) {
    Ui2Control.PlayPause -> if (isPlaying) "Пауза" else "Смотреть"
    Ui2Control.Rewind -> "Назад 10 с"
    Ui2Control.Forward -> "Вперёд 10 с"
    is Ui2Control.Action -> {
        val value = menu.selected(action)?.label
        if (value.isNullOrBlank()) action.label else "${action.label} · $value"
    }
}

/**
 * Короткое значение под кнопкой: код языка озвучки и субтитров («rus», «eng», «—» без субтитров)
 * и класс качества («FHD», «4K») — уже посчитанное [PlayerChoice.shortValue], здесь ничего не
 * разбирается из подписи. null — подписи нет (транспорт, серии, пресет, скорость). Полные подписи
 * поповера («2. Русский · Многоголосый · BaibaKo», «2160p») под кнопку в 44dp не влезают.
 */
private fun Ui2Control.caption(menu: PlayerActions): String? = when (this) {
    Ui2Control.PlayPause, Ui2Control.Rewind, Ui2Control.Forward -> null
    is Ui2Control.Action -> when (action) {
        SettingsAction.Audio, SettingsAction.Subtitle, SettingsAction.Quality ->
            menu.selected(action)?.shortValue ?: NO_VALUE_CAPTION
        SettingsAction.Speed, SettingsAction.Preset, SettingsAction.Episodes, SettingsAction.NextEpisode -> null
    }
}

/** Шаг кнопок «±10 с». */
private const val SKIP_STEP_MS = 10_000L

/** Ниже этой доли высоты кадр начинает уходить в тень под текст и полосу. */
private const val GRADIENT_START = 0.5f

private val Ui2Accent = Color(0xFFE50914)
private val PausedDim = Color(0x59000000)
private val BottomShade = Color(0xE6000000)
private val TrackBackground = Color(0x59FFFFFF)
private val BufferedBackground = Color(0x40FFFFFF)

/** Синопсис на паузе — не шире половины кадра, как абзац, а не бегущая строка. */
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

/** Подпись значения под кнопкой — приглушённо-белая: значение, а не действие. */
private val CaptionColor = Color(0xCCFFFFFF)

/** Поповер стоит над рядом кнопок, чуть выше его верхнего края; подписи под кнопками — ниже. */
private val PopoverBottom = BottomInset + ButtonSize + CaptionGap + CaptionHeight + 12.dp
