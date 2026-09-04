package com.filmax.core.tv.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Одноразовые запросы обновления от повторного OK по активной вкладке верхнего меню. */
val LocalTvRefreshRequests: ProvidableCompositionLocal<Flow<Unit>> =
    staticCompositionLocalOf { emptyFlow() }

/** Подписывает текущий top-level экран на повторный выбор его активной вкладки. */
@Composable
fun RefreshOnTopNavReselect(onRefresh: () -> Unit) {
    val requests = LocalTvRefreshRequests.current
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    LaunchedEffect(requests) {
        requests.collect { currentOnRefresh.value() }
    }
}
