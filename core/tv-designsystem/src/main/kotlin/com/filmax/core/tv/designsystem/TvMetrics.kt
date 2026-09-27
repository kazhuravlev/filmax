package com.filmax.core.tv.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FilmaxTvShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

object TvMetrics {
    val SafeHorizontal = 58.dp

    val SafeVertical = 28.dp

    val TopBarHeight = 64.dp

    val ContentTop = 78.dp

    val CardGap = 18.dp

    val RowGap = 24.dp

    val FocusInset = 12.dp

    val PosterWidth = 190.dp
    val PosterHeight = 285.dp

    val CompactPosterWidth = 150.dp
    val CompactPosterHeight = 225.dp

    val ContinueWidth = 250.dp
    val ContinueHeight = 141.dp

    val EpisodeWidth = 236.dp
    val EpisodeHeight = 133.dp

    val HeroHeight = 326.dp

    val DetailsHeroHeight = 290.dp

    val PosterShape = RoundedCornerShape(8.dp)
    val CardShape = RoundedCornerShape(10.dp)
    val PanelShape = RoundedCornerShape(12.dp)
    val ButtonShape = RoundedCornerShape(9.dp)
    val ChipShape = RoundedCornerShape(20.dp)

    const val FocusScale = 1.08f

    const val DimmedAlpha = 0.55f

    val FocusBorderWidth = 3.dp
    val FocusHaloWidth = 5.dp
}
