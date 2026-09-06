package com.filmax.core.domain.playback

import kotlinx.coroutines.flow.Flow

/**
 * Пользовательские предпочтения воспроизведения — выбираются в Профиле и
 * применяются на экране плеера (качество по умолчанию, язык аудио и субтитров).
 */
data class PlaybackSettings(
    val quality: String = QualityAuto,
    val audioLanguage: String = AudioOriginal,
    val subtitleLanguage: String = SubtitleOff,
) {
    companion object {
        const val QualityAuto = "Авто"
        const val AudioOriginal = "Оригинал"

        /**
         * «Выкл» для аудио — это не «нет звука», а «не авто-выбирать»: играет первая/дефолтная
         * дорожка потока, как её отдаёт плеер без вмешательства (см. [resolveAudioGroupIndex] в
         * feature:player:common — при этом значении override на аудиогруппу не ставится).
         */
        const val AudioOff = "Выкл"
        const val SubtitleOff = "Выкл"

        /** Предпочитаемое качество; «Авто» — лучшее из доступных у конкретного фильма. */
        val qualityOptions = listOf(QualityAuto, "2160p", "1080p", "720p", "480p", "360p")

        /**
         * «Выкл» первым — самый безопасный default для нового выбора (ничего не трогать), дальше
         * «Оригинал» (реальный default приложения, чтобы не менять поведение существующих
         * пользователей), затем конкретные языки. Эвристика авто-выбора по каждому значению —
         * в feature:player:common (`resolveAudioGroupIndex`).
         */
        val audioOptions = listOf(AudioOff, AudioOriginal, "Русский", "English")
        val subtitleOptions = listOf(SubtitleOff, "Русский", "English")
    }
}

interface PlaybackSettingsRepository {
    val settings: Flow<PlaybackSettings>

    suspend fun setQuality(quality: String)

    suspend fun setAudioLanguage(language: String)

    suspend fun setSubtitleLanguage(language: String)

    /**
     * Выбранные субтитры конкретного тайтла. `null` означает, что плеер должен взять глобальный
     * default из [PlaybackSettings.subtitleLanguage]; запись не устаревает сама.
     */
    suspend fun subtitlePreferenceFor(itemId: Int): String?

    /**
     * Сохраняет непрозрачный идентификатор выбранной дорожки. Его формирует и разбирает плеер;
     * старые сохранённые значения языка также поддерживаются для миграции без очистки настроек.
     */
    suspend fun setSubtitlePreference(itemId: Int, selectionKey: String)

    /** Удаляет все сохранённые привязки субтитров к тайтлам, не меняя глобальный default. */
    suspend fun clearSubtitlePreferences()

    /**
     * Озвучка, выбранная для конкретного тайтла: следующие серии сериала стартуют с неё же.
     * [key] — непрозрачный идентификатор дорожки (язык|тип|студия), собирает и разбирает его
     * плеер. null — для тайтла озвучку ещё не выбирали.
     */
    suspend fun voiceKeyFor(itemId: Int): String?

    suspend fun setVoiceKey(itemId: Int, key: String)
}
