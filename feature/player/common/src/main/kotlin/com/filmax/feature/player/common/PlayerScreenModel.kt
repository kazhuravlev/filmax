package com.filmax.feature.player.common

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.AudioTrack
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.common.ErrorReporting
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.RequestFailure
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.core.presentation.DataDomain
import com.filmax.core.presentation.DataInvalidation
import com.filmax.feature.player.common.navigation.PlayerRoute
import kotlinx.coroutines.flow.first
import kotlin.math.abs

// Контракт плеера целен: загрузка, выбор дорожек/качества, фолбэк CDN-вариантов и прогресс —
// одна связная машина воспроизведения, дробление раздало бы половину полей в каждый кусок.
@Suppress("TooManyFunctions")
class PlayerScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    private val userRepository: UserRepository,
    context: Context,
) : BaseScreenModel<PlayerState, PlayerSideEffect, PlayerEvent>(PlayerState()) {

    private val route = savedStateHandle.toRoute<PlayerRoute>()

    // Шаг перемотки задан явно: дефолты Media3 (5 с назад / 15 с вперёд) не совпадают
    // с иконками Replay10/Forward10 на кнопках плеера.
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()

    private var audioPreference: String = PlaybackSettings.AudioOriginal
    private var subtitlePreference: String = PlaybackSettings.SubtitleOff

    /** Выбранный трек/эпизод — нужен для сохранения прогресса (сериалы пишутся по сезону). */
    private var selectedTrack: MediaTrack? = null

    /** Аудиогруппы последнего onTracksChanged — по ним selectAudio делает точечный override. */
    private var audioGroups: List<Tracks.Group> = emptyList()

    /** Текстовые группы последнего onTracksChanged — по ним selectSubtitle выбирает HLS-дорожку. */
    private var textGroups: List<Tracks.Group> = emptyList()

    /**
     * Озвучка, выбранная для этого тайтла (язык|тип|студия). Читается при загрузке и
     * применяется к КАЖДОМУ onTracksChanged: так следующая серия сериала стартует с той же
     * студией, а смена качества не сбрасывает выбор. Обновляется при ручном выборе дорожки.
     */
    private var savedVoiceKey: String? = null

    /** Позиция последней отправки прогресса — база для троттлинга в [saveProgress]. */
    private var lastSentSeconds: Int? = null

    /** Индекс текущего варианта доставки в [StreamQuality.urls]; сбрасывается сменой качества. */
    private var streamVariantIndex = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Ошибки плеера не проходят через safeRequest — репортим сами, иначе телеметрия
                // не увидит именно тот класс сбоев, на который жалуются («серия не запустилась»).
                // Обёртка RequestFailure даёт читаемый заголовок issue (см. reportRequestFailure).
                ErrorReporting.reporter.report(RequestFailure.of(AppError.Playback, error))
                // Ошибка источника часто значит «CDN этого варианта недоступен» (DPI/SNI-блокировка
                // CDN): прежде чем показывать модалку, пробуем следующий вариант доставки.
                if (!playNextStreamVariant()) {
                    screenModelScope { showError(AppError.Playback) }
                }
            }

            // Аудио и субтитры известны только после разбора манифеста — читаем их здесь.
            override fun onTracksChanged(tracks: Tracks) {
                updateAudioTracks(tracks)
                updateSubtitleTracks(tracks)
            }
        })
        onFetchData()
    }

    override fun dispatch(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.SaveProgress -> saveProgress(event.positionMs)
            is PlayerEvent.SelectQuality -> selectQuality(event.label)
            is PlayerEvent.SelectAudio -> selectAudio(event.label)
            is PlayerEvent.SelectSubtitle -> selectSubtitle(event.label)
            // Скорость сессионная и простая: меняем на плеере и в state прямо тут. Отдельный
            // метод перевёл бы класс за порог TooManyFunctions detekt — незачем.
            is PlayerEvent.SetSpeed -> {
                player.setPlaybackSpeed(event.speed)
                screenModelScope { _ -> updateState { it.copy(currentSpeed = event.speed) } }
            }
        }
    }

    /**
     * Подписка проверяется параллельно с загрузкой и не блокирует старт: без неё поток всё равно
     * не пойдёт, а плашка объяснит почему. Ошибка профиля (офлайн и т.п.) плашку не показывает.
     */
    private fun checkSubscription() {
        screenModelScope { _ ->
            val profile = userRepository.getProfile()
            if (profile is RequestResult.Success && profile.data.subscription?.active != true) {
                updateState { it.copy(subscriptionRequired = true) }
            }
        }
    }

    override fun onFetchData() {
        checkSubscription()
        screenModelScope { _ ->
            val settings = playbackSettings.settings.first()
            audioPreference = settings.audioLanguage
            // Привязка к тайтлу сильнее глобального default и разделяется всеми его сериями.
            subtitlePreference = playbackSettings.subtitlePreferenceFor(route.itemId)
                ?: settings.subtitleLanguage
            savedVoiceKey = playbackSettings.voiceKeyFor(route.itemId)
            // forceRefresh: списочные экраны (главная/поиск/похожее) кэшируют этот тайтл без
            // ссылок на видео — кэш-чтение здесь легко отдало бы треклист без единого трека.
            when (val result = catalog.getItemDetails(route.itemId, forceRefresh = true)) {
                is RequestResult.Success -> {
                    val item = result.data
                    // Сериал: играем выбранный эпизод. `videoId` — это НОМЕР видео (`number` из
                    // API), а не id трека: тем же числом kino.watch принимает и отдаёт прогресс
                    // в watching/marktime. Номер уникален только внутри сезона, поэтому сезон
                    // обязателен в матчинге — без него S3E2 находил бы S1E2.
                    // Фильм/нет совпадения — первый трек.
                    val trackIndex = item.tracklist.indexOfFirst { it.matchesRoute(route) }.coerceAtLeast(0)
                    val track = item.tracklist.getOrNull(trackIndex)
                    selectedTrack = track

                    val qualities = streamQualities(track)
                    // Предпочитаемое качество из настроек; «Авто»/нет совпадения — лучшее доступное.
                    val initial = qualities.firstOrNull { it.label == settings.quality }
                        ?: qualities.firstOrNull()

                    updateState {
                        it.copy(
                            loading = false,
                            item = item,
                            track = track,
                            previousTrack = item.tracklist.getOrNull(trackIndex - 1),
                            nextTrack = item.tracklist.getOrNull(trackIndex + 1),
                            streamUrl = initial?.url,
                            qualities = qualities,
                            currentQuality = initial?.label,
                        )
                    }

                    if (initial != null) {
                        streamVariantIndex = 0
                        reportPlaybackStart(initial)
                        player.setMediaItem(buildMediaItem(initial.url))
                        player.prepare()
                        applyAudioPreference()
                        // Только явный маршрут «Продолжить» восстанавливает позицию. Статус трека
                        // здесь не участвует: history может хранить позицию при watchStatus == 1.
                        route.resumePositionSeconds
                            .takeIf { it > 0 }
                            ?.let { player.seekTo(it * MILLIS_IN_SECOND) }
                        player.playWhenReady = true
                    }
                }

                is RequestResult.Error -> {
                    updateState { it.copy(loading = false, error = result.message) }
                    showError(result)
                }
            }
        }
    }

    /**
     * Доступные качества — из файлов трека; все варианты доставки в порядке предпочтения,
     * чтобы плееру было куда фолбэчить при недоступном CDN.
     */
    private fun streamQualities(track: MediaTrack?): List<StreamQuality> =
        track?.files.orEmpty().mapNotNull { file ->
            listOfNotNull(file.hls4, file.hls, file.http)
                .takeIf { it.isNotEmpty() }
                ?.let { StreamQuality(file.quality, it) }
        }

    private fun selectQuality(label: String) {
        val quality = state.qualities.firstOrNull { it.label == label } ?: return
        if (label == state.currentQuality) return
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        streamVariantIndex = 0
        ErrorReporting.reporter.log("player: quality $label host=${urlHost(quality.url)}")
        // trackSelectionParameters (аудио/субтитры) живут на плеере и переживают смену MediaItem.
        player.setMediaItem(buildMediaItem(quality.url))
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = wasPlaying
        screenModelScope { _ -> updateState { it.copy(currentQuality = label, streamUrl = quality.url) } }
    }

    /**
     * Переключает поток на следующий вариант доставки текущего качества (hls4 → hls → http).
     * Варианты ведут на разные CDN-хосты, и недоступность одного из них (например, из-за SNI-блокировки)
     * не значит, что тайтл не посмотреть. false — варианты кончились, ошибку показывает вызывающий.
     */
    /** Хлебная крошка старта: при ошибке в отчёте видно тайтл, качество и CDN-хост. */
    private fun reportPlaybackStart(initial: StreamQuality) {
        ErrorReporting.reporter.log(
            "player: start item=${route.itemId} quality=${initial.label} host=${urlHost(initial.url)}",
        )
    }

    private fun playNextStreamVariant(): Boolean {
        val quality = state.qualities.firstOrNull { it.label == state.currentQuality }
        val nextUrl = quality?.urls?.getOrNull(streamVariantIndex + 1) ?: return false
        streamVariantIndex++
        ErrorReporting.reporter.log("player: variant fallback #$streamVariantIndex host=${urlHost(nextUrl)}")
        val position = player.currentPosition
        // Состояние воспроизведения переносим как есть: сбой CDN — не повод запускать видео
        // у того, кто стоял на паузе.
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildMediaItem(nextUrl))
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = wasPlaying
        screenModelScope { _ -> updateState { it.copy(streamUrl = nextUrl) } }
        return true
    }

    private fun selectSubtitle(label: String) {
        val option = state.subtitles.firstOrNull { it.label == label } ?: return
        subtitlePreference = option.preferenceKey()
        applySubtitleSelection(option)
        screenModelScope { _ ->
            // Запоминаем по тайтлу, а не как глобальный default: другая история не должна
            // внезапно получить субтитры, выбранные для этого сериала.
            playbackSettings.setSubtitlePreference(route.itemId, subtitlePreference)
            updateState { it.copy(currentSubtitle = label) }
        }
    }

    private fun selectAudio(label: String) {
        val option = state.audioTracks.firstOrNull { it.label == label } ?: return
        val group = audioGroups.getOrNull(option.groupIndex) ?: return
        // Точечный override на конкретную группу: предпочитаемый ЯЗЫК не различил бы несколько
        // русских озвучек разных студий.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        // Запоминаем озвучку на весь тайтл: следующие серии стартуют с этой же студии.
        val key = voiceKey(option.groupIndex, group, selectedTrack?.audios.orEmpty())
        savedVoiceKey = key
        screenModelScope { _ ->
            playbackSettings.setVoiceKey(route.itemId, key)
            updateState { it.copy(currentAudio = label) }
        }
    }

    /**
     * Снимает список аудиодорожек с плеера — ВСЕ группы, а не уникальные языки: у тайтла
     * обычно несколько озвучек одного языка (дубляж, многоголоски разных студий, оригинал),
     * и оригинальный клиент kino.watch показывает их полным списком. Подписи — из `audios[]`
     * ответа API (язык · тип · студия); селектор показываем только при выборе из нескольких.
     */
    private fun updateAudioTracks(tracks: Tracks) {
        audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val apiAudios = selectedTrack?.audios.orEmpty()
        val options = audioGroups.mapIndexed { index, group ->
            AudioOption(label = audioLabel(index, group, apiAudios), groupIndex = index)
        }

        // Запомненная озвучка тайтла: находим группу с тем же ключом и ставим override —
        // следующая серия стартует с той же студии, а смена качества не сбрасывает выбор.
        // Не нашлась (у серии другой набор озвучек) — остаёмся на выборе плеера.
        val savedIndex = savedVoiceKey?.let { key ->
            audioGroups.indices.firstOrNull { voiceKey(it, audioGroups[it], apiAudios) == key }
        }
        if (savedIndex != null && !audioGroups[savedIndex].isSelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audioGroups[savedIndex].mediaTrackGroup, 0))
                .build()
        }

        val selectedIndex = savedIndex ?: audioGroups.indexOfFirst { it.isSelected }
        screenModelScope { _ ->
            updateState {
                it.copy(
                    audioTracks = if (options.size > 1) options else emptyList(),
                    currentAudio = options.getOrNull(selectedIndex)?.label
                        ?: options.firstOrNull()?.label.orEmpty(),
                )
            }
        }
    }

    /** Снимает список субтитров непосредственно с HLS-дорожек, найденных ExoPlayer. */
    private fun updateSubtitleTracks(tracks: Tracks) {
        textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val options = buildList {
            add(SubtitleOption(PlaybackSettings.SubtitleOff, null))
            textGroups.forEachIndexed { index, group ->
                repeat(group.length) { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label?.takeIf { it.isNotBlank() }
                        ?: langDisplay(format.language)
                    add(
                        SubtitleOption(
                            label = label,
                            lang = format.language,
                            groupIndex = index,
                            trackIndex = trackIndex,
                            isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        ),
                    )
                }
            }
        }
        val selected = resolveSubtitleOption(options, subtitlePreference)
        applySubtitleSelection(selected)
        screenModelScope { _ ->
            updateState {
                it.copy(
                    subtitles = options.takeIf { it.size > 1 }.orEmpty(),
                    currentSubtitle = selected.label,
                )
            }
        }
    }

    /** Собирает MediaItem только с потоком: текстовые дорожки приходят из его HLS-манифеста. */
    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .build()
    }

    /** Применяет предпочтение аудио; текстовая дорожка выбирается после разбора HLS. */
    private fun applyAudioPreference() {
        val builder = player.trackSelectionParameters.buildUpon()
        langCode(audioPreference)?.let { builder.setPreferredAudioLanguage(it) }
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = builder.build()
    }

    /** Включает/выключает конкретную HLS-группу субтитров. */
    private fun applySubtitleSelection(option: SubtitleOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option.groupIndex < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            textGroups.getOrNull(option.groupIndex)?.let { group ->
                builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    /**
     * Пишет прогресс на сервер. `video` — это НОМЕР видео (`MediaTrack.number`), а не id трека:
     * kino.watch в `watching/marktime` ждёт именно номер, и тем же числом отдаёт прогресс обратно
     * в `items/{id}`. С id прогресс уходил «в никуда» — история оставалась пустой.
     *
     * Троттлинг по позиции: пока не отъехали от последней отправки дальше [PROGRESS_STEP_SECONDS],
     * не дёргаем сервер — тик плеера идёт раз в секунду, а это на порядок чаще, чем нужно.
     *
     * Первую отправку дополнительно держим до [MIN_SECONDS_BEFORE_FIRST_SAVE]: `lastSentSeconds`
     * стартует с `null`, и без этого порога троттлинг никак не срабатывает на самом первом тике —
     * случайный OK на постере/серии с мгновенным выходом из плеера всё равно успевал бы записать
     * позицию на сервер и title навсегда оседал в «Продолжить», хотя его никто не смотрел.
     */
    private fun saveProgress(positionMs: Long) {
        val item = state.item
        val track = selectedTrack
        if (item == null || track == null) return
        val seconds = (positionMs / MILLIS_IN_SECOND).toInt()
        val sent = lastSentSeconds
        val tooEarly = if (sent == null) {
            seconds < MIN_SECONDS_BEFORE_FIRST_SAVE
        } else {
            abs(seconds - sent) < PROGRESS_STEP_SECONDS
        }
        if (tooEarly) return
        lastSentSeconds = seconds
        screenModelScope {
            // Сериалы прогресс пишут по сезону+эпизоду, фильмы — по одному видео.
            if (track.seasonNumber > 0) {
                watching.saveProgressSerial(item.id, track.seasonNumber, track.number, seconds)
            } else {
                watching.saveProgress(item.id, track.number, seconds)
            }
            // Позиция ушла на сервер — «Я смотрю» в библиотеке может отставать до возврата туда.
            DataInvalidation.markDirty(DataDomain.WATCHING)
        }
    }

    // Финальный SaveProgress на выход с экрана уходит из Compose (TvPlayerScreen.PlayerEffects,
    // DisposableEffect.onDispose), а НЕ отсюда: androidx.lifecycle.viewmodel.internal.ViewModelImpl
    // закрывает viewModelScope (JOB_KEY-closeable) ДО вызова onCleared() у самого ViewModel —
    // к моменту, когда этот метод выполняется, job screenModelScope уже отменён, и
    // screenModelScope.launch{} внутри saveProgress() молча не выполнил бы своё тело (запуск
    // корутины на отменённом родителе). Вызов saveProgress() здесь был бы «мёртвым кодом»,
    // который выглядит рабочим, но никогда не долетает до сети — поэтому его нет.
    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        const val MILLIS_IN_SECOND = 1000L

        /** Хост из URL — для хлебных крошек телеметрии (сам URL с подписью в логи не пишем). */
        fun urlHost(url: String): String = url.substringAfter("://").substringBefore("/")

        /** Трек маршрута: номер видео + сезон (у фильма сезона нет — совпадения по номеру достаточно). */
        fun MediaTrack.matchesRoute(route: PlayerRoute): Boolean =
            number == route.videoId && (route.season <= 0 || seasonNumber == route.season)

        /** Порог отправки прогресса: реже, чем тик плеера (1 с), но чаще, чем теряется место. */
        const val PROGRESS_STEP_SECONDS = 5

        /** Сколько реально проигранных секунд нужно набрать до первой записи прогресса на сервер. */
        const val MIN_SECONDS_BEFORE_FIRST_SAVE = 15

        fun langCode(display: String): String? = when (display.lowercase()) {
            "русский" -> "rus"
            "english" -> "eng"
            else -> null // «Оригинал» / неизвестно — пусть плеер выбирает сам
        }

        fun langDisplay(code: String?): String = when (code?.lowercase()) {
            "rus", "ru" -> "Русский"
            "eng", "en" -> "English"
            "ukr", "uk" -> "Українська"
            null, "" -> "Субтитры"
            else -> code
        }

        fun audioDisplay(code: String?): String = when (code?.lowercase()) {
            "rus", "ru" -> "Русский"
            "eng", "en" -> "English"
            "ukr", "uk" -> "Українська"
            null, "" -> "Оригинал"
            else -> code
        }

        /**
         * Подпись дорожки: «2. Русский · Многоголосый · BaibaKo» — как в оригинальном клиенте
         * kino.watch. Метаданные берём из `audios[]` ответа API, сопоставляя с группой Media3 по
         * порядку (`audios[].index` 1-based = порядок дорожек в HLS-манифесте): сам манифест
         * kino.watch кладёт в NAME только код языка, и по нему озвучки неотличимы. Номер в начале
         * гарантирует уникальность подписи, даже если у двух озвучек совпали студия и тип.
         */
        fun audioLabel(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            val language = meta?.lang ?: group.getTrackFormat(0).language
            val parts = buildList {
                add(audioDisplay(language))
                meta?.voiceType?.let { add(it) }
                meta?.voiceAuthor?.let { add(it) }
            }.distinct()
            return "${groupIndex + 1}. ${parts.joinToString(" · ")}"
        }

        /**
         * Ключ озвучки для памяти на тайтл: `язык|тип|студия` из метаданных API. Позиционный
         * индекс не годится — у разных серий порядок дорожек может отличаться, а связка
         * язык+тип+студия идентифицирует именно озвучку.
         */
        fun voiceKey(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            val language = meta?.lang ?: group.getTrackFormat(0).language
            return listOf(language.orEmpty(), meta?.voiceType.orEmpty(), meta?.voiceAuthor.orEmpty())
                .joinToString("|")
        }
    }
}
