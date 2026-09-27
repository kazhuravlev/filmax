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

/**
 * Общая часть состояния любого интерфейса плеера: зеркала плеера, скраббинг с разгоном,
 * автопереход, поповер выбора и панель серий, автоскрытие оверлея. Раскладку пульта по своим
 * блокам каждый интерфейс задаёт сам в [onKey]; сюда вынесено только то, что у всех одинаково.
 *
 * Плеер — единственный экран, где D-pad НЕ ходит по фокусу: стрелки это транспорт, а не навигация
 * (правило Google, training/tv/playback/controls). Поэтому «курсор» по кнопкам и по списку поповера
 * эмулируется индексами, а клавиши разбирает один обработчик: пока открыт поповер, всё уходит
 * в него, и панель не может «зависнуть», когда фокус ушёл мимо списка.
 */
// Клавиатурный автомат плеера: обработчики слоёв (поповер/панель серий/скраббинг) и есть его API —
// дробить их по классам значило бы разорвать одну раскладку пульта на куски.
@Suppress("TooManyFunctions")
@Stable
internal abstract class BasePlayerUiState(val player: Player) {

    /** Виден ли оверлей поверх кадра. */
    var visible by mutableStateOf(true)

    /** Открытая категория поповера выбора; null — поповера нет. */
    var submenu by mutableStateOf<SettingsAction?>(null)
    var submenuCursor by mutableIntStateOf(0)

    /** Открыта ли панель серий; курсоры — выбранный сезон и серия внутри него. */
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

    /** Докуда докачан поток — интерфейс может показать это на полосе. */
    var bufferedMs by mutableLongStateOf(0L)

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
    open val idleHidesOverlay: Boolean
        get() = visible

    /** Было действие пользователя: оверлей на экран, таймер автоскрытия — с нуля. */
    fun touch() {
        visible = true
        interactionTick++
    }

    /** Закрывает весь интерфейс одним действием, не оставляя скрытый поповер активным. */
    open fun hideOverlay() {
        visible = false
        submenu = null
        episodesOpen = false
        seekLabel = null
    }

    /**
     * Раскладка пульта интерфейса. Неизвестные клавиши обязаны вернуть false — иначе плеер
     * съест громкость и системные кнопки.
     */
    abstract fun onKey(key: Key, menu: PlayerActions): Boolean

    /** OK при видимой плашке автоперехода — следующая серия сразу; вызывающий решает, когда это уместно. */
    protected fun acceptAutoNext(menu: PlayerActions) {
        autoNextVisible = false
        menu.onNextEpisode()
    }

    /** Открывает панель серий на играющей сейчас — переключить на соседнюю быстрее всего. */
    protected fun openEpisodes(menu: PlayerActions) {
        val panel = menu.episodes ?: return
        episodesSeasonCursor = panel.currentSeasonIndex
        episodesCursor = panel.currentEpisodeIndex
        episodesOpen = true
    }

    /** Открывает поповер выбора с курсором на текущем значении категории. */
    protected fun openSubmenu(action: SettingsAction, menu: PlayerActions) {
        submenu = action
        submenuCursor = menu.selectedIndex(action)
    }

    /** Перемотка на фиксированный шаг сразу, без скраббинга — для кнопок «±10 с». */
    protected fun seekBy(deltaMs: Long) {
        commitScrub()
        val duration = durationMs.takeIf { it > 0 } ?: return
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        positionMs = target
        touch()
    }

    /**
     * Панель серий: ↑/↓ — по сериям сезона, ◄/► — соседний сезон (курсор серий — в начало),
     * OK — играть выбранную. Как и поповер, панель забирает весь ввод, кроме чужих клавиш.
     */
    // Та же структура, что у onSubmenuKey: ветка «клавиша не наша» обязана вернуть false.
    @Suppress("ReturnCount")
    protected fun onEpisodesKey(key: Key, menu: PlayerActions): Boolean {
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
    protected fun onSubmenuKey(key: Key, menu: PlayerActions): Boolean {
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

    /**
     * «Назад» снимает по одному слою за нажатие: сначала поповер или панель серий — оверлей при
     * этом остаётся, и курсор стоит там же, где был (на кнопке, из которой открыли выбор);
     * потом сам оверлей. Когда интерфейс уже скрыт, оставляет специальное действие только для
     * плашки автоперехода; иначе возвращает false, чтобы экран вышел из плеера.
     */
    fun back(): Boolean = when {
        episodesOpen -> {
            episodesOpen = false
            touch()
            true
        }
        submenu != null -> {
            submenu = null
            touch()
            true
        }
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
    protected fun scrub(direction: Int) {
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

    protected fun togglePlay() {
        // Незакоммиченный скраб не теряем: сначала уходим на выбранную позицию, потом переключаем.
        commitScrub()
        if (player.isPlaying) player.pause() else player.play()
        seekLabel = null
        touch()
    }
}
