package com.filmax.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun FilmaxVersionLabel(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            .getOrDefault("")
    }
    if (versionName.isEmpty()) return
    Text(
        "Filmax $versionName",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}
