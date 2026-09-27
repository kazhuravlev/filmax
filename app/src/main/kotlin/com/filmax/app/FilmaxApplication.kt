package com.filmax.app

import android.app.Application
import android.content.pm.PackageManager
import coil3.SingletonImageLoader
import com.filmax.app.di.appModule
import com.filmax.app.image.FilmaxImageLoaderFactory
import com.filmax.app.warmup.AppWarmup
import com.filmax.core.domain.common.ErrorReporting
import com.filmax.core.domain.error.ErrorClassification
import com.filmax.core.network.KtorErrorClassifier
import com.filmax.core.network.TokenStorage
import com.filmax.core.network.di.networkModule
import com.filmax.core.network.di.platformNetworkModule
import com.filmax.core.ui.di.coreUiModule
import com.filmax.data.auth.di.authModule
import com.filmax.data.catalog.di.catalogModule
import com.filmax.data.search.di.searchModule
import com.filmax.data.tmdb.di.TMDB_API_KEY_PROPERTY
import com.filmax.data.tmdb.di.tmdbModule
import com.filmax.data.user.di.userModule
import com.filmax.data.watching.di.watchingModule
import com.filmax.feature.collections.common.di.collectionsModule
import com.filmax.feature.details.common.di.detailsModule
import com.filmax.feature.home.common.di.homeModule
import com.filmax.feature.library.common.di.libraryModule
import com.filmax.feature.onboarding.common.di.onboardingModule
import com.filmax.feature.player.common.di.playerModule
import com.filmax.feature.profile.common.di.profileModule
import com.filmax.feature.search.common.di.searchFeatureModule
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class FilmaxApplication :
    Application(),
    SingletonImageLoader.Factory by FilmaxImageLoaderFactory() {
    override fun onCreate() {
        super.onCreate()
        initErrorReporting()
        ErrorClassification.classifier = KtorErrorClassifier
        runOneTimeHousekeeping()
        val koinApp = startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FilmaxApplication)
            properties(mapOf(TMDB_API_KEY_PROPERTY to BuildConfig.TMDB_API_KEY))
            modules(
                // core / data
                networkModule,
                platformNetworkModule,
                authModule,
                catalogModule,
                searchModule,
                userModule,
                watchingModule,
                tmdbModule,
                coreUiModule,
                // features
                onboardingModule,
                homeModule,
                searchFeatureModule,
                collectionsModule,
                libraryModule,
                profileModule,
                detailsModule,
                playerModule,
                // app
                appModule,
            )
        }
        seedDemoTokenIfNeeded(koinApp.koin.get())
        koinApp.koin.get<AppWarmup>().start(CoroutineScope(Dispatchers.IO))
    }

    private fun initErrorReporting() {
        if (BuildConfig.DEBUG) {
            FirebaseApp.initializeApp(this)?.let {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
            }
            ErrorReporting.reporter = LogcatErrorReporter()
            return
        }
        FirebaseApp.initializeApp(this) ?: return
        val crashlytics = FirebaseCrashlytics.getInstance()
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        crashlytics.setCustomKey("form_factor", if (isTv) "tv" else "mobile")
        ErrorReporting.reporter = CrashlyticsErrorReporter(crashlytics)
    }

    private fun seedDemoTokenIfNeeded(tokenStorage: TokenStorage) {
        val access = BuildConfig.DEMO_ACCESS_TOKEN
        val refresh = BuildConfig.DEMO_REFRESH_TOKEN
        if (access.isBlank() || refresh.isBlank()) return
        tokenStorage.seedIfEmpty(access, refresh)
    }

    private fun runOneTimeHousekeeping() {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = getSharedPreferences("filmax_housekeeping", MODE_PRIVATE)

            if (!prefs.getBoolean(KEY_CLEANUP_V1_DONE, false)) {
                deleteSharedPreferences("filmax_item_cache")
                prefs.edit().putBoolean(KEY_CLEANUP_V1_DONE, true).apply()
            }
        }
    }

    private companion object {
        const val KEY_CLEANUP_V1_DONE = "cleanup_v1_done"
    }
}
