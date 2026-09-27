package com.filmax.feature.player.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * UI 1 ([com.filmax.core.domain.playback.PlayerUi.Ui1]): оверлей под пульт поверх кадра —
 * полоса прокрутки, Play со стрелками серий и сетка плиток настроек (см. [PlayerOverlay]).
 * Раскладка пульта — [TvPlayerUiState].
 */
internal object Ui1PlayerUi : TvPlayerUi {

    @Composable
    override fun Content(session: TvPlayerSession, modifier: Modifier) {
        val ui = remember(session.player) { TvPlayerUiState(session.player) }
        PlayerEffects(ui = ui, session = session)
        // «Назад» сначала закрывает то, что открыто в оверлее (поповер, панель серий), и только
        // потом отдаётся сессии.
        BackHandler { if (!ui.back()) session.onBack() }
        PlayerFrame(ui = ui, session = session, modifier = modifier) {
            PlayerOverlay(ui = ui, menu = session.menu, title = session.title, subtitle = session.subtitle)
        }
    }
}
