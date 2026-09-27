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

/**
 * Тики и таймеры плеера: UI-прогресс, автопереход, скраб, автоскрытие оверлея, а также события,
 * по которым источник получает [PlaybackSignal.Progress] (пауза/перемотка/конец/выход с экрана — не по таймеру).
 */
@Composable
internal fun PlayerEffects(ui: BasePlayerUiState, session: TvPlayerSession) {
    val player = session.player

    // Эффекты живут с ключом player и переживают рекомпозиции, а menu пересобирается, когда
    // доезжает плейлист (при старте треков ещё нет и hasNextEpisode=false) — читаем всегда
    // АКТУАЛЬНЫЙ через rememberUpdatedState, иначе эффекты замкнут пустой первый экземпляр.
    val currentMenu by rememberUpdatedState(session.menu)

    DisposableEffect(player) {
        ui.isPlaying = player.isPlaying
        ui.isBuffering = player.playbackState == Player.STATE_BUFFERING
        val listener = object : Player.Listener {
            // Событийный marktime (не по таймеру): пауза/буферизация/конец/ошибка — везде, где
            // плеер перестаёт идти вперёд, это подходящий момент зафиксировать позицию. Лишние
            // вызовы (эта колбэка срабатывает и на буферизации, не только на паузе пользователя)
            // безвредны — троттлинг внутри saveProgress их отфильтрует.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.isPlaying = isPlaying
                if (!isPlaying) session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
            }

            // Серия дотекла до конца раньше тика — переходим сразу (если не отменяли «Назад»).
            override fun onPlaybackStateChanged(playbackState: Int) {
                ui.isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState != Player.STATE_ENDED) return
                session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
                // Конец потока — серия досмотрена, что бы ни говорила разница позиции и длительности.
                session.onSignal(PlaybackSignal.Ended)
                if (currentMenu.hasNextEpisode && !ui.autoNextDismissed) {
                    ui.autoNextVisible = false
                    currentMenu.onNextEpisode()
                }
            }

            // Перемотка пультом/скрабом — второе из трёх событий эталонного клиента. Другие
            // причины разрыва (авто-переход между сегментами, внутренние сбои плеера) — шум,
            // не пользовательское намерение, поэтому фильтруем по причине.
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
            // Уход с экрана плеера, пока видео ещё играет (например, «Назад» без предварительной
            // паузы) — единственный случай, не покрытый событиями выше: без этой финальной записи
            // прогресс потерялся бы вплоть до последнего pause/seek. Дедупликация в saveProgress
            // делает вызов безвредным, если позиция уже была сохранена только что.
            session.onSignal(PlaybackSignal.Progress(player.currentPosition, player.duration))
            player.removeListener(listener)
        }
    }

    // Тик прогресса. Сохранение позиции на сервер ушло на события плеера (пауза/перемотка/конец/
    // выход с экрана, см. DisposableEffect выше) — здесь только UI-состояние прогресс-бара и
    // автопереход. Пока длительность неизвестна — не обновляем: записали бы нулевую позицию
    // поверх реального прогресса. Во время скраббинга позицию ведёт пульт.
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            val duration = player.duration.takeIf { it > 0 } ?: continue
            ui.durationMs = duration
            if (!ui.isScrubbing) ui.positionMs = player.currentPosition
            ui.bufferedMs = player.bufferedPosition

            // Автопереход: плашка появляется в конце серии, отсчёт дошёл до нуля — следующая.
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

    // Плашка автоперехода появилась — до реального перехода ещё AUTO_NEXT_COUNTDOWN_SEC секунд,
    // и это лучший момент прогреть следующую серию (см. [PlaybackSignal.AutoNextShown]).
    // LaunchedEffect с ключом на само значение срабатывает ровно на фронте false → true.
    LaunchedEffect(ui.autoNextVisible) {
        if (ui.autoNextVisible) session.onSignal(PlaybackSignal.AutoNextShown)
    }

    LaunchedEffect(ui.isScrubbing, ui.scrubTargetMs) {
        if (ui.isScrubbing) {
            delay(SCRUB_COMMIT_TIMEOUT_MS)
            ui.commitScrub()
        }
    }

    // Индикатор шага — про последнее нажатие, а не про состояние: живёт мгновение и гаснет.
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

/**
 * Общий каркас кадра для любого интерфейса: видеоповерхность, спиннер, ошибка, плашки подписки
 * и автоперехода; [overlay] — то, что интерфейс рисует поверх кадра, пока [BasePlayerUiState.visible].
 * Фокусируемый узел на экране ровно один — корневой Box: он и держит фокус, и разбирает клавиши,
 * поэтому пульт работает одинаково при видимом и скрытом оверлее.
 */
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

    // Пока идёт воспроизведение, экран не гаснет; на паузе — обычный таймаут системы.
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
                    // «Назад» не перехватываем: он весь в BackHandler, иначе система не увидит KeyUp.
                    event.key == Key.Back || event.key == Key.Escape -> false
                    else -> ui.onKey(event.key, menu)
                }
            },
    ) {
        VideoSurface(player = ui.player)

        // Спиннер и на буферизации, а не только на загрузке деталей: старт следующей серии,
        // перемотка и смена качества иначе выглядели бы как зависший чёрный экран.
        if ((session.loading || ui.isBuffering) && error == null) {
            CircularProgressIndicator(color = TvAccent, modifier = Modifier.align(Alignment.Center))
        }

        // Раньше ошибка не показывалась вовсе: сбой загрузки серии оставлял чёрный экран
        // без единого индикатора (жалоба «следующая серия не запустилась»).
        error?.let { PlayerErrorCard(error = it, modifier = Modifier.align(Alignment.Center)) }

        AnimatedVisibility(
            visible = ui.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            overlay()
        }

        // Плашка подписки — ВНЕ оверлея: без подписки поток не идёт, и объяснение должно быть
        // видно всегда, а не только пока оверлей на экране.
        if (session.subscriptionRequired && error == null) {
            SubscriptionCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = SubscriptionCardTop),
            )
        }

        // Плашка автоперехода — тоже ВНЕ оверлея: в конце серии он обычно скрыт, а отсчёт
        // должен быть виден всегда.
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

/** Видеоповерхность ExoPlayer; контролы отключены — весь UI поверх кадра рисует Compose. */
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

/** Отступ плашки автоперехода от низа кадра — над рядом плиток настроек транспорта. */
private val AutoNextCardBottom = 120.dp

/** Отступ плашки подписки от верха кадра — ниже строки шапки, выше центра с поповерами. */
private val SubscriptionCardTop = 96.dp
