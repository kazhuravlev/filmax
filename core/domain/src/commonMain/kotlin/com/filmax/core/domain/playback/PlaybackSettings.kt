package com.filmax.core.domain.playback

import kotlinx.coroutines.flow.Flow

/**
 * Пресет пары «озвучка + субтитры». Порядок объявления — это и порядок авто-подбора: в режиме
 * «Авто» плеер берёт ПЕРВЫЙ пресет, у которого и озвучка, и субтитры нашлись среди дорожек
 * тайтла (субтитры «Выкл» подходят всегда). Эвристики матчинга по языку — в feature:player:common.
 *
 * [audio] и [subtitle] — те же display-значения, что понимают `resolveAudioGroupIndex` /
 * `resolveSubtitleOption`: «Оригинал» / «Русский» / «English» и «Выкл».
 */
enum class TrackPreset(
    val label: String,
    /** Короткая подпись для узкой плитки плеера. */
    val shortLabel: String,
    val audio: String,
    val subtitle: String,
) {
    OriginalEnglishSubs("Оригинал + англ. субтитры", "Ориг. + EN", PlaybackSettings.AudioOriginal, "English"),
    OriginalRussianSubs("Оригинал + рус. субтитры", "Ориг. + RU", PlaybackSettings.AudioOriginal, "Русский"),
    RussianDub("Русская озвучка, без субтитров", "Русский", "Русский", PlaybackSettings.SubtitleOff),
    ;

    companion object {
        fun byLabel(label: String): TrackPreset? = entries.firstOrNull { it.label == label }
    }
}

/**
 * Что помнит тайтл о своих дорожках — ровно одно из двух:
 *  - [Preset] — выбранный для тайтла пресет (null внутри — «Авто», подбор по порядку
 *    [TrackPreset.entries] заново на каждой серии);
 *  - [Custom] — пользователь выбрал озвучку и/или субтитры руками; пресет при этом снимается, и
 *    плеер показывает «Свой». Ключи непрозрачные, собирает и разбирает их плеер: озвучка —
 *    `язык|тип|студия`, субтитры — `track:язык|лейбл` либо «Выкл». Отсутствующая половина
 *    (старые сохранения до появления пресетов) подбирается пресетом.
 */
sealed interface TitleTracks {
    data class Preset(val preset: TrackPreset?) : TitleTracks

    data class Custom(val voiceKey: String?, val subtitleKey: String?) : TitleTracks
}

/**
 * Пользовательские предпочтения воспроизведения — выбираются в Профиле и
 * применяются на экране плеера (качество по умолчанию и пресет дорожек).
 */
data class PlaybackSettings(
    val quality: String = QualityAuto,
    /** Пресет по умолчанию для тайтлов без своего выбора; null — «Авто» (см. [TrackPreset]). */
    val preset: TrackPreset? = null,
) {
    /** Подпись пресета для настроек: «Авто» или имя пресета. */
    val presetLabel: String get() = preset?.label ?: PresetAuto

    companion object {
        const val QualityAuto = "Авто"
        const val PresetAuto = "Авто"
        const val AudioOriginal = "Оригинал"
        const val SubtitleOff = "Выкл"

        /** Предпочитаемое качество; «Авто» — лучшее из доступных у конкретного фильма. */
        val qualityOptions = listOf(QualityAuto, "2160p", "1080p", "720p", "480p", "360p")

        /** «Авто» первым, дальше пресеты в порядке авто-подбора. */
        val presetOptions: List<String> = listOf(PresetAuto) + TrackPreset.entries.map { it.label }
    }
}

interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>

    suspend fun setQuality(quality: String)

    /** null — «Авто». */
    suspend fun setPreset(preset: TrackPreset?)

    /**
     * Память тайтла о дорожках (см. [TitleTracks]); разделяется всеми сериями сериала. null —
     * для тайтла ничего не выбирали, плеер берёт [PlaybackSettings.preset].
     */
    suspend fun titleTracksFor(itemId: Int): TitleTracks?

    suspend fun setTitleTracks(itemId: Int, tracks: TitleTracks)

    /** Забывает выбор дорожек у ВСЕХ тайтлов, не трогая глобальный пресет. */
    suspend fun clearTitleTracks()
}
