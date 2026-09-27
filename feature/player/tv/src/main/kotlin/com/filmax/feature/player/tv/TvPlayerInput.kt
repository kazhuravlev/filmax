package com.filmax.feature.player.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.media3.common.Player
import com.filmax.core.domain.catalog.model.MediaTrack

internal enum class PlayerMode { Transport, Progress, EpisodeNav, Settings }

internal enum class EpisodeNavArrow { Previous, Next }

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

internal class EpisodesPanelData(
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    val currentTrackId: Int?,
    val currentSeasonIndex: Int,
    val currentEpisodeIndex: Int,
    val onPlayEpisode: (season: Int, videoId: Int) -> Unit,
)

@Suppress("LongParameterList")
internal class PlayerActions(
    val items: List<SettingsAction>,
    val options: (SettingsAction) -> List<PlayerChoice>,
    val selected: (SettingsAction) -> PlayerChoice?,
    val onSelect: (SettingsAction, Int) -> Unit,
    val onNextEpisode: () -> Unit,
    val onPreviousEpisode: (() -> Unit)? = null,
    val episodes: EpisodesPanelData? = null,
    val enabled: (SettingsAction) -> Boolean = { true },
) {
    val hasNextEpisode: Boolean get() = SettingsAction.NextEpisode in items

    val hasPreviousEpisode: Boolean get() = onPreviousEpisode != null

    fun selectedIndex(action: SettingsAction): Int =
        selected(action)?.let { options(action).indexOf(it) }?.coerceAtLeast(0) ?: 0

    fun isEnabled(action: SettingsAction): Boolean = enabled(action)
}

@Suppress("TooManyFunctions")
@Stable
internal class TvPlayerUiState(player: Player) : BasePlayerUiState(player) {
    var mode by mutableStateOf(PlayerMode.Transport)
    var settingsCursor by mutableIntStateOf(0)

    var episodeNavArrow by mutableStateOf(EpisodeNavArrow.Next)

    override fun hideOverlay() {
        super.hideOverlay()
        mode = PlayerMode.Transport
    }

    override fun onKey(key: Key, menu: PlayerActions): Boolean = when {
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

    private fun onTransportKey(key: Key, menu: PlayerActions): Boolean = when (mode) {
        PlayerMode.Progress -> onProgressKey(key)
        PlayerMode.Transport, PlayerMode.EpisodeNav, PlayerMode.Settings -> onPlayKey(key, menu)
    }

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

    private fun openEpisodeNav(menu: PlayerActions) {
        if (!menu.hasPreviousEpisode && !menu.hasNextEpisode) return
        mode = PlayerMode.EpisodeNav
        episodeNavArrow = if (menu.hasNextEpisode) EpisodeNavArrow.Next else EpisodeNavArrow.Previous
        seekLabel = null
        touch()
    }

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

internal const val PROGRESS_TICK_MS = 1000L

internal const val OVERLAY_AUTO_HIDE_MS = 5_000L

internal const val SCRUB_COMMIT_TIMEOUT_MS = 700L

internal const val SEEK_LABEL_HOLD_MS = 800L

internal const val SEEK_STREAK_WINDOW_MS = 450L

internal const val MILLIS_IN_SECOND = 1000L

internal val SEEK_STEPS_SEC = listOf(10, 10, 20, 30, 60, 90, 120)

internal const val AUTO_NEXT_WINDOW_MS = 20_000L

internal const val AUTO_NEXT_COUNTDOWN_SEC = 5

internal const val SETTINGS_GRID_ROWS = 2
