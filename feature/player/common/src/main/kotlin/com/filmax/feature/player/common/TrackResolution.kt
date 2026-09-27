package com.filmax.feature.player.common

import com.filmax.core.domain.playback.SubtitlePreference
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.playback.VoiceKey

/**
 * Итог подбора дорожек для только что разобранного манифеста: [audioIndex] — индекс аудиогруппы
 * под override (null — override не ставим, играет дефолт плеера), [subtitle] — что включить,
 * [preset] — какой пресет сработал (null — ручной выбор или ни один не подошёл).
 */
internal data class TrackResolution(
    val audioIndex: Int?,
    val subtitle: SubtitleOption,
    val preset: TrackPreset?,
    val isCustom: Boolean,
) {
    /** Что показывает плитка «Пресет»: «Свой» / пресет / «Авто». */
    val presetSelection: PresetSelection
        get() = when {
            isCustom -> PresetSelection.Custom
            preset != null -> PresetSelection.Preset(preset)
            else -> PresetSelection.Auto
        }
}

/** Пресет подходит тайтлу, если нашлась его озвучка и его субтитры («Выкл» подходят всегда). */
internal fun TrackPreset.matches(candidates: List<AudioMatchCandidate>, options: List<SubtitleOption>): Boolean =
    resolveAudioGroupIndex(audio, candidates) != null &&
        (
            subtitle == SubtitlePreference.Off ||
                resolveSubtitleOption(options, SubtitleSelection.of(subtitle)) is SubtitleOption.Track
            )

/**
 * Подбирает озвучку и субтитры по памяти тайтла и глобальному пресету.
 *
 *  - Тайтл помнит пресет ([TitleTracks.Preset]) — берём его; null внутри — «Авто». Тайтл ничего
 *    не помнит — [globalPreset], null там — тоже «Авто».
 *  - «Авто» — первый пресет из [TrackPreset.entries], который [TrackPreset.matches] дорожкам.
 *    Ни один не подошёл — override не ставим, субтитры выключаем.
 *  - Ручной выбор ([TitleTracks.Custom]) сильнее всего: озвучку ищем по её ключу среди
 *    [voiceKeys] (параллелен [candidates]), субтитры — по сохранённому ключу (см.
 *    [SubtitleSelection.parse]). Половина, которой у тайтла нет (старые записи) или чей ключ не
 *    нашёлся у этой серии (другой набор озвучек), подбирается пресетом как выше.
 */
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
