package com.filmax.feature.player.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.playback.AudioPreference
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.SubtitleKey
import com.filmax.core.domain.playback.SubtitlePreference
import com.filmax.core.domain.playback.TrackLanguage
import com.filmax.core.domain.playback.TrackPreset

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

/**
 * Вариант субтитров: «Выкл» ([Off]) либо конкретная HLS-дорожка ([Track]). Два случая — два типа,
 * а не «дорожка с пустым языком»: у настоящей дорожки язык в манифесте тоже бывает не указан.
 */
sealed interface SubtitleOption {
    val label: String

    /** Короткая подпись под кнопкой плеера: код языка, сырой код для незнакомого, «—» для «Выкл». */
    val shortCode: String

    data object Off : SubtitleOption {
        override val label: String get() = SubtitlePreference.Off.label
        override val shortCode: String get() = NO_VALUE_CAPTION
    }

    /**
     * [lang] — сырой код языка из HLS-манифеста (null — манифест его не указал); [language] — тот
     * же код, приведённый к известному языку, если он нам знаком. [groupIndex]/[trackIndex] —
     * адрес дорожки в Media3 `Tracks`.
     */
    data class Track(
        override val label: String,
        val lang: String?,
        val groupIndex: Int,
        val trackIndex: Int,
        val isForced: Boolean = false,
    ) : SubtitleOption {
        val language: TrackLanguage? get() = TrackLanguage.fromCode(lang)

        override val shortCode: String
            get() = language?.code ?: lang?.lowercase()?.takeIf { it.isNotBlank() } ?: UNKNOWN_LANGUAGE_SHORT_CODE

        companion object {
            /** Дорожка без кода языка в манифесте — под кнопкой хотя бы «есть субтитры». */
            const val UNKNOWN_LANGUAGE_SHORT_CODE = "sub"
        }
    }
}

/**
 * Ключ памяти тайтла для этого варианта. Раньше сохранялся только язык, из-за чего при
 * нескольких `rus` после повторного разбора манифеста всегда выбиралась первая дорожка — часто
 * это forced-субтитры. Старые значения языка остаются поддержанными в [SubtitleSelection.parse].
 */
internal fun SubtitleOption.preferenceKey(): SubtitleKey = when (this) {
    SubtitleOption.Off -> SubtitleKey(SUBTITLE_OFF_KEY)
    is SubtitleOption.Track -> SubtitleKey(
        "$SUBTITLE_TRACK_PREFERENCE_PREFIX${lang.orEmpty().lowercase()}$SUBTITLE_PREFERENCE_SEPARATOR$label",
    )
}

/**
 * Что просят включить у только что разобранного манифеста — разобранная форма ключа памяти
 * тайтла ([parse]) либо предпочтения пресета ([of]). Все ветки перечислены здесь, а не размазаны
 * по строковым префиксам: [resolveSubtitleOption] обязан обработать каждую.
 */
internal sealed interface SubtitleSelection {
    data object Off : SubtitleSelection

    /** Язык из пресета или старого сохранения кодом/подписью языка. */
    data class ByLanguage(val language: TrackLanguage) : SubtitleSelection

    /** Точная сохранённая дорожка (`track:язык|лейбл`, см. [preferenceKey]); язык пуст, если манифест его не дал. */
    data class SavedTrack(val language: String, val label: String) : SubtitleSelection

    /** Очень старое сохранение per-title без обвязки `track:` — целиком подпись дорожки. */
    data class LegacyLabel(val label: String) : SubtitleSelection

    companion object {
        fun of(preference: SubtitlePreference): SubtitleSelection = when (preference) {
            SubtitlePreference.Off -> Off
            is SubtitlePreference.Language -> ByLanguage(preference.language)
        }

        /**
         * Разбирает ключ памяти тайтла. Принимает и старые сырые ISO-коды («rus»/«eng»), и
         * display-значения («Русский»), которыми per-title выбор сохранялся до появления схемы
         * `track:` — для эвристики это один и тот же смысл: «дай русскую/английскую дорожку».
         */
        fun parse(key: SubtitleKey): SubtitleSelection {
            val raw = key.value
            return when {
                raw == SUBTITLE_OFF_KEY -> Off
                else -> raw.toSavedSubtitleTrack() ?: raw.toLanguage()?.let(::ByLanguage) ?: LegacyLabel(raw)
            }
        }

        /** Код или подпись известного языка; null — ни то, ни другое. */
        private fun String.toLanguage(): TrackLanguage? =
            TrackLanguage.fromCode(this)
                ?: TrackLanguage.entries.firstOrNull { it.display.equals(this, ignoreCase = true) }
    }
}

/**
 * Выбирает субтитры для только что разобранного манифеста.
 *
 * Приоритет (сильнее → слабее):
 * 1. [SubtitleSelection.SavedTrack] — если дорожку когда-то выбрали руками, форсированная она или
 *    нет, значение не пересматриваем. Тот же язык нашёлся на дорожке с другим лейблом (другая
 *    серия/качество сменили набор) — не-forced приоритетнее forced, лишь бы не «Выкл».
 * 2. [SubtitleSelection.LegacyLabel] — целиком совпавший лейбл дорожки.
 * 3. [SubtitleSelection.ByLanguage] — lowercase-substring эвристика по языку/подписи дорожки, см.
 *    [matchSubtitleByLanguage]. Ничего не подошло — «Выкл»: показать субтитры не на том языке
 *    хуже, чем не показать вовсе.
 */
internal fun resolveSubtitleOption(
    options: List<SubtitleOption>,
    selection: SubtitleSelection,
): SubtitleOption {
    val tracks = options.filterIsInstance<SubtitleOption.Track>()
    val resolved = when (selection) {
        SubtitleSelection.Off -> null
        is SubtitleSelection.SavedTrack -> resolveSavedSubtitleTrack(tracks, selection)
        is SubtitleSelection.LegacyLabel -> tracks.firstOrNull { it.label == selection.label }
        is SubtitleSelection.ByLanguage -> matchSubtitleByLanguage(tracks, selection.language)
    }
    return resolved ?: SubtitleOption.Off
}

/** Ветка точного сохранённого выбора дорожки — см. приоритет 1 в [resolveSubtitleOption]. */
private fun resolveSavedSubtitleTrack(
    tracks: List<SubtitleOption.Track>,
    saved: SubtitleSelection.SavedTrack,
): SubtitleOption.Track? {
    fun SubtitleOption.Track.sameLanguage() = lang.orEmpty().equals(saved.language, ignoreCase = true)
    return tracks.firstOrNull { it.sameLanguage() && it.label == saved.label }
        ?: tracks.firstOrNull { it.sameLanguage() && !it.isForced }
        ?: tracks.firstOrNull { it.sameLanguage() }
}

/**
 * Начала слов, по которым язык узнаётся в подписи дорожки («RUS», «Русский», «англ.»): почти все
 * HLS-манифесты kino.watch называют язык прямо в NAME, даже когда поле language у Format пустое.
 * Точный код — [TrackLanguage.isoCodes]. Новый язык в enum — компилятор потребует ветку и здесь.
 */
private fun TrackLanguage.needles(): List<String> = when (this) {
    TrackLanguage.Russian -> listOf("rus", "рус")
    TrackLanguage.English -> listOf("eng", "англ")
    TrackLanguage.Ukrainian -> listOf("ukr", "укр")
}

/**
 * Есть ли в тексте слово, начинающееся с одной из подстрок. Именно начало слова, а не вхождение:
 * «Беларуская» содержит «рус», «Bengali» — «eng», и поиском по вхождению обе дорожки выдавались
 * бы за русскую/английскую.
 */
private fun String.hasWordStartingWith(needles: List<String>): Boolean =
    split(WORD_SEPARATORS).any { word -> needles.any { needle -> word.startsWith(needle) } }

private val WORD_SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

/**
 * Эвристика авто-выбора субтитров по языку: lowercase-substring поиск по языку/подписи дорожки.
 * Форсированные дорожки (титры к иноязычным вставкам и т.п.) языковой default никогда не
 * выбирает — это не полноценные субтитры, доставать их должен только явный ручной выбор (см.
 * [SubtitleSelection.SavedTrack]). Несколько совпадений (два русских трека) — первая по порядку
 * в манифесте.
 */
private fun matchSubtitleByLanguage(
    tracks: List<SubtitleOption.Track>,
    target: TrackLanguage,
): SubtitleOption.Track? {
    val needles = target.needles()
    return tracks.firstOrNull { track ->
        val lang = track.lang.orEmpty().lowercase()
        !track.isForced &&
            (lang in target.isoCodes || "$lang ${track.label.lowercase()}".hasWordStartingWith(needles))
    }
}

private fun String.toSavedSubtitleTrack(): SubtitleSelection.SavedTrack? {
    if (!startsWith(SUBTITLE_TRACK_PREFERENCE_PREFIX)) return null
    val saved = removePrefix(SUBTITLE_TRACK_PREFERENCE_PREFIX)
    val separatorIndex = saved.indexOf(SUBTITLE_PREFERENCE_SEPARATOR)
    // Язык может быть пустым (`track:|лейбл`) — дорожка без LANGUAGE в манифесте; лейбл — нет.
    val hasValidSeparator = separatorIndex >= 0 && separatorIndex != saved.lastIndex
    return if (hasValidSeparator) {
        SubtitleSelection.SavedTrack(
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
 * Как «Выкл» записано в памяти тайтла. Совпадает с подписью [SubtitlePreference.Off] исторически
 * (так сохранялось до типизации ключей) — это формат хранения, менять нельзя без миграции.
 */
private const val SUBTITLE_OFF_KEY = "Выкл"

/** Подпись под кнопкой, когда значения нет: субтитры выключены, дорожка без языка. */
const val NO_VALUE_CAPTION = "—"

/**
 * Аудиодорожка потока. [groupIndex] — индекс аудиогруппы в Media3 `Tracks`: выбор идёт точечным
 * override, а не «предпочитаемым языком» — у тайтла бывает несколько русских озвучек разных
 * студий, и по языку они неотличимы. [lang] — код языка из метаданных API/манифеста (null или
 * пусто — оригинал, так API размечает оригинальную озвучку), [language] — известный нам язык.
 */
data class AudioOption(val label: String, val groupIndex: Int, val lang: String?) {
    val language: TrackLanguage? get() = TrackLanguage.fromCode(lang)

    val isOriginal: Boolean get() = lang.isNullOrBlank()

    /** Короткая подпись под кнопкой плеера: «rus», «orig», сырой код для незнакомого языка. */
    val shortCode: String
        get() = when {
            isOriginal -> ORIGINAL_SHORT_CODE
            else -> language?.code ?: lang.orEmpty().lowercase()
        }

    companion object {
        const val ORIGINAL_SHORT_CODE = "orig"
    }
}

/**
 * Данные аудиогруппы для эвристики авто-выбора: [lang] — язык из метаданных API (`AudioTrack.lang`)
 * или, если API его не прислал, из формата дорожки Media3; [label] — уже собранная подпись
 * (`audioLabel()` в `PlayerScreenModel`, вида «2. Русский · Многоголосый · BaibaKo»). Оба поля
 * участвуют в поиске: почти каждая подпись kino.watch называет язык прямо в тексте, а оригинал API
 * помечает пустым `lang` (см. `audioDisplay`: null → «Оригинал»).
 */
internal data class AudioMatchCandidate(val lang: String?, val label: String)

/**
 * Эвристика авто-выбора озвучки по предпочтению пресета ([TrackPreset.audio]) — lowercase-substring
 * поиск по языку/подписи дорожки, по той же логике, что и субтитры (см. `matchSubtitleByLanguage`):
 * - [AudioPreference.Original] — первая дорожка с пустым/бланковым языком ИЛИ подписью/языком,
 *   содержащими «оригинал»/«original» (так API размечает оригинальную озвучку).
 * - [AudioPreference.Language] — первая дорожка, чей язык — точный код языка, либо язык/подпись
 *   содержат одну из его подстрок ([needles]).
 *
 * Несколько совпадений (несколько русских озвучек разных студий) — берёт первую по порядку
 * дорожек в HLS-манифесте. Нет совпадения — null: override не ставится, выбор остаётся за плеером.
 */
internal fun resolveAudioGroupIndex(
    preference: AudioPreference,
    candidates: List<AudioMatchCandidate>,
): Int? {
    val index = when (preference) {
        AudioPreference.Original -> candidates.indexOfFirst { candidate ->
            candidate.lang.isNullOrBlank() || candidate.matchesAudio(ORIGINAL_NEEDLES)
        }
        is AudioPreference.Language -> candidates.indexOfFirst { candidate ->
            candidate.lang?.lowercase() in preference.language.isoCodes ||
                candidate.matchesAudio(preference.language.needles())
        }
    }
    return index.takeIf { it >= 0 }
}

private fun AudioMatchCandidate.matchesAudio(needles: List<String>): Boolean =
    "${lang.orEmpty()} $label".lowercase().hasWordStartingWith(needles)

/** Так API размечает оригинальную озвучку, когда код языка у неё всё же заполнен. */
private val ORIGINAL_NEEDLES = listOf("оригинал", "original")

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

    /** Вариант текущей скорости; неизвестное значение показываем как «Обычная». */
    fun optionFor(value: Float): SpeedOption =
        options.firstOrNull { it.value == value } ?: options.first { it.value == NormalSpeed }

    /** Подпись текущей скорости; неизвестное значение показываем как «Обычная». */
    fun labelFor(value: Float): String = optionFor(value).label
}

/** Что сейчас показывает плитка «Пресет»: авто-подбор, конкретный пресет или ручной выбор. */
sealed interface PresetSelection {
    /** Полная подпись (поповер) и короткая (плитка). */
    val label: String
    val shortLabel: String

    data object Auto : PresetSelection {
        override val label: String get() = PlaybackSettings.PresetAuto
        override val shortLabel: String get() = PlaybackSettings.PresetAuto
    }

    data class Preset(val preset: TrackPreset) : PresetSelection {
        override val label: String get() = preset.label
        override val shortLabel: String get() = preset.shortLabel
    }

    /** Пользователь выбрал озвучку и/или субтитры руками — пресет снят. */
    data object Custom : PresetSelection {
        override val label: String get() = "Свой"
        override val shortLabel: String get() = label
    }
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
    val currentQuality: StreamQuality? = null,
    /** Аудиодорожки потока; пусто, если выбирать не из чего (одна дорожка). */
    val audioTracks: List<AudioOption> = emptyList(),
    /** null — манифест ещё не разобран или у потока нет ни одной аудиогруппы. */
    val currentAudio: AudioOption? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    val currentSubtitle: SubtitleOption = SubtitleOption.Off,
    val currentPreset: PresetSelection = PresetSelection.Auto,
    /** Скорость воспроизведения; сессионная, дефолт — обычная (1.0). */
    val currentSpeed: Float = PlaybackSpeeds.NormalSpeed,
    /** У аккаунта нет активной подписки — поток не отдаётся, плеер объясняет это плашкой. */
    val subscriptionRequired: Boolean = false,
    val error: String? = null,
)

sealed interface PlayerEvent {
    /**
     * Зафиксировать позицию на сервере. [durationMs] — длительность потока (0, если ещё не
     * известна): по ней модель понимает, что позиция уже в хвосте серии, и вместе с `marktime`
     * ставит серверную отметку «досмотрено» (см. `PlayerScreenModel.saveProgress`).
     */
    data class SaveProgress(val positionMs: Long, val durationMs: Long = 0L) : PlayerEvent

    /** Поток дошёл до конца — серия досмотрена независимо от расхождений позиции и длительности. */
    data object MarkWatched : PlayerEvent

    /** Значения — из [PlayerState.qualities]/[PlayerState.audioTracks]/[PlayerState.subtitles]. */
    data class SelectQuality(val quality: StreamQuality) : PlayerEvent
    data class SelectAudio(val option: AudioOption) : PlayerEvent
    data class SelectSubtitle(val option: SubtitleOption) : PlayerEvent

    /** Пресет для тайтла; null — «Авто» (см. [PlaybackSettings.presetOptions]). */
    data class SelectPreset(val preset: TrackPreset?) : PlayerEvent
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
