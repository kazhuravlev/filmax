package com.filmax.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import com.filmax.core.navigation.Destination
import com.filmax.core.navigation.NavCommand
import com.filmax.core.navigation.Navigator
import com.filmax.feature.collections.common.navigation.CollectionDetailRoute
import com.filmax.feature.designsystem.navigation.DesignSystemRoute
import com.filmax.feature.details.common.navigation.DetailsRoute
import com.filmax.feature.home.mobile.navigation.HomeRoute
import com.filmax.feature.library.mobile.navigation.BookmarksRoute
import com.filmax.feature.library.mobile.navigation.WatchingRoute
import com.filmax.feature.onboarding.mobile.navigation.OnboardingRoute
import com.filmax.feature.player.common.navigation.PlayerRoute
import com.filmax.feature.player.common.navigation.TrailerRoute
import com.filmax.feature.profile.mobile.navigation.DeviceSettingsRoute
import com.filmax.feature.profile.mobile.navigation.ProfileRoute
import com.filmax.feature.search.common.navigation.FilmographyRoute
import com.filmax.feature.search.mobile.navigation.SearchRoute
import org.koin.compose.koinInject

/**
 * Исполнитель команд навигации телефонного графа: единственное место, где абстрактный
 * [Destination] превращается в маршрут и в конкретные NavOptions.
 *
 * Экраны отправляют команды через [Navigator] и не знают ни маршрутов соседних фич, ни того,
 * сколько в приложении графов. Захочется разложить разделы по вложенным графам со своими
 * стеками — меняется этот файл, а не двадцать экранов.
 */
@Composable
internal fun NavigationCommands(
    navController: NavHostController,
    navigator: Navigator = koinInject(),
) {
    LaunchedEffect(navController) {
        navigator.commands.collect { command -> navController.execute(command) }
    }
}

private fun NavHostController.execute(command: NavCommand) {
    when (command) {
        is NavCommand.Open -> open(command.destination)
        is NavCommand.Replace -> replaceCurrent(command.destination)
        is NavCommand.Root -> makeRoot(command.destination)
        NavCommand.Back -> popBackStack()
    }
}

private fun NavHostController.open(destination: Destination) {
    if (destination is Destination.Tab) {
        switchTab(destination)
        return
    }
    navigate(destination.toRoute())
}

/**
 * Переход по вкладке — механика multiple back stacks: стек покидаемого раздела сохраняется
 * целиком (`saveState`), стек открываемого — восстанавливается (`restoreState`). Поэтому
 * «Каталог → карточка тайтла → Я смотрю → Каталог» возвращает в карточку, а не на корень раздела.
 *
 * Сохранённый стек кладётся под ключ САМОЙ ГЛУБОКОЙ снятой записи, а достаётся по цели перехода —
 * поэтому и попап, и переход адресуют экраны-корни разделов. С вложенными графами (`popUpTo` до
 * графа, переход на граф) ключи не сходятся, и стек молча не восстанавливается.
 *
 * Попап — до [HomeRoute], реального корня вкладок: стартовый destination графа это сплэш,
 * которого после логина в стеке нет (popUpTo{inclusive}), и попап по нему молча не срабатывал.
 */
private fun NavHostController.switchTab(tab: Destination.Tab) {
    navigate(tab.toRoute()) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Заменить текущий экран. Попап по id текущего destination, а не по маршруту: следующая серия —
 * это тот же `PlayerRoute` с другими аргументами, и снимать надо именно открытую запись, иначе
 * перещёлкивание серий копит стек.
 */
private fun NavHostController.replaceCurrent(destination: Destination) {
    val current = currentDestination?.id
    navigate(destination.toRoute()) {
        if (current != null) popUpTo(current) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Новый корень: чистим ВЕСЬ стек. При протухании сессии посреди работы онбординг иначе встаёт
 * ПОВЕРХ авторизованных экранов, и «Назад» возвращает на них.
 */
private fun NavHostController.makeRoot(destination: Destination) {
    navigate(destination.toRoute()) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Адрес → маршрут телефонного графа. `SearchRoute` отдаёт Каталог, а личные разделы имеют
 * отдельные маршруты, чтобы сохранять свои стеки независимо.
 */
private fun Destination.toRoute(): Any = when (this) {
    Destination.Home -> HomeRoute
    Destination.Watching -> WatchingRoute
    Destination.Catalog -> SearchRoute
    Destination.Bookmarks -> BookmarksRoute
    Destination.Profile -> ProfileRoute
    Destination.Onboarding -> OnboardingRoute
    Destination.DeviceSettings -> DeviceSettingsRoute
    Destination.DesignSystem -> DesignSystemRoute
    is Destination.Details -> DetailsRoute(itemId)
    is Destination.Player -> PlayerRoute(
        itemId = itemId,
        videoId = videoId,
        season = season,
        resumePositionSeconds = resumePositionSeconds,
    )
    is Destination.Trailer -> TrailerRoute(url = url, title = title)
    is Destination.CollectionDetail -> CollectionDetailRoute(collectionId = collectionId, title = title)
    is Destination.Filmography -> FilmographyRoute(name = name, isDirector = isDirector)
}
