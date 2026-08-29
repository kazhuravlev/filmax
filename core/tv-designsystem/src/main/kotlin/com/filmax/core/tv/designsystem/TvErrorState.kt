package com.filmax.core.tv.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Экран целиком не загрузился: заголовок, пояснение и «Повторить».
 *
 * Тексты приходят готовыми ([com.filmax.core.ui.components.appErrorText] у вызывающего) —
 * дизайн-система про домен не знает. [onRetry] == null убирает кнопку: бывают ошибки, из
 * которых повтор не выводит (нет подписки, контент снят с каталога).
 *
 * Кнопка сама забирает фокус: на пульте состояние без единого focusable — это тупик, из
 * которого не выйти ничем, кроме «Назад».
 */
@Composable
fun TvErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val retryFocus = remember { FocusRequester() }
    // Фокус просим в onPlaced: до раскладки кнопка ещё не привязала FocusRequester, а лэйаут
    // сам сообщает, когда она готова — ждать кадры наугад не нужно.
    var focusRequested by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = TvMetrics.SafeHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = TvOnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 10.dp)
                .widthIn(max = MessageMaxWidth),
        )
        if (onRetry != null) {
            TvButton(
                text = "Повторить",
                onClick = onRetry,
                focusRequester = retryFocus,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .onPlaced {
                        if (!focusRequested) {
                            focusRequested = true
                            retryFocus.requestFocus()
                        }
                    },
            )
        }
    }
}

/** Длинная строка на трёх метрах не читается — держим пояснение в колонке. */
private val MessageMaxWidth = 620.dp
