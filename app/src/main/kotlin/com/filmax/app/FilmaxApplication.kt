package com.filmax.app

import android.app.Application
import android.content.pm.PackageManager
import coil3.SingletonImageLoader
import com.filmax.app.di.appModule
import com.filmax.app.image.FilmaxImageLoaderFactory
import com.filmax.app.warmup.AppWarmup
import com.filmax.core.domain.common.ErrorReporting
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

// SingletonImageLoader.Factory — делегирован FilmaxImageLoaderFactory: Coil сам находит эту
// реализацию через applicationContext при первом обращении к синглтон-загрузчику, вызывать
// что-то явно в onCreate не нужно (см. com.filmax.app.image.FilmaxImageLoaderFactory).
class FilmaxApplication :
    Application(),
    SingletonImageLoader.Factory by FilmaxImageLoaderFactory() {
    override fun onCreate() {
        super.onCreate()
        initErrorReporting()
        seedDemoTokenIfNeeded()
        runOneTimeHousekeeping()
        val koinApp = startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@FilmaxApplication)
            // Ключ TMDB отдаём модулю свойством, а не в коде: секрет живёт в BuildConfig (из
            // local.properties), а data:tmdb читает его через getProperty.
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
        // Фоновый прогрев вкладок «Моё»/«Каталог» (главная прогревает себя сама — см. doc
        // AppWarmup). Сам себя ограничивает по авторизации и одноразовости — здесь только запуск
        // на фоновом скоупе, чтобы не задерживать onCreate.
        koinApp.koin.get<AppWarmup>().start(CoroutineScope(Dispatchers.IO))
    }

    /**
     * Включает телеметрию ошибок. Debug пишет в logcat (Crashlytics в debug — шум разработки);
     * release/demo — в Crashlytics, если сборка шла с app/google-services.json (без него
     * Firebase не сконфигурирован, initializeApp вернёт null, и репортинг остаётся no-op).
     */
    private fun initErrorReporting() {
        if (BuildConfig.DEBUG) {
            // Сегодня в debug-сборке конфига Firebase нет (её applicationId не зарегистрирован,
            // и задачи google-services для неё выключены), но стоит его зарегистрировать — и
            // Crashlytics поднимется САМ через FirebaseInitProvider со сбором, включённым по
            // умолчанию. Поэтому запрет явный, а не «по счастливому стечению обстоятельств».
            FirebaseApp.initializeApp(this)?.let {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
            }
            ErrorReporting.reporter = LogcatErrorReporter()
            return
        }
        FirebaseApp.initializeApp(this) ?: return
        val crashlytics = FirebaseCrashlytics.getInstance()
        // Один APK на оба форм-фактора — в отчётах различаем их так же, как MainActivity выбирает UI.
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        crashlytics.setCustomKey("form_factor", if (isTv) "tv" else "mobile")
        ErrorReporting.reporter = CrashlyticsErrorReporter(crashlytics)
    }

    /**
     * Demo-сборка стартует авторизованной: если в BuildConfig зашит токен (только build type `demo`)
     * и хранилище ещё пустое — засеваем те же SharedPreferences `filmax_tokens`, что читает
     * TokenStorage при создании. Так demo-билд открывается без входа на любом устройстве. В
     * release/debug оба токена пустые — метод сразу выходит и ничего не трогает.
     */
    private fun seedDemoTokenIfNeeded() {
        val access = BuildConfig.DEMO_ACCESS_TOKEN
        val refresh = BuildConfig.DEMO_REFRESH_TOKEN
        if (access.isBlank() || refresh.isBlank()) return
        val prefs = getSharedPreferences("filmax_tokens", MODE_PRIVATE)
        if (prefs.getString("access_token", null) != null) return
        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .apply()
    }

    /**
     * Разовая уборка после обновления: удаляет файлы хранилищ, которые перестали использоваться
     * в текущей версии, но остались на диске у тех, кто ставил приложение раньше. Выполняется
     * не более одного раза — факт запуска фиксируется отдельным маленьким файлом
     * `filmax_housekeeping` (не тем, что чистим), чтобы сама уборка не плодила I/O при каждом
     * старте. Идёт на фоновом потоке: диск не должен трогаться из `onCreate` синхронно.
     *
     * Каждый шаг уборки — свой ключ (`cleanup_v1`, дальше `cleanup_v2`, …), выполняется независимо
     * и один раз. Добавить новый шаг в будущем — дописать проверку `if (!prefs.getBoolean("cleanup_vN", false))`
     * с соответствующими удалениями и пометкой ключа как выполненного, не трогая предыдущие.
     */
    private fun runOneTimeHousekeeping() {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = getSharedPreferences("filmax_housekeeping", MODE_PRIVATE)

            // cleanup_v1: файл "filmax_item_cache" — кэш деталей тайтлов на старом Settings/
            // SharedPreferences-хранилище. Заменён на SQLite ("filmax_item_cache.db"), миграции
            // данных нет — старый файл просто больше никем не читается и не пишется.
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
