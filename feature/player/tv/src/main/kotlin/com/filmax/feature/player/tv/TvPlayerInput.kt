package com.filmax.feature.player.tv

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.media3.common.Player
import com.filmax.core.domain.catalog.model.MediaTrack

/** Что сейчас ведёт D-pad: Play/Pause, прогресс-бар или сетка настроек справа от него. */
internal enum class PlayerMode { Transport, Progress, Settings }

/**
 * Пункт сетки настроек. Первые четыре открывают поповер выбора, [Episodes] — панель сезонов
 * и серий, [NextEpisode] — действие сразу.
 */
internal enum class SettingsAction(val label: String) {
    Quality("Качество"),
    Audio("Аудио"),
    Subtitle("Субтитры"),
    Speed("Скорость"),
    Episodes("Серии"),
    NextEpisode("Следующая серия"),
}

/**
 * Данные боковой панели серий: сезоны с эпизодами, «где мы сейчас» для стартового курсора и
 * отметки, и колбэк воспроизведения (та же навигация, что у «Следующей серии»).
 */
internal class EpisodesPanelData(
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    val currentTrackId: Int?,
    val currentSeasonIndex: Int,
    val currentEpisodeIndex: Int,
    val onPlayEpisode: (season: Int, videoId: Int) -> Unit,
)

/**
 * Сетка настроек в терминах текущего кадра композиции: что показываем и что делать по OK.
 * Передаётся обработчику клавиш параметром — [TvPlayerUiState] про PlayerState ничего не знает.
 * [episodes] == null — фильм или навигации по сериям нет: пункта «Серии» в ряду не будет.
 */
internal class PlayerActions(
    val items: List<SettingsAction>,
    val options: (SettingsAction) -> List<String>,
    /** Подпись выбранного сейчас значения категории — по ней считается и стартовый курсор поповера. */
    val selected: (SettingsAction) -> String,
    val onSelect: (SettingsAction, String) -> Unit,
    val onNextEpisode: () -> Unit,
    val episodes: EpisodesPanelData? = null,
    val enabled: (SettingsAction) -> Boolean = { true },
) {
    /** Есть ли следующая серия и навигация к ней — условие автоперехода и пункта в ряду. */
    val hasNextEpisode: Boolean get() = SettingsAction.NextEpisode in items

    fun selectedIndex(action: SettingsAction): Int =
        options(action).indexOf(selected(action)).coerceAtLeast(0)

    fun isEnabled(action: SettingsAction): Boolean = enabled(action)
}

/**
 * Состояние оверлея и вся раскладка пульта.
 *
 * Плеер — единственный экран, где D-pad НЕ ходит по фокусу: стрелки это транспорт, а не навигация
 * (правило Google, training/tv/playback/controls). Поэтому «курсор» по чипам и по списку поповера
 * эмулируется индексами, а клавиши разбирает один обработчик: пока открыт поповер, всё уходит
 * в него, и панель не может «зависнуть», когда фокус ушёл мимо списка.
 */
// Клавиатурный автомат плеера: обработчики слоёв (транспорт/настройки/поповер/панель серий) и
// есть его API — дробить их по классам значило бы разорвать одну раскладку пульта на куски.
@Suppress("TooManyFunctions")
@Stable
internal class TvPlayerUiState(val player: Player) {

    /** Виден ли оверлей поверх кадра. */
    var visible by mutableStateOf(true)
    var mode by mutableStateOf(PlayerMode.Transport)

    /** Открытая категория поповера выбора; null — поповера нет. */
    var submenu by mutableStateOf<SettingsAction?>(null)
    var settingsCursor by mutableIntStateOf(0)
    var submenuCursor by mutableIntStateOf(0)

    /** Открыта ли боковая панель серий; курсоры — выбранный сезон и серия внутри него. */
    var episodesOpen by mutableStateOf(false)
    var episodesSeasonCursor by mutableIntStateOf(0)
    var episodesCursor by mutableIntStateOf(0)

    /** Плашка автоперехода: видимость, секунды до старта и отмена «Назад» до конца серии. */
    var autoNextVisible by mutableStateOf(false)
    var autoNextSeconds by mutableIntStateOf(0)
    var autoNextDismissed by mutableStateOf(false)

    /** Индикатор шага последней перемотки («+30 с»); гаснет сам. */
    var seekLabel by mutableStateOf<String?>(null)

    /** Зеркала плеера: сам он не Compose-state и рекомпозицию не вызывает. */
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    /** Скраббинг: позиция, которую двигает D-pad до подтверждения seekTo. */
    var isScrubbing by mutableStateOf(false)
    var scrubTargetMs by mutableLongStateOf(0L)

    /** Счётчики нажатий — только чтобы перезапускать таймеры автоскрытия и индикатора шага. */
    var interactionTick by mutableIntStateOf(0)
    var seekTick by mutableIntStateOf(0)

    /** Длина текущей серии быстрых нажатий перемотки и время последнего из них. */
    private var seekStreak = 0
    private var lastSeekAtMs = 0L

    /**
     * Обновляется тиком прогресса: плашка появляется в последних [AUTO_NEXT_WINDOW_MS] серии
     * (наш прокси «пошли титры» — маркеров у kino.watch нет), и с этого момента идёт ФИКСИРОВАННЫЙ
     * отсчёт [AUTO_NEXT_COUNTDOWN_SEC]: конца титров не ждём, хвост серии срезается переходом.
     * Отсчёт замирает на паузе; перемотка прячет плашку и начинает отсчёт заново. Нижней границы
     * у окна нет: у HLS позиция может уйти ЗА заявленную длительность (поток длиннее метаданных).
     */
    fun updateAutoNext(remainingMs: Long, enabled: Boolean, playing: Boolean) {
        val inWindow = enabled && !autoNextDismissed && !isScrubbing &&
            remainingMs <= AUTO_NEXT_WINDOW_MS
        when {
            inWindow && !autoNextVisible -> autoNextSeconds = AUTO_NEXT_COUNTDOWN_SEC
            inWindow && playing -> autoNextSeconds = (autoNextSeconds - 1).coerceAtLeast(0)
        }
        autoNextVisible = inWindow
    }

    /** Оверлей уходит после любого бездействия, в том числе в открытом селекторе. */
    val idleHidesOverlay: Boolean
        get() = visible

    /** Было действие пользователя: оверлей на экран, таймер автоскрытия — с нуля. */
    fun touch() {
        visible = true
        interactionTick++
    }

    /** Закрывает весь интерфейс одним действием, не оставляя скрытый поповер активным. */
    fun hideOverlay() {
        visible = false
        mode = PlayerMode.Transport
        submenu = null
        episodesOpen = false
        seekLabel = null
    }

    /**
     * Раскладка D-pad — дословно по гайдлайну Google: Center — пауза/воспроизведение, Left/Right —
     * перемотка доступна только после перехода на прогресс-бар клавишей Up; Right и Down ведут
     * в сетку справа от Play. Неизвестные клавиши не трогаем — иначе съедим громкость и системные.
     */
    fun onKey(key: Key, menu: PlayerActions): Boolean = when {
        // OK при видимой плашке автоперехода (и только в транспорте) — следующая серия сразу.
        autoNextVisible && submenu == null && !episodesOpen && mode == PlayerMode.Transport &&
            (key == Key.DirectionCenter || key == Key.Enter) -> {
            autoNextVisible = false
            menu.onNextEpisode()
            true
        }
        episodesOpen -> onEpisodesKey(key, menu)
        submenu != null -> onSubmenuKey(key, menu)
        mode == PlayerMode.Settings -> onSettingsKey(key, menu)
        else -> onTransportKey(key, menu)
    }

    /**
     * Панель серий: ↑/↓ — по сериям сезона, ◄/► — соседний сезон (курсор серий — в начало),
     * OK — играть выбранную. Как и поповер, панель забирает весь ввод, кроме чужих клавиш.
     */
    // Та же структура, что у onSubmenuKey: ветка «клавиша не наша» обязана вернуть false.
    @Suppress("ReturnCount")
    private fun onEpisodesKey(key: Key, menu: PlayerActions): Boolean {
        val panel = menu.episodes ?: return false
        val episodes = panel.seasons.getOrNull(episodesSeasonCursor)?.second.orEmpty()

        fun switchSeason(delta: Int) {
            val next = (episodesSeasonCursor + delta).coerceIn(0, panel.seasons.lastIndex)
            if (next != episodesSeasonCursor) {
                episodesSeasonCursor = next
                episodesCursor = 0
            }
        }

        when (key) {
            Key.DirectionUp -> episodesCursor = (episodesCursor - 1).coerceAtLeast(0)
            Key.DirectionDown -> episodesCursor = (episodesCursor + 1).coerceAtMost(episodes.lastIndex)
            Key.DirectionLeft -> switchSeason(-1)
            Key.DirectionRight -> switchSeason(+1)
            Key.DirectionCenter, Key.Enter -> {
                episodes.getOrNull(episodesCursor)?.let { episode ->
                    panel.onPlayEpisode(episode.seasonNumber, episode.number)
                }
                episodesOpen = false
            }
            else -> return false
        }
        touch()
        return true
    }

    // Три выхода вместо двух: ветка «клавиша не наша» обязана вернуть false, иначе плеер
    // проглотит громкость и системные кнопки. Разворачивать в единый выход — только запутать.
    @Suppress("ReturnCount")
    private fun onSubmenuKey(key: Key, menu: PlayerActions): Boolean {
        val category = submenu ?: return false
        val options = menu.options(category)
        when (key) {
            Key.DirectionUp -> submenuCursor = (submenuCursor - 1).coerceAtLeast(0)
            Key.DirectionDown -> submenuCursor = (submenuCursor + 1).coerceAtMost(options.lastIndex)
            Key.DirectionCenter, Key.Enter -> {
                options.getOrNull(submenuCursor)?.let { option -> menu.onSelect(category, option) }
                submenu = null
            }
            // Горизонталь при открытом поповере глушим: иначе стрелка улетела бы в перемотку.
            Key.DirectionLeft, Key.DirectionRight -> Unit
            else -> return false
        }
        touch()
        return true
    }

    private fun onSettingsKey(key: Key, menu: PlayerActions): Boolean {
        when (key) {
            Key.DirectionLeft -> moveInSettingsGrid(menu, -SETTINGS_GRID_ROWS, returnToTransport = true)
            Key.DirectionRight -> moveInSettingsGrid(menu, SETTINGS_GRID_ROWS)
            Key.DirectionUp -> if (settingsCursor % SETTINGS_GRID_ROWS == 0) {
                mode = PlayerMode.Transport
            } else {
                moveInSettingsGrid(menu, -1)
            }
            Key.DirectionCenter, Key.Enter -> menu.items.getOrNull(settingsCursor)?.let { activate(it, menu) }
            Key.DirectionDown -> if (settingsCursor % SETTINGS_GRID_ROWS == 0) moveInSettingsGrid(menu, +1)
            else -> return false
        }
        touch()
        return true
    }

    private fun onTransportKey(key: Key, menu: PlayerActions): Boolean {
        when (key) {
            Key.DirectionLeft, Key.MediaRewind -> if (mode == PlayerMode.Progress) scrub(-1) else touch()
            Key.DirectionRight -> if (mode == PlayerMode.Progress) scrub(1) else openSettings(menu)
            Key.MediaFastForward -> if (mode == PlayerMode.Progress) scrub(1) else touch()
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> togglePlay()
            Key.DirectionDown -> openSettings(menu)
            Key.DirectionUp -> {
                mode = PlayerMode.Progress
                seekLabel = null
                touch()
            }
            else -> return false
        }
        return true
    }

    private fun openSettings(menu: PlayerActions) {
        if (menu.items.isEmpty()) return
        mode = PlayerMode.Settings
        settingsCursor = menu.firstEnabledSettingsIndex()
        seekLabel = null
        touch()
    }

    private fun moveInSettingsGrid(menu: PlayerActions, delta: Int, returnToTransport: Boolean = false) {
        val next = menu.moveSettingsCursor(settingsCursor, delta)
        if (next == settingsCursor && returnToTransport) mode = PlayerMode.Transport else settingsCursor = next
    }

    fun activate(action: SettingsAction, menu: PlayerActions) {
        if (!menu.isEnabled(action)) return
        when (action) {
            SettingsAction.NextEpisode -> menu.onNextEpisode()
            // Панель открывается на играющей сейчас серии — переключить на соседнюю быстрее всего.
            SettingsAction.Episodes -> menu.episodes?.let { panel ->
                episodesSeasonCursor = panel.currentSeasonIndex
                episodesCursor = panel.currentEpisodeIndex
                episodesOpen = true
            }
            else -> {
                submenu = action
                submenuCursor = menu.selectedIndex(action)
            }
        }
        touch()
    }

    private fun PlayerActions.firstEnabledSettingsIndex(): Int =
        items.indexOfFirst { isEnabled(it) }.coerceAtLeast(0)

    private fun PlayerActions.moveSettingsCursor(current: Int, delta: Int): Int {
        var index = current + delta
        while (index in items.indices) {
            if (isEnabled(items[index])) return index
            index += delta
        }
        return current
    }

    /**
     * «Назад» при открытом интерфейсе всегда сначала скрывает его. Когда интерфейс уже скрыт,
     * оставляет специальное действие только для плашки автоперехода; иначе возвращает false,
     * чтобы экран вышел из плеера.
     */
    fun back(): Boolean = when {
        visible -> {
            hideOverlay()
            true
        }
        // «Назад» при плашке автоперехода — отмена: серия дотечёт до конца и остановится.
        autoNextVisible -> {
            autoNextDismissed = true
            autoNextVisible = false
            touch()
            true
        }
        else -> false
    }

    /**
     * Шаг перемотки с разгоном: пока нажатия идут чаще [SEEK_STREAK_WINDOW_MS], шаг берётся
     * следующим по лестнице [SEEK_STEPS_SEC]. Двухчасовой фильм так перематывается за десяток
     * нажатий, а не за 360. Часы монотонные: системное время может прыгнуть и сорвать серию.
     */
    private fun scrub(direction: Int) {
        val duration = durationMs
        if (duration <= 0) return
        val now = SystemClock.uptimeMillis()
        seekStreak = if (now - lastSeekAtMs < SEEK_STREAK_WINDOW_MS) seekStreak + 1 else 0
        lastSeekAtMs = now
        val stepSec = SEEK_STEPS_SEC[seekStreak.coerceAtMost(SEEK_STEPS_SEC.lastIndex)]

        if (!isScrubbing) {
            scrubTargetMs = positionMs
            isScrubbing = true
        }
        scrubTargetMs = (scrubTargetMs + direction * stepSec * MILLIS_IN_SECOND).coerceIn(0L, duration)
        // Подпись показывает шаг именно этого нажатия — на разгоне «10 с» на кнопке было бы враньём.
        seekLabel = if (direction > 0) "+$stepSec с" else "−$stepSec с"
        seekTick++
        touch()
    }

    /**
     * Подтверждение перемотки. seekTo на каждое нажатие рвал бы HLS-буфер, поэтому позицию
     * ведём в состоянии и уходим на неё по паузе в нажатиях.
     */
    fun commitScrub() {
        if (!isScrubbing) return
        player.seekTo(scrubTargetMs)
        positionMs = scrubTargetMs
        isScrubbing = false
    }

    private fun togglePlay() {
        // Незакоммиченный скраб не теряем: сначала уходим на выбранную позицию, потом переключаем.
        commitScrub()
        if (player.isPlaying) player.pause() else player.play()
        seekLabel = null
        touch()
    }
}

/** Тик прогресса и сохранения позиции (мс). Ровно секунда: на нём же держится SaveProgress. */
internal const val PROGRESS_TICK_MS = 1000L

/** Бездействие, после которого оверлей уходит с кадра. */
internal const val OVERLAY_AUTO_HIDE_MS = 5_000L

/** Пауза в нажатиях, после которой скраббинг подтверждается seekTo. */
internal const val SCRUB_COMMIT_TIMEOUT_MS = 700L

/** Сколько держится индикатор шага перемотки после последнего нажатия. */
internal const val SEEK_LABEL_HOLD_MS = 800L

/** Пауза между нажатиями, которая сбрасывает разгон перемотки в начало лестницы. */
internal const val SEEK_STREAK_WINDOW_MS = 450L

internal const val MILLIS_IN_SECOND = 1000L

/** Лестница разгона перемотки (секунды): шаг растёт, пока пользователь давит стрелку. */
internal val SEEK_STEPS_SEC = listOf(10, 10, 20, 30, 60, 90, 120)

/** Окно плашки автоперехода: последние 20 секунд серии (прокси «пошли титры»). */
internal const val AUTO_NEXT_WINDOW_MS = 20_000L

/** Отсчёт до автостарта следующей серии с момента появления плашки. */
internal const val AUTO_NEXT_COUNTDOWN_SEC = 5

/** В каждом столбце сетки ровно две плитки. */
private const val SETTINGS_GRID_ROWS = 2
