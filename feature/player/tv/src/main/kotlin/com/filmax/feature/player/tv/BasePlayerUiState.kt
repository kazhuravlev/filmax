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

@Suppress("TooManyFunctions")
@Stable
internal abstract class BasePlayerUiState(val player: Player) {
    var visible by mutableStateOf(true)

    var submenu by mutableStateOf<SettingsAction?>(null)
    var submenuCursor by mutableIntStateOf(0)

    var episodesOpen by mutableStateOf(false)
    var episodesSeasonCursor by mutableIntStateOf(0)
    var episodesCursor by mutableIntStateOf(0)

    var autoNextVisible by mutableStateOf(false)
    var autoNextSeconds by mutableIntStateOf(0)
    var autoNextDismissed by mutableStateOf(false)

    var seekLabel by mutableStateOf<String?>(null)

    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    var bufferedMs by mutableLongStateOf(0L)

    var isScrubbing by mutableStateOf(false)
    var scrubTargetMs by mutableLongStateOf(0L)

    var interactionTick by mutableIntStateOf(0)
    var seekTick by mutableIntStateOf(0)

    private var seekStreak = 0
    private var lastSeekAtMs = 0L

    fun updateAutoNext(remainingMs: Long, enabled: Boolean, playing: Boolean) {
        val inWindow = enabled && !autoNextDismissed && !isScrubbing &&
            remainingMs <= AUTO_NEXT_WINDOW_MS
        when {
            inWindow && !autoNextVisible -> autoNextSeconds = AUTO_NEXT_COUNTDOWN_SEC
            inWindow && playing -> autoNextSeconds = (autoNextSeconds - 1).coerceAtLeast(0)
        }
        autoNextVisible = inWindow
    }

    open val idleHidesOverlay: Boolean
        get() = visible

    fun touch() {
        visible = true
        interactionTick++
    }

    open fun hideOverlay() {
        visible = false
        submenu = null
        episodesOpen = false
        seekLabel = null
    }

    abstract fun onKey(key: Key, menu: PlayerActions): Boolean

    protected fun acceptAutoNext(menu: PlayerActions) {
        autoNextVisible = false
        menu.onNextEpisode()
    }

    protected fun openEpisodes(menu: PlayerActions) {
        val panel = menu.episodes ?: return
        episodesSeasonCursor = panel.currentSeasonIndex
        episodesCursor = panel.currentEpisodeIndex
        episodesOpen = true
    }

    protected fun openSubmenu(action: SettingsAction, menu: PlayerActions) {
        submenu = action
        submenuCursor = menu.selectedIndex(action)
    }

    protected fun seekBy(deltaMs: Long) {
        commitScrub()
        val duration = durationMs.takeIf { it > 0 } ?: return
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        positionMs = target
        touch()
    }

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

    @Suppress("ReturnCount")
    protected fun onSubmenuKey(key: Key, menu: PlayerActions): Boolean {
        val category = submenu ?: return false
        val options = menu.options(category)
        when (key) {
            Key.DirectionUp -> submenuCursor = (submenuCursor - 1).coerceAtLeast(0)
            Key.DirectionDown -> submenuCursor = (submenuCursor + 1).coerceAtMost(options.lastIndex)
            Key.DirectionCenter, Key.Enter -> {
                if (submenuCursor in options.indices) menu.onSelect(category, submenuCursor)
                submenu = null
            }
            Key.DirectionLeft, Key.DirectionRight -> Unit
            else -> return false
        }
        touch()
        return true
    }

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
        autoNextVisible -> {
            autoNextDismissed = true
            autoNextVisible = false
            touch()
            true
        }
        else -> false
    }

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
        seekLabel = if (direction > 0) "+$stepSec с" else "−$stepSec с"
        seekTick++
        touch()
    }

    fun commitScrub() {
        if (!isScrubbing) return
        player.seekTo(scrubTargetMs)
        positionMs = scrubTargetMs
        isScrubbing = false
    }

    protected fun togglePlay() {
        commitScrub()
        if (player.isPlaying) player.pause() else player.play()
        seekLabel = null
        touch()
    }
}
