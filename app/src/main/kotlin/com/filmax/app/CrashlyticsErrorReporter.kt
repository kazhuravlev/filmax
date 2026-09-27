package com.filmax.app

import com.filmax.core.domain.common.ErrorReporter
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal class CrashlyticsErrorReporter(
    private val crashlytics: FirebaseCrashlytics,
) : ErrorReporter {
    override fun setUser(id: String?) = crashlytics.setUserId(id.orEmpty())

    override fun log(message: String) = crashlytics.log(message)

    override fun report(error: Throwable) = crashlytics.recordException(error)
}
