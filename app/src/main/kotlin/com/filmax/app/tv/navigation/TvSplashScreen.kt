package com.filmax.app.tv.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.filmax.app.R
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import kotlinx.coroutines.delay

/**
 * Единственный экран, который видно, пока [RootScreenModel] ещё не знает, авторизован ли
 * зритель (`isAuthenticated == null` — на большинстве запусков доля секунды). Статусная строка
 * внизу нарочно появляется не сразу: задержка [STATUS_DELAY_MILLIS] прячет её при обычном
 * мгновенном разрешении сессии и показывает, только когда шаг запуска реально заметен.
 *
 * Логотип — тот же векторный ассет `splash_logo.xml`, что нарисован в `android:windowBackground`
 * темы (см. `splash_background.xml`, `themes.xml`): системное окно запуска видно первым кадром,
 * до старта Compose, и должно бесшовно смениться этим экраном — один файл-ассет с одинаковым
 * размером в обоих местах гарантирует, что логотип не «дёрнется» и не сменит начертание при
 * переходе, в отличие от попытки подобрать Text() под системную отрисовку на глаз.
 */
@Composable
internal fun TvSplashScreen(modifier: Modifier = Modifier) {
    var showStatus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(STATUS_DELAY_MILLIS)
        showStatus = true
    }

    Box(modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = SplashLogoWidth, height = SplashLogoHeight),
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

// Совпадает с размером логотипа в splash_background.xml (drawable/splash_background.xml) —
// см. doc-комментарий у TvSplashScreen о бесшовном переходе от окна запуска.
private val SplashLogoWidth = 240.dp
private val SplashLogoHeight = 47.4.dp
private const val STATUS_DELAY_MILLIS = 400L
