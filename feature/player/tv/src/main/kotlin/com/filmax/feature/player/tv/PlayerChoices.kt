package com.filmax.feature.player.tv

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.tv.designsystem.qualityLabel
import com.filmax.feature.player.common.AudioOption
import com.filmax.feature.player.common.PresetSelection
import com.filmax.feature.player.common.SpeedOption
import com.filmax.feature.player.common.StreamQuality
import com.filmax.feature.player.common.SubtitleOption

// Единственное место, где типизированные значения модели плеера превращаются в подписи для
// обоих интерфейсов (UI 1 и UI 2): полная — в поповер, короткая — под кнопку/на плитку.
// Интерфейсы получают готовый [PlayerChoice] и ничего не разбирают из строк.

internal fun StreamQuality.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = qualityCaption(label))

internal fun AudioOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortCode)

internal fun SubtitleOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortCode)

/** Вариант списка пресетов; null — «Авто» (см. [PlaybackSettings.presetOptions]). */
internal fun TrackPreset?.toChoice(): PlayerChoice = PlayerChoice(
    label = this?.label ?: PlaybackSettings.PresetAuto,
    shortValue = this?.shortLabel ?: PlaybackSettings.PresetAuto,
)

/** Текущее состояние плитки «Пресет»; для [PresetSelection.Preset] совпадает с вариантом списка. */
internal fun PresetSelection.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortLabel)

internal fun SpeedOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = label)

/** «1080p» → «FHD», «2160p» → «4K»: высота кадра из подписи качества через общий [qualityLabel]. */
internal fun qualityCaption(label: String): String =
    label.takeWhile { it.isDigit() }.toIntOrNull()?.let(::qualityLabel) ?: label
