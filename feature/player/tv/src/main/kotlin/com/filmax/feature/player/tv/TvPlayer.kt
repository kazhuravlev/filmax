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

/**
 * Один интерфейс плеера: рисует кадр [TvPlayerSession.player] и управление поверх него, сам
 * разбирает пульт и «Назад» ([TvPlayerSession.onBack] — когда интерфейсу больше нечего закрывать).
 */
internal interface TvPlayerUi {
    @Composable
    fun Content(session: TvPlayerSession, modifier: Modifier)
}

/** Реализация каждого пункта [PlayerUi] из настроек. Новый интерфейс — новая ветка здесь. */
internal fun PlayerUi.implementation(): TvPlayerUi = when (this) {
    PlayerUi.Classic -> ClassicPlayerUi
}

/**
 * Абстрактный плеер — единственная точка, где выбирается интерфейс: читает
 * [PlaybackSettings.playerUi] и отдаёт сессию соответствующей реализации. Через него идут и
 * тайтлы, и трейлеры, поэтому общее для всех интерфейсов живёт тут: пауза, когда с экрана ушли
 * не «Назад», а HOME/лаунчером — иначе звук продолжал играть за пределами приложения.
 */
@Composable
internal fun TvPlayer(session: TvPlayerSession, modifier: Modifier = Modifier) {
    val settings by koinInject<PlaybackSettingsRepository>().settings.collectAsState(PlaybackSettings())
    LifecycleStartEffect(session.player) {
        onStopOrDispose { session.player.pause() }
    }
    settings.playerUi.implementation().Content(session, modifier)
}
