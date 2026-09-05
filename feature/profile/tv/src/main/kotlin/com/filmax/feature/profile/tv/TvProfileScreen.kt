package com.filmax.feature.profile.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.filmax.core.domain.cache.ItemCacheTtl
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

private val RowGap = 10.dp

/**
 * TV-Профиль. Одна колонка: шапка аккаунта, затем группы «Просмотр» и «Аккаунт».
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
        Column(Modifier.widthIn(max = ContentMaxWidth)) {
            ProfileHeader(profile = state.profile)
            Spacer(Modifier.height(32.dp))
            TvOverline("Просмотр", color = TvOnSurfaceDim)
            Spacer(Modifier.height(12.dp))
            PlaybackRows(state = state, actions = actions)
            Spacer(Modifier.height(26.dp))
            // Блока «Устройство» временно нет: device/info и device/settings отвечают 500,
            // и строка вела на нерабочий экран. Вернуть, когда бэкенд починят.
            TvOverline("Аккаунт", color = TvOnSurfaceDim)
            Spacer(Modifier.height(12.dp))
            AccountRows(state = state, actions = actions)
            Spacer(Modifier.height(26.dp))
            // На телевизоре магазина нет вообще — приложение ставится APK, и ручная проверка
            // здесь нужнее, чем на телефоне.
            TvOverline("Приложение", color = TvOnSurfaceDim)
            Spacer(Modifier.height(12.dp))
            SettingRow(
                spec = SettingRowSpec(label = "Сервер API", value = apiHostLabel(state.apiHost)),
                onClick = actions.onCycleApiHost,
            )
            SettingRow(
                spec = SettingRowSpec(label = "Проверить обновления"),
                onClick = actions.onCheckUpdates,
            )
            Spacer(Modifier.height(26.dp))
            TvOverline("Изображения и постеры", color = TvOnSurfaceDim)
            Spacer(Modifier.height(12.dp))
            ImageSettingsRows(state = state, actions = actions)
            Spacer(Modifier.height(26.dp))
            TvOverline("Информация о тайтлах", color = TvOnSurfaceDim)
            Spacer(Modifier.height(12.dp))
            ItemCacheRows(state = state, actions = actions)
            Spacer(Modifier.height(26.dp))
            FilmaxVersionLabel(color = TvOnSurfaceDim)
        }
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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

private data class ProfileActions(
    val onCycleQuality: () -> Unit,
    val onCycleAudio: () -> Unit,
    val onCycleSubtitle: () -> Unit,
    val onResetSubtitlePreferences: () -> Unit,
    val onLogout: () -> Unit,
    val onCheckUpdates: () -> Unit,
    val onCycleApiHost: () -> Unit,
    val onClearImageCache: () -> Unit,
    val onToggleImageProxy: () -> Unit,
    val onToggleImagePrefetch: () -> Unit,
    val onClearItemCache: () -> Unit,
    val onCycleItemCacheTtl: () -> Unit,
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
    onCycleAudio = {
        screenModel.dispatch(
            ProfileEvent.SetAudioLanguage(next(PlaybackSettings.audioOptions, state.playback.audioLanguage))
        )
    },
    onCycleSubtitle = {
        screenModel.dispatch(
            ProfileEvent.SetSubtitleLanguage(
                next(PlaybackSettings.subtitleOptions, state.playback.subtitleLanguage)
            )
        )
    },
    onResetSubtitlePreferences = { screenModel.dispatch(ProfileEvent.ResetSubtitlePreferences) },
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
    onToggleImagePrefetch = {
        screenModel.dispatch(ProfileEvent.SetImagePrefetchEnabled(!state.imagePrefetchEnabled))
    },
    onClearItemCache = { screenModel.dispatch(ProfileEvent.ClearItemCache) },
    onCycleItemCacheTtl = {
        screenModel.dispatch(ProfileEvent.SetItemCacheTtl(next(ItemCacheTtlOptions, state.itemCacheTtl)))
    },
)

@Composable
private fun PlaybackRows(state: ProfileState, actions: ProfileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        SettingRow(
            spec = SettingRowSpec(label = "Качество видео", value = state.playback.quality),
            onClick = actions.onCycleQuality,
        )
        SettingRow(
            spec = SettingRowSpec(label = "Язык аудио", value = state.playback.audioLanguage),
            onClick = actions.onCycleAudio,
        )
        SettingRow(
            spec = SettingRowSpec(label = "Субтитры", value = state.playback.subtitleLanguage),
            onClick = actions.onCycleSubtitle,
        )
        SettingRow(
            spec = SettingRowSpec(
                label = "Сбросить настройки субтитров",
                labelColor = TvError,
            ),
            onClick = actions.onResetSubtitlePreferences,
        )
    }
}

@Composable
private fun AccountRows(state: ProfileState, actions: ProfileActions) {
    val active = state.profile?.subscription?.active == true
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        // Подписка — справочная строка: менять её из приложения нельзя, поэтому не фокусируется.
        SettingRow(
            spec = SettingRowSpec(label = "Подписка", value = if (active) "Premium" else "Неактивна"),
            onClick = null,
        )
        SettingRow(
            spec = SettingRowSpec(label = "Выйти из аккаунта", labelColor = TvError),
            onClick = actions.onLogout,
        )
    }
}

@Composable
private fun ImageSettingsRows(state: ProfileState, actions: ProfileActions) {
    val stats = state.imageCacheStats
    val usedMb = stats.sizeBytes / (1024.0 * 1024.0)
    val maxMb = stats.maxSizeBytes / (1024.0 * 1024.0)
    val sizeLabel = String.format(Locale.US, "%.1f из %.0f МБ", usedMb, maxMb)
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        SettingRow(
            spec = SettingRowSpec(label = "Прокси изображений", value = onOff(state.imageProxyEnabled)),
            onClick = actions.onToggleImageProxy,
        )
        SettingRow(
            spec = SettingRowSpec(
                label = "Фоновая загрузка изображений",
                value = onOff(state.imagePrefetchEnabled),
            ),
            onClick = actions.onToggleImagePrefetch,
        )
        SettingRow(
            spec = SettingRowSpec(
                label = "Прогресс фоновой загрузки",
                value = "Скачано ${state.imagePrefetchDownloaded}, осталось ${state.imagePrefetchRemaining}",
            ),
            onClick = null,
        )
        SettingRow(
            spec = SettingRowSpec(
                label = "Сбросить кеш изображений ($sizeLabel)",
                labelColor = TvError,
            ),
            onClick = actions.onClearImageCache,
        )
    }
}

@Composable
private fun ItemCacheRows(state: ProfileState, actions: ProfileActions) {
    val count = state.itemCacheCount
    val titleWord = when {
        count % 100 in 11..14 -> "тайтлов"
        count % 10 == 1 -> "тайтл"
        count % 10 in 2..4 -> "тайтла"
        else -> "тайтлов"
    }
    Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
        SettingRow(
            spec = SettingRowSpec(
                label = "Хранить кэш тайтлов",
                value = itemCacheTtlLabel(state.itemCacheTtl),
            ),
            onClick = actions.onCycleItemCacheTtl,
        )
        SettingRow(
            spec = SettingRowSpec(
                label = "Сбросить кэш ($count $titleWord)",
                labelColor = TvError,
            ),
            onClick = actions.onClearItemCache,
        )
    }
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
        Text(spec.label, style = MaterialTheme.typography.titleMedium, color = spec.labelColor)
        if (!spec.value.isNullOrEmpty()) {
            Text(
                spec.value,
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ── Вспомогательное ──────────────────────────────────────────────────────────

/** Следующее значение в списке опций по кругу. */
private fun <T> next(options: List<T>, current: T): T {
    val index = options.indexOf(current)
    return options[(index + 1).mod(options.size)]
}

/** Порядок цикла кнопки «Хранить кэш тайтлов»: месяц (по умолчанию) → неделя → 3 дня → никогда. */
private val ItemCacheTtlOptions =
    listOf(ItemCacheTtl.MONTH, ItemCacheTtl.WEEK, ItemCacheTtl.THREE_DAYS, ItemCacheTtl.NEVER)

private fun itemCacheTtlLabel(ttl: ItemCacheTtl): String = when (ttl) {
    ItemCacheTtl.MONTH -> "Месяц"
    ItemCacheTtl.WEEK -> "Неделя"
    ItemCacheTtl.THREE_DAYS -> "3 дня"
    ItemCacheTtl.NEVER -> "Не кэшировать"
}

/** Хост без схемы — короче для строки настройки (`smarttvcdn.online` вместо полного URL). */
private fun apiHostLabel(host: String): String = host.removePrefix("https://").removePrefix("http://")

private fun onOff(enabled: Boolean): String = if (enabled) "Вкл" else "Выкл"
