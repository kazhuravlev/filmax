package com.filmax.feature.player.tv

import androidx.media3.common.Player
import com.filmax.core.domain.error.AppError

/**
 * Что играем и чем управляем — общий вход для любого интерфейса плеера (см. [TvPlayer]).
 * Сессия не знает, откуда взялся поток: тайтл с `PlayerScreenModel` или трейлер по готовому URL —
 * интерфейс рисует одно и то же по одним и тем же данным.
 */
internal class TvPlayerSession(
    val player: Player,
    val title: String,
    val subtitle: String,
    /** Данные ещё грузятся (у тайтла — детали и ссылка на поток); буферизацию плеера интерфейс видит сам. */
    val loading: Boolean,
    val error: AppError?,
    /** У аккаунта нет подписки — поток не пойдёт, интерфейс обязан это объяснить. */
    val subscriptionRequired: Boolean,
    /** Подпись плашки автоперехода («Дальше: 5. Название»); null — следующей серии нет. */
    val autoNextLabel: String?,
    /** Сетка настроек и действия по сериям — что доступно в этой сессии. */
    val menu: PlayerActions,
    /** События воспроизведения, важные источнику (прогресс, конец); трейлеру они не нужны. */
    val onSignal: (PlaybackSignal) -> Unit,
    val onBack: () -> Unit,
)

/**
 * Что интерфейс сообщает источнику о ходе воспроизведения. Интерфейс не знает про
 * `PlayerEvent`/сервер: тайтл переводит сигналы в события `PlayerScreenModel`, трейлер игнорирует.
 */
internal sealed interface PlaybackSignal {
    /** Позиция стоит зафиксировать: пауза, перемотка, конец, уход с экрана. */
    data class Progress(val positionMs: Long, val durationMs: Long) : PlaybackSignal

    /** Поток дошёл до конца. */
    data object Ended : PlaybackSignal

    /** Показалась плашка автоперехода — источник может прогреть следующую серию. */
    data object AutoNextShown : PlaybackSignal
}
