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

/**
 * TV-экран трейлера — одноразовый плеер по готовому HLS-URL в том же интерфейсе, что и тайтлы
 * (см. [TvPlayer]): пульт, оверлей и плитки настроек у трейлера те же, только сетка короче —
 * из настроек доступна одна скорость: качество, дорожки и серии у трейлера выбирать не из чего.
 *
 * [url] — временный .m3u8 с истекающим токеном в query, поэтому плеер намеренно простой: он не
 * переживает пересоздание (по протухшему токену воспроизведение не восстановить — для трейлера
 * это допустимо). Отдельного ScreenModel не заводим: прогресс трейлера никуда не пишется,
 * сигналы интерфейса игнорируются.
 */
@Composable
fun TvTrailerScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // remember(url) пересоздаёт плеер только при смене трейлера; ключ URL связывает жизненный
    // цикл ExoPlayer с конкретным HLS-адресом.
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    var error by remember(exoPlayer) { mutableStateOf<AppError?>(null) }
    DisposableEffect(exoPlayer) {
        // Штатный контроллер Media3 сам показывал текст ошибки; наш оверлей ждёт её в сессии.
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
    // Скорость сессионная, как и у тайтла (см. PlayerEvent.SetSpeed) — живёт ровно с этим плеером.
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

/** Сетка настроек трейлера: одна плитка «Скорость». */
private fun trailerMenu(speed: Float, onSpeed: (Float) -> Unit) = PlayerActions(
    items = listOf(SettingsAction.Speed),
    options = { PlaybackSpeeds.labels },
    selected = { PlaybackSpeeds.labelFor(speed) },
    onSelect = { _, label -> PlaybackSpeeds.valueFor(label)?.let(onSpeed) },
    onNextEpisode = {},
)
