package com.filmax.app.tv.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.filmax.app.navigation.AuthStateNavigation
import com.filmax.app.navigation.RootScreenModel
import com.filmax.app.navigation.navFadeIn
import com.filmax.app.navigation.navFadeOut
import com.filmax.core.tv.designsystem.LocalTvNavBarFocused
import com.filmax.core.tv.designsystem.LocalTvRefreshRequests
import com.filmax.core.tv.designsystem.LocalTvScrollToTop
import com.filmax.core.tv.designsystem.TvBottomNotification
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.feature.collections.common.navigation.CollectionDetailRoute
import com.filmax.feature.collections.tv.navigation.tvCollectionDetailScreen
import com.filmax.feature.details.common.navigation.DetailsRoute
import com.filmax.feature.details.tv.navigation.tvDetailsScreen
import com.filmax.feature.home.tv.navigation.TvHomeRoute
import com.filmax.feature.home.tv.navigation.tvHomeScreen
import com.filmax.feature.library.tv.navigation.tvBookmarksScreen
import com.filmax.feature.library.tv.navigation.tvWatchingScreen
import com.filmax.feature.onboarding.tv.navigation.TvOnboardingRoute
import com.filmax.feature.onboarding.tv.navigation.tvOnboardingScreen
import com.filmax.feature.player.common.navigation.PlayerRoute
import com.filmax.feature.player.common.navigation.TrailerRoute
import com.filmax.feature.player.tv.navigation.tvPlayerScreen
import com.filmax.feature.player.tv.navigation.tvTrailerScreen
import com.filmax.feature.profile.tv.navigation.tvDeviceSettingsScreen
import com.filmax.feature.profile.tv.navigation.tvProfileScreen
import com.filmax.feature.search.common.navigation.FilmographyRoute
import com.filmax.feature.search.tv.navigation.tvFilmographyScreen
import com.filmax.feature.search.tv.navigation.tvSearchScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

// Корневой composable намеренно оркестрирует auth, back, focus, tab navigation и общие
// CompositionLocal-сигналы в одном месте: разносить связанное состояние по владельцам рискованнее.
@Suppress("LongMethod")
@Composable
fun FilmaxTvNavGraph(
    onCheckUpdates: () -> Unit,
    onExit: () -> Unit,
    // Переиспользуем общий RootScreenModel (тот же auth.isAuthenticated, что и в телефонном графе).
    rootScreenModel: RootScreenModel = koinViewModel(),
) {
    val rootState by rootScreenModel.collectAsState()
    val isAuthenticated = rootState.isAuthenticated
    val navController = rememberNavController()

    AuthStateNavigation(
        isAuthenticated = isAuthenticated,
        navController = navController,
        homeRoute = TvHomeRoute,
        onboardingRoute = TvOnboardingRoute,
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentDest = backStack?.destination
    val showTopBar = TOP_LEVEL_ROUTES.any { currentDest?.hasRoute(it) == true }
    var exitArmed by remember { mutableStateOf(false) }

    // Пока в стеке навигации есть куда вернуться, Back поднимается по иерархии на уровень
    // выше; подтверждение выхода включается только когда возвращаться уже некуда (главная).
    //
    // У NavHost внутри свой BackHandler на асинхронном PredictiveBackHandler (включён, пока
    // в стеке больше одной записи) — раньше наш хендлер стоял ДО NavHost в композиции и потому
    // регистрировался раньше него, а Compose отдаёт Back самому позднему из зарегистрированных
    // колбэков. На пульте это давало гонку: серия быстрых нажатий «Назад» иногда обгоняла
    // асинхронный поп NavHost, и подтверждение выхода всплывало посреди стека, хотя выхода не
    // происходило. Теперь сами решаем «вверх по стеку или выйти» одним синхронным хендлером,
    // объявленным ПОСЛЕ NavHost (см. конец функции) — он регистрируется позже внутреннего и
    // получает нажатие первым, так что до внутреннего predictive-back дело не доходит вовсе.
    val canGoBack = navController.previousBackStackEntry != null
    LaunchedEffect(canGoBack) {
        if (canGoBack) exitArmed = false
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRMATION_TIMEOUT_MILLIS)
            exitArmed = false
        }
    }

    // Явная связь фокуса между оверлейной шапкой и контентом: они — соседи в Box, и
    // D-pad-поиск между ними сам по себе не проходит, поэтому шапка по «вниз» уводит в
    // [contentFocus], а контент по «вверх» — в шапку.
    val navBarFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }

    // Фокус в шапке — единственная причина, по которой экран НЕ забирает стартовый фокус себе
    // (см. tvScreenFocus): пользователь выбирает вкладки подряд, и подхват фокуса контентом
    // стоил бы ему лишнего «вверх» на каждый переход.
    val navBarFocused = remember { mutableStateOf(false) }

    // Сигнал «контент — в начало»: растёт при каждом заходе фокуса в шапку, экраны слушают его
    // через LocalTvScrollToTop и скроллят свой контейнер вверх, чтобы он не «застревал» внизу.
    var scrollToTopSignal by remember { mutableIntStateOf(0) }

    // Без replay: обновляется только экран, который сейчас жив и выбран. При последующем
    // переходе на другую вкладку старый клик не должен запускать у неё лишний запрос.
    val refreshRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Переход по вкладкам: единственный экземпляр + сохранение/восстановление состояния.
    fun navigateTab(route: Any) {
        navController.navigate(route) {
            popUpTo<TvHomeRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(
            LocalTvScrollToTop provides scrollToTopSignal,
            LocalTvNavBarFocused provides navBarFocused,
            LocalTvRefreshRequests provides refreshRequests,
        ) {
            NavHost(
                navController = navController,
                startDestination = TvHomeRoute,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusProperties { up = navBarFocus }
                    .focusGroup(),
                enterTransition = { navFadeIn },
                exitTransition = { navFadeOut },
                popEnterTransition = { navFadeIn },
                popExitTransition = { navFadeOut },
            ) {
                tvDestinations(navController, onCheckUpdates)
            }
        }

        if (showTopBar) {
            TvTopNavBar(
                currentDestination = currentDest,
                actions = TvTopNavBarActions(
                    onSelectTab = { navigateTab(it) },
                    onReselectActiveTab = { refreshRequests.tryEmit(Unit) },
                ),
                focus = TvTopNavBarFocus(navBar = navBarFocus, content = contentFocus),
                initials = rootState.initials,
                // Любой заход фокуса в шапку (с контента или стартовый) — повод увести контент вверх.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onFocusChanged {
                        navBarFocused.value = it.hasFocus
                        if (it.hasFocus) scrollToTopSignal++
                    },
            )
        }

        AnimatedVisibility(
            visible = exitArmed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = TvMetrics.SafeVertical),
        ) {
            TvBottomNotification("Нажмите «Назад» ещё раз, чтобы выйти из приложения")
        }
    }

    // Ниже NavHost намеренно (см. комментарий у canGoBack): у экрана, который сам не берёт
    // Back (свой BackHandler — плеер, «Подборки» с открытой папкой, фильмография), нажатие
    // доходит именно сюда, а не в предиктивный обработчик NavHost.
    BackHandler {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else if (exitArmed) {
            onExit()
        } else {
            exitArmed = true
        }
    }
}

private const val EXIT_CONFIRMATION_TIMEOUT_MILLIS = 1_000L

/** Регистрация всех экранов TV-графа: сплэш, онбординг, разделы и детали/плеер. */
private fun NavGraphBuilder.tvDestinations(
    navController: NavHostController,
    onCheckUpdates: () -> Unit,
) {
    tvOnboardingScreen(
        onAuthenticated = {
            navController.navigate(TvHomeRoute) { popUpTo(TvOnboardingRoute) { inclusive = true } }
        },
    )

    tvHomeScreen(
        onOpenItem = { navController.navigate(DetailsRoute(it)) },
        onPlay = { itemId, season, videoId, resumePositionSeconds ->
            navController.navigate(PlayerRoute(itemId, videoId, season, resumePositionSeconds))
        },
        onOpenCollection = { id, title ->
            navController.navigate(CollectionDetailRoute(collectionId = id, title = title))
        },
    )
    tvSearchScreen(onOpenItem = { navController.navigate(DetailsRoute(it)) })
    // «Подборки» больше не вкладка — это контент внутри Каталога. Экран содержимого
    // подборки остаётся push-экраном: в него ведёт Каталог.
    tvCollectionDetailScreen(onOpenItem = { navController.navigate(DetailsRoute(it)) })
    // Карточки «Я смотрю» ведут в детали: кнопка там показывает таймкод продолжения и, для
    // сериала, номер эпизода.
    tvWatchingScreen(
        onOpenItem = { navController.navigate(DetailsRoute(it)) },
    )
    tvBookmarksScreen(
        onOpenItem = { navController.navigate(DetailsRoute(it)) },
    )
    tvProfileScreen(
        onLogout = {
            navController.navigate(TvOnboardingRoute) { popUpTo(TvHomeRoute) { inclusive = true } }
        },
        onCheckUpdates = onCheckUpdates,
    )
    // Экран настроек устройства остаётся в графе, но из Профиля временно не открывается:
    // device/info и device/settings на бэкенде отвечают 500.
    tvDeviceSettingsScreen(onBack = { navController.popBackStack() })

    tvDetailsScreen(
        onPlay = { itemId, season, videoId, resumePositionSeconds ->
            navController.navigate(PlayerRoute(itemId, videoId, season, resumePositionSeconds))
        },
        onOpenItem = { navController.navigate(DetailsRoute(it)) },
        onOpenPerson = { name, isDirector ->
            navController.navigate(FilmographyRoute(name = name, isDirector = isDirector))
        },
        onPlayTrailer = { url, title -> navController.navigate(TrailerRoute(url = url, title = title)) },
    )
    // Фильмография человека — push-экран из деталей (тап по актёру/режиссёру).
    tvFilmographyScreen(
        onBack = { navController.popBackStack() },
        onOpenItem = { navController.navigate(DetailsRoute(it)) },
    )
    tvTrailerScreen(onBack = { navController.popBackStack() })
    tvPlayerScreen(
        onBack = { navController.popBackStack() },
        // «Следующая серия» — навигация, а не подмена MediaItem: прогресс пишется в трек,
        // выбранный при старте плеера, и подмена на месте писала бы позицию новой серии
        // в запись предыдущей. popUpTo не копит стек при перещёлкивании серий подряд.
        onPlayEpisode = { itemId, season, videoId ->
            navController.navigate(PlayerRoute(itemId, videoId, season)) {
                popUpTo<PlayerRoute> { inclusive = true }
            }
        },
    )
}
