package com.filmax.data.watching

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.PlayerUi
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки воспроизведения на multiplatform-settings; реактивность — через [MutableStateFlow]. */
internal class PlaybackSettingsRepositoryImpl(
    private val storage: Settings,
) : PlaybackSettingsRepository {

    private val state = MutableStateFlow(load())

    override val settings: Flow<PlaybackSettings> = state.asStateFlow()

    override suspend fun setQuality(quality: String) = update { it.copy(quality = quality) }

    override suspend fun setPreset(preset: TrackPreset?) = update { it.copy(preset = preset) }

    override suspend fun setPlayerUi(ui: PlayerUi) = update { it.copy(playerUi = ui) }

    // Память тайтла — точечные ключи мимо state: это не глобальная настройка, а «что выбрали в
    // этом сериале», и подписки на неё не нужны. Пресет и ручной выбор — взаимоисключающие
    // ключи: запись одного стирает другой, чтобы чтение было однозначным.
    //
    // Ручной выбор хранится в тех же двух ключах (озвучка и субтитры), что были до появления
    // пресетов, — старые записи читаются как [TitleTracks.Custom] без миграции.
    override suspend fun titleTracksFor(itemId: Int): TitleTracks? {
        val preset = storage.getStringOrNull(KEY_TITLE_PRESET_PREFIX + itemId)
        val voice = storage.getStringOrNull(KEY_VOICE_PREFIX + itemId)
        val subtitle = storage.getStringOrNull(KEY_SUBTITLE_PREFIX + itemId)
        return when {
            preset != null -> TitleTracks.Preset(preset.toPreset())
            voice != null || subtitle != null -> TitleTracks.Custom(voiceKey = voice, subtitleKey = subtitle)
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
                putOrRemove(KEY_VOICE_PREFIX + itemId, tracks.voiceKey)
                putOrRemove(KEY_SUBTITLE_PREFIX + itemId, tracks.subtitleKey)
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
        storage.putString(KEY_QUALITY, updated.quality)
        storage.putString(KEY_PRESET, updated.preset.toRaw())
        storage.putString(KEY_PLAYER_UI, updated.playerUi.name)
        state.value = updated
    }

    private fun load(): PlaybackSettings {
        // Глобальные «язык аудио»/«субтитры» заменены пресетом — их ключи больше не читаем.
        storage.remove(KEY_LEGACY_AUDIO)
        storage.remove(KEY_LEGACY_SUBTITLES)
        return PlaybackSettings(
            quality = storage.getStringOrNull(KEY_QUALITY) ?: PlaybackSettings.QualityAuto,
            preset = storage.getStringOrNull(KEY_PRESET)?.toPreset(),
            // Неизвестное имя (интерфейс убрали) — дефолтный, а не падение при загрузке.
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

private fun TrackPreset?.toRaw(): String = this?.name ?: PRESET_AUTO

/** Неизвестное имя (пресет переименовали/удалили) читается как «Авто», а не роняет загрузку. */
private fun String.toPreset(): TrackPreset? = TrackPreset.entries.firstOrNull { it.name == this }
