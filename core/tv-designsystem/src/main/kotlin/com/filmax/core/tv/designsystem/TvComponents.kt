@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.filmax.core.tv.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Suppress("LongParameterList")
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = if (primary) {
        ButtonDefaults.colors(
            containerColor = TvAccent,
            contentColor = TvOnAccent,
            focusedContainerColor = TvAccent,
            focusedContentColor = TvOnAccent,
        )
    } else {
        ButtonDefaults.colors(
            containerColor = TvSurfaceContainerHigh,
            contentColor = TvOnSurface,
            focusedContainerColor = TvSurfaceContainerHigh,
            focusedContentColor = TvOnSurface,
        )
    }

    val shape = TvMetrics.ButtonShape
    Button(
        onClick = onClick,
        colors = colors,
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = TvMetrics.FocusScale),
        border = ButtonDefaults.border(
            focusedBorder = Border(BorderStroke(TvMetrics.FocusBorderWidth, TvFocus), shape = shape),
        ),
        modifier = modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = leadingIconTint ?: LocalContentColor.current,
                )
            }
            Text(
                text,
                style = FilmaxTvTypography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun TvFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TvMetrics.CardShape,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvMetrics.FocusScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
            contentColor = TvOnSurface,
            focusedContentColor = TvOnSurface,
        ),
        modifier = modifier
            .onFocusChanged { focused.value = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (focused.value) drawFocusRing(shape)
            }
        ) {
            content()
        }
    }
}

private fun DrawScope.drawFocusRing(shape: Shape) {
    val halo = Stroke(width = TvMetrics.FocusHaloWidth.toPx())
    val border = Stroke(width = TvMetrics.FocusBorderWidth.toPx())
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> {
            drawRect(color = TvFocusHalo, style = halo)
            drawRect(color = TvFocus, style = border)
        }

        is Outline.Rounded -> {
            val rr = outline.roundRect
            val topLeft = Offset(rr.left, rr.top)
            val rectSize = Size(rr.width, rr.height)
            val radius = CornerRadius(rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y)
            drawRoundRect(color = TvFocusHalo, topLeft = topLeft, size = rectSize, cornerRadius = radius, style = halo)
            drawRoundRect(color = TvFocus, topLeft = topLeft, size = rectSize, cornerRadius = radius, style = border)
        }

        is Outline.Generic -> {
            drawPath(path = outline.path, color = TvFocusHalo, style = halo)
            drawPath(path = outline.path, color = TvFocus, style = border)
        }
    }
}

@Composable
fun rememberDimAlpha(focused: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (focused) 1f else TvMetrics.DimmedAlpha,
        label = "cardDim",
    )
