package com.filmax.feature.profile.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.user.model.UserProfile
import com.filmax.core.tv.designsystem.ScrollToTopOnNavFocus
import com.filmax.core.tv.designsystem.TvError
import com.filmax.core.tv.designsystem.TvFocus
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOnSurfaceDim
import com.filmax.core.tv.designsystem.TvOnSurfaceVariant
import com.filmax.core.tv.designsystem.TvOverline
import com.filmax.core.tv.designsystem.TvSurface
import com.filmax.core.tv.designsystem.TvSurfaceContainer
import com.filmax.core.tv.designsystem.TvSurfaceContainerHigh
import com.filmax.core.tv.designsystem.TvSurfaceContainerHighest
import com.filmax.core.tv.designsystem.rememberTvScreenFocus
import com.filmax.core.ui.components.FilmaxVersionLabel
import com.filmax.feature.profile.common.ProfileEvent
import com.filmax.feature.profile.common.ProfileScreenModel
import com.filmax.feature.profile.common.ProfileSideEffect
import com.filmax.feature.profile.common.ProfileState
import com.filmax.feature.profile.common.initialsOrFallback
import com.filmax.feature.profile.common.label
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/** Ширина колонки настроек. Читать строку длиной во весь экран с 3 метров невозможно. */
private val ContentMaxWidth = 640.dp

/** Отступ сверху: шапка профиля не под таб-баром, а заметно ниже — это первый экран раздела. */
private val ContentTop = 96.dp

private val AvatarSize = 76.dp

/** Высота строки настройки. Фиксированная: разная высота строк ломает ритм списка под пультом. */
private val RowHeight = 60.dp

/** Шаг между строками одной группы. Задаётся ТОЛЬКО в [SettingsGroup] — см. её doc. */
private val RowGap = 10.dp

/** Отступ от надзаголовка группы до первой строки. */
private val GroupTitleGap = 12.dp

/** Промежуток между группами и между шапкой профиля и первой группой. */
private val GroupGap = 26.dp

/** Промежуток между ярлыком и значением строки: длинный ярлык не наезжает на значение. */
private val RowLabelValueGap = 16.dp

/**
 * TV-Профиль. Одна колонка: шапка аккаунта, затем группы «Просмотр», «Приложение», «Фоновая
 * загрузка» и в самом низу «Аккаунт» с единственной строкой выхода.
 * Данные и события — общие с мобильным профилем ([ProfileScreenModel]), меняется только
 * раскладка под 10-foot. Клик по строке настройки циклически меняет её значение.
 *
 * Статистики (просмотрено/в избранном) здесь нет: на пульте она ни на что не влияет и только
 * оттягивает внимание от единственной задачи экрана — поменять настройку или выйти.
 */
@Composable
fun TvProfileScreen(
    onLogout: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    screenModel: ProfileScreenModel = koinViewModel(),
) {
    val state by screenModel.collectAsState()

    screenModel.collectSideEffect { effect ->
        when (effect) {
            ProfileSideEffect.LoggedOut -> onLogout()
        }
    }

    if (state.loading) {
        Box(
            modifier
                .fillMaxSize()
                .background(TvSurface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = TvOnSurface)
        }
        return
    }

    ProfileContent(
        state = state,
        actions = profileActions(screenModel, state, onCheckUpdates),
        modifier = modifier,
    )
}

// ── Контент ──────────────────────────────────────────────────────────────────

@Composable
private fun ProfileContent(
    state: ProfileState,
    actions: ProfileActions,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    ScrollToTopOnNavFocus(scrollState)
    val focus = rememberTvScreenFocus()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvSurface)
            .then(focus.containerModifier)
            .verticalScroll(scrollState)
            .padding(
                start = TvMetrics.SafeHorizontal,
                end = TvMetrics.SafeHorizontal,
                top = ContentTop,
                bottom = TvMetrics.SafeVertical,
            ),
    ) {
        Column(
            Modifier.widthIn(max = ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            ProfileHeader(profile = state.profile, modifier = Modifier.padding(bottom = 6.dp))
            SettingsGroup("Просмотр") { PlaybackRows(state = state, actions = actions) }
            // Блока «Устройство» временно нет: device/info и device/settings отвечают 500,
            // и строка вела на нерабочий экран. Вернуть, когда бэкенд починят.
            //
            // На телевизоре магазина нет вообще — приложение ставится APK, и ручная проверка
            // здесь нужнее, чем на телефоне.
            SettingsGroup("Приложение") { AppRows(state = state, actions = actions) }
            SettingsGroup("Фоновая загрузка") { BackgroundFetchRows(state = state, actions = actions) }
            // Выход — последняя строка экрана: случайно до неё не доезжают, а подписка в шапке
            // уже показана — отдельная справочная строка «Подписка» здесь ничего не добавляла.
            SettingsGroup("Аккаунт") { AccountRows(actions = actions) }
            FilmaxVersionLabel(color = TvOnSurfaceDim)
        }
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(AvatarSize)
                .clip(CircleShape)
                .background(TvSurfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.initialsOrFallback(),
                style = MaterialTheme.typography.headlineMedium,
                color = TvOnSurface,
            )
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                profile?.username ?: "Гость",
                style = MaterialTheme.typography.headlineSmall,
                color = TvOnSurface,
                maxLines = 2,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                profile?.subscription.label(),
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ── Группы настроек ──────────────────────────────────────────────────────────

/**
 * Группа строк настроек: надзаголовок и строки с единым шагом [RowGap]. ЕДИНСТВЕННОЕ место,
 * где задаются отступы между строками — раньше «Сервер API» и «Проверить обновления» лежали в
 * колонке экрана голыми, без `spacedBy`, и слипались в одну плашку, пока остальные группы
 * держали шаг каждая своей `Column`. Строки внутри — только [SettingRow], своих отступов у них
 * нет и быть не должно.
 */
@Composable
private fun SettingsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column {
        TvOverline(title, color = TvOnSurfaceDim)
        Spacer(Modifier.height(GroupTitleGap))
        Column(verticalArrangement = Arrangement.spacedBy(RowGap), content = rows)
    }
}

private data class ProfileActions(
    val onCycleQuality: () -> Unit,
    val onCyclePreset: () -> Unit,
    val onResetTitleTracks: () -> Unit,
    val onCyclePlayerUi: () -> Unit,
    val onLogout: () -> Unit,
    val onCheckUpdates: () -> Unit,
    val onCycleApiHost: () -> Unit,
    val onClearImageCache: () -> Unit,
    val onToggleImageProxy: () -> Unit,
    val onToggleBackgroundFetch: () -> Unit,
    val onToggleTechOverlay: () -> Unit,
    val onClearItemCache: () -> Unit,
)

/** Лямбды замыкают текущий [state], поэтому пересобираются вместе с ним — без remember. */
private fun profileActions(
    screenModel: ProfileScreenModel,
    state: ProfileState,
    onCheckUpdates: () -> Unit,
) = ProfileActions(
    onCheckUpdates = onCheckUpdates,
    onCycleQuality = {
        screenModel.dispatch(
            ProfileEvent.SetQuality(next(PlaybackSettings.qualityOptions, state.playback.quality))
        )
    },
    onCyclePreset = {
        screenModel.dispatch(
            ProfileEvent.SetPreset(next(PlaybackSettings.presetOptions, state.playback.presetLabel))
        )
    },
    onResetTitleTracks = { screenModel.dispatch(ProfileEvent.ResetTitleTracks) },
    onCyclePlayerUi = {
        screenModel.dispatch(
            ProfileEvent.SetPlayerUi(next(PlaybackSettings.playerUiOptions, state.playback.playerUi.label))
        )
    },
    onLogout = { screenModel.dispatch(ProfileEvent.Logout) },
    onCycleApiHost = {
        val hosts = state.availableApiHosts
        if (hosts.isNotEmpty()) {
            screenModel.dispatch(ProfileEvent.SetApiHost(next(hosts, state.apiHost)))
        }
    },
    onClearImageCache = { screenModel.dispatch(ProfileEvent.ClearImageCache) },
    onToggleImageProxy = {
        screenModel.dispatch(ProfileEvent.SetImageProxyEnabled(!state.imageProxyEnabled))
    },
    onToggleBackgroundFetch = {
        screenModel.dispatch(ProfileEvent.SetBackgroundFetchEnabled(!state.backgroundFetchEnabled))
    },
    onToggleTechOverlay = {
        screenModel.dispatch(ProfileEvent.SetTechOverlayEnabled(!state.techOverlayEnabled))
    },
    onClearItemCache = { screenModel.dispatch(ProfileEvent.ClearItemCache) },
)

@Composable
private fun PlaybackRows(state: ProfileState, actions: ProfileActions) {
    SettingRow(
        spec = SettingRowSpec(label = "Качество видео", value = state.playback.quality),
        onClick = actions.onCycleQuality,
    )
    // «Авто» — первый пресет из списка, чьи озвучка и субтитры есть у тайтла; в плеере
    // пресет можно сменить или переопределить ручным выбором дорожек — на этот тайтл.
    SettingRow(
        spec = SettingRowSpec(label = "Озвучка и субтитры", value = state.playback.presetLabel),
        onClick = actions.onCyclePreset,
    )
    // Интерфейс плеера — общий для тайтлов и трейлеров (см. PlayerUi). Пока вариант один,
    // пункт всё равно на месте: следующий интерфейс появится как новое значение перечисления.
    SettingRow(
        spec = SettingRowSpec(label = "Интерфейс плеера", value = state.playback.playerUi.label),
        onClick = actions.onCyclePlayerUi,
    )
    SettingRow(
        spec = SettingRowSpec(
            label = "Сбросить дорожки тайтлов",
            labelColor = TvError,
        ),
        onClick = actions.onResetTitleTracks,
    )
}

@Composable
private fun AppRows(state: ProfileState, actions: ProfileActions) {
    SettingRow(
        spec = SettingRowSpec(label = "Сервер API", value = apiHostLabel(state.apiHost)),
        onClick = actions.onCycleApiHost,
    )
    SettingRow(
        spec = SettingRowSpec(label = "Проверить обновления"),
        onClick = actions.onCheckUpdates,
    )
}

@Composable
private fun AccountRows(actions: ProfileActions) {
    SettingRow(
        spec = SettingRowSpec(label = "Выйти из аккаунта", labelColor = TvError),
        onClick = actions.onLogout,
    )
}

/**
 * Единый раздел настроек фоновой докачки: общий выключатель (картинки И информация о тайтлах,
 * см. [com.filmax.core.domain.cache.BackgroundFetchSettings]), прокси изображений, оверлей
 * технической диагностики этой же докачки ([com.filmax.core.domain.cache.TechOverlaySettings]) и
 * сброс обоих дисковых кэшей по отдельности — у каждого свой размер/счётчик, поэтому и
 * сбрасываются порознь.
 */
@Composable
private fun BackgroundFetchRows(state: ProfileState, actions: ProfileActions) {
    val stats = state.imageCacheStats
    val usedMb = stats.sizeBytes / (1024.0 * 1024.0)
    val maxMb = stats.maxSizeBytes / (1024.0 * 1024.0)
    val sizeLabel = String.format(Locale.US, "%.1f из %.0f МБ", usedMb, maxMb)
    val itemCount = state.itemCacheCount
    val titleWord = when {
        itemCount % 100 in 11..14 -> "тайтлов"
        itemCount % 10 == 1 -> "тайтл"
        itemCount % 10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    SettingRow(
        spec = SettingRowSpec(label = "Фоновая загрузка", value = onOff(state.backgroundFetchEnabled)),
        onClick = actions.onToggleBackgroundFetch,
    )
    SettingRow(
        spec = SettingRowSpec(label = "Прокси изображений", value = onOff(state.imageProxyEnabled)),
        onClick = actions.onToggleImageProxy,
    )
    SettingRow(
        spec = SettingRowSpec(
            label = "Показывать технические данные",
            value = onOff(state.techOverlayEnabled),
        ),
        onClick = actions.onToggleTechOverlay,
    )
    SettingRow(
        spec = SettingRowSpec(
            label = "Сбросить кеш изображений ($sizeLabel)",
            labelColor = TvError,
        ),
        onClick = actions.onClearImageCache,
    )
    SettingRow(
        spec = SettingRowSpec(
            label = "Сбросить кеш тайтлов ($itemCount $titleWord)",
            labelColor = TvError,
        ),
        onClick = actions.onClearItemCache,
    )
}

// ── Строка настройки ─────────────────────────────────────────────────────────

private data class SettingRowSpec(
    val label: String,
    val value: String? = null,
    val labelColor: Color = TvOnSurface,
)

/**
 * Строка настройки: слева ярлык, справа значение.
 *
 * Фокус рисуем вручную, а не через `TvFocusCard`, несмотря на единую схему фокуса в остальном
 * приложении. Причина геометрическая: `Modifier.verticalScroll` клипает контент по горизонтали
 * (`clipScrollableContainer` расширяет бокс только сверху и снизу), а `FocusScale` = 1.08 на
 * строке шириной 640dp — это +25dp с каждой стороны. Рамке столько не дать: карточные ряды
 * решают это запасом `FocusInset` = 12dp, здесь его не хватит вдвое, а расширить колонку до
 * 690dp — значит вынести рамку на 32dp от края экрана, внутрь оверскан-зоны, ради защиты
 * от которой и существует `SafeHorizontal`.
 *
 * Поэтому масштаб заменён вторым статичным сигналом — подъёмом фона: рамка [TvFocus] и цвет
 * фона меняются вместе, так что фокус читается и без геометрии.
 */
@Composable
private fun SettingRow(spec: SettingRowSpec, onClick: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvMetrics.PanelShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .then(
                if (onClick != null) {
                    Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .background(if (focused) TvSurfaceContainerHigh else TvSurfaceContainer, shape)
            .then(if (focused) Modifier.border(TvMetrics.FocusBorderWidth, TvFocus, shape) else Modifier)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Ярлык уступает место значению: длинный ярлык («Сбросить кеш изображений (12.3 из
        // 200 МБ)») режется многоточием, а не выдавливает значение за край строки.
        Text(
            spec.label,
            style = MaterialTheme.typography.titleMedium,
            color = spec.labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!spec.value.isNullOrEmpty()) {
            Text(
                spec.value,
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = RowLabelValueGap),
            )
        }
    }
}

// ── Вспомогательное ──────────────────────────────────────────────────────────

private fun <T> next(options: List<T>, current: T): T {
    val index = options.indexOf(current)
    return options[(index + 1).mod(options.size)]
}

/** Хост без схемы — короче для строки настройки (`smarttvcdn.online` вместо полного URL). */
private fun apiHostLabel(host: String): String = host.removePrefix("https://").removePrefix("http://")

private fun onOff(enabled: Boolean): String = if (enabled) "Вкл" else "Выкл"
