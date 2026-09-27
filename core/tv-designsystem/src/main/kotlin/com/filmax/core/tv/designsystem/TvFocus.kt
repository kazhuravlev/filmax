package com.filmax.core.tv.designsystem

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced

val LocalTvNavBarFocused: ProvidableCompositionLocal<State<Boolean>> =
    compositionLocalOf { mutableStateOf(false) }

fun Modifier.tvFocusGroup(): Modifier = focusRestorer().focusGroup()

@Stable
class TvScreenFocus internal constructor(
    private val lastFocused: MutableState<String?>,
    private val navBarFocused: State<Boolean>,
    returnTo: String?,
) {
    val initialReturnTarget: String? = returnTo

    private var returnTo: String? = returnTo

    private val container = FocusRequester()

    private var done = false

    val containerModifier: Modifier = Modifier
        .focusRequester(container)
        .tvFocusGroup()
        .onPlaced {
            if (!done && returnTo == null && !navBarFocused.value) {
                done = true
                container.requestFocus()
            }
        }

    fun focusOn(key: String? = null) {
        returnTo = key
        done = false
    }

    @Composable
    fun item(key: String): Modifier {
        val requester = remember { FocusRequester() }
        return Modifier
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) lastFocused.value = key }
            .onPlaced {
                if (!done && key == returnTo && !navBarFocused.value) {
                    done = true
                    requester.requestFocus()
                }
            }
    }
}

@Composable
fun rememberTvScreenFocus(startAt: String? = null): TvScreenFocus {
    val lastFocused = rememberSaveable { mutableStateOf<String?>(null) }
    val navBarFocused = LocalTvNavBarFocused.current
    return remember { TvScreenFocus(lastFocused, navBarFocused, returnTo = lastFocused.value ?: startAt) }
}
