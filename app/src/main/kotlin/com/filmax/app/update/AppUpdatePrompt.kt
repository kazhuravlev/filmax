package com.filmax.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filmax.app.BuildConfig
import org.koin.androidx.compose.koinViewModel

/**
 * Диалог обновления приложения поверх любого графа (телефон и TV): «Доступна версия X.Y.Z» →
 * скачивание с прогрессом → системный установщик. Появляется, когда GitHub Releases отдал релиз
 * новее установленного, «Позже» прячет его до следующего запуска.
 *
 * Он же отчитывается о ручной проверке из Профиля ([AppUpdateEvent.Check]): ожидание и «версия
 * последняя» — это ответ на явный тап, поэтому показываются, а фоновая проверка при старте
 * по-прежнему молчит.
 *
 * Компоненты material3 намеренно: диалог общий для двух дизайн-систем, а фокус на кнопках
 * диалога пульт получает штатно — Dialog перехватывает весь ввод.
 */
@Composable
fun AppUpdatePrompt(screenModel: AppUpdateScreenModel = koinViewModel()) {
    val state by screenModel.collectAsState()
    val context = LocalContext.current

    screenModel.collectSideEffect { effect ->
        when (effect) {
            is AppUpdateSideEffect.LaunchInstaller -> installApk(context, effect.apk)
        }
    }

    val update = state.update
    val onDismiss = { screenModel.dispatch(AppUpdateEvent.Dismiss) }
    when {
        state.checking -> CheckingDialog()
        state.upToDate -> UpToDateDialog(onDismiss = onDismiss)
        update != null && !state.dismissed -> UpdateDialog(
            state = state,
            update = update,
            onEvent = screenModel::dispatch,
        )
    }
}

@Composable
private fun UpdateDialog(state: AppUpdateState, update: UpdateInfo, onEvent: (AppUpdateEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.downloading) onEvent(AppUpdateEvent.Dismiss) },
        title = { Text("Доступна версия ${update.version}") },
        text = { UpdateDialogBody(state) },
        confirmButton = { UpdateConfirmButton(state, onEvent = onEvent) },
        dismissButton = {
            // Пока качаем — отложить нечего; когда ставить нельзя, «Понятно» и так закрывает диалог.
            if (!state.downloading && state.installable) {
                TextButton(onClick = { onEvent(AppUpdateEvent.Dismiss) }) {
                    Text("Позже")
                }
            }
        },
    )
}

/** Ожидание ответа GitHub. Без кнопок: отменять нечего — это один короткий запрос. */
@Composable
private fun CheckingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Проверяем обновления") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Запрашиваем последний релиз…")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun UpToDateDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обновлений нет") },
        text = { Text("Установлена ${BuildConfig.VERSION_NAME} — это последняя версия.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ок") } },
    )
}

@Composable
private fun UpdateDialogBody(state: AppUpdateState) {
    Column {
        when {
            state.downloading -> {
                Text("Скачивание обновления…")
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }

            state.downloadError -> Text("Не удалось скачать обновление. Проверьте сеть и повторите.")

            state.downloadedApk != null -> Text("Обновление скачано — осталось установить.")

            // Ставить релизный APK поверх debug/demo нельзя: applicationId другой, и система
            // поставит его вторым приложением рядом, а не обновит текущее.
            !state.installable -> Text(
                "Установлена ${BuildConfig.VERSION_NAME}. Обновиться можно только из релизной сборки.",
            )

            else -> Text("Установлена ${BuildConfig.VERSION_NAME}. Скачать и установить обновление?")
        }
    }
}

@Composable
private fun UpdateConfirmButton(state: AppUpdateState, onEvent: (AppUpdateEvent) -> Unit) {
    if (state.downloading) return
    if (!state.installable) {
        TextButton(onClick = { onEvent(AppUpdateEvent.Dismiss) }) { Text("Понятно") }
        return
    }
    val (label, event) = when {
        state.downloadedApk != null -> "Установить" to AppUpdateEvent.Install
        state.downloadError -> "Повторить" to AppUpdateEvent.Download
        else -> "Обновить" to AppUpdateEvent.Download
    }
    TextButton(onClick = { onEvent(event) }) {
        Text(label)
    }
}
