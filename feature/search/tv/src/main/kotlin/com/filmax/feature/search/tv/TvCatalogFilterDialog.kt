package com.filmax.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.filmax.core.domain.catalog.CatalogFilters
import com.filmax.core.domain.catalog.model.Country
import com.filmax.core.tv.designsystem.TvButton
import com.filmax.core.tv.designsystem.TvChip
import com.filmax.core.tv.designsystem.TvMetrics
import com.filmax.core.tv.designsystem.TvOnSurface
import com.filmax.core.tv.designsystem.TvOverline
import com.filmax.core.tv.designsystem.TvSurfaceContainer

@Composable
fun TvCatalogFilterDialog(
    current: CatalogFilters,
    countries: List<Country>,
    onApply: (CatalogFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    val firstChipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstChipFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        FilterPanel(
            draft = draft,
            countries = countries,
            firstChipFocus = firstChipFocus,
            onDraft = { draft = it },
            actions = FilterDialogActions(
                onApply = {
                    onApply(draft)
                    onDismiss()
                },
                onReset = { draft = CatalogFilters() },
            ),
        )
    }
}

private data class FilterDialogActions(
    val onApply: () -> Unit,
    val onReset: () -> Unit,
)

@Composable
private fun FilterPanel(
    draft: CatalogFilters,
    countries: List<Country>,
    firstChipFocus: FocusRequester,
    onDraft: (CatalogFilters) -> Unit,
    actions: FilterDialogActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(PANEL_WIDTH_FRACTION)
            .widthIn(max = PanelMaxWidth)
            .heightIn(max = PanelMaxHeight)
            .clip(TvMetrics.PanelShape)
            .background(TvSurfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        Text("Фильтры", style = MaterialTheme.typography.headlineSmall, color = TvOnSurface)
        YearSection(draft = draft, firstChipFocus = firstChipFocus, onDraft = onDraft)
        RatingSection(
            title = "Рейтинг Кинопоиска",
            selected = draft.kpRatingFrom,
            onSelect = { onDraft(draft.copy(kpRatingFrom = it)) },
        )
        RatingSection(
            title = "Рейтинг IMDb",
            selected = draft.imdbRatingFrom,
            onSelect = { onDraft(draft.copy(imdbRatingFrom = it)) },
        )
        CountrySection(
            countries = countries,
            selectedId = draft.countryId,
            onSelect = { onDraft(draft.copy(countryId = it)) },
        )
        QualitySection(only4k = draft.only4k, onToggle = { onDraft(draft.copy(only4k = !draft.only4k)) })
        StatusSection(onlyFinished = draft.onlyFinished, onToggle = { onDraft(draft.copy(onlyFinished = it)) })
        FilterDialogButtons(actions = actions)
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionTitleGap)) {
        TvOverline(title)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalArrangement = Arrangement.spacedBy(ChipGap),
    ) { content() }
}

@Composable
private fun YearSection(
    draft: CatalogFilters,
    firstChipFocus: FocusRequester,
    onDraft: (CatalogFilters) -> Unit,
) {
    FilterSection(title = "Год выпуска") {
        ChipFlowRow {
            YearBuckets.forEachIndexed { index, bucket ->
                TvChip(
                    label = bucket.label,
                    selected = bucket.matches(draft),
                    onClick = { onDraft(draft.copy(yearFrom = bucket.yearFrom, yearTo = bucket.yearTo)) },
                    modifier = if (index == 0) Modifier.focusRequester(firstChipFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun RatingSection(title: String, selected: Int?, onSelect: (Int?) -> Unit) {
    FilterSection(title = title) {
        ChipFlowRow {
            RatingThresholds.forEach { (label, threshold) ->
                TvChip(label = label, selected = selected == threshold, onClick = { onSelect(threshold) })
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CountrySection(countries: List<Country>, selectedId: Int?, onSelect: (Int?) -> Unit) {
    FilterSection(title = "Страна") {
        val firstCountryFocus = remember { FocusRequester() }
        LazyRow(
            modifier = Modifier.focusRestorer(firstCountryFocus).offset(x = -TvMetrics.FocusInset),
            contentPadding = PaddingValues(horizontal = TvMetrics.FocusInset),
            horizontalArrangement = Arrangement.spacedBy(ChipGap),
        ) {
            item(key = "any") {
                TvChip(
                    label = "Любая",
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
                    modifier = Modifier.focusRequester(firstCountryFocus),
                )
            }
            items(countries, key = { it.id }) { country ->
                TvChip(
                    label = country.title,
                    selected = country.id == selectedId,
                    onClick = { onSelect(country.id) },
                )
            }
        }
    }
}

@Composable
private fun QualitySection(only4k: Boolean, onToggle: () -> Unit) {
    FilterSection(title = "Качество") {
        TvChip(label = "Только 4K", selected = only4k, onClick = onToggle)
    }
}

@Composable
private fun StatusSection(onlyFinished: Boolean?, onToggle: (Boolean?) -> Unit) {
    FilterSection(title = "Статус") {
        TvChip(
            label = "Только завершённые",
            selected = onlyFinished == true,
            onClick = { onToggle(if (onlyFinished == true) null else true) },
        )
    }
}

@Composable
private fun FilterDialogButtons(actions: FilterDialogActions) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvButton(text = "Применить", onClick = actions.onApply, primary = true)
        TvButton(text = "Сбросить", onClick = actions.onReset, primary = false)
    }
}

private data class YearBucket(val label: String, val yearFrom: Int?, val yearTo: Int?) {
    fun matches(filters: CatalogFilters): Boolean =
        filters.yearFrom == yearFrom && filters.yearTo == yearTo
}

private val YearBuckets = listOf(
    YearBucket(label = "Любой", yearFrom = null, yearTo = null),
    YearBucket(label = "2020-е", yearFrom = 2020, yearTo = 2029),
    YearBucket(label = "2010-е", yearFrom = 2010, yearTo = 2019),
    YearBucket(label = "2000-е", yearFrom = 2000, yearTo = 2009),
    YearBucket(label = "1990-е", yearFrom = 1990, yearTo = 1999),
    YearBucket(label = "1980-е", yearFrom = 1980, yearTo = 1989),
    YearBucket(label = "до 1980", yearFrom = null, yearTo = 1979),
)

private val RatingThresholds: List<Pair<String, Int?>> = listOf(
    "Любой" to null,
    "5+" to 5,
    "6+" to 6,
    "7+" to 7,
    "8+" to 8,
    "9+" to 9,
)

private const val PANEL_WIDTH_FRACTION = 0.7f

private val PanelMaxWidth = 900.dp
private val PanelMaxHeight = 560.dp
private val SectionGap = 22.dp
private val SectionTitleGap = 10.dp
private val ChipGap = 10.dp
