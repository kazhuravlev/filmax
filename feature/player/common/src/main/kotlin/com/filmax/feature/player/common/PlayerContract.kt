package com.filmax.feature.player.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.playback.AudioPreference
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.SubtitleKey
import com.filmax.core.domain.playback.SubtitlePreference
import com.filmax.core.domain.playback.TrackLanguage
import com.filmax.core.domain.playback.TrackPreset

data class StreamQuality(val label: String, val urls: List<String>) {
    init {
        require(urls.isNotEmpty()) { "Качество $label без единой ссылки на поток" }
    }

    val url: String get() = urls.first()
}

sealed interface SubtitleOption {
    val label: String

    val shortCode: String

    data object Off : SubtitleOption {
        override val label: String get() = SubtitlePreference.Off.label
        override val shortCode: String get() = NO_VALUE_CAPTION
    }

    data class Track(
        override val label: String,
        val lang: String?,
        val groupIndex: Int,
        val trackIndex: Int,
        val isForced: Boolean = false,
    ) : SubtitleOption {
        val language: TrackLanguage? get() = TrackLanguage.fromCode(lang)

        override val shortCode: String
            get() = language?.code ?: lang?.lowercase()?.takeIf { it.isNotBlank() } ?: UNKNOWN_LANGUAGE_SHORT_CODE

        companion object {
            const val UNKNOWN_LANGUAGE_SHORT_CODE = "sub"
        }
    }
}

internal fun SubtitleOption.preferenceKey(): SubtitleKey = when (this) {
    SubtitleOption.Off -> SubtitleKey(SUBTITLE_OFF_KEY)
    is SubtitleOption.Track -> SubtitleKey(
        "$SUBTITLE_TRACK_PREFERENCE_PREFIX${lang.orEmpty().lowercase()}$SUBTITLE_PREFERENCE_SEPARATOR$label",
    )
}

internal sealed interface SubtitleSelection {
    data object Off : SubtitleSelection

    data class ByLanguage(val language: TrackLanguage) : SubtitleSelection

    data class SavedTrack(val language: String, val label: String) : SubtitleSelection

    data class LegacyLabel(val label: String) : SubtitleSelection

    companion object {
        fun of(preference: SubtitlePreference): SubtitleSelection = when (preference) {
            SubtitlePreference.Off -> Off
            is SubtitlePreference.Language -> ByLanguage(preference.language)
        }

        fun parse(key: SubtitleKey): SubtitleSelection {
            val raw = key.value
            return when {
                raw == SUBTITLE_OFF_KEY -> Off
                else -> raw.toSavedSubtitleTrack() ?: raw.toLanguage()?.let(::ByLanguage) ?: LegacyLabel(raw)
            }
        }

        private fun String.toLanguage(): TrackLanguage? =
            TrackLanguage.fromCode(this)
                ?: TrackLanguage.entries.firstOrNull { it.display.equals(this, ignoreCase = true) }
    }
}

internal fun resolveSubtitleOption(
    options: List<SubtitleOption>,
    selection: SubtitleSelection,
): SubtitleOption {
    val tracks = options.filterIsInstance<SubtitleOption.Track>()
    val resolved = when (selection) {
        SubtitleSelection.Off -> null
        is SubtitleSelection.SavedTrack -> resolveSavedSubtitleTrack(tracks, selection)
        is SubtitleSelection.LegacyLabel -> tracks.firstOrNull { it.label == selection.label }
        is SubtitleSelection.ByLanguage -> matchSubtitleByLanguage(tracks, selection.language)
    }
    return resolved ?: SubtitleOption.Off
}

private fun resolveSavedSubtitleTrack(
    tracks: List<SubtitleOption.Track>,
    saved: SubtitleSelection.SavedTrack,
): SubtitleOption.Track? {
    fun SubtitleOption.Track.sameLanguage() = lang.orEmpty().equals(saved.language, ignoreCase = true)
    return tracks.firstOrNull { it.sameLanguage() && it.label == saved.label }
        ?: tracks.firstOrNull { it.sameLanguage() && !it.isForced }
        ?: tracks.firstOrNull { it.sameLanguage() }
}

private fun TrackLanguage.needles(): List<String> = when (this) {
    TrackLanguage.Russian -> listOf("rus", "рус")
    TrackLanguage.English -> listOf("eng", "англ")
    TrackLanguage.Ukrainian -> listOf("ukr", "укр")
}

private fun String.hasWordStartingWith(needles: List<String>): Boolean =
    split(WORD_SEPARATORS).any { word -> needles.any { needle -> word.startsWith(needle) } }

private val WORD_SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

private fun matchSubtitleByLanguage(
    tracks: List<SubtitleOption.Track>,
    target: TrackLanguage,
): SubtitleOption.Track? {
    val needles = target.needles()
    return tracks.firstOrNull { track ->
        val lang = track.lang.orEmpty().lowercase()
        !track.isForced &&
            (lang in target.isoCodes || "$lang ${track.label.lowercase()}".hasWordStartingWith(needles))
    }
}

private fun String.toSavedSubtitleTrack(): SubtitleSelection.SavedTrack? {
    if (!startsWith(SUBTITLE_TRACK_PREFERENCE_PREFIX)) return null
    val saved = removePrefix(SUBTITLE_TRACK_PREFERENCE_PREFIX)
    val separatorIndex = saved.indexOf(SUBTITLE_PREFERENCE_SEPARATOR)
    val hasValidSeparator = separatorIndex >= 0 && separatorIndex != saved.lastIndex
    return if (hasValidSeparator) {
        SubtitleSelection.SavedTrack(
            language = saved.substring(0, separatorIndex),
            label = saved.substring(separatorIndex + 1),
        )
    } else {
        null
    }
}

private const val SUBTITLE_TRACK_PREFERENCE_PREFIX = "track:"
private const val SUBTITLE_PREFERENCE_SEPARATOR = "|"

private const val SUBTITLE_OFF_KEY = "Выкл"

const val NO_VALUE_CAPTION = "—"

data class AudioOption(val label: String, val groupIndex: Int, val lang: String?) {
    val language: TrackLanguage? get() = TrackLanguage.fromCode(lang)

    val isOriginal: Boolean get() = lang.isNullOrBlank()

    val shortCode: String
        get() = when {
            isOriginal -> ORIGINAL_SHORT_CODE
            else -> language?.code ?: lang.orEmpty().lowercase()
        }

    companion object {
        const val ORIGINAL_SHORT_CODE = "orig"
    }
}

internal data class AudioMatchCandidate(val lang: String?, val label: String)

internal fun resolveAudioGroupIndex(
    preference: AudioPreference,
    candidates: List<AudioMatchCandidate>,
): Int? {
    val index = when (preference) {
        AudioPreference.Original -> candidates.indexOfFirst { candidate ->
            candidate.lang.isNullOrBlank() || candidate.matchesAudio(ORIGINAL_NEEDLES)
        }
        is AudioPreference.Language -> candidates.indexOfFirst { candidate ->
            candidate.lang?.lowercase() in preference.language.isoCodes ||
                candidate.matchesAudio(preference.language.needles())
        }
    }
    return index.takeIf { it >= 0 }
}

private fun AudioMatchCandidate.matchesAudio(needles: List<String>): Boolean =
    "${lang.orEmpty()} $label".lowercase().hasWordStartingWith(needles)

private val ORIGINAL_NEEDLES = listOf("оригинал", "original")

data class SpeedOption(val label: String, val value: Float)

object PlaybackSpeeds {
    const val NormalLabel = "Обычная"
    const val NormalSpeed = 1.0f

    val options: List<SpeedOption> = listOf(
        SpeedOption("0.25×", 0.25f),
        SpeedOption("0.5×", 0.5f),
        SpeedOption("0.75×", 0.75f),
        SpeedOption(NormalLabel, NormalSpeed),
        SpeedOption("1.25×", 1.25f),
        SpeedOption("1.5×", 1.5f),
        SpeedOption("1.75×", 1.75f),
        SpeedOption("2×", 2.0f),
    )

    fun optionFor(value: Float): SpeedOption =
        options.firstOrNull { it.value == value } ?: options.first { it.value == NormalSpeed }

    fun labelFor(value: Float): String = optionFor(value).label
}

sealed interface PresetSelection {
    val label: String
    val shortLabel: String

    data object Auto : PresetSelection {
        override val label: String get() = PlaybackSettings.PresetAuto
        override val shortLabel: String get() = PlaybackSettings.PresetAuto
    }

    data class Preset(val preset: TrackPreset) : PresetSelection {
        override val label: String get() = preset.label
        override val shortLabel: String get() = preset.shortLabel
    }

    data object Custom : PresetSelection {
        override val label: String get() = "Свой"
        override val shortLabel: String get() = label
    }
}

data class PlayerState(
    val loading: Boolean = true,
    val item: Item? = null,
    val track: MediaTrack? = null,
    val previousTrack: MediaTrack? = null,
    val nextTrack: MediaTrack? = null,
    val streamUrl: String? = null,
    val qualities: List<StreamQuality> = emptyList(),
    val currentQuality: StreamQuality? = null,
    val audioTracks: List<AudioOption> = emptyList(),
    val currentAudio: AudioOption? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    val currentSubtitle: SubtitleOption = SubtitleOption.Off,
    val currentPreset: PresetSelection = PresetSelection.Auto,
    val currentSpeed: Float = PlaybackSpeeds.NormalSpeed,
    val subscriptionRequired: Boolean = false,
    val error: String? = null,
)

sealed interface PlayerEvent {
    data class SaveProgress(val positionMs: Long, val durationMs: Long = 0L) : PlayerEvent

    data object MarkWatched : PlayerEvent

    data class SelectQuality(val quality: StreamQuality) : PlayerEvent
    data class SelectAudio(val option: AudioOption) : PlayerEvent
    data class SelectSubtitle(val option: SubtitleOption) : PlayerEvent

    data class SelectPreset(val preset: TrackPreset?) : PlayerEvent
    data class SetSpeed(val speed: Float) : PlayerEvent

    data object PrefetchNextEpisode : PlayerEvent
}

sealed interface PlayerSideEffect

@Suppress("MagicNumber")
fun formatPlayerTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
