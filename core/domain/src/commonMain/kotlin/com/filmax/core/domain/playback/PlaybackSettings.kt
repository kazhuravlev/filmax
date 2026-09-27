package com.filmax.core.domain.playback

import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

enum class TrackLanguage(val display: String, val code: String, val isoCodes: Set<String>) {
    Russian("Русский", "rus", setOf("ru", "rus")),
    English("English", "eng", setOf("en", "eng")),
    Ukrainian("Українська", "ukr", setOf("uk", "ukr")),
    ;

    companion object {
        fun fromCode(code: String?): TrackLanguage? {
            val normalized = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { normalized in it.isoCodes }
        }
    }
}

sealed interface AudioPreference {
    val label: String

    data object Original : AudioPreference {
        override val label: String get() = "Оригинал"
    }

    data class Language(val language: TrackLanguage) : AudioPreference {
        override val label: String get() = language.display
    }
}

sealed interface SubtitlePreference {
    val label: String

    data object Off : SubtitlePreference {
        override val label: String get() = "Выкл"
    }

    data class Language(val language: TrackLanguage) : SubtitlePreference {
        override val label: String get() = language.display
    }
}

enum class TrackPreset(
    val label: String,
    val shortLabel: String,
    val audio: AudioPreference,
    val subtitle: SubtitlePreference,
) {
    OriginalEnglishSubs(
        "Оригинал + англ. субтитры",
        "Ориг. + EN",
        AudioPreference.Original,
        SubtitlePreference.Language(TrackLanguage.English),
    ),
    OriginalRussianSubs(
        "Оригинал + рус. субтитры",
        "Ориг. + RU",
        AudioPreference.Original,
        SubtitlePreference.Language(TrackLanguage.Russian),
    ),
    RussianDub(
        "Русская озвучка, без субтитров",
        "Русский",
        AudioPreference.Language(TrackLanguage.Russian),
        SubtitlePreference.Off,
    ),
}

@JvmInline
value class VoiceKey(val value: String)

@JvmInline
value class SubtitleKey(val value: String)

sealed interface TitleTracks {
    data class Preset(val preset: TrackPreset?) : TitleTracks

    data class Custom(val voiceKey: VoiceKey?, val subtitleKey: SubtitleKey?) : TitleTracks
}

enum class PlayerUi(val label: String) {
    Ui1("UI 1"),

    Ui2("UI 2"),
    ;

    companion object {
        val Default = Ui1
    }
}

sealed interface QualityPreference {
    val label: String

    data object Auto : QualityPreference {
        override val label: String get() = "Авто"
    }

    data class Fixed(override val label: String) : QualityPreference

    companion object {
        val options: List<QualityPreference> =
            listOf(Auto) + listOf("2160p", "1080p", "720p", "480p", "360p").map(::Fixed)
    }
}

data class PlaybackSettings(
    val quality: QualityPreference = QualityPreference.Auto,
    val preset: TrackPreset? = null,
    val playerUi: PlayerUi = PlayerUi.Default,
) {
    val presetLabel: String get() = preset?.label ?: PresetAuto

    companion object {
        const val PresetAuto = "Авто"

        val presetOptions: List<TrackPreset?> = listOf(null) + TrackPreset.entries

        val playerUiOptions: List<PlayerUi> = PlayerUi.entries
    }
}

interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>

    suspend fun setQuality(quality: QualityPreference)

    suspend fun setPreset(preset: TrackPreset?)

    suspend fun setPlayerUi(ui: PlayerUi)

    suspend fun titleTracksFor(itemId: Int): TitleTracks?

    suspend fun setTitleTracks(itemId: Int, tracks: TitleTracks)

    suspend fun clearTitleTracks()
}
