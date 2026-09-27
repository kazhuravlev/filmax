package com.filmax.feature.player.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.media3.common.Player
import com.filmax.core.domain.catalog.model.MediaTrack

/**
 * Что сейчас ведёт D-pad — один из трёх блоков оверлея (см. `PlayerTransport`):
 *
 *  - [Progress] — полоса прокрутки (верхняя строка);
 *  - [Transport] и [EpisodeNav] — левая колонка нижней строки: Play и ряд стрелок серий под ним;
 *  - [Settings] — правая колонка нижней строки: сетка настроек.
 *
 * У каждого блока своя точка входа: полоса, Play, первая плитка сетки. Внутри блока курсор ходит
 * по его геометрии, на границе — переходит в соседний блок ровно на его точку входа.
 */
internal enum class PlayerMode { Transport, Progress, EpisodeNav, Settings }

/** Какая из двух стрелок под Play выбрана сейчас в [PlayerMode.EpisodeNav]. */
internal enum class EpisodeNavArrow { Previous, Next }

/**
 * Пункт сетки настроек. Первые пять открывают поповер выбора, [Episodes] — панель сезонов
 * и серий, [NextEpisode] — действие сразу.
 */
/**
 * Вариант категории настроек плеера: [label] — строка поповера, [shortValue] — то же значение
 * коротко, под кнопку/плитку («rus», «FHD», «Ориг. + EN»). Оба уже посчитаны из типизированных
 * данных модели (см. `PlayerChoices.kt`): интерфейс ничего не разбирает из подписи.
 */
internal data class PlayerChoice(val label: String, val shortValue: String)

internal enum class SettingsAction(val label: String) {
    Preset("Пресет"),
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
// Восемь полей — и есть весь API меню плеера; сворачивать их во вложенные структуры ради порога
// значило бы разрезать одну раскладку на куски.
@Suppress("LongParameterList")
internal class PlayerActions(
    val items: List<SettingsAction>,
    val options: (SettingsAction) -> List<PlayerChoice>,
    /** Выбранный сейчас вариант категории (null — выбора нет); по нему считается стартовый курсор поповера. */
    val selected: (SettingsAction) -> PlayerChoice?,
    /** Выбор варианта по его индексу в [options] — тот же индекс, что у курсора поповера. */
    val onSelect: (SettingsAction, Int) -> Unit,
    val onNextEpisode: () -> Unit,
    /** null — предыдущей серии нет (первый эпизод) или граф не дал навигацию по сериям. */
    val onPreviousEpisode: (() -> Unit)? = null,
    val episodes: EpisodesPanelData? = null,
    val enabled: (SettingsAction) -> Boolean = { true },
) {
    /** Есть ли следующая серия и навигация к ней — условие автоперехода и пункта в ряду. */
    val hasNextEpisode: Boolean get() = SettingsAction.NextEpisode in items

    /** Есть ли предыдущая серия — условие показа стрелки «влево» под Play. */
    val hasPreviousEpisode: Boolean get() = onPreviousEpisode != null

    fun selectedIndex(action: SettingsAction): Int =
        selected(action)?.let { options(action).indexOf(it) }?.coerceAtLeast(0) ?: 0

    fun isEnabled(action: SettingsAction): Boolean = enabled(action)
}

/**
 * Состояние оверлея UI 1 и его раскладка пульта — три блока (см. [PlayerMode]): полоса прокрутки,
 * Play со стрелками серий и сетка плиток настроек.
 */
// Обработчики блоков и есть API раскладки — дробить их по классам значило бы разорвать её на куски.
@Suppress("TooManyFunctions")
@Stable
internal class TvPlayerUiState(player: Player) : BasePlayerUiState(player) {

    var mode by mutableStateOf(PlayerMode.Transport)
    var settingsCursor by mutableIntStateOf(0)

    /** Выбранная стрелка в [PlayerMode.EpisodeNav] — что сделает OK. */
    var episodeNavArrow by mutableStateOf(EpisodeNavArrow.Next)

    override fun hideOverlay() {
        super.hideOverlay()
        mode = PlayerMode.Transport
    }

    /**
     * Раскладка D-pad по блокам оверлея (см. [PlayerMode]):
     *
     *  - Полоса прокрутки: ◄/► — перемотка, ▼ — на Play. OK — пауза/воспроизведение.
     *  - Play: OK — пауза/воспроизведение, ▲ — полоса, ▼ — стрелки серий (если есть хоть одна),
     *    ► — сетка настроек. Вход в блок всегда на Play.
     *  - Стрелки серий: вход на «Следующая» (если она есть), ◄ — «Предыдущая», ► — «Следующая»
     *    или дальше в сетку, ▲ — Play.
     *  - Сетка: вход на первую плитку слева сверху; ◄/► — по столбцам, ▲/▼ — внутри столбца;
     *    ▲ из верхнего ряда — полоса, ◄ из левого столбца — Play.
     *
     * Неизвестные клавиши не трогаем — иначе съедим громкость и системные.
     */
    override fun onKey(key: Key, menu: PlayerActions): Boolean = when {
        // OK при видимой плашке автоперехода (и только в транспорте) — следующая серия сразу.
        autoNextVisible && submenu == null && !episodesOpen && mode == PlayerMode.Transport &&
            (key == Key.DirectionCenter || key == Key.Enter) -> {
            acceptAutoNext(menu)
            true
        }
        episodesOpen -> onEpisodesKey(key, menu)
        submenu != null -> onSubmenuKey(key, menu)
        mode == PlayerMode.Settings -> onSettingsKey(key, menu)
        mode == PlayerMode.EpisodeNav -> onEpisodeNavKey(key, menu)
        else -> onTransportKey(key, menu)
    }

    /**
     * Сетка настроек — по геометрии (см. [SettingsGridNavigation]): ◄/► ходят между столбцами,
     * ▲/▼ — внутри столбца. Слева от левого столбца — Play; выше верхнего ряда — полоса прокрутки.
     * Вправо и вниз за краем сетки ничего нет — курсор остаётся на месте.
     */
    private fun onSettingsKey(key: Key, menu: PlayerActions): Boolean {
        when (key) {
            Key.DirectionLeft -> {
                val next = menu.neighbourColumn(settingsCursor, -1)
                if (next == null) openTransport() else settingsCursor = next
            }
            Key.DirectionRight -> menu.neighbourColumn(settingsCursor, +1)?.let { settingsCursor = it }
            Key.DirectionUp -> {
                val above = menu.sameColumnNeighbour(settingsCursor, -1)
                if (above != null) settingsCursor = above else openProgress()
            }
            Key.DirectionDown -> menu.sameColumnNeighbour(settingsCursor, +1)?.let { settingsCursor = it }
            Key.DirectionCenter, Key.Enter -> menu.items.getOrNull(settingsCursor)?.let { activate(it, menu) }
            else -> return false
        }
        touch()
        return true
    }

    /** Play и полоса прокрутки — оба «транспорт» (OK везде пауза/воспроизведение), но соседи разные. */
    private fun onTransportKey(key: Key, menu: PlayerActions): Boolean = when (mode) {
        PlayerMode.Progress -> onProgressKey(key)
        PlayerMode.Transport, PlayerMode.EpisodeNav, PlayerMode.Settings -> onPlayKey(key, menu)
    }

    /** Полоса прокрутки: горизонталь — перемотка, ▼ — на Play, ▲ — некуда, стоим. */
    private fun onProgressKey(key: Key): Boolean {
        when (key) {
            Key.DirectionLeft, Key.MediaRewind -> scrub(-1)
            Key.DirectionRight, Key.MediaFastForward -> scrub(1)
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> togglePlay()
            Key.DirectionDown -> openTransport()
            Key.DirectionUp -> touch()
            else -> return false
        }
        return true
    }

    /** Кнопка Play: ▲ — полоса, ▼ — стрелки серий (если есть), ► — сетка, ◄ — некуда, стоим. */
    private fun onPlayKey(key: Key, menu: PlayerActions): Boolean {
        when (key) {
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> togglePlay()
            Key.DirectionUp -> openProgress()
            Key.DirectionDown -> if (menu.hasPreviousEpisode || menu.hasNextEpisode) openEpisodeNav(menu) else touch()
            Key.DirectionRight -> openSettings(menu)
            Key.DirectionLeft, Key.MediaRewind, Key.MediaFastForward -> touch()
            else -> return false
        }
        return true
    }

    /** Точка входа в левую колонку — всегда Play, откуда бы ни пришли. */
    private fun openTransport() {
        mode = PlayerMode.Transport
        seekLabel = null
        touch()
    }

    private fun openProgress() {
        mode = PlayerMode.Progress
        seekLabel = null
        touch()
    }

    /**
     * Стрелки соседних серий под Play. Курсор встаёт на «Следующая», если она есть — это
     * основной сценарий (долистать сериал), «Предыдущая» — только когда следующей нет.
     */
    private fun openEpisodeNav(menu: PlayerActions) {
        if (!menu.hasPreviousEpisode && !menu.hasNextEpisode) return
        mode = PlayerMode.EpisodeNav
        episodeNavArrow = if (menu.hasNextEpisode) EpisodeNavArrow.Next else EpisodeNavArrow.Previous
        seekLabel = null
        touch()
    }

    /**
     * Стрелки под Play: ◄ — «Предыдущая» (если есть), ► — «Следующая», а с неё (или когда
     * следующей нет) — дальше в сетку настроек; ▲ — Play; ▼ — под стрелками ничего нет.
     */
    // Тот же каркас, что у onSettingsKey/onEpisodesKey: ветка «клавиша не наша» обязана вернуть false.
    @Suppress("ReturnCount")
    private fun onEpisodeNavKey(key: Key, menu: PlayerActions): Boolean {
        when (key) {
            Key.DirectionLeft -> if (menu.hasPreviousEpisode) episodeNavArrow = EpisodeNavArrow.Previous
            Key.DirectionRight -> if (episodeNavArrow == EpisodeNavArrow.Previous && menu.hasNextEpisode) {
                episodeNavArrow = EpisodeNavArrow.Next
            } else {
                openSettings(menu)
            }
            Key.DirectionUp -> mode = PlayerMode.Transport
            Key.DirectionDown -> Unit
            Key.DirectionCenter, Key.Enter -> when (episodeNavArrow) {
                EpisodeNavArrow.Previous -> menu.onPreviousEpisode?.invoke()
                EpisodeNavArrow.Next -> menu.onNextEpisode()
            }
            else -> return false
        }
        touch()
        return true
    }

    /** Точка входа в сетку — первая включённая плитка слева сверху, откуда бы ни пришли. */
    private fun openSettings(menu: PlayerActions) {
        if (menu.items.isEmpty()) return
        mode = PlayerMode.Settings
        settingsCursor = SettingsGridNavigation.firstEnabled(menu.items.size) { menu.isEnabled(menu.items[it]) }
        seekLabel = null
        touch()
    }

    fun activate(action: SettingsAction, menu: PlayerActions) {
        if (!menu.isEnabled(action)) return
        when (action) {
            SettingsAction.NextEpisode -> menu.onNextEpisode()
            SettingsAction.Episodes -> openEpisodes(menu)
            SettingsAction.Preset, SettingsAction.Quality, SettingsAction.Audio, SettingsAction.Subtitle,
            SettingsAction.Speed,
            -> openSubmenu(action, menu)
        }
        touch()
    }

    private fun PlayerActions.neighbourColumn(current: Int, delta: Int): Int? =
        SettingsGridNavigation.neighbourColumn(current, delta, items.size, SETTINGS_GRID_ROWS) { isEnabled(items[it]) }

    private fun PlayerActions.sameColumnNeighbour(current: Int, delta: Int): Int? =
        SettingsGridNavigation.sameColumnNeighbour(current, delta, items.size, SETTINGS_GRID_ROWS) {
            isEnabled(items[it])
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

/** В каждом столбце сетки ровно две плитки — то же число, по которому `SettingsGrid` режет `items`. */
internal const val SETTINGS_GRID_ROWS = 2
