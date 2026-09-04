package com.filmax.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.filmax.app.tv.navigation.FilmaxTvNavGraph
import com.filmax.app.update.AppUpdateEvent
import com.filmax.app.update.AppUpdatePrompt
import com.filmax.app.update.AppUpdateScreenModel
import com.filmax.core.tv.designsystem.FilmaxTvTheme
import org.koin.androidx.compose.koinViewModel

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
            FilmaxTvTheme {
                FilmaxTvNavGraph(
                    onCheckUpdates = onCheckUpdates,
                    // На корневом экране TV не оставляем task в фоне: следующий запуск из
                    // лаунчера создаст Activity заново, а не вернёт прежний экран.
                    onExit = { finishAndRemoveTask() },
                )
                AppUpdatePrompt(updateScreenModel)
            }
        }
    }
}
