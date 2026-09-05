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

/** Выбирает точную сохранённую дорожку, а для старой настройки по языку предпочитает не-forced. */
internal fun resolveSubtitleOption(
    options: List<SubtitleOption>,
    preference: String,
): SubtitleOption {
    if (preference == PlaybackSettings.SubtitleOff) return options.first()

    val savedTrack = preference.toSavedSubtitleTrack()
    val preferredLanguage = savedTrack?.language ?: langCode(preference)
    val exactSavedTrack = savedTrack?.let { saved ->
        options.firstOrNull { option ->
            option.lang.equals(saved.language, true) && option.label == saved.label
        }
    }
    return exactSavedTrack ?: options.firstOrNull { option ->
        option.lang != null && option.label == preference
    } ?: options.firstOrNull { option ->
        option.lang.equals(preferredLanguage, true) && !option.isForced
    } ?: options.firstOrNull { option ->
        option.lang.equals(preferredLanguage, true)
    } ?: options.first()
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

private fun langCode(display: String): String? = when (display.lowercase()) {
    "русский" -> "rus"
    "english" -> "eng"
    else -> null
}

private const val SUBTITLE_TRACK_PREFERENCE_PREFIX = "track:"
private const val SUBTITLE_PREFERENCE_SEPARATOR = "|"

/**
 * Аудиодорожка потока. [groupIndex] — индекс аудиогруппы в Media3 `Tracks`: выбор идёт точечным
 * override, а не «предпочитаемым языком» — у тайтла бывает несколько русских озвучек разных
 * студий, и по языку они неотличимы.
 */
data class AudioOption(val label: String, val groupIndex: Int)

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
