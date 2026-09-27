package com.filmax.feature.player.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.PlayerUi
import org.koin.compose.koinInject

internal interface TvPlayerUi {
    @Composable
    fun Content(session: TvPlayerSession, modifier: Modifier)
}

internal fun PlayerUi.implementation(): TvPlayerUi = when (this) {
    PlayerUi.Ui1 -> Ui1PlayerUi
    PlayerUi.Ui2 -> Ui2PlayerUi
}

@Composable
internal fun TvPlayer(session: TvPlayerSession, modifier: Modifier = Modifier) {
    val settings by koinInject<PlaybackSettingsRepository>().settings.collectAsState(PlaybackSettings())
    LifecycleStartEffect(session.player) {
        onStopOrDispose { session.player.pause() }
    }
    settings.playerUi.implementation().Content(session, modifier)
}
