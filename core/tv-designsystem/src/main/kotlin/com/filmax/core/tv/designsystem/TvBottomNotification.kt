package com.filmax.core.tv.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Нефокусируемое нижнее уведомление: не перехватывает управление пультом у контента. */
@Composable
fun TvBottomNotification(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = TvSurfaceContainerHigh,
        contentColor = TvOnSurface,
        shape = TvMetrics.PanelShape,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Уведомление о серверной ошибке с общей для всех экранов формулировкой и анимацией. */
@Composable
fun TvServerRetryNotification(
    visible: Boolean,
    retriesExhausted: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        TvBottomNotification(
            text = if (retriesExhausted) {
                "Сервер всё ещё не отвечает. Попробуйте обновить раздел позже"
            } else {
                "Сервер не вернул данные. Повторим запрос через 3 секунды"
            },
        )
    }
}
