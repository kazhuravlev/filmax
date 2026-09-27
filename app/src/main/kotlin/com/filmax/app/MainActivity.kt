package com.filmax.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.filmax.app.diagnostics.TechOverlay
import com.filmax.app.tv.navigation.FilmaxTvNavGraph
import com.filmax.app.update.AppUpdateEvent
import com.filmax.app.update.AppUpdatePrompt
import com.filmax.app.update.AppUpdateScreenModel
import com.filmax.core.domain.cache.ImageProxyRepository
import com.filmax.core.tv.designsystem.FilmaxTvTheme
import com.filmax.core.ui.components.LocalImageProxyEnabled
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val updateScreenModel: AppUpdateScreenModel = koinViewModel()
            val onCheckUpdates = { updateScreenModel.dispatch(AppUpdateEvent.Check) }
            val proxyEnabled by koinInject<ImageProxyRepository>().enabled.collectAsState()
            CompositionLocalProvider(LocalImageProxyEnabled provides proxyEnabled) {
                FilmaxTvTheme {
                    FilmaxTvNavGraph(
                        onCheckUpdates = onCheckUpdates,
                        onExit = { finishAndRemoveTask() },
                    )
                    AppUpdatePrompt(updateScreenModel)
                    TechOverlay()
                }
            }
        }
    }
}
