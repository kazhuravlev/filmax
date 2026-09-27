package com.filmax.feature.player.common

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset

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
    /** Подпись для плитки «Пресет»: «Свой» / имя пресета / «Авто». */
    val presetLabel: String
        get() = when {
            isCustom -> CUSTOM_PRESET_LABEL
            preset != null -> preset.shortLabel
            else -> PlaybackSettings.PresetAuto
        }
}

internal const val CUSTOM_PRESET_LABEL = "Свой"

/** Пресет подходит тайтлу, если нашлась его озвучка и его субтитры («Выкл» подходят всегда). */
internal fun TrackPreset.matches(candidates: List<AudioMatchCandidate>, options: List<SubtitleOption>): Boolean =
    resolveAudioGroupIndex(audio, candidates) != null &&
        (subtitle == PlaybackSettings.SubtitleOff || resolveSubtitleOption(options, subtitle).lang != null)

/**
 * Подбирает озвучку и субтитры по памяти тайтла и глобальному пресету.
 *
 *  - Тайтл помнит пресет ([TitleTracks.Preset]) — берём его; null внутри — «Авто». Тайтл ничего
 *    не помнит — [globalPreset], null там — тоже «Авто».
 *  - «Авто» — первый пресет из [TrackPreset.entries], который [TrackPreset.matches] дорожкам.
 *    Ни один не подошёл — override не ставим, субтитры выключаем.
 *  - Ручной выбор ([TitleTracks.Custom]) сильнее всего: озвучку ищем по её ключу среди
 *    [voiceKeys] (параллелен [candidates]), субтитры — по сохранённому ключу (см.
 *    [resolveSubtitleOption]). Половина, которой у тайтла нет (старые записи) или чей ключ не
 *    нашёлся у этой серии (другой набор озвучек), подбирается пресетом как выше.
 */
internal fun resolveTracks(
    candidates: List<AudioMatchCandidate>,
    voiceKeys: List<String>,
    options: List<SubtitleOption>,
    selection: TitleTracks?,
    globalPreset: TrackPreset?,
): TrackResolution {
    val fixed = if (selection is TitleTracks.Preset) selection.preset else globalPreset
    val preset = fixed ?: TrackPreset.entries.firstOrNull { it.matches(candidates, options) }
    val presetAudio = preset?.let { resolveAudioGroupIndex(it.audio, candidates) }
    val presetSubtitle = resolveSubtitleOption(options, preset?.subtitle ?: PlaybackSettings.SubtitleOff)
    if (selection !is TitleTracks.Custom) {
        return TrackResolution(presetAudio, presetSubtitle, preset, isCustom = false)
    }
    val audio = selection.voiceKey
        ?.let { key -> voiceKeys.indexOf(key).takeIf { it >= 0 } }
        ?: presetAudio
    val subtitle = selection.subtitleKey?.let { resolveSubtitleOption(options, it) } ?: presetSubtitle
    return TrackResolution(audio, subtitle, preset = null, isCustom = true)
}
