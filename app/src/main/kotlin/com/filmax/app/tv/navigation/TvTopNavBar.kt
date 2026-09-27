package com.filmax.app.tv.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.filmax.core.tv.designsystem.TvAccent
import com.filmax.core.tv.designsystem.TvFocusCard
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceDim
import com.filmax.core.tv.designsystem.TvSurfaceContainerHighest
import com.filmax.feature.home.tv.navigation.TvHomeRoute
import com.filmax.feature.library.tv.navigation.TvBookmarksRoute
import com.filmax.feature.library.tv.navigation.TvHistoryRoute
import com.filmax.feature.library.tv.navigation.TvWatchingRoute
import com.filmax.feature.profile.tv.navigation.TvProfileRoute
import com.filmax.feature.search.tv.navigation.TvSearchRoute
import kotlin.reflect.KClass

private data class TvTab(val label: String, val route: Any, val match: (NavDestination?) -> Boolean)

internal data class TvTopNavBarActions(
    val onSelectTab: (route: Any) -> Unit,
    val onReselectActiveTab: () -> Unit,
)

private val TABS = listOf(
    TvTab("Главная", TvHomeRoute) { it?.hasRoute(TvHomeRoute::class) == true },
    TvTab("Я смотрю", TvWatchingRoute) { it?.hasRoute(TvWatchingRoute::class) == true },
    TvTab("Подборки", TvBookmarksRoute) { it?.hasRoute(TvBookmarksRoute::class) == true },
    TvTab("История", TvHistoryRoute) { it?.hasRoute(TvHistoryRoute::class) == true },
    TvTab("Каталог", TvSearchRoute) { it?.hasRoute(TvSearchRoute::class) == true },
    TvTab("Настройки", TvProfileRoute) { it?.hasRoute(TvProfileRoute::class) == true },
)

val TOP_LEVEL_ROUTES: List<KClass<*>> = listOf(
    TvHomeRoute::class,
    TvWatchingRoute::class,
    TvBookmarksRoute::class,
    TvHistoryRoute::class,
    TvSearchRoute::class,
    TvProfileRoute::class,
)

internal data class TvTopNavBarFocus(
    val navBar: FocusRequester,
    val content: FocusRequester,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvTopNavBar(
    currentDestination: NavDestination?,
    actions: TvTopNavBarActions,
    focus: TvTopNavBarFocus,
    initials: String,
    modifier: Modifier = Modifier,
) {
    val activeIndex = TABS.indexOfFirst { it.match(currentDestination) }.coerceAtLeast(0)
    val tabFocusRequesters = remember { TABS.map { FocusRequester() } }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TvMetrics.TopBarHeight)
            .focusRequester(focus.navBar)
            .focusProperties { enter = { tabFocusRequesters[activeIndex] } }
            .focusGroup()
            .padding(horizontal = TvMetrics.SafeHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvBrandLabel()
        Spacer(Modifier.weight(1f))
        TvNavTabs(
            activeIndex = activeIndex,
            tabFocusRequesters = tabFocusRequesters,
            contentFocus = focus.content,
            onTabFocused = { index -> if (index != activeIndex) actions.onSelectTab(TABS[index].route) },
            onActiveTabClick = actions.onReselectActiveTab,
        )
        Spacer(Modifier.weight(1f))
        TvAvatar(initials = initials)
    }
}

@Composable
private fun TvBrandLabel() {
    Text(
        "FILMAX",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 2.5.sp,
        color = TvOnSurface,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun TvNavTabs(
    activeIndex: Int,
    tabFocusRequesters: List<FocusRequester>,
    contentFocus: FocusRequester,
    onTabFocused: (index: Int) -> Unit,
    onActiveTabClick: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TABS.forEachIndexed { index, tab ->
            NavTab(
                label = tab.label,
                active = index == activeIndex,
                onClick = {
                    if (index == activeIndex) onActiveTabClick() else onTabFocused(index)
                },
                modifier = Modifier
                    .focusRequester(tabFocusRequesters[index])
                    .onFocusChanged { if (it.isFocused) onTabFocused(index) }
                    .focusProperties { down = contentFocus },
            )
        }
    }
}

@Composable
private fun TvAvatar(initials: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TvSurfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNotBlank()) {
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge,
                color = TvOnSurface,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = TvOnSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NavTab(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusCard(onClick = onClick, shape = TvMetrics.ButtonShape, modifier = modifier) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                color = if (active) TvOnSurface else TvOnSurfaceDim,
            )
            if (active) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(TvAccent),
                )
            }
        }
    }
}
