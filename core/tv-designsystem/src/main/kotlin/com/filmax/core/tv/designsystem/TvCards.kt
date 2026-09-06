package com.filmax.core.tv.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Размер карточки 16:9. Оба варианта — одна и та же карточка, разной ширины: «продолжить»
 * на главной крупнее, эпизод в ряду серий компактнее.
 */
enum class TvCardSize(val width: Dp, val height: Dp) {
    Continue(TvMetrics.ContinueWidth, TvMetrics.ContinueHeight),
    Episode(TvMetrics.EpisodeWidth, TvMetrics.EpisodeHeight),
}

/**
 * Карточка-постер 2:3 — один из двух типов медиа-карточек на всё приложение. Используется
 * и в рядах, и в сетках каталога: один размер везде.
 *
 * Подпись живёт ПОД постером, а не поверх него: в монохроме постер — единственный источник
 * цвета, и накрывать его градиентом-скримом с текстом значит гасить единственное, что
 * держит экран. Заодно уходит риск бандинга на сером градиенте.
 *
 * [imdbRating]/[kinopoiskRating] — уже отформатированные строки (например «8.3»), каждая своя
 * пилюля с лого источника; null — эта пилюля не рисуется. Обе null — бейджа нет вовсе.
 *
 * [badgeContent] — расширяемый слот рядом с рейтингами: например, «В процессе» выводит в нём
 * число непросмотренных серий, не меняя устройство самой карточки.
 *
 * [advert] — в видео тайтла есть реклама (kino.watch `advert`): рисуем маленький бейдж-предупреждение
 * в противоположном от рейтинга углу (TopStart), чтобы они никогда не накладывались друг на друга.
 */
// Компонент дизайн-системы: параметры — его публичный API (Compose-конвенция: modifier прямым
// параметром, хвост — опции с дефолтами). Обёртка в data-класс сломала бы «минимальный API».
@Suppress("LongParameterList")
@Composable
fun TvPosterCard(
    title: String,
    meta: String?,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TvMetrics.PosterWidth,
    height: Dp = TvMetrics.PosterHeight,
    imdbRating: String? = null,
    kinopoiskRating: String? = null,
    advert: Boolean = false,
    focusRequester: FocusRequester? = null,
    badgeContent: (@Composable RowScope.() -> Unit)? = null,
    posterContent: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)

    Column(
        modifier = modifier
            .width(width)
            .onFocusChanged { focused = it.hasFocus }
            // Читаем dim.value в drawing-фазе (graphicsLayer), а не в composition (alpha()):
            // иначе каждый кадр анимации затухания рекомпозирует всю карточку с AsyncImage
            // постера — тот же приём, что и с рамкой фокуса в TvFocusCard.
            .graphicsLayer { alpha = dim.value },
    ) {
        TvFocusCard(
            onClick = onClick,
            shape = TvMetrics.PosterShape,
            focusRequester = focusRequester,
            modifier = Modifier.size(width = width, height = height),
        ) {
            Box(Modifier.fillMaxSize().clip(TvMetrics.PosterShape)) {
                posterContent(posterUrl, Modifier.fillMaxSize())
                val hasRating = imdbRating != null || kinopoiskRating != null
                if (hasRating || badgeContent != null) {
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        TvRatingPill(imdbRating = imdbRating, kinopoiskRating = kinopoiskRating)
                        badgeContent?.let { content ->
                            Row(modifier = if (hasRating) Modifier.padding(top = 6.dp) else Modifier) {
                                content()
                            }
                        }
                    }
                }
                if (advert) {
                    TvAdvertBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
        }
        TvCardCaption(title = title, meta = meta, focused = focused)
    }
}

/**
 * Карточка 16:9 с полосой прогресса — второй и последний тип медиа-карточки. «Продолжить
 * смотреть», история, эпизоды сериала.
 *
 * [progress] — доля 0..1. [meta] несёт то, ради чего карточка существует: «S2 · осталось 18 мин».
 * [size] — карточка эпизода уже, чем карточка продолжения ([TvCardSize.Episode] против
 * [TvCardSize.Continue]): в ряд эпизодов их помещается больше, а пропорция 16:9 та же.
 */
// Компонент дизайн-системы: параметры — его публичный API (см. TvPosterCard).
@Suppress("LongParameterList")
@Composable
fun TvProgressCard(
    title: String,
    meta: String?,
    posterUrl: String,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: TvCardSize = TvCardSize.Continue,
    focusRequester: FocusRequester? = null,
    posterContent: @Composable (url: String, modifier: Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val dim = rememberDimAlpha(focused)

    Column(
        modifier = modifier
            .width(size.width)
            .onFocusChanged { focused = it.hasFocus }
            // См. комментарий в TvPosterCard выше: alpha читается в drawing-фазе, не в composition.
            .graphicsLayer { alpha = dim.value },
    ) {
        TvFocusCard(
            onClick = onClick,
            shape = TvMetrics.CardShape,
            focusRequester = focusRequester,
            modifier = Modifier.size(width = size.width, height = size.height),
        ) {
            Box(Modifier.fillMaxSize().clip(TvMetrics.CardShape)) {
                posterContent(posterUrl, Modifier.fillMaxSize())
                TvProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        TvCardCaption(title = title, meta = meta, focused = focused)
    }
}

@Composable
fun TvProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(TvAccent.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(TvAccent),
        )
    }
}

/**
 * Пилюля рейтинга поверх постера: IMDb и Кинопоиск раздельно, каждый со своим лого — источник
 * должен быть понятен без подписи текстом, а не только числом. Полупрозрачная тёмная подложка
 * вместо цветной: в монохроме сам рейтинг не кодируем цветом, число говорит само. Источник без
 * оценки (null) просто не рисуется; если оба null — пилюли нет вовсе.
 */
@Composable
fun TvRatingPill(imdbRating: String?, kinopoiskRating: String?, modifier: Modifier = Modifier) {
    if (imdbRating == null && kinopoiskRating == null) return
    Row(
        modifier
            .clip(TvMetrics.PosterShape)
            .background(TvSurface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (imdbRating != null) TvRatingSource(icon = ImdbLogo, value = imdbRating)
        if (kinopoiskRating != null) TvRatingSource(icon = KinopoiskLogo, value = kinopoiskRating)
    }
}

@Composable
private fun TvRatingSource(icon: ImageVector, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TvOnSurface,
            modifier = Modifier.size(12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = TvOnSurface,
        )
    }
}

/**
 * Бейдж «в видео есть реклама» (kino.watch `advert`) — тот же визуальный язык, что у
 * [TvRatingPill] (полупрозрачная тёмная подложка, форма постера), но без иконки источника:
 * здесь важен сам факт, а не число. Маленькая пилюля, а не баннер — карточка узкая (2:3).
 */
@Composable
fun TvAdvertBadge(modifier: Modifier = Modifier) {
    Text(
        "Реклама",
        style = MaterialTheme.typography.labelSmall,
        color = TvOnSurface,
        modifier = modifier
            .clip(TvMetrics.PosterShape)
            .background(TvSurface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Круглый бейдж-счётчик поверх постера — количество непросмотренных серий. Единственное
 * цветное пятно интерфейса, наравне с ошибками ([TvError]): число, которое должно бросаться
 * в глаза раньше, чем зритель успеет прочитать постер.
 *
 * Ставится в дополнительный слот [TvPosterCard] под [TvRatingPill], а не поверх неё — иначе
 * рейтинг и счётчик серий накладываются друг на друга и оба становятся нечитаемыми.
 */
@Composable
fun TvCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(TvError)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = TvSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Отступ подписи от постера. Рамка фокуса рисуется поверх увеличенной (scale 1.08) карточки
 * и опускается ниже её исходной границы на ~11dp у постера 2:3 — при меньшем отступе белая
 * рамка ложится прямо на текст.
 */
private val CaptionTopGap = 16.dp

/**
 * Подпись под карточкой: название (16sp) + мета (13sp). Мельче на TV не опускаемся.
 *
 * Длинное название не переносится и не обрезается многоточием навсегда: строка одна, а при
 * фокусе на карточке запускается бегущая строка — так виден весь заголовок без роста карточки.
 */
@Composable
private fun TvCardCaption(title: String, meta: String?, focused: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = TvOnSurface,
        maxLines = 1,
        softWrap = false,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = CaptionTopGap)
            .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
    )
    if (!meta.isNullOrBlank()) {
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = TvOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Собирает мету постера `тип · год`, пропуская пустые части. */
fun posterMeta(type: String?, year: Int): String? {
    val parts = buildList {
        if (!type.isNullOrBlank()) add(type)
        if (year > 0) add(year.toString())
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/**
 * Мета единой сетки тайтлов (каталог, подборка, «Продолжить», «История») — `год · жанр`,
 * пропуская пустые части. Порядок обратный [posterMeta]: в этой сетке год важнее типа.
 */
fun gridPosterMeta(year: Int, genre: String?): String? {
    val parts = buildList {
        if (year > 0) add(year.toString())
        if (!genre.isNullOrBlank()) add(genre)
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/**
 * Оценка для пилюли и меты: в домене это строка вида «8.312», на экране нужен один знак.
 *
 * Ноль — это «оценки нет», а не «ноль баллов»: kino.watch отдаёт `0` для тайтлов без рейтинга,
 * и печатать «0.0 КП» под постером — врать зрителю. Такие карточки остаются без пилюли.
 */
fun ratingLabel(raw: String?): String? = ratingLabel(raw?.toDoubleOrNull())

/** То же для уже разобранной оценки (`rating.external` — усреднённая IMDb+КП). */
fun ratingLabel(value: Double?): String? =
    value?.takeIf { it > 0 }
        ?.let { String.format(Locale.US, "%.1f", it) }
