package com.filmax.core.tv.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

val LocalTvScrollToTop = compositionLocalOf { 0 }

@Composable
fun ScrollToTopOnNavFocus(state: LazyListState) {
    val initial = rememberInitialSignal()
    val signal = LocalTvScrollToTop.current
    LaunchedEffect(signal) { if (signal > initial) state.animateScrollToItem(0) }
}

@Composable
fun ScrollToTopOnNavFocus(state: LazyGridState) {
    val initial = rememberInitialSignal()
    val signal = LocalTvScrollToTop.current
    LaunchedEffect(signal) { if (signal > initial) state.animateScrollToItem(0) }
}

@Composable
fun ScrollToTopOnNavFocus(state: ScrollState) {
    val initial = rememberInitialSignal()
    val signal = LocalTvScrollToTop.current
    LaunchedEffect(signal) { if (signal > initial) state.animateScrollTo(0) }
}

@Composable
private fun rememberInitialSignal(): Int {
    val signal = LocalTvScrollToTop.current
    return remember { signal }
}
