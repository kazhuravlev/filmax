package com.filmax.feature.player.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.ui.components.KeepScreenOn
import kotlinx.coroutines.delay

@Composable
internal fun PlayerEffects(ui: BasePlayerUiState, session: TvPlayerSession) {
    val player = session.player

    val currentMenu by rememberUpdatedState(session.menu)

    DisposableEffect(player) {
        ui.isPlaying = player.isPlaying
        ui.isBuffering = player.playbackState == Player.STATE_BUFFERING
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.isPlaying = isPlaying
                if (!isPlaying) session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ui.isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState != Player.STATE_ENDED) return
                session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
                session.onSignal(PlaybackSignal.Ended)
                if (currentMenu.hasNextEpisode && !ui.autoNextDismissed) {
                    ui.autoNextVisible = false
                    currentMenu.onNextEpisode()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
                }
            }
        }
        player.addListener(listener)
        onDispose {
            session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            val duration = player.duration.takeIf { it > 0 } ?: continue
            ui.durationMs = duration
            if (!ui.isScrubbing) ui.positionMs = player.currentPosition
            ui.bufferedMs = player.bufferedPosition

            ui.updateAutoNext(
                remainingMs = duration - player.currentPosition,
                enabled = currentMenu.hasNextEpisode,
                playing = ui.isPlaying,
            )
            if (ui.autoNextVisible && ui.autoNextSeconds <= 0) {
                ui.autoNextVisible = false
                currentMenu.onNextEpisode()
            }
        }
    }

    LaunchedEffect(ui.autoNextVisible) {
        if (ui.autoNextVisible) session.onSignal(PlaybackSignal.AutoNextShown)
    }

    LaunchedEffect(ui.isScrubbing, ui.scrubTargetMs) {
        if (ui.isScrubbing) {
            delay(SCRUB_COMMIT_TIMEOUT_MS)
            ui.commitScrub()
        }
    }

    LaunchedEffect(ui.seekTick) {
        delay(SEEK_LABEL_HOLD_MS)
        ui.seekLabel = null
    }

    LaunchedEffect(ui.interactionTick, ui.idleHidesOverlay) {
        if (ui.idleHidesOverlay) {
            delay(OVERLAY_AUTO_HIDE_MS)
            ui.hideOverlay()
        }
    }
}

@Composable
internal fun PlayerFrame(
    ui: BasePlayerUiState,
    session: TvPlayerSession,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit,
) {
    val menu = session.menu
    val error = session.error
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    KeepScreenOn(enabled = ui.isPlaying)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvSurface)
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.Back || event.key == Key.Escape -> false
                    else -> ui.onKey(event.key, menu)
                }
            },
    ) {
        VideoSurface(player = ui.player)

        if ((session.loading || ui.isBuffering) && error == null) {
            CircularProgressIndicator(color = TvAccent, modifier = Modifier.align(Alignment.Center))
        }

        error?.let { PlayerErrorCard(error = it, modifier = Modifier.align(Alignment.Center)) }

        AnimatedVisibility(
            visible = ui.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            overlay()
        }

        if (session.subscriptionRequired && error == null) {
            SubscriptionCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = SubscriptionCardTop),
            )
        }

        val autoNextLabel = session.autoNextLabel
        if (ui.autoNextVisible && autoNextLabel != null) {
            AutoNextCard(
                label = autoNextLabel,
                seconds = ui.autoNextSeconds,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = TvMetrics.SafeHorizontal, bottom = AutoNextCardBottom),
            )
        }
    }
}

@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).also { view ->
                view.player = player
                view.useController = false
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

private val AutoNextCardBottom = 120.dp

private val SubscriptionCardTop = 96.dp
