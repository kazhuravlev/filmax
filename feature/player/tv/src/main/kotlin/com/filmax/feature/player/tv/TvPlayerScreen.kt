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

/**
 * TV-экран тайтла: собирает [TvPlayerSession] из [PlayerScreenModel] и отдаёт её абстрактному
 * плееру [TvPlayer], который рисует выбранный в настройках интерфейс.
 *
 * [onPlayEpisode] задаёт граф навигации: другая серия — это новый экран плеера с новым
 * [PlayerScreenModel], а не подмена MediaItem (иначе прогресс писался бы в предыдущую серию).
 * null — панели серий и «Следующей серии» не будет.
 */
@Composable
fun TvPlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayEpisode: ((season: Int, videoId: Int) -> Unit)? = null,
    screenModel: PlayerScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()
    val appError by screenModel.collectErrorAsState()

    // Панель серий есть только у сериала и только когда граф дал навигацию по сериям.
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

/** Сигналы интерфейса — в события модели: прогресс на сервер, отметка «досмотрено», прогрев серии. */
private fun PlaybackSignal.toEvent(): PlayerEvent = when (this) {
    is PlaybackSignal.Progress -> PlayerEvent.SaveProgress(positionMs, durationMs)
    PlaybackSignal.Ended -> PlayerEvent.MarkWatched
    PlaybackSignal.AutoNextShown -> PlayerEvent.PrefetchNextEpisode
}

/**
 * Порядок плиток справа от Play: пресет первым (слева сверху), под ним аудио, дальше субтитры и
 * скорость, следующая серия/качество, серии.
 */
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
    onSelect = { action, label -> action.toEvent(label)?.let(dispatch) },
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
            else -> true
        }
    },
)

/**
 * Данные панели серий из плейлиста: сезоны отсортированы, стартовый курсор — играющая серия.
 * null — фильм (один трек) или граф не дал [onPlayEpisode].
 */
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

/**
 * Подстрока шапки: «Сезон 2 · Серия 5 · Название серии» у сериала (название — если оно не
 * дублирует номер), «год · качество» у фильма.
 */
private fun playerSubtitle(state: PlayerState): String {
    val track = state.track ?: return ""
    val episodeTitle = track.title.takeIf { it.isNotBlank() && it != "Серия ${track.number}" }
    return when {
        track.seasonNumber > 0 ->
            listOfNotNull("Сезон ${track.seasonNumber} · Серия ${track.number}", episodeTitle).joinToString(" · ")
        state.item?.tracklist.orEmpty().size > 1 ->
            listOfNotNull("Серия ${track.number}", episodeTitle).joinToString(" · ")
        else -> listOfNotNull(state.item?.year?.takeIf { it > 0 }?.toString(), state.currentQuality)
            .joinToString(" · ")
    }
}

private fun SettingsAction.options(state: PlayerState): List<String> = when (this) {
    SettingsAction.Quality -> state.qualities.map { it.label }
    SettingsAction.Preset -> PlaybackSettings.presetOptions
    SettingsAction.Audio -> state.audioTracks.map { it.label }
    SettingsAction.Subtitle -> state.subtitles.map { it.label }
    SettingsAction.Speed -> PlaybackSpeeds.labels
    SettingsAction.Episodes, SettingsAction.NextEpisode -> emptyList()
}

private fun SettingsAction.selected(state: PlayerState): String = when (this) {
    SettingsAction.Quality -> state.currentQuality.orEmpty()
    // Короткая подпись плитки в списке поповера отсутствует («Свой» и shortLabel) — курсор встанет
    // на «Авто», это ожидаемо: пресет сейчас не выбран.
    SettingsAction.Preset -> state.currentPreset
    SettingsAction.Audio -> state.currentAudio
    SettingsAction.Subtitle -> state.currentSubtitle
    SettingsAction.Speed -> PlaybackSpeeds.labelFor(state.currentSpeed)
    SettingsAction.Episodes, SettingsAction.NextEpisode -> ""
}

private fun SettingsAction.toEvent(label: String): PlayerEvent? = when (this) {
    SettingsAction.Quality -> PlayerEvent.SelectQuality(label)
    SettingsAction.Preset -> PlayerEvent.SelectPreset(label)
    SettingsAction.Audio -> PlayerEvent.SelectAudio(label)
    SettingsAction.Subtitle -> PlayerEvent.SelectSubtitle(label)
    SettingsAction.Speed -> PlaybackSpeeds.valueFor(label)?.let { PlayerEvent.SetSpeed(it) }
    SettingsAction.Episodes, SettingsAction.NextEpisode -> null
}
