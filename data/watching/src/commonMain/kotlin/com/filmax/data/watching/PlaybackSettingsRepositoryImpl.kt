package com.filmax.data.watching

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.PlayerUi
import com.filmax.core.domain.playback.QualityPreference
import com.filmax.core.domain.playback.SubtitleKey
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.playback.VoiceKey
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlaybackSettingsRepositoryImpl(
    private val storage: Settings,
) : PlaybackSettingsRepository {
    private val state = MutableStateFlow(load())

    override val settings: Flow<PlaybackSettings> = state.asStateFlow()

    override suspend fun setQuality(quality: QualityPreference) = update { it.copy(quality = quality) }

    override suspend fun setPreset(preset: TrackPreset?) = update { it.copy(preset = preset) }

    override suspend fun setPlayerUi(ui: PlayerUi) = update { it.copy(playerUi = ui) }

    override suspend fun titleTracksFor(itemId: Int): TitleTracks? {
        val preset = storage.getStringOrNull(KEY_TITLE_PRESET_PREFIX + itemId)
        val voice = storage.getStringOrNull(KEY_VOICE_PREFIX + itemId)
        val subtitle = storage.getStringOrNull(KEY_SUBTITLE_PREFIX + itemId)
        return when {
            preset != null -> TitleTracks.Preset(preset.toPreset())
            voice != null || subtitle != null ->
                TitleTracks.Custom(voiceKey = voice?.let(::VoiceKey), subtitleKey = subtitle?.let(::SubtitleKey))
            else -> null
        }
    }

    override suspend fun setTitleTracks(itemId: Int, tracks: TitleTracks) {
        when (tracks) {
            is TitleTracks.Preset -> {
                storage.remove(KEY_VOICE_PREFIX + itemId)
                storage.remove(KEY_SUBTITLE_PREFIX + itemId)
                storage.putString(KEY_TITLE_PRESET_PREFIX + itemId, tracks.preset.toRaw())
            }

            is TitleTracks.Custom -> {
                storage.remove(KEY_TITLE_PRESET_PREFIX + itemId)
                putOrRemove(KEY_VOICE_PREFIX + itemId, tracks.voiceKey?.value)
                putOrRemove(KEY_SUBTITLE_PREFIX + itemId, tracks.subtitleKey?.value)
            }
        }
    }

    override suspend fun clearTitleTracks() {
        storage.keys
            .filter { key -> TITLE_PREFIXES.any { key.startsWith(it) } }
            .forEach(storage::remove)
    }

    private fun putOrRemove(key: String, value: String?) {
        if (value == null) storage.remove(key) else storage.putString(key, value)
    }

    private fun update(transform: (PlaybackSettings) -> PlaybackSettings) {
        val updated = transform(state.value)
        storage.putString(KEY_QUALITY, updated.quality.toRaw())
        storage.putString(KEY_PRESET, updated.preset.toRaw())
        storage.putString(KEY_PLAYER_UI, updated.playerUi.name)
        state.value = updated
    }

    private fun load(): PlaybackSettings {
        storage.remove(KEY_LEGACY_AUDIO)
        storage.remove(KEY_LEGACY_SUBTITLES)
        return PlaybackSettings(
            quality = storage.getStringOrNull(KEY_QUALITY)?.toQuality() ?: QualityPreference.Auto,
            preset = storage.getStringOrNull(KEY_PRESET)?.toPreset(),
            playerUi = storage.getStringOrNull(KEY_PLAYER_UI)
                ?.let { raw -> PlayerUi.entries.firstOrNull { it.name == raw } }
                ?: PlayerUi.Default,
        )
    }

    private companion object {
        const val KEY_QUALITY = "playback_quality"
        const val KEY_PRESET = "playback_preset"
        const val KEY_PLAYER_UI = "playback_player_ui"
        const val KEY_LEGACY_AUDIO = "playback_audio"
        const val KEY_LEGACY_SUBTITLES = "playback_subtitles"
        const val KEY_TITLE_PRESET_PREFIX = "playback_preset_"
        const val KEY_VOICE_PREFIX = "playback_voice_"
        const val KEY_SUBTITLE_PREFIX = "playback_subtitle_"
        val TITLE_PREFIXES = listOf(KEY_TITLE_PRESET_PREFIX, KEY_VOICE_PREFIX, KEY_SUBTITLE_PREFIX)
    }
}

private const val PRESET_AUTO = "auto"

private const val LEGACY_QUALITY_AUTO = "Авто"

private fun TrackPreset?.toRaw(): String = this?.name ?: PRESET_AUTO

private fun QualityPreference.toRaw(): String = when (this) {
    QualityPreference.Auto -> LEGACY_QUALITY_AUTO
    is QualityPreference.Fixed -> label
}

private fun String.toQuality(): QualityPreference =
    if (this == LEGACY_QUALITY_AUTO) QualityPreference.Auto else QualityPreference.Fixed(this)

private fun String.toPreset(): TrackPreset? = TrackPreset.entries.firstOrNull { it.name == this }
