package com.filmax.app.tv.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import kotlinx.coroutines.delay

/**
 * Единственный экран, который видно, пока [RootScreenModel] ещё не знает, авторизован ли
 * зритель (`isAuthenticated == null` — на большинстве запусков доля секунды). Статусная строка
 * внизу нарочно появляется не сразу: задержка [STATUS_DELAY_MILLIS] прячет её при обычном
 * мгновенном разрешении сессии и показывает, только когда шаг запуска реально заметен.
 */
@Composable
internal fun TvSplashScreen(modifier: Modifier = Modifier) {
    var showStatus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(STATUS_DELAY_MILLIS)
        showStatus = true
    }

    Box(modifier.fillMaxSize()) {
        Text(
            "FILMAX",
            fontWeight = FontWeight.Black,
            fontSize = SplashTitleSize,
            letterSpacing = SplashTitleSpacing,
            color = TvOnSurface,
            modifier = Modifier.align(Alignment.Center),
        )
        AnimatedVisibility(
            visible = showStatus,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = TvMetrics.SafeVertical),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    color = TvOnSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text("Подключение…", style = MaterialTheme.typography.bodySmall, color = TvOnSurfaceVariant)
            }
        }
    }
}

private val SplashTitleSize = 88.sp
private val SplashTitleSpacing = 8.sp
private const val STATUS_DELAY_MILLIS = 400L
