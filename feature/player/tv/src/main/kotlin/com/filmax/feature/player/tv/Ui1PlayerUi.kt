package com.filmax.feature.player.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

internal object Ui1PlayerUi : TvPlayerUi {
    @Composable
    override fun Content(session: TvPlayerSession, modifier: Modifier) {
        val ui = remember(session.player) { TvPlayerUiState(session.player) }
        PlayerEffects(ui = ui, session = session)
        BackHandler { if (!ui.back()) session.onBack() }
        PlayerFrame(ui = ui, session = session, modifier = modifier) {
            PlayerOverlay(ui = ui, menu = session.menu, title = session.title, subtitle = session.subtitle)
        }
    }
}
