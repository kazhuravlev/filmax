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
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.AudioTrack
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.common.ErrorReporting
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.RequestFailure
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.TitleTracks
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.isFinishedByPosition
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

    /** Глобальный пресет из настроек профиля; null — «Авто». */
    private var globalPreset: TrackPreset? = null

    /**
     * Память тайтла о дорожках (см. [TitleTracks]): пресет либо ручной выбор. Читается при
     * загрузке и применяется к КАЖДОМУ onTracksChanged: так следующая серия сериала стартует с
     * той же студией, а смена качества не сбрасывает выбор. Обновляется синхронно при ручном
     * выборе дорожки и при выборе пресета — следующий re-fire onTracksChanged с ней не спорит.
     */
    private var titleTracks: TitleTracks? = null

    /** Последний разобранный манифест — по нему выбор пресета из плеера применяется на месте. */
    private var lastTracks: Tracks? = null

    /**
     * Ключи играющих сейчас озвучки и субтитров. Ручной выбор одной половины снимает пресет, и
     * вторая половина фиксируется в [TitleTracks.Custom] как есть — чтобы следующая серия не
     * пересобрала её заново по пресету, которого пользователь уже не хотел.
     */
    private var currentVoiceKey: String? = null
    private var currentSubtitleKey: String = PlaybackSettings.SubtitleOff

    /** Выбранный трек/эпизод — нужен для сохранения прогресса (сериалы пишутся по сезону). */
    private var selectedTrack: MediaTrack? = null

    /** Аудиогруппы последнего onTracksChanged — по ним selectAudio делает точечный override. */
    private var audioGroups: List<Tracks.Group> = emptyList()

    /** Текстовые группы последнего onTracksChanged — по ним selectSubtitle выбирает HLS-дорожку. */
    private var textGroups: List<Tracks.Group> = emptyList()

    /** Позиция последней отправки прогресса — база для троттлинга в [saveProgress]. */
    private var lastSentSeconds: Int? = null

    /** Индекс текущего варианта доставки в [StreamQuality.urls]; сбрасывается сменой качества. */
    private var streamVariantIndex = 0

    /** Не долбим сеть повторно, если плашка автоперехода моргнёт ещё раз (см. [prefetchNextEpisode]). */
    private var nextEpisodePrefetched = false

    /** Серверная отметка «досмотрено» уже ушла для текущей дорожки — повторять её незачем. */
    private var watchedMarked = false

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
                lastTracks = tracks
                applyTracks(tracks)
            }

            // Пока идёт воспроизведение, фоновая закачка картинок придушивает себя — не
            // соревнуется за канал с активным видео (см. ImagePrefetchThrottle).
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ImagePrefetchThrottle.setPlaying(isPlaying)
            }
        })
        onFetchData()
    }

    override fun dispatch(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.SaveProgress -> saveProgress(event.positionMs, event.durationMs)
            PlayerEvent.MarkWatched -> markWatched()
            is PlayerEvent.SelectQuality -> selectQuality(event.label)
            is PlayerEvent.SelectAudio -> selectAudio(event.label)
            is PlayerEvent.SelectSubtitle -> selectSubtitle(event.label)
            is PlayerEvent.SelectPreset -> selectPreset(event.label)
            // Скорость сессионная и простая: меняем на плеере и в state прямо тут. Отдельный
            // метод перевёл бы класс за порог TooManyFunctions detekt — незачем.
            is PlayerEvent.SetSpeed -> {
                player.setPlaybackSpeed(event.speed)
                screenModelScope { _ -> updateState { it.copy(currentSpeed = event.speed) } }
            }
            PlayerEvent.PrefetchNextEpisode -> prefetchNextEpisode()
        }
    }

    /**
     * Спекулятивный прогрев следующей серии — см. doc [PlayerEvent.PrefetchNextEpisode]. Чисто
     * фоновая подсказка кэшу: результат никуда не пишем в state и ошибку не показываем — если
     * сбой, настоящий forceRefresh на экране следующей серии просто отработает как обычно.
     * Флаг — не сам механизм адопции (тот живёт в CatalogRepositoryImpl и одноразовый по своей
     * природе), а защита от лишнего похода в сеть, если плашка почему-то моргнёт дважды.
     */
    private fun prefetchNextEpisode() {
        if (nextEpisodePrefetched) return
        nextEpisodePrefetched = true
        screenModelScope { _ -> catalog.getItemDetails(route.itemId, forceRefresh = true) }
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
            globalPreset = settings.preset
            // Память тайтла сильнее глобального пресета и разделяется всеми его сериями.
            titleTracks = playbackSettings.titleTracksFor(route.itemId)
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
        currentSubtitleKey = option.preferenceKey()
        applySubtitleSelection(option)
        // Ручной выбор снимает пресет: тайтл переходит в «Свой» с текущей озвучкой как есть.
        rememberCustomTracks()
        screenModelScope { _ -> updateState { it.copy(currentSubtitle = label) } }
    }

    private fun selectAudio(label: String) {
        val option = state.audioTracks.firstOrNull { it.label == label } ?: return
        val group = audioGroups.getOrNull(option.groupIndex) ?: return
        // Точечный override на конкретную группу: предпочитаемый ЯЗЫК не различил бы несколько
        // русских озвучек разных студий.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        currentVoiceKey = voiceKey(option.groupIndex, group, selectedTrack?.audios.orEmpty())
        rememberCustomTracks()
        screenModelScope { _ -> updateState { it.copy(currentAudio = label) } }
    }

    /**
     * Фиксирует ручной выбор на весь тайтл ([TitleTracks.Custom]): следующие серии стартуют с
     * той же озвучки и субтитров. Запоминаем по тайтлу, а не глобально: другая история не должна
     * внезапно получить дорожки, выбранные для этого сериала.
     */
    private fun rememberCustomTracks() {
        val custom = TitleTracks.Custom(voiceKey = currentVoiceKey, subtitleKey = currentSubtitleKey)
        titleTracks = custom
        screenModelScope { _ ->
            playbackSettings.setTitleTracks(route.itemId, custom)
            updateState { it.copy(currentPreset = CUSTOM_PRESET_LABEL) }
        }
    }

    /**
     * Пресет из плитки плеера: запоминается на тайтл (даже «Авто» — он сильнее глобального
     * фиксированного пресета) и применяется к уже разобранному манифесту сразу.
     */
    private fun selectPreset(label: String) {
        val preset = TrackPreset.byLabel(label)
        if (preset == null && label != PlaybackSettings.PresetAuto) return
        val selection = TitleTracks.Preset(preset)
        titleTracks = selection
        screenModelScope { _ -> playbackSettings.setTitleTracks(route.itemId, selection) }
        lastTracks?.let(::applyTracks)
    }

    /**
     * Снимает с плеера аудиодорожки и субтитры и подбирает, что включить (см. [resolveTracks]).
     *
     * Аудио — ВСЕ группы, а не уникальные языки: у тайтла обычно несколько озвучек одного языка
     * (дубляж, многоголоски разных студий, оригинал), и оригинальный клиент kino.watch показывает
     * их полным списком. Подписи — из `audios[]` ответа API (язык · тип · студия); селектор
     * показываем только при выборе из нескольких. Субтитры — непосредственно с HLS-дорожек,
     * найденных ExoPlayer.
     */
    private fun applyTracks(tracks: Tracks) {
        audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val apiAudios = selectedTrack?.audios.orEmpty()
        val audioOptions = audioGroups.mapIndexed { index, group ->
            AudioOption(label = audioLabel(index, group, apiAudios), groupIndex = index)
        }
        val voiceKeys = audioGroups.indices.map { voiceKey(it, audioGroups[it], apiAudios) }
        val subtitleOptions = subtitleOptions()

        val resolution = resolveTracks(
            candidates = audioGroups.indices.map { index ->
                AudioMatchCandidate(
                    lang = audioLanguage(index, audioGroups[index], apiAudios),
                    label = audioOptions[index].label,
                )
            },
            voiceKeys = voiceKeys,
            options = subtitleOptions,
            selection = titleTracks,
            globalPreset = globalPreset,
        )

        val audioIndex = resolution.audioIndex
        if (audioIndex != null && !audioGroups[audioIndex].isSelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audioGroups[audioIndex].mediaTrackGroup, 0))
                .build()
        }
        applySubtitleSelection(resolution.subtitle)

        val selectedIndex = audioIndex ?: audioGroups.indexOfFirst { it.isSelected }
        currentVoiceKey = voiceKeys.getOrNull(selectedIndex)
        currentSubtitleKey = resolution.subtitle.preferenceKey()
        screenModelScope { _ ->
            updateState {
                it.copy(
                    audioTracks = if (audioOptions.size > 1) audioOptions else emptyList(),
                    currentAudio = audioOptions.getOrNull(selectedIndex)?.label
                        ?: audioOptions.firstOrNull()?.label.orEmpty(),
                    subtitles = subtitleOptions.takeIf { it.size > 1 }.orEmpty(),
                    currentSubtitle = resolution.subtitle.label,
                    currentPreset = resolution.presetLabel,
                )
            }
        }
    }

    /** Варианты субтитров из [textGroups]; «Выкл» всегда первый (на это полагается [resolveSubtitleOption]). */
    private fun subtitleOptions(): List<SubtitleOption> = buildList {
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

    /** Собирает MediaItem только с потоком: текстовые дорожки приходят из его HLS-манифеста. */
    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .build()
    }

    /**
     * Слабая подсказка плееру ДО разбора манифеста: `setPreferredAudioLanguage` смотрит только на
     * язык HLS-дорожки (у kino.watch это скупой код в NAME, часто бесполезный и не различающий
     * несколько озвучек одного языка), поэтому она лишь чуть смещает самый первый, ещё
     * недетерминированный выбор ExoPlayer. Дальше при onTracksChanged — единственном моменте,
     * когда доступны реальные метаданные API (`audios[]`) — на конкретную группу ставится точечный
     * override (см. [applyTracks] и [resolveAudioGroupIndex]): именно он источник истины,
     * этот метод оставлен как безобидный первый кадр без озвучки не того языка.
     */
    private fun applyAudioPreference() {
        val builder = player.trackSelectionParameters.buildUpon()
        // Подсказка есть только у фиксированного пресета; «Авто» и ручной выбор решаются по манифесту.
        val fixed = (titleTracks as? TitleTracks.Preset)?.preset ?: globalPreset.takeIf { titleTracks == null }
        fixed?.let { langCode(it.audio) }?.let { builder.setPreferredAudioLanguage(it) }
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
    private fun saveProgress(positionMs: Long, durationMs: Long = 0L) {
        val item = state.item
        val track = selectedTrack
        if (item == null || track == null) return
        val seconds = (positionMs / MILLIS_IN_SECOND).toInt()
        // Позиция уже на титрах — серия досмотрена. Сам `marktime` статус на сервере не ставит,
        // поэтому «Продолжить» без этой отметки предлагал бы досмотреть последние секунды и
        // титры вместо перехода к следующей серии. Проверка стоит ДО троттлинга: выход с экрана
        // сразу после автосохранения на паузе тоже обязан оставить отметку.
        // Длительность у Media3 может быть TIME_UNSET (отрицательная), пока манифест не разобран.
        val durationSeconds = if (durationMs > 0) (durationMs / MILLIS_IN_SECOND).toInt() else 0
        if (isFinishedByPosition(seconds, durationSeconds)) markWatched()
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

    /**
     * Серверная отметка «видео досмотрено» — то, что эталонный клиент kino.watch шлёт по
     * завершении воспроизведения (`watching/toggle?…&status=1`), см. [PlayerEvent.MarkWatched].
     * Один раз на дорожку: отметка идемпотентна, но повторные походы в сеть не нужны. Кэш
     * деталей тайтла сбрасываем сразу — экран деталей строит быструю оценку continuation по
     * `watching.status` дорожек из `items/{id}` и иначе показал бы старую серию.
     */
    private fun markWatched() {
        val item = state.item
        val track = selectedTrack
        if (item == null || track == null || watchedMarked) return
        watchedMarked = true
        screenModelScope {
            watching.markWatched(item.id, track.seasonNumber, track.number)
            catalog.invalidateItemCache(item.id)
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
        ImagePrefetchThrottle.setPlaying(false)
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
         * Язык дорожки для подписи/ключа/эвристики авто-выбора: из метаданных API (сопоставление
         * по `audios[].index`, 1-based = порядок дорожек в HLS-манифесте), а если API их не
         * прислал — из формата самой Media3-группы (сырой код языка из HLS-манифеста).
         */
        fun audioLanguage(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String? {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            return meta?.lang ?: group.getTrackFormat(0).language
        }

        /**
         * Подпись дорожки: «2. Русский · Многоголосый · BaibaKo» — как в оригинальном клиенте
         * kino.watch. Метаданные берём из `audios[]` ответа API: сам манифест kino.watch кладёт в
         * NAME только код языка, и по нему озвучки неотличимы. Номер в начале гарантирует
         * уникальность подписи, даже если у двух озвучек совпали студия и тип.
         */
        fun audioLabel(groupIndex: Int, group: Tracks.Group, apiAudios: List<AudioTrack>): String {
            val meta = apiAudios.firstOrNull { it.index == groupIndex + 1 }
            val parts = buildList {
                add(audioDisplay(audioLanguage(groupIndex, group, apiAudios)))
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
            val language = audioLanguage(groupIndex, group, apiAudios)
            return listOf(language.orEmpty(), meta?.voiceType.orEmpty(), meta?.voiceAuthor.orEmpty())
                .joinToString("|")
        }
    }
}
