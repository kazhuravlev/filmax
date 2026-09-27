package com.filmax.feature.player.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.filmax.core.domain.common.ErrorReporting
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.RequestFailure
import com.filmax.feature.player.common.PlaybackSpeeds

@Composable
fun TvTrailerScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    var error by remember(exoPlayer) { mutableStateOf<AppError?>(null) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) {
                ErrorReporting.reporter.report(RequestFailure.of(AppError.Playback, playbackError))
                error = AppError.Playback
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    var speed by remember(exoPlayer) { mutableFloatStateOf(PlaybackSpeeds.NormalSpeed) }

    val session = TvPlayerSession(
        player = exoPlayer,
        title = title,
        subtitle = "Трейлер",
        description = "",
        loading = false,
        error = error,
        subscriptionRequired = false,
        autoNextLabel = null,
        menu = trailerMenu(speed = speed) { value ->
            speed = value
            exoPlayer.setPlaybackSpeed(value)
        },
        onSignal = {},
        onBack = onBack,
    )
    TvPlayer(session = session, modifier = modifier)
}

private fun trailerMenu(speed: Float, onSpeed: (Float) -> Unit) = PlayerActions(
    items = listOf(SettingsAction.Speed),
    options = { _ -> PlaybackSpeeds.options.map { it.toChoice() } },
    selected = { PlaybackSpeeds.optionFor(speed).toChoice() },
    onSelect = { _, index -> PlaybackSpeeds.options.getOrNull(index)?.let { onSpeed(it.value) } },
    onNextEpisode = {},
)
