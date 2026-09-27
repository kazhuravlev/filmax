package com.filmax.feature.player.tv

import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.tv.designsystem.qualityLabel
import com.filmax.feature.player.common.AudioOption
import com.filmax.feature.player.common.PresetSelection
import com.filmax.feature.player.common.SpeedOption
import com.filmax.feature.player.common.StreamQuality
import com.filmax.feature.player.common.SubtitleOption

internal fun StreamQuality.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = qualityCaption(label))

internal fun AudioOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortCode)

internal fun SubtitleOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortCode)

internal fun TrackPreset?.toChoice(): PlayerChoice = PlayerChoice(
    label = this?.label ?: PlaybackSettings.PresetAuto,
    shortValue = this?.shortLabel ?: PlaybackSettings.PresetAuto,
)

internal fun PresetSelection.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = shortLabel)

internal fun SpeedOption.toChoice(): PlayerChoice = PlayerChoice(label = label, shortValue = label)

internal fun qualityCaption(label: String): String =
    label.takeWhile { it.isDigit() }.toIntOrNull()?.let(::qualityLabel) ?: label
