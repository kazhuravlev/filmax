package com.filmax.core.domain.playback

import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

/**
 * Язык дорожки, который приложение различает. [display] — подпись в списках, [code] — короткий
 * код под кнопкой плеера, [isoCodes] — как язык приходит в метаданных API и HLS-манифесте.
 * Новый язык — новое значение здесь; все `when` по нему исчерпывающие, компилятор укажет места.
 */
enum class TrackLanguage(val display: String, val code: String, val isoCodes: Set<String>) {
    Russian("Русский", "rus", setOf("ru", "rus")),
    English("English", "eng", setOf("en", "eng")),
    Ukrainian("Українська", "ukr", setOf("uk", "ukr")),
    ;

    companion object {
        /** null — код пустой или язык нам неизвестен (подпись тогда показывает сырой код). */
        fun fromCode(code: String?): TrackLanguage? {
            val normalized = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { normalized in it.isoCodes }
        }
    }
}

/** Какую озвучку хочет пресет: оригинальную дорожку или конкретный язык. */
sealed interface AudioPreference {
    val label: String

    data object Original : AudioPreference {
        override val label: String get() = "Оригинал"
    }

    data class Language(val language: TrackLanguage) : AudioPreference {
        override val label: String get() = language.display
    }
}

/** Какие субтитры хочет пресет: никаких или конкретный язык. */
sealed interface SubtitlePreference {
    val label: String

    data object Off : SubtitlePreference {
        override val label: String get() = "Выкл"
    }

    data class Language(val language: TrackLanguage) : SubtitlePreference {
        override val label: String get() = language.display
    }
}

/**
 * Пресет пары «озвучка + субтитры». Порядок объявления — это и порядок авто-подбора: в режиме
 * «Авто» плеер берёт ПЕРВЫЙ пресет, у которого и озвучка, и субтитры нашлись среди дорожек
 * тайтла (субтитры «Выкл» подходят всегда). Эвристики матчинга по языку — в feature:player:common.
 */
enum class TrackPreset(
    val label: String,
    /** Короткая подпись для узкой плитки плеера. */
    val shortLabel: String,
    val audio: AudioPreference,
    val subtitle: SubtitlePreference,
) {
    OriginalEnglishSubs(
        "Оригинал + англ. субтитры",
        "Ориг. + EN",
        AudioPreference.Original,
        SubtitlePreference.Language(TrackLanguage.English),
    ),
    OriginalRussianSubs(
        "Оригинал + рус. субтитры",
        "Ориг. + RU",
        AudioPreference.Original,
        SubtitlePreference.Language(TrackLanguage.Russian),
    ),
    RussianDub(
        "Русская озвучка, без субтитров",
        "Русский",
        AudioPreference.Language(TrackLanguage.Russian),
        SubtitlePreference.Off,
    ),
}

/**
 * Непрозрачный ключ озвучки в памяти тайтла (`язык|тип|студия`, см. `voiceKey` в
 * `PlayerScreenModel`). Отдельный тип, а не String: ключ нельзя перепутать с подписью дорожки —
 * они похожи на глаз, но подпись нестабильна между сериями, а ключ — нет.
 */
@JvmInline
value class VoiceKey(val value: String)

/**
 * Непрозрачный ключ субтитров в памяти тайтла. Собирает и разбирает его плеер
 * (`SubtitleSelection` в feature:player:common): `track:язык|лейбл`, «Выкл» либо старый сырой язык.
 */
@JvmInline
value class SubtitleKey(val value: String)

/**
 * Что помнит тайтл о своих дорожках — ровно одно из двух:
 *  - [Preset] — выбранный для тайтла пресет (null внутри — «Авто», подбор по порядку
 *    [TrackPreset.entries] заново на каждой серии);
 *  - [Custom] — пользователь выбрал озвучку и/или субтитры руками; пресет при этом снимается, и
 *    плеер показывает «Свой». Отсутствующая половина (старые сохранения до появления пресетов)
 *    подбирается пресетом.
 */
sealed interface TitleTracks {
    data class Preset(val preset: TrackPreset?) : TitleTracks

    data class Custom(val voiceKey: VoiceKey?, val subtitleKey: SubtitleKey?) : TitleTracks
}

/**
 * Интерфейс плеера — какой набор экранов рисуется поверх кадра. Выбирается в Профиле и
 * применяется ко ВСЕМ видео в приложении: и к тайтлам, и к трейлерам. Реализации живут в
 * feature:player:tv (`TvPlayer` → `PlayerUi.implementation()`), новый интерфейс — новый пункт здесь
 * и новая ветка там.
 */
enum class PlayerUi(val label: String) {
    /** Оверлей с полосой прокрутки, Play и сеткой плиток настроек под пульт. */
    Ui1("UI 1"),

    /** Полоса и ряд круглых кнопок внизу кадра, поповеры справа — раскладка стриминговых плееров. */
    Ui2("UI 2"),
    ;

    companion object {
        val Default = Ui1
    }
}

/**
 * Предпочитаемое качество потока. [Auto] — лучшее из доступных у конкретного файла; [Fixed] —
 * подпись качества kino.watch («1080p»), совпадающая с `VideoFile.quality`: сравнение идёт по
 * ней, поэтому она и хранится как есть.
 */
sealed interface QualityPreference {
    val label: String

    data object Auto : QualityPreference {
        override val label: String get() = "Авто"
    }

    data class Fixed(override val label: String) : QualityPreference

    companion object {
        /** «Авто» первым, дальше по убыванию. */
        val options: List<QualityPreference> =
            listOf(Auto) + listOf("2160p", "1080p", "720p", "480p", "360p").map(::Fixed)
    }
}

/**
 * Пользовательские предпочтения воспроизведения — выбираются в Профиле и
 * применяются на экране плеера (качество по умолчанию, пресет дорожек, интерфейс плеера).
 */
data class PlaybackSettings(
    val quality: QualityPreference = QualityPreference.Auto,
    /** Пресет по умолчанию для тайтлов без своего выбора; null — «Авто» (см. [TrackPreset]). */
    val preset: TrackPreset? = null,
    val playerUi: PlayerUi = PlayerUi.Default,
) {
    /** Подпись пресета для настроек: «Авто» или имя пресета. */
    val presetLabel: String get() = preset?.label ?: PresetAuto

    companion object {
        const val PresetAuto = "Авто"

        /** «Авто» (null) первым, дальше пресеты в порядке авто-подбора. */
        val presetOptions: List<TrackPreset?> = listOf(null) + TrackPreset.entries

        val playerUiOptions: List<PlayerUi> = PlayerUi.entries
    }
}

interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>

    suspend fun setQuality(quality: QualityPreference)

    /** null — «Авто». */
    suspend fun setPreset(preset: TrackPreset?)

    suspend fun setPlayerUi(ui: PlayerUi)

    /**
     * Память тайтла о дорожках (см. [TitleTracks]); разделяется всеми сериями сериала. null —
     * для тайтла ничего не выбирали, плеер берёт [PlaybackSettings.preset].
     */
    suspend fun titleTracksFor(itemId: Int): TitleTracks?

    suspend fun setTitleTracks(itemId: Int, tracks: TitleTracks)

    /** Забывает выбор дорожек у ВСЕХ тайтлов, не трогая глобальный пресет. */
    suspend fun clearTitleTracks()
}
