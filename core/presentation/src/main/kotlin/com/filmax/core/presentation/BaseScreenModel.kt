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

@Suppress("TooManyFunctions")
abstract class BaseScreenModel<STATE : Any, SIDE_EFFECT : Any, EVENT : Any>(
    initialState: STATE,
) : ViewModel() {
    private val sideEffectsQueue: MutableList<SIDE_EFFECT> = mutableListOf()
    private var sideEffectsSubscriber: ((SIDE_EFFECT) -> Unit)? = null

    private val mainThreadDispatcher = Dispatchers.Main.immediate

    private val _state: MutableStateFlow<STATE> = MutableStateFlow(initialState)

    private val _error: MutableStateFlow<AppError?> = MutableStateFlow(null)

    private val _offlineBanner: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val _serverRetryNotice: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private var serverRetryNoticeJob: Job? = null

    protected val state: STATE
        get() = _state.value

    private val updateStateLock = Mutex()
    private val sideEffectLock = Mutex()

    abstract fun dispatch(event: EVENT)

    protected abstract fun onFetchData()

    protected val screenModelScope: CoroutineScope = viewModelScope

    protected fun screenModelScope(
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        call: suspend CoroutineScope.(STATE) -> Unit,
    ): Job = screenModelScope.launch(dispatcher) {
        runCatching { call(state) }
    }

    protected suspend fun postSideEffect(effect: SIDE_EFFECT) {
        sideEffectLock.withLock {
            withContext(mainThreadDispatcher) {
                sideEffectsSubscriber?.invoke(effect) ?: sideEffectsQueue.add(effect)
            }
        }
    }

    protected suspend fun updateState(call: (STATE) -> STATE) {
        updateStateLock.withLock {
            withContext(mainThreadDispatcher) {
                _state.emit(call(state))
            }
        }
    }

    @Composable
    fun collectAsState(): State<STATE> {
        return _state.collectAsState()
    }

    protected suspend fun showError(error: AppError) {
        withContext(mainThreadDispatcher) { _error.emit(error) }
    }

    protected suspend fun showError(error: RequestResult.Error) {
        showError(error.kind)
    }

    protected suspend fun showOfflineBanner() {
        withContext(mainThreadDispatcher) { _offlineBanner.emit(true) }
    }

    fun dismissOfflineBanner() {
        _offlineBanner.value = false
    }

    @Composable
    fun collectOfflineBannerAsState(): State<Boolean> {
        return _offlineBanner.collectAsState()
    }

    @Composable
    fun collectServerRetryNoticeAsState(): State<Boolean> {
        return _serverRetryNotice.collectAsState()
    }

    protected fun showServerRetryNotice() {
        serverRetryNoticeJob?.cancel()
        _serverRetryNotice.value = true
        serverRetryNoticeJob = screenModelScope.launch(mainThreadDispatcher) {
            delay(SERVER_RETRY_NOTICE_MILLIS)
            _serverRetryNotice.value = false
            serverRetryNoticeJob = null
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun retry() {
        _error.value = null
        _offlineBanner.value = false
        onFetchData()
    }

    @Composable
    fun collectErrorAsState(): State<AppError?> {
        return _error.collectAsState()
    }

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
        serverRetryNoticeJob?.cancel()
        super.onCleared()
    }
}

private const val SERVER_RETRY_NOTICE_MILLIS = 3_000L
