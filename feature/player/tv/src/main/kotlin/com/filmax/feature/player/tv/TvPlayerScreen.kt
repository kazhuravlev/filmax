package com.filmax.feature.player.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.feature.player.common.PlaybackSpeeds
import com.filmax.feature.player.common.PlayerEvent
import com.filmax.feature.player.common.PlayerScreenModel
import com.filmax.feature.player.common.PlayerState
import org.koin.androidx.compose.koinViewModel

@Composable
fun TvPlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayEpisode: ((season: Int, videoId: Int) -> Unit)? = null,
    screenModel: PlayerScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()
    val appError by screenModel.collectErrorAsState()

    val episodesPanel = remember(state.item, state.track, onPlayEpisode) {
        episodesPanelData(state.item?.tracklist.orEmpty(), state.track, onPlayEpisode)
    }
    val menu = playerMenu(
        state = state,
        episodesPanel = episodesPanel,
        onPlayEpisode = onPlayEpisode,
        dispatch = screenModel::dispatch,
    )
    val next = state.nextTrack
    val session = TvPlayerSession(
        player = screenModel.player,
        title = state.item?.title.orEmpty(),
        subtitle = playerSubtitle(state),
        description = state.item?.plot.orEmpty(),
        loading = state.loading,
        error = appError,
        subscriptionRequired = state.subscriptionRequired,
        autoNextLabel = next?.let { "Дальше: ${it.number}. ${it.title.ifBlank { "Серия ${it.number}" }}" },
        menu = menu,
        onSignal = { signal -> screenModel.dispatch(signal.toEvent()) },
        onBack = onBack,
    )
    TvPlayer(session = session, modifier = modifier)
}

private fun PlaybackSignal.toEvent(): PlayerEvent = when (this) {
    is PlaybackSignal.Progress -> PlayerEvent.SaveProgress(positionMs, durationMs)
    PlaybackSignal.Ended -> PlayerEvent.MarkWatched
    PlaybackSignal.AutoNextShown -> PlayerEvent.PrefetchNextEpisode
}

private fun playerMenu(
    state: PlayerState,
    episodesPanel: EpisodesPanelData?,
    onPlayEpisode: ((season: Int, videoId: Int) -> Unit)?,
    dispatch: (PlayerEvent) -> Unit,
): PlayerActions = PlayerActions(
    items = buildList {
        add(SettingsAction.Preset)
        add(SettingsAction.Audio)
        add(SettingsAction.Subtitle)
        add(SettingsAction.Speed)
        if (state.nextTrack != null && onPlayEpisode != null) add(SettingsAction.NextEpisode)
        add(SettingsAction.Quality)
        if (episodesPanel != null) add(SettingsAction.Episodes)
    },
    options = { action -> action.options(state) },
    selected = { action -> action.selected(state) },
    onSelect = { action, index -> action.toEvent(state, index)?.let(dispatch) },
    onNextEpisode = {
        state.nextTrack?.let { next -> onPlayEpisode?.invoke(next.seasonNumber, next.number) }
    },
    onPreviousEpisode = if (state.previousTrack != null && onPlayEpisode != null) {
        { state.previousTrack?.let { previous -> onPlayEpisode.invoke(previous.seasonNumber, previous.number) } }
    } else {
        null
    },
    episodes = episodesPanel,
    enabled = { action ->
        when (action) {
            SettingsAction.Audio -> state.audioTracks.size > 1
            SettingsAction.Subtitle -> state.subtitles.size > 1
            SettingsAction.Quality -> state.qualities.size > 1
            SettingsAction.Preset, SettingsAction.Speed, SettingsAction.Episodes, SettingsAction.NextEpisode -> true
        }
    },
)

private fun episodesPanelData(
    tracks: List<MediaTrack>,
    track: MediaTrack?,
    onPlayEpisode: ((season: Int, videoId: Int) -> Unit)?,
): EpisodesPanelData? {
    if (tracks.size < 2 || onPlayEpisode == null) return null
    val seasons = tracks
        .groupBy { it.seasonNumber }
        .toSortedMap()
        .map { (number, episodes) -> number to episodes.sortedBy { it.number } }
    val seasonIndex = seasons.indexOfFirst { it.first == track?.seasonNumber }.coerceAtLeast(0)
    val episodeIndex = seasons.getOrNull(seasonIndex)?.second.orEmpty()
        .indexOfFirst { it.id == track?.id }
        .coerceAtLeast(0)
    return EpisodesPanelData(
        seasons = seasons,
        currentTrackId = track?.id,
        currentSeasonIndex = seasonIndex,
        currentEpisodeIndex = episodeIndex,
        onPlayEpisode = onPlayEpisode,
    )
}

private fun playerSubtitle(state: PlayerState): String {
    val track = state.track ?: return ""
    val episodeTitle = track.title.takeIf { it.isNotBlank() && it != "Серия ${track.number}" }
    return when {
        track.seasonNumber > 0 ->
            listOfNotNull("Сезон ${track.seasonNumber} · Серия ${track.number}", episodeTitle).joinToString(" · ")
        state.item?.tracklist.orEmpty().size > 1 ->
            listOfNotNull("Серия ${track.number}", episodeTitle).joinToString(" · ")
        else -> listOfNotNull(state.item?.year?.takeIf { it > 0 }?.toString(), state.currentQuality?.label)
            .joinToString(" · ")
    }
}

private fun SettingsAction.options(state: PlayerState): List<PlayerChoice> = when (this) {
    SettingsAction.Quality -> state.qualities.map { it.toChoice() }
    SettingsAction.Preset -> PlaybackSettings.presetOptions.map { it.toChoice() }
    SettingsAction.Audio -> state.audioTracks.map { it.toChoice() }
    SettingsAction.Subtitle -> state.subtitles.map { it.toChoice() }
    SettingsAction.Speed -> PlaybackSpeeds.options.map { it.toChoice() }
    SettingsAction.Episodes, SettingsAction.NextEpisode -> emptyList()
}

private fun SettingsAction.selected(state: PlayerState): PlayerChoice? = when (this) {
    SettingsAction.Quality -> state.currentQuality?.toChoice()
    SettingsAction.Preset -> state.currentPreset.toChoice()
    SettingsAction.Audio -> state.currentAudio?.toChoice()
    SettingsAction.Subtitle -> state.currentSubtitle.toChoice()
    SettingsAction.Speed -> PlaybackSpeeds.optionFor(state.currentSpeed).toChoice()
    SettingsAction.Episodes, SettingsAction.NextEpisode -> null
}

private fun presetEvent(index: Int): PlayerEvent? =
    PlaybackSettings.presetOptions.takeIf { index in it.indices }?.let { PlayerEvent.SelectPreset(it[index]) }

private fun SettingsAction.toEvent(state: PlayerState, index: Int): PlayerEvent? = when (this) {
    SettingsAction.Quality -> state.qualities.getOrNull(index)?.let(PlayerEvent::SelectQuality)
    SettingsAction.Preset -> presetEvent(index)
    SettingsAction.Audio -> state.audioTracks.getOrNull(index)?.let(PlayerEvent::SelectAudio)
    SettingsAction.Subtitle -> state.subtitles.getOrNull(index)?.let(PlayerEvent::SelectSubtitle)
    SettingsAction.Speed -> PlaybackSpeeds.options.getOrNull(index)?.let { PlayerEvent.SetSpeed(it.value) }
    SettingsAction.Episodes, SettingsAction.NextEpisode -> null
}
