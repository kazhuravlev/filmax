package com.filmax.app.update

import com.filmax.app.BuildConfig
import com.filmax.core.presentation.BaseScreenModel
import kotlinx.coroutines.CoroutineDispatcher

private const val RELEASE_APPLICATION_ID = "com.filmax.app"

private val IS_RELEASE_BUILD = BuildConfig.APPLICATION_ID == RELEASE_APPLICATION_ID

private const val PROGRESS_STEP = 0.01f

class AppUpdateScreenModel(
    private val repository: GitHubUpdateRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : BaseScreenModel<AppUpdateState, AppUpdateSideEffect, AppUpdateEvent>(AppUpdateState()) {
    init {
        onFetchData()
    }

    override fun dispatch(event: AppUpdateEvent) {
        when (event) {
            AppUpdateEvent.Check -> check()
            AppUpdateEvent.Download -> download()
            AppUpdateEvent.Install -> install()
            AppUpdateEvent.Dismiss -> dismiss()
        }
    }

    override fun onFetchData() {
        if (!IS_RELEASE_BUILD) return
        screenModelScope(ioDispatcher) { _ ->
            val update = repository.latestUpdate() ?: return@screenModelScope
            updateState { it.copy(update = update) }
        }
    }

    private fun check() {
        screenModelScope(ioDispatcher) { snapshot ->
            if (snapshot.checking) return@screenModelScope
            updateState {
                it.copy(checking = true, upToDate = false, dismissed = false, downloadError = false)
            }
            val update = repository.latestUpdate()
            updateState {
                it.copy(
                    checking = false,
                    update = update,
                    upToDate = update == null,
                    installable = IS_RELEASE_BUILD,
                )
            }
        }
    }

    private fun download() {
        screenModelScope(ioDispatcher) { snapshot ->
            val update = snapshot.update ?: return@screenModelScope
            updateState { it.copy(downloading = true, progress = 0f, downloadError = false) }
            var lastReported = 0f
            val result = runCatching {
                repository.downloadApk(update) { fraction ->
                    if (fraction - lastReported >= PROGRESS_STEP) {
                        lastReported = fraction
                        updateState { it.copy(progress = fraction) }
                    }
                }
            }
            result.fold(
                onSuccess = { apk ->
                    updateState { it.copy(downloading = false, progress = 1f, downloadedApk = apk) }
                    postSideEffect(AppUpdateSideEffect.LaunchInstaller(apk))
                },
                onFailure = { _ ->
                    updateState { it.copy(downloading = false, downloadError = true) }
                },
            )
        }
    }

    private fun install() {
        screenModelScope { snapshot ->
            snapshot.downloadedApk?.let { postSideEffect(AppUpdateSideEffect.LaunchInstaller(it)) }
        }
    }

    private fun dismiss() {
        screenModelScope { _ ->
            updateState { it.copy(dismissed = true, upToDate = false) }
        }
    }
}
