package com.filmax.feature.player.common

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.AudioTrack
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.common.ErrorReporting
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.RequestFailure
import com.filmax.core.domain.playback.AudioPreference
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.QualityPreference
import com.filmax.core.domain.playback.SubtitleKey
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackLanguage
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.playback.VoiceKey
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.isFinishedByPosition
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import com.filmax.feature.player.common.navigation.PlayerRoute
import kotlinx.coroutines.flow.first
import kotlin.math.abs

@Suppress("TooManyFunctions")
class PlayerScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    private val userRepository: UserRepository,
    context: Context,
) : BaseScreenModel<PlayerState, PlayerSideEffect, PlayerEvent>(PlayerState()) {
    private val route = savedStateHandle.toRoute<PlayerRoute>()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()

    private var globalPreset: TrackPreset? = null

    private var titleTracks: TitleTracks? = null

    private var lastTracks: Tracks? = null

    private var currentVoiceKey: VoiceKey? = null
    private var currentSubtitleKey: SubtitleKey = SubtitleOption.Off.preferenceKey()

    private var selectedTrack: MediaTrack? = null

    private var audioGroups: List<Tracks.Group> = emptyList()

    private var textGroups: List<Tracks.Group> = emptyList()

    private var lastSentSeconds: Int? = null

    private var streamVariantIndex = 0

    private var nextEpisodePrefetched = false

    private var watchedMarked = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                ErrorReporting.reporter.report(RequestFailure.of(AppError.Playback, error))
                if (!playNextStreamVariant()) {
                    screenModelScope { showError(AppError.Playback) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                lastTracks = tracks
                applyTracks(tracks)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ImagePrefetchThrottle.setPlaying(isPlaying)
            }
        })
        onFetchData()
    }

    override fun dispatch(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.SaveProgress -> saveProgress(event.positionMs, event.durationMs)
            PlayerEvent.MarkWatched -> markWatched()
            is PlayerEvent.SelectQuality -> selectQuality(event.quality)
            is PlayerEvent.SelectAudio -> selectAudio(event.option)
            is PlayerEvent.SelectSubtitle -> selectSubtitle(event.option)
            is PlayerEvent.SelectPreset -> selectPreset(event.preset)
            is PlayerEvent.SetSpeed -> {
                player.setPlaybackSpeed(event.speed)
                screenModelScope { _ -> updateState { it.copy(currentSpeed = event.speed) } }
            }
            PlayerEvent.PrefetchNextEpisode -> prefetchNextEpisode()
        }
    }

    private fun prefetchNextEpisode() {
        if (nextEpisodePrefetched) return
        nextEpisodePrefetched = true
        screenModelScope { _ -> catalog.getItemDetails(route.itemId, forceRefresh = true) }
    }

    private fun checkSubscription() {
        screenModelScope { _ ->
            val profile = userRepository.getProfile()
            if (profile is RequestResult.Success && profile.data.subscription?.active != true) {
                updateState { it.copy(subscriptionRequired = true) }
            }
        }
    }

    override fun onFetchData() {
        checkSubscription()
        screenModelScope { _ ->
            val settings = playbackSettings.settings.first()
            globalPreset = settings.preset
            titleTracks = playbackSettings.titleTracksFor(route.itemId)
            when (val result = catalog.getItemDetails(route.itemId, forceRefresh = true)) {
                is RequestResult.Success -> {
                    val item = result.data
                    val trackIndex = item.tracklist.indexOfFirst { it.matchesRoute(route) }.coerceAtLeast(0)
                    val track = item.tracklist.getOrNull(trackIndex)
                    selectedTrack = track

                    val qualities = streamQualities(track)
                    val preferred = when (val quality = settings.quality) {
                        QualityPreference.Auto -> null
                        is QualityPreference.Fixed -> qualities.firstOrNull { it.label == quality.label }
                    }
                    val initial = preferred ?: qualities.firstOrNull()

                    updateState {
                        it.copy(
                            loading = false,
                            item = item,
                            track = track,
                            previousTrack = item.tracklist.getOrNull(trackIndex - 1),
                            nextTrack = item.tracklist.getOrNull(trackIndex + 1),
                            streamUrl = initial?.url,
                            qualities = qualities,
                            currentQuality = initial,
                        )
                    }

                    if (initial != null) {
                        streamVariantIndex = 0
                        reportPlaybackStart(initial)
                        player.setMediaItem(buildMediaItem(initial.url))
                        player.prepare()
                        applyAudioPreference()
                        route.resumePositionSeconds
                            .takeIf { it > 0 }
                            ?.let { player.seekTo(it * MILLIS_IN_SECOND) }
                        player.playWhenReady = true
                    }
                }

                is RequestResult.Error -> {
                    updateState { it.copy(loading = false, error = result.message) }
                    showError(result)
                }
            }
        }
    }

    private fun streamQualities(track: MediaTrack?): List<StreamQuality> =
        track?.files.orEmpty().mapNotNull { file ->
            listOfNotNull(file.hls4, file.hls, file.http)
                .takeIf { it.isNotEmpty() }
                ?.let { StreamQuality(file.quality, it) }
        }

    private fun selectQuality(quality: StreamQuality) {
        if (quality == state.currentQuality) return
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        streamVariantIndex = 0
        ErrorReporting.reporter.log("player: quality ${quality.label} host=${urlHost(quality.url)}")
        player.setMediaItem(buildMediaItem(quality.url))
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = wasPlaying
        screenModelScope { _ -> updateState { it.copy(currentQuality = quality, streamUrl = quality.url) } }
    }

    private fun reportPlaybackStart(initial: StreamQuality) {
        ErrorReporting.reporter.log(
            "player: start item=${route.itemId} quality=${initial.label} host=${urlHost(initial.url)}",
        )
    }

    private fun playNextStreamVariant(): Boolean {
        val nextUrl = state.currentQuality?.urls?.getOrNull(streamVariantIndex + 1) ?: return false
        streamVariantIndex++
        ErrorReporting.reporter.log("player: variant fallback #$streamVariantIndex host=${urlHost(nextUrl)}")
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildMediaItem(nextUrl))
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = wasPlaying
        screenModelScope { _ -> updateState { it.copy(streamUrl = nextUrl) } }
        return true
    }

    private fun selectSubtitle(option: SubtitleOption) {
        currentSubtitleKey = option.preferenceKey()
        applySubtitleSelection(option)
        rememberCustomTracks()
        screenModelScope { _ -> updateState { it.copy(currentSubtitle = option) } }
    }

    private fun selectAudio(option: AudioOption) {
        val group = audioGroups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        currentVoiceKey = voiceKey(option.groupIndex, group, selectedTrack?.audios.orEmpty())
        rememberCustomTracks()
        screenModelScope { _ -> updateState { it.copy(currentAudio = option) } }
    }

    private fun rememberCustomTracks() {
        val custom = TitleTracks.Custom(voiceKey = currentVoiceKey, subtitleKey = currentSubtitleKey)
        titleTracks = custom
        screenModelScope { _ ->
            playbackSettings.setTitleTracks(route.itemId, custom)
            updateState { it.copy(currentPreset = PresetSelection.Custom) }
        }
    }

    private fun selectPreset(preset: TrackPreset?) {
        val selection = TitleTracks.Preset(preset)
        titleTracks = selection
        screenModelScope { _ -> playbackSettings.setTitleTracks(route.itemId, selection) }
        lastTracks?.let(::applyTracks)
    }

    private fun applyTracks(tracks: Tracks) {
        audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val apiAudios = selectedTrack?.audios.orEmpty()
        val audioOptions = audioGroups.mapIndexed { index, group ->
            AudioOption(
                label = audioLabel(index, group, apiAudios),
                groupIndex = index,
                lang = audioLanguage(index, group, apiAudios),
            )
        }
        val voiceKeys = audioGroups.indices.map { voiceKey(it, audioGroups[it], apiAudios) }
        val subtitleOptions = subtitleOptions()

        val resolution = resolveTracks(
            candidates = audioOptions.map { option -> AudioMatchCandidate(lang = option.lang, label = option.label) },
            voiceKeys = voiceKeys,
            options = subtitleOptions,
            selection = titleTracks,
            globalPreset = globalPreset,
        )

        val audioIndex = resolution.audioIndex
        if (audioIndex != null && !audioGroups[audioIndex].isSelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audioGroups[audioIndex].mediaTrackGroup, 0))
                .build()
        }
        applySubtitleSelection(resolution.subtitle)

        val selectedIndex = audioIndex ?: audioGroups.indexOfFirst { it.isSelected }
        currentVoiceKey = voiceKeys.getOrNull(selectedIndex)
        currentSubtitleKey = resolution.subtitle.preferenceKey()
        screenModelScope { _ ->
            updateState {
                it.copy(
                    audioTracks = if (audioOptions.size > 1) audioOptions else emptyList(),
                    currentAudio = audioOptions.getOrNull(selectedIndex) ?: audioOptions.firstOrNull(),
                    subtitles = subtitleOptions.takeIf { it.size > 1 }.orEmpty(),
                    currentSubtitle = resolution.subtitle,
                    currentPreset = resolution.presetSelection,
                )
            }
        }
    }

    private fun subtitleOptions(): List<SubtitleOption> = buildList {
        add(SubtitleOption.Off)
        textGroups.forEachIndexed { index, group ->
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: langDisplay(format.language)
                add(
                    SubtitleOption.Track(
                        label = label,
                        lang = format.language,
                        groupIndex = index,
                        trackIndex = trackIndex,
                        isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    ),
                )
            }
        }
    }

    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .build()
    }

    private fun applyAudioPreference() {
        val builder = player.trackSelectionParameters.buildUpon()
        val fixed = (titleTracks as? TitleTracks.Preset)?.preset ?: globalPreset.takeIf { titleTracks == null }
        val hint = when (val audio = fixed?.audio) {
            null, AudioPreference.Original -> null
            is AudioPreference.Language -> audio.language.code
        }
        hint?.let { builder.setPreferredAudioLanguage(it) }
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = builder.build()
    }

    private fun applySubtitleSelection(option: SubtitleOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        when (option) {
            SubtitleOption.Off -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is SubtitleOption.Track -> textGroups.getOrNull(option.groupIndex)?.let { group ->
                builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun saveProgress(positionMs: Long, durationMs: Long = 0L) {
        val item = state.item
        val track = selectedTrack
        if (item == null || track == null) return
        val seconds = (positionMs / MILLIS_IN_SECOND).toInt()
        val durationSeconds = if (durationMs > 0) (durationMs / MILLIS_IN_SECOND).toInt() else 0
        if (isFinishedByPosition(seconds, durationSeconds)) markWatched()
        val sent = lastSentSeconds
        val tooEarly = if (sent == null) {
            seconds < MIN_SECONDS_BEFORE_FIRST_SAVE
        } else {
            abs(seconds - sent) < PROGRESS_STEP_SECONDS
        }
        if (tooEarly) return
        lastSentSeconds = seconds
        screenModelScope {
            if (track.seasonNumber > 0) {
                watching.saveProgressSerial(item.id, track.seasonNumber, track.number, seconds)
            } else {
                watching.saveProgress(item.id, track.number, seconds)
            }
            DataInvalidation.markDirty(DataDomain.WATCHING)
        }
    }

    private fun markWatched() {
        val item = state.item
        val track = selectedTrack
        if (item == null || track == null || watchedMarked) return
        watchedMarked = true
        screenModelScope {
            watching.markWatched(item.id, track.seasonNumber, track.number)
            catalog.invalidateItemCache(item.id)
            DataInvalidation.markDirty(DataDomain.WATCHING)
        }
    }

    override fun onCleared() {
        ImagePrefetchThrottle.setPlaying(false)
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        const val MILLIS_IN_SECOND = 1000L

        fun urlHost(url: String): String = url.substringAfter("://").substringBefore("/")

        fun MediaTrack.matchesRoute(route: PlayerRoute): Boolean =
            number == route.videoId && (route.season <= 0 || seasonNumber == route.season)

        const val PROGRESS_STEP_SECONDS = 5

        const val MIN_SECONDS_BEFORE_FIRST_SAVE = 15

        fun langDisplay(code: String?): String =
            TrackLanguage.fromCode(code)?.display ?: code?.takeIf { it.isNotBlank() } ?: "Субтитры"

        fun audioDisplay(code: String?): String =
            TrackLanguage.fromCode(code)?.display ?: code?.takeIf { it.isNotBlank() } ?: AudioPreference.Original.label

        fun audioLanguage(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String? {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            return meta?.lang ?: group.getTrackFormat(0).language
        }

        fun audioLabel(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            val parts = buildList {
                add(audioDisplay(audioLanguage(groupIndex, group, apiAudios)))
                meta?.voiceType?.let { add(it) }
                meta?.voiceAuthor?.let { add(it) }
            }.distinct()
            return "${groupIndex + 1}. ${parts.joinToString(" · ")}"
        }

        fun voiceKey(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): VoiceKey {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            val language = audioLanguage(groupIndex, group, apiAudios)
            return VoiceKey(
                listOf(language.orEmpty(), meta?.voiceType.orEmpty(), meta?.voiceAuthor.orEmpty())
                    .joinToString("|"),
            )
        }
    }
}
