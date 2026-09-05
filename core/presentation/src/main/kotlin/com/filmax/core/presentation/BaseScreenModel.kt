package com.filmax.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.toAppError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Базовый MVI-ScreenModel проекта. Единственное место, где presentation-слой касается
 * `androidx.lifecycle.ViewModel` — фичи об этом не знают и наследуют только [BaseScreenModel].
 * Это снимает техдолг #9 (Android-only ViewModel в фичах) и держит фичи KMP-ready: при
 * переходе на commonMain/Decompose меняется только этот класс, а не каждая фича.
 *
 * Триада MVI:
 *  - [STATE] — единый неизменяемый снимок экрана (state down);
 *  - [EVENT] — намерения пользователя, приходят через [dispatch] (events up);
 *  - [SIDE_EFFECT] — одноразовые эффекты (навигация, snackbar, …), доставляются через [postSideEffect].
 *
 * Удержание экземпляра и автоотмена [screenModelScope] обеспечиваются механизмом ViewModel
 * (переживает поворот экрана, привязан к back stack-записи навигации).
 */
// Базовый MVI-класс: перечисленные функции — это осознанный контракт фреймворка (dispatch,
// updateState/postSideEffect, семейство showError, retry/dismissError, lifecycle). Дробить их по
// связности незачем — набор методов и есть API базового ScreenModel.
@Suppress("TooManyFunctions")
abstract class BaseScreenModel<STATE : Any, SIDE_EFFECT : Any, EVENT : Any>(
    initialState: STATE,
) : ViewModel() {

    private val sideEffectsQueue: MutableList<SIDE_EFFECT> = mutableListOf()
    private var sideEffectsSubscriber: ((SIDE_EFFECT) -> Unit)? = null

    private val mainThreadDispatcher = Dispatchers.Main.immediate

    private val _state: MutableStateFlow<STATE> = MutableStateFlow(initialState)

    /** Текущая ошибка для модального окна (null — модалки нет). */
    private val _error: MutableStateFlow<AppError?> = MutableStateFlow(null)

    /** Показан ли ненавязчивый баннер «нет сети» (issue #42): контент из кэша при офлайне. */
    private val _offlineBanner: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Нижнее TV-уведомление о сбое сервера и состоянии автоматического повтора. */
    private val _serverRetryNotice: MutableStateFlow<ServerRetryNotice?> = MutableStateFlow(null)
    private var serverRetryJob: Job? = null
    private var retryRecoveryJob: Job? = null
    private var automaticRetryCount = 0

    /** Текущий снимок состояния. Доступен подклассам для чтения внутри корутин. */
    protected val state: STATE
        get() = _state.value

    private val updateStateLock = Mutex()
    private val sideEffectLock = Mutex()

    /** Единая точка входа для пользовательских событий экрана. */
    abstract fun dispatch(event: EVENT)

    /** Первичная загрузка данных экрана. Вызывается подклассом из его `init` после инициализации зависимостей. */
    protected abstract fun onFetchData()

    /** Скоуп жизненного цикла ScreenModel (отменяется в [onCleared]). */
    protected val screenModelScope: CoroutineScope = viewModelScope

    /**
     * Запускает корутину в [screenModelScope] на [dispatcher], предоставляя актуальный снимок [STATE].
     * Исключения внутри блока изолируются, чтобы один сбой не ронял ScreenModel.
     */
    protected fun screenModelScope(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        call: suspend CoroutineScope.(STATE) -> Unit,
    ): Job = screenModelScope.launch(dispatcher) {
        runCatching { call(state) }
    }

    /** Отправляет одноразовый side-effect. Если подписчика ещё нет — эффект буферизуется до подписки. */
    protected suspend fun postSideEffect(effect: SIDE_EFFECT) {
        sideEffectLock.withLock {
            withContext(mainThreadDispatcher) {
                sideEffectsSubscriber?.invoke(effect) ?: sideEffectsQueue.add(effect)
            }
        }
    }

    /** Атомарно обновляет состояние на main-потоке. */
    protected suspend fun updateState(call: (STATE) -> STATE) {
        updateStateLock.withLock {
            withContext(mainThreadDispatcher) {
                _state.emit(call(state))
            }
        }
    }

    /** Подписка экрана на состояние как на Compose [State]. */
    @Composable
    fun collectAsState(): State<STATE> {
        return _state.collectAsState()
    }

    /**
     * Резолвит ошибку запроса в [AppError] и показывает модалку.
     * Вызывается из ScreenModel в ветке [RequestResult.Error]:
     * `showError(result)` или `showError(message, cause)`.
     */
    protected suspend fun showError(error: AppError) {
        withContext(mainThreadDispatcher) { _error.emit(error) }
    }

    protected suspend fun showError(message: String?, cause: Throwable? = null) {
        showError(AppError.resolve(message, cause))
    }

    protected suspend fun showError(error: RequestResult.Error) {
        showError(error.toAppError())
    }

    /** Показать баннер «нет сети» (контент отдан из кэша). */
    protected suspend fun showOfflineBanner() {
        withContext(mainThreadDispatcher) { _offlineBanner.emit(true) }
    }

    /** Скрыть баннер «нет сети» (например, после успешного обновления). */
    fun dismissOfflineBanner() {
        _offlineBanner.value = false
    }

    /** Подписка экрана на баннер «нет сети». */
    @Composable
    fun collectOfflineBannerAsState(): State<Boolean> {
        return _offlineBanner.collectAsState()
    }

    /** Подписка TV-экрана на нижнее уведомление об автоматическом повторе запроса. */
    @Composable
    fun collectServerRetryNoticeAsState(): State<ServerRetryNotice?> {
        return _serverRetryNotice.collectAsState()
    }

    /**
     * Показывает уведомление и один раз вызывает [retryAction] через три секунды. Повторные
     * ошибки образуют ограниченную серию: после трёх попыток запросы автоматически больше не
     * отправляются, а пользователь получает честное сообщение вместо ложного пустого состояния.
     */
    protected fun scheduleServerRetry(retryAction: () -> Unit) = scheduleRetry(silent = false, retryAction)

    /**
     * Как [scheduleServerRetry], но без баннера «Сервер не вернул данные», пока автоматические
     * попытки не исчерпаны. Для неявной (не по действию пользователя) первой загрузки экрана,
     * когда на экране уже могут быть пригодные данные с прошлого раза ([preserveEmpty]-подобный
     * приём): баннер «повторим через 3 секунды» на фоне уже показанного контента только пугает,
     * не неся никакой полезной информации — а через 3 секунды всё равно тихо чинится само.
     * Стойкий сбой (см. [MAX_AUTOMATIC_SERVER_RETRIES]) всё равно показывается — это уже не шум.
     */
    protected fun scheduleSilentServerRetry(retryAction: () -> Unit) = scheduleRetry(silent = true, retryAction)

    private fun scheduleRetry(silent: Boolean, retryAction: () -> Unit) {
        retryRecoveryJob?.cancel()
        retryRecoveryJob = null
        if (serverRetryJob?.isActive == true) return
        if (automaticRetryCount >= MAX_AUTOMATIC_SERVER_RETRIES) {
            _serverRetryNotice.value = ServerRetryNotice.Exhausted
            return
        }

        automaticRetryCount += 1
        if (!silent) _serverRetryNotice.value = ServerRetryNotice.Scheduled
        serverRetryJob = screenModelScope.launch(mainThreadDispatcher) {
            delay(SERVER_RETRY_DELAY_MILLIS)
            _serverRetryNotice.value = null
            serverRetryJob = null
            retryAction()
            // Если повтор снова упадёт, scheduleRetry отменит этот таймер. Если новых
            // ошибок нет, считаем серию завершённой и следующий сбой снова получит три попытки.
            retryRecoveryJob = screenModelScope.launch(mainThreadDispatcher) {
                delay(SERVER_RETRY_RECOVERY_MILLIS)
                automaticRetryCount = 0
                _serverRetryNotice.value = null
                retryRecoveryJob = null
            }
        }
    }

    /** Новое явное действие пользователя начинает отдельную серию автоматических повторов. */
    protected fun resetServerRetryCycle() {
        serverRetryJob?.cancel()
        retryRecoveryJob?.cancel()
        serverRetryJob = null
        retryRecoveryJob = null
        automaticRetryCount = 0
        _serverRetryNotice.value = null
    }

    /** Закрывает модалку ошибки. Вызывается из UI. */
    fun dismissError() {
        _error.value = null
    }

    /** Повторная загрузка данных — для кнопки «Повторить» в модалке ошибки или баннере «нет сети». */
    fun retry() {
        _error.value = null
        _offlineBanner.value = false
        resetServerRetryCycle()
        onFetchData()
    }

    /** Подписка экрана на текущую ошибку для показа [com.filmax.core.ui]-модалки. */
    @Composable
    fun collectErrorAsState(): State<AppError?> {
        return _error.collectAsState()
    }

    /** Подписка экрана на side-effects. Буферизованные до подписки эффекты доставляются сразу. */
    @Composable
    fun collectSideEffect(key: Any? = Unit, onSideEffect: (SIDE_EFFECT) -> Unit) {
        val job = remember { mutableStateOf<Job?>(null) }
        DisposableEffect(key1 = key) {
            job.value = screenModelScope.launch(mainThreadDispatcher) {
                job.value?.let { runCatching { it.cancelAndJoin() } }
                sideEffectsSubscriber = onSideEffect
                sideEffectsQueue.forEach { postSideEffect(it) }
                sideEffectsQueue.clear()
            }
            onDispose {
                screenModelScope.launch(mainThreadDispatcher) {
                    sideEffectsSubscriber = null
                    job.value?.let { runCatching { it.cancelAndJoin() } }
                }
            }
        }
    }

    override fun onCleared() {
        sideEffectsSubscriber = null
        serverRetryJob?.cancel()
        retryRecoveryJob?.cancel()
        super.onCleared()
    }
}

/** Состояние нижнего уведомления: повтор запланирован либо лимит повторов исчерпан. */
sealed interface ServerRetryNotice {
    data object Scheduled : ServerRetryNotice
    data object Exhausted : ServerRetryNotice
}

private const val SERVER_RETRY_DELAY_MILLIS = 3_000L
private const val SERVER_RETRY_RECOVERY_MILLIS = 10_000L
private const val MAX_AUTOMATIC_SERVER_RETRIES = 3
