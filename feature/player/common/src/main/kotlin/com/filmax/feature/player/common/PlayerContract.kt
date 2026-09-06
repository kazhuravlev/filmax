package com.filmax.feature.player.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.playback.PlaybackSettings

/**
 * Доступное качество потока. [urls] — варианты доставки в порядке предпочтения (hls4 → hls → http):
 * у kino.watch они ведут на РАЗНЫЕ CDN-хосты, и один из них бывает недоступен из-за
 * DPI/SNI-блокировок. Плеер стартует с первого и при ошибке источника переключается на следующий.
 */
data class StreamQuality(val label: String, val urls: List<String>) {
    init {
        require(urls.isNotEmpty()) { "Качество $label без единой ссылки на поток" }
    }

    val url: String get() = urls.first()
}

/** Вариант субтитров; [lang] == null означает «Выкл». */
data class SubtitleOption(
    val label: String,
    val lang: String?,
    val groupIndex: Int = -1,
    val trackIndex: Int = 0,
    val isForced: Boolean = false,
)

/**
 * Код сохранённого выбора конкретной дорожки. Раньше сохранялся только язык, из-за чего при
 * нескольких `rus` после повторного разбора манифеста всегда выбиралась первая дорожка — часто
 * это forced-субтитры. Старые значения языка остаются поддержанными в [resolveSubtitleOption].
 */
internal fun SubtitleOption.preferenceKey(): String =
    lang?.let { "$SUBTITLE_TRACK_PREFERENCE_PREFIX${it.lowercase()}$SUBTITLE_PREFERENCE_SEPARATOR$label" }
        ?: PlaybackSettings.SubtitleOff

/**
 * Выбирает субтитры для только что разобранного манифеста.
 *
 * Приоритет (сильнее → слабее):
 * 1. Точная сохранённая дорожка тайтла (`track:язык|лейбл`, см. [preferenceKey]) — если её когда-то
 *    выбрали руками, форсированная она или нет, значение не пересматриваем.
 *    Тот же язык нашёлся на дорожке с другим лейблом (другая серия/качество сменили набор) —
 *    старое поведение: не-forced приоритетнее forced, лишь бы не «Выкл».
 * 2. Очень старое сохранение per-title без обвязки `track:` — целиком совпавший лейбл дорожки.
 * 3. Глобальный default профиля («Русский»/«English», см. [PlaybackSettings.subtitleOptions]) —
 *    lowercase-substring эвристика по языку/подписи дорожки, см. [matchSubtitleByLanguage].
 *    Ничего не подошло (включая «Выкл» и нераспознанные значения) — «Выкл»: показать субтитры не
 *    на том языке хуже, чем не показать вовсе.
 */
internal fun resolveSubtitleOption(
    options: List<SubtitleOption>,
    preference: String,
): SubtitleOption {
    val savedTrack = preference.toSavedSubtitleTrack()
    val resolved = when {
        preference == PlaybackSettings.SubtitleOff -> null
        savedTrack != null -> resolveSavedSubtitleTrack(options, savedTrack)
        else ->
            options.firstOrNull { option -> option.lang != null && option.label == preference }
                ?: subtitleLanguageTarget(preference)?.let { matchSubtitleByLanguage(options, it) }
    }
    // options.first() — всегда «Выкл» (см. updateSubtitleTracks: она добавляется первой).
    return resolved ?: options.first()
}

/** Ветка точного сохранённого выбора дорожки (`track:язык|лейбл`) — см. приоритет 1 в
 * [resolveSubtitleOption]. */
private fun resolveSavedSubtitleTrack(
    options: List<SubtitleOption>,
    saved: SavedSubtitleTrack,
): SubtitleOption? =
    options.firstOrNull { option -> option.lang.equals(saved.language, true) && option.label == saved.label }
        ?: options.firstOrNull { option -> option.lang.equals(saved.language, true) && !option.isForced }
        ?: options.firstOrNull { option -> option.lang.equals(saved.language, true) }

/** Язык, под который подбираем субтитры глобальной эвристикой. */
private enum class SubtitleLanguageTarget { RUSSIAN, ENGLISH }

/**
 * Приводит предпочтение к языковому бакету — принимает и новые display-значения из
 * [PlaybackSettings.subtitleOptions] («Русский»/«English»), и старые сырые ISO-коды («rus»/«eng»),
 * которыми per-title выбор субтитров сохранялся до появления схемы `track:` (см. [preferenceKey]):
 * для эвристики это один и тот же смысл — «дай русскую/английскую дорожку».
 */
private fun subtitleLanguageTarget(preference: String): SubtitleLanguageTarget? = when (preference.lowercase()) {
    "русский", "rus", "ru" -> SubtitleLanguageTarget.RUSSIAN
    "english", "eng", "en" -> SubtitleLanguageTarget.ENGLISH
    else -> null
}

/**
 * Эвристика авто-выбора субтитров по языку: lowercase-substring поиск по языку/подписи дорожки —
 * почти все HLS-манифесты kino.watch называют язык прямо в NAME («RUS», «ENG»), даже когда поле
 * language у Format пустое. Форсированные дорожки (титры к иноязычным вставкам и т.п.) языковой
 * default никогда не выбирает — это не полноценные субтитры, доставать их должен только явный
 * ручной выбор (см. точное совпадение по сохранённой дорожке в [resolveSubtitleOption]). Несколько
 * совпадений (два русских трека) — берёт первую дорожку по порядку в манифесте.
 */
private fun matchSubtitleByLanguage(
    options: List<SubtitleOption>,
    target: SubtitleLanguageTarget,
): SubtitleOption? {
    val (needles, exactCode) = when (target) {
        SubtitleLanguageTarget.RUSSIAN -> listOf("rus", "рус") to "ru"
        SubtitleLanguageTarget.ENGLISH -> listOf("eng", "англ") to "en"
    }
    return options.firstOrNull { option ->
        val lang = option.lang?.lowercase()
        !option.isForced && lang != null &&
            (lang == exactCode || needles.any { "$lang ${option.label.lowercase()}".contains(it) })
    }
}

private data class SavedSubtitleTrack(val language: String, val label: String)

private fun String.toSavedSubtitleTrack(): SavedSubtitleTrack? {
    if (!startsWith(SUBTITLE_TRACK_PREFERENCE_PREFIX)) return null
    val saved = removePrefix(SUBTITLE_TRACK_PREFERENCE_PREFIX)
    val separatorIndex = saved.indexOf(SUBTITLE_PREFERENCE_SEPARATOR)
    val hasValidSeparator = separatorIndex > 0 && separatorIndex != saved.lastIndex
    return if (hasValidSeparator) {
        SavedSubtitleTrack(
            language = saved.substring(0, separatorIndex),
            label = saved.substring(separatorIndex + 1),
        )
    } else {
        null
    }
}

private const val SUBTITLE_TRACK_PREFERENCE_PREFIX = "track:"
private const val SUBTITLE_PREFERENCE_SEPARATOR = "|"

/**
 * Аудиодорожка потока. [groupIndex] — индекс аудиогруппы в Media3 `Tracks`: выбор идёт точечным
 * override, а не «предпочитаемым языком» — у тайтла бывает несколько русских озвучек разных
 * студий, и по языку они неотличимы.
 */
data class AudioOption(val label: String, val groupIndex: Int)

/**
 * Данные аудиогруппы для эвристики авто-выбора: [lang] — язык из метаданных API (`AudioTrack.lang`)
 * или, если API его не прислал, из формата дорожки Media3; [label] — уже собранная подпись
 * (`audioLabel()` в `PlayerScreenModel`, вида «2. Русский · Многоголосый · BaibaKo»). Оба поля
 * участвуют в поиске: почти каждая подпись kino.watch называет язык прямо в тексте, а оригинал API
 * помечает пустым `lang` (см. `audioDisplay`: null → «Оригинал»).
 */
internal data class AudioMatchCandidate(val lang: String?, val label: String)

/**
 * Эвристика авто-выбора озвучки по глобальному предпочтению из настроек профиля
 * ([PlaybackSettings.audioOptions]) — lowercase-substring поиск по языку/подписи дорожки, по той же
 * логике, что и субтитры (см. `matchSubtitleByLanguage`):
 * - «Выкл» — авто-выбор отключён, override не ставим, играет дефолтная дорожка плеера.
 * - «Оригинал» — первая дорожка с пустым/бланковым языком ИЛИ подписью/языком, содержащими
 *   «оригинал»/«original» (так API размечает оригинальную озвучку).
 * - «Русский» / «English» — первая дорожка, чей язык/подпись содержит «rus»/«рус» либо
 *   «eng»/«англ» (или сам язык — точный код `ru`/`en`) соответственно.
 *
 * Несколько совпадений (несколько русских озвучек разных студий) — берёт первую по порядку
 * дорожек в HLS-манифесте. Нет совпадения или преференция не распознана — null: override не
 * ставится, выбор остаётся за плеером.
 */
internal fun resolveAudioGroupIndex(
    preference: String,
    candidates: List<AudioMatchCandidate>,
): Int? {
    if (preference == PlaybackSettings.AudioOff) return null
    val index = when (preference) {
        PlaybackSettings.AudioOriginal -> candidates.indexOfFirst { candidate ->
            candidate.lang.isNullOrBlank() || candidate.matchesAudio("оригинал", "original")
        }
        "Русский" -> candidates.indexOfFirst { candidate ->
            candidate.lang?.lowercase() == "ru" || candidate.matchesAudio("rus", "рус")
        }
        "English" -> candidates.indexOfFirst { candidate ->
            candidate.lang?.lowercase() == "en" || candidate.matchesAudio("eng", "англ")
        }
        else -> -1
    }
    return index.takeIf { it >= 0 }
}

private fun AudioMatchCandidate.matchesAudio(vararg needles: String): Boolean {
    val haystack = "${lang.orEmpty()} $label".lowercase()
    return needles.any { haystack.contains(it) }
}

/** Вариант скорости воспроизведения: [value] уходит в ExoPlayer, [label] — на экран. */
data class SpeedOption(val label: String, val value: Float)

/**
 * Набор скоростей воспроизведения — единый для mobile и TV, чтобы список и подписи совпадали.
 * Скорость сессионная: между пересозданием плеера не сохраняется.
 */
object PlaybackSpeeds {
    const val NormalLabel = "Обычная"
    const val NormalSpeed = 1.0f

    val options: List<SpeedOption> = listOf(
        SpeedOption("0.25×", 0.25f),
        SpeedOption("0.5×", 0.5f),
        SpeedOption("0.75×", 0.75f),
        SpeedOption(NormalLabel, NormalSpeed),
        SpeedOption("1.25×", 1.25f),
        SpeedOption("1.5×", 1.5f),
        SpeedOption("1.75×", 1.75f),
        SpeedOption("2×", 2.0f),
    )

    /** Подписи для меню/поповера в порядке возрастания скорости. */
    val labels: List<String> = options.map { it.label }

    /** Подпись текущей скорости; неизвестное значение показываем как «Обычная». */
    fun labelFor(value: Float): String = options.firstOrNull { it.value == value }?.label ?: NormalLabel

    /** Значение скорости по подписи из меню; null — подписи нет в наборе. */
    fun valueFor(label: String): Float? = options.firstOrNull { it.label == label }?.value
}

data class PlayerState(
    val loading: Boolean = true,
    val item: Item? = null,
    /**
     * Играющий трек и его соседи по плейлисту — модель выбирает их по маршруту, UI не ищет заново.
     */
    val track: MediaTrack? = null,
    val previousTrack: MediaTrack? = null,
    val nextTrack: MediaTrack? = null,
    val streamUrl: String? = null,
    val qualities: List<StreamQuality> = emptyList(),
    val currentQuality: String? = null,
    /** Аудиодорожки потока; пусто, если выбирать не из чего (одна дорожка). */
    val audioTracks: List<AudioOption> = emptyList(),
    val currentAudio: String = "",
    val subtitles: List<SubtitleOption> = emptyList(),
    val currentSubtitle: String = "Выкл",
    /** Скорость воспроизведения; сессионная, дефолт — обычная (1.0). */
    val currentSpeed: Float = PlaybackSpeeds.NormalSpeed,
    /** У аккаунта нет активной подписки — поток не отдаётся, плеер объясняет это плашкой. */
    val subscriptionRequired: Boolean = false,
    val error: String? = null,
)

sealed interface PlayerEvent {
    data class SaveProgress(val positionMs: Long) : PlayerEvent
    data class SelectQuality(val label: String) : PlayerEvent
    data class SelectAudio(val label: String) : PlayerEvent
    data class SelectSubtitle(val label: String) : PlayerEvent
    data class SetSpeed(val speed: Float) : PlayerEvent

    /**
     * Плашка автоперехода стала видна — до реального перехода на следующую серию ещё несколько
     * секунд (`AUTO_NEXT_COUNTDOWN_SEC` в feature:player:tv). Спекулятивно прогревает
     * `catalog.getItemDetails(itemId, forceRefresh = true)` для СЛЕДУЮЩЕЙ серии заранее: у неё тот
     * же itemId, что и у текущей (сериал один), и её PlayerScreenModel сделает тот же forceRefresh
     * секунды спустя (см. onFetchData) — с адопцией в CatalogRepositoryImpl тот второй вызов
     * окажется мгновенным, а не новым походом в сеть.
     */
    data object PrefetchNextEpisode : PlayerEvent
}

sealed interface PlayerSideEffect

/** «1:23:45» / «23:45» — формат времени плеера, единый для mobile и TV. */
@Suppress("MagicNumber")
fun formatPlayerTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
