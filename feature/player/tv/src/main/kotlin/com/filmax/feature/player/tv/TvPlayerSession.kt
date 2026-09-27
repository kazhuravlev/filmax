package com.filmax.feature.player.tv

import androidx.media3.common.Player
import com.filmax.core.domain.error.AppError

@Suppress("LongParameterList")
internal class TvPlayerSession(
    val player: Player,
    val title: String,
    val subtitle: String,
    val description: String,
    val loading: Boolean,
    val error: AppError?,
    val subscriptionRequired: Boolean,
    val autoNextLabel: String?,
    val menu: PlayerActions,
    val onSignal: (PlaybackSignal) -> Unit,
    val onBack: () -> Unit,
)

internal sealed interface PlaybackSignal {
    data class Progress(val positionMs: Long, val durationMs: Long) : PlaybackSignal

    data object Ended : PlaybackSignal

    data object AutoNextShown : PlaybackSignal
}
