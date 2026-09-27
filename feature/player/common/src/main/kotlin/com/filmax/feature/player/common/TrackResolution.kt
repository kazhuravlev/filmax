package com.filmax.feature.player.common

import com.filmax.core.domain.playback.SubtitlePreference
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.playback.VoiceKey

internal data class TrackResolution(
    val audioIndex: Int?,
    val subtitle: SubtitleOption,
    val preset: TrackPreset?,
    val isCustom: Boolean,
) {
    val presetSelection: PresetSelection
        get() = when {
            isCustom -> PresetSelection.Custom
            preset != null -> PresetSelection.Preset(preset)
            else -> PresetSelection.Auto
        }
}

internal fun TrackPreset.matches(candidates: List<AudioMatchCandidate>, options: List<SubtitleOption>): Boolean =
    resolveAudioGroupIndex(audio, candidates) != null &&
        (
            subtitle == SubtitlePreference.Off ||
                resolveSubtitleOption(options, SubtitleSelection.of(subtitle)) is SubtitleOption.Track
            )

internal fun resolveTracks(
    candidates: List<AudioMatchCandidate>,
    voiceKeys: List<VoiceKey>,
    options: List<SubtitleOption>,
    selection: TitleTracks?,
    globalPreset: TrackPreset?,
): TrackResolution {
    val fixed = if (selection is TitleTracks.Preset) selection.preset else globalPreset
    val preset = fixed ?: TrackPreset.entries.firstOrNull { it.matches(candidates, options) }
    val presetAudio = preset?.let { resolveAudioGroupIndex(it.audio, candidates) }
    val presetSubtitle = resolveSubtitleOption(
        options,
        SubtitleSelection.of(preset?.subtitle ?: SubtitlePreference.Off),
    )
    if (selection !is TitleTracks.Custom) {
        return TrackResolution(presetAudio, presetSubtitle, preset, isCustom = false)
    }
    val audio = selection.voiceKey
        ?.let { key -> voiceKeys.indexOf(key).takeIf { it >= 0 } }
        ?: presetAudio
    val subtitle = selection.subtitleKey
        ?.let { key -> resolveSubtitleOption(options, SubtitleSelection.parse(key)) }
        ?: presetSubtitle
    return TrackResolution(audio, subtitle, preset = null, isCustom = true)
}
