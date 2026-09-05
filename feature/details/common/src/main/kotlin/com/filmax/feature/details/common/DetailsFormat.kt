// Чистые производные и форматирование экрана Деталей, общие для mobile и tv:
// модель сериала (сезоны + точка «продолжить»), подписи меты и склонения. Без UI-зависимостей.

package com.filmax.feature.details.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.watching.model.Continuation

/** Статусы нужны подписям и прогрессу карточек эпизодов. */
const val WATCH_STATUS_IN_PROGRESS = 0
const val WATCH_STATUS_FINISHED = 1

// Модули русских правил склонения по числу (последние две / одна цифра).
private const val PLURAL_MOD_HUNDRED = 100
private const val PLURAL_MOD_TEN = 10

private const val MINUTES_IN_HOUR = 60

/**
 * Производные данные сериала для экрана: сезоны (сгруппированы и отсортированы) и точка «продолжить».
 * Отдельная чистая модель вместо переплетённых remember-блоков в Composable.
 */
data class SeriesData(
    /** Пары «номер сезона → серии по порядку», отсортированные по номеру сезона. */
    val seasons: List<Pair<Int, List<MediaTrack>>>,
    /** Эпизод для «Продолжить», только когда общий расчёт признал его незавершённым. */
    val resume: MediaTrack?,
    /** Индекс сезона эпизода «продолжить» в [seasons] (0, если не определён). */
    val resumeSeasonIndex: Int,
)

/** Считает [SeriesData] из плейлиста серий — чистая функция, тестируемая отдельно от UI. */
fun calculateSeriesData(tracklist: List<MediaTrack>, continuation: Continuation? = null): SeriesData {
    val seasons = tracklist
        .groupBy { it.seasonNumber }
        .toSortedMap()
        .map { (number, episodes) -> number to episodes.sortedBy { it.number } }
    val resume = continuation
        ?.takeIf { it.isActualContinuation }
        ?.let { target ->
            seasons.asSequence().flatMap { it.second.asSequence() }
                .firstOrNull { it.seasonNumber == target.season && it.number == target.videoId }
        }
    val resumeSeasonIndex = resume
        ?.let { episode -> seasons.indexOfFirst { it.first == episode.seasonNumber }.takeIf { it >= 0 } }
        ?: 0
    return SeriesData(seasons = seasons, resume = resume, resumeSeasonIndex = resumeSeasonIndex)
}

/**
 * Сериал определяем по ТИПУ тайтла, а не по числу дорожек: у фильма с двумя озвучками
 * `tracklist.size > 1`, и он получил бы селектор сезонов из одного бессмысленного сезона.
 */
fun Item.isSeries(): Boolean =
    type == ItemType.SERIES || type == ItemType.ANIME || type == ItemType.DOCUMENTARY

/**
 * У фильма в мете длительность, у сериала — объём: «3 сезона», а у односезонного «12 серий»
 * («1 сезон» не сообщает ничего).
 */
fun volumeLabel(item: Item, series: SeriesData?): String? = when {
    series == null -> item.duration.averageMinutes?.toInt()?.takeIf { it > 0 }?.let { durationLabel(it) }
    series.seasons.size > 1 -> "${series.seasons.size} ${seasonsWord(series.seasons.size)}"
    item.tracklist.isNotEmpty() -> "${item.tracklist.size} ${episodesWord(item.tracklist.size)}"
    else -> null
}

/** «2 ч 46 мин» / «46 мин» — часы опускаем, когда их нет. */
private fun durationLabel(minutes: Int): String {
    val hours = minutes / MINUTES_IN_HOUR
    val rest = minutes % MINUTES_IN_HOUR
    return if (hours > 0) "$hours ч $rest мин" else "$rest мин"
}

private const val GROUPING_SIZE = 3

/**
 * «12 345» — число просмотров с разрядами через пробел (аналог JS `toLocaleString()` в
 * ru-локали). 0 — это «данных нет», а не «ноль просмотров», поэтому такие тайтлы без подписи.
 */
fun viewsLabel(views: Int): String? = views.takeIf { it > 0 }
    ?.toString()
    ?.reversed()
    ?.chunked(GROUPING_SIZE)
    ?.joinToString(" ")
    ?.reversed()

fun initials(name: String): String =
    name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { word -> word.first().uppercaseChar() }
        .joinToString("")

fun typeLabel(type: ItemType): String = when (type) {
    ItemType.MOVIE -> "Фильм"
    ItemType.SERIES -> "Сериал"
    ItemType.ANIME -> "Аниме"
    ItemType.DOCUMENTARY -> "Док. сериал"
    ItemType.TV -> "ТВ"
}

private fun seasonsWord(count: Int): String = when {
    count % PLURAL_MOD_HUNDRED in 11..14 -> "сезонов"
    count % PLURAL_MOD_TEN == 1 -> "сезон"
    count % PLURAL_MOD_TEN in 2..4 -> "сезона"
    else -> "сезонов"
}

private fun episodesWord(count: Int): String = when {
    count % PLURAL_MOD_HUNDRED in 11..14 -> "серий"
    count % PLURAL_MOD_TEN == 1 -> "серия"
    count % PLURAL_MOD_TEN in 2..4 -> "серии"
    else -> "серий"
}
