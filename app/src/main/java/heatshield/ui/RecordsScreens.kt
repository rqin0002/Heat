@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package heatshield.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.MelbourneZone
import heatshield.data.ShiftEvent
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.domain.HeatLevel
import heatshield.domain.ShiftRules
import heatshield.domain.WorkTasks
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

private val recordDateFormat = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
private val shortDateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
// The same work list as setup, Profile and Plan. Filters add any older name still in the log ("Planting beds").
private val taskTypes = WorkTasks.all.map { it.name }
// Every stored status (ShiftStatus.all), in the order the Follow-up filter lists them. Screens show statusLabel(), never these.
private val statusOptions = listOf(ShiftStatus.TO_RAISE, ShiftStatus.NONE, ShiftStatus.RAISED)
private fun WorkSession.localDate(): LocalDate = LocalDate.parse(date, recordDateFormat)
private fun displayDate(stored: String): String = Formats.fullDate(LocalDate.parse(stored, recordDateFormat))

/** What a stored status means to the gardener: something to raise with the team leader, already raised, or nothing. */
@Composable
private fun statusLabel(status: String): String = stringResource(when (status) {
    ShiftStatus.TO_RAISE -> R.string.log_status_to_raise
    ShiftStatus.RAISED -> R.string.log_status_raised
    else -> R.string.log_status_nothing
})

/** Each stored status with its label, so a picker can show the label and map the choice back. */
@Composable
private fun statusLabels(): Map<String, String> = statusOptions.associateWith { statusLabel(it) }

private enum class RecordOrder(@param:StringRes val label: Int) {
    NEWEST(R.string.log_sort_newest), LONGEST(R.string.log_sort_longest)
}

private enum class TrendPeriod(@param:StringRes val chip: Int, @param:StringRes val heading: Int) {
    DAY(R.string.trends_by_day, R.string.trends_minutes_by_day),
    WEEK(R.string.trends_by_week, R.string.trends_minutes_by_week)
}

@Composable
private fun RecordList(content: LazyListScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val margin = if (maxWidth >= 600.dp) 32.dp else 24.dp
        LazyColumn(
            Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("page_content"),
            contentPadding = PaddingValues(margin, 16.dp, margin, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

@Composable
fun RecordsScreen(
    sessions: List<WorkSession>, onDetail: (Int) -> Unit, onAdd: () -> Unit, onSearch: () -> Unit
) {
    val ordered = sessions.sortedWith(compareByDescending<WorkSession> { it.localDate() }.thenByDescending { it.id })
    val largeText = LocalDensity.current.fontScale > 1.3f
    RecordList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (largeText) {
                    RecordCount(sessions.size)
                    PrimaryButton(stringResource(R.string.log_add_past), onClick = onAdd)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordCount(sessions.size, Modifier.weight(1f))
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.heightIn(min = 56.dp),
                        shape = UiShape.control
                    ) { Text(stringResource(R.string.log_add_past)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.log_sort_newest),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                    TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.log_search))
                    }
                }
            }
        }
        if (ordered.isEmpty()) item {
            EmptyRecords(stringResource(R.string.log_empty_title), stringResource(R.string.log_empty_body))
        }
        items(ordered, key = { it.id }) { session -> SessionCard(session) { onDetail(session.id) } }
    }
}

@Composable
private fun RecordCount(count: Int, modifier: Modifier = Modifier) {
    Text(
        pluralStringResource(R.plurals.log_shift_count, count, count),
        modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun SessionCard(session: WorkSession, onClick: () -> Unit) {
    val use24Hour = rememberUse24Hour()
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(workTaskName(session.title), style = MaterialTheme.typography.titleMedium)
            RecordCaption(stringResource(R.string.log_place_date, session.site, displayDate(session.date)))
            Text(sessionLine(session, use24Hour), style = MaterialTheme.typography.bodyLarge)
            ShiftChips(session)
        }
    }
}

/** "7 am – 3 pm · 7 h worked · 1 h breaks", or the minutes a legacy row was typed with. */
@Composable
internal fun sessionLine(session: WorkSession, use24Hour: Boolean): String {
    val start = session.startedAt?.let(ShiftRules::localTime)
    val end = session.endedAt?.let(ShiftRules::localTime)
    return if (start != null && end != null) stringResource(R.string.log_times_line, Formats.time(start.toLocalTime(), use24Hour),
        Formats.time(end.toLocalTime(), use24Hour), Formats.duration(session.outdoorMinutes), Formats.duration(session.breakMinutes))
    else stringResource(R.string.log_minutes_line, Formats.duration(session.outdoorMinutes), Formats.duration(session.breakMinutes))
}

/**
 * Where the times came from and whether there is something to raise. Nothing is shown for an ordinary tracked shift
 * with nothing to raise. The chips wrap onto a new line rather than squeeze, at any font size.
 */
@Composable
private fun ShiftChips(session: WorkSession) {
    // Each chip's text, and whether it is the follow-up status (drawn in the attention colours).
    val chips = listOfNotNull(
        (stringResource(R.string.log_chip_preview) to false).takeIf { session.source == ShiftSource.PREVIEW },
        (stringResource(R.string.log_chip_manual) to false).takeIf { session.source == ShiftSource.MANUAL },
        (stringResource(R.string.log_chip_auto) to false).takeIf { session.autoEnded },
        (statusLabel(session.status) to true).takeIf { session.status != ShiftStatus.NONE }
    )
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { (chip, raise) ->
            Surface(shape = UiShape.small, color = if (raise) AttentionSurface else MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Line)) {
                Text(chip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (raise) Attention else Muted, fontWeight = if (raise) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
fun SearchScreen(sessions: List<WorkSession>, onDetail: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var workType by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(RecordOrder.NEWEST) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    val labels = statusLabels()
    val workOptions = (taskTypes + sessions.map { it.title }).distinct()
    val workLabels = workOptions.associateWith { workTaskName(it) }
    val selectedWorkLabel = workType?.let { workTaskName(it) }
    val filtered = sessions.filter {
        (query.isBlank() || "${it.title} ${it.site} ${it.note.orEmpty()}".contains(query.trim(), ignoreCase = true) ||
            "${workLabels[it.title]} ${it.site} ${it.note.orEmpty()}".contains(query.trim(), ignoreCase = true)) &&
            (workType == null || it.title == workType) &&
            (status == null || it.status == status) &&
            (selectedDate == null || it.date == selectedDate)
    }.let { found ->
        if (order == RecordOrder.LONGEST) {
            found.sortedWith(compareByDescending<WorkSession> { it.outdoorMinutes }.thenByDescending { it.localDate() })
        } else found.sortedWith(compareByDescending<WorkSession> { it.localDate() }.thenByDescending { it.id })
    }
    val activeFilters = listOfNotNull(selectedWorkLabel, status?.let { statusLabel(it) }, selectedDate?.let(::displayDate))
    val hasFilters = query.isNotBlank() || activeFilters.isNotEmpty() || order != RecordOrder.NEWEST
    val largeText = LocalDensity.current.fontScale > 1.3f
    val allWork = stringResource(R.string.log_filter_all_work)
    val allStatuses = stringResource(R.string.log_filter_all_statuses)
    fun reset() {
        query = ""; workType = null; status = null; order = RecordOrder.NEWEST; selectedDate = null
    }
    RecordList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.log_search_label)) },
                    placeholder = { Text(stringResource(R.string.log_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.log_search_clear))
                        }
                    }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("search_query"),
                    singleLine = true,
                    shape = UiShape.control
                )
                Disclosure(
                    if (activeFilters.isEmpty()) stringResource(R.string.log_filters)
                    else stringResource(R.string.log_filters_active, activeFilters.size)
                ) {
                    ChoiceField(stringResource(R.string.log_filter_work_type), selectedWorkLabel ?: allWork,
                        listOf(allWork) + workLabels.values,
                        { picked -> workType = workLabels.entries.firstOrNull { it.value == picked }?.key })
                    // Shows "To raise" / "Nothing to raise" / "Raised" and maps the choice back to the stored value.
                    ChoiceField(stringResource(R.string.log_follow_up), status?.let { statusLabel(it) } ?: allStatuses,
                        listOf(allStatuses) + labels.values,
                        { picked -> status = labels.entries.firstOrNull { it.value == picked }?.key })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showDate = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = UiShape.control
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(selectedDate?.let(::displayDate) ?: stringResource(R.string.log_filter_any_date))
                        }
                        if (selectedDate != null) IconButton(onClick = { selectedDate = null }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.log_filter_clear_date))
                        }
                    }
                }
                if (activeFilters.isNotEmpty()) RecordCaption(activeFilters.joinToString(" · "))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.log_result_count, filtered.size, filtered.size),
                        Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (hasFilters) TextButton(onClick = { reset() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.log_reset))
                    }
                    if (!largeText) RecordSort(order) { order = it }
                }
                if (largeText) RecordSort(order) { order = it }
            }
        }
        if (filtered.isEmpty()) item {
            EmptyRecords(stringResource(R.string.log_no_match_title), stringResource(R.string.log_no_match_body))
        }
        items(filtered, key = { it.id }) { session -> SessionCard(session) { onDetail(session.id) } }
    }
    if (showDate) RecordDateDialog(
        selectedDate ?: sessions.firstOrNull()?.date ?: LocalDate.now(MelbourneZone).format(recordDateFormat),
        onDismiss = { showDate = false }
    ) { selectedDate = it; showDate = false }
}

@Composable
private fun RecordSort(order: RecordOrder, onSort: (RecordOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(order.label)
    val description = stringResource(R.string.log_sort_description, label)
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }
        ) {
            Text(label); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecordOrder.entries.forEach { option ->
                DropdownMenuItem(text = { Text(stringResource(option.label)) }, onClick = { onSort(option); expanded = false })
            }
        }
    }
}

/**
 * Route `detail/{id}` (parent Log): one shift with its times, totals, peak heat and what happened, then Fix times.
 * [events] come from Room with the row; a legacy typed row has none and shows its typed minutes.
 */
@Composable
fun SessionDetailScreen(session: WorkSession, events: List<ShiftEvent>, onFixTimes: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val use24Hour = rememberUse24Hour()
    val date = displayDate(session.date)
    PageColumn {
        ScreenHeader(workTaskName(session.title), stringResource(R.string.log_place_date, session.site, date))
        Text(sessionLine(session, use24Hour), style = MaterialTheme.typography.bodyLarge)
        if (session.autoEnded) session.endedAt?.let {
            NoticeText(stringResource(R.string.shift_auto_ended, Formats.time(ShiftRules.localTime(it).toLocalTime(), use24Hour)),
                style = MaterialTheme.typography.bodyMedium)
        }
        ShiftChips(session)
        FormSection(stringResource(R.string.log_follow_up)) {
            Text(statusLabel(session.status), style = MaterialTheme.typography.bodyLarge, color = Ink)
            if (session.status == ShiftStatus.TO_RAISE) Text(stringResource(R.string.log_raise_hint), style = MaterialTheme.typography.bodyLarge)
        }
        RecordMetricPair(stringResource(R.string.log_worked), Formats.duration(session.outdoorMinutes),
            stringResource(R.string.log_breaks), Formats.duration(session.breakMinutes))
        val peakLevel = session.peakLevel?.let { name -> HeatLevel.entries.firstOrNull { it.name == name } }
        if (peakLevel != null && session.peakHeat != null) {
            Text(stringResource(R.string.log_peak, peakLevel.word, Formats.temperature(session.peakHeat)), style = MaterialTheme.typography.bodyLarge)
        }
        if (events.isNotEmpty()) FormSection(stringResource(R.string.summary_events)) { events.forEach { EventLine(it, use24Hour) } }
        session.note?.let { FormSection(stringResource(R.string.log_note)) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        session.checkInNote?.let { note ->
            FormSection(stringResource(R.string.log_latest_note)) {
                Text(note, style = MaterialTheme.typography.bodyLarge, color = if (session.status == ShiftStatus.TO_RAISE) Attention else Ink)
            }
        }
        PrimaryButton(stringResource(R.string.log_fix_times), Modifier.testTag("detail_fix"), onClick = onFixTimes)
        // One full-width action per screen, so delete is a red text button sized to its label.
        TextButton(
            onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
        ) { Text(stringResource(R.string.log_delete)) }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.log_delete_title)) },
        text = { Text(stringResource(R.string.log_delete_body, workTaskName(session.title).lowercase(), session.site, date)) },
        confirmButton = {
            Button(
                onClick = { confirmDelete = false; onDelete() },
                modifier = Modifier.heightIn(min = 56.dp), shape = UiShape.control,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) { Text(stringResource(R.string.log_delete)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { confirmDelete = false },
                modifier = Modifier.heightIn(min = 56.dp), shape = UiShape.control
            ) { Text(stringResource(R.string.log_keep)) }
        }
    )
}

@Composable
private fun RecordDateDialog(date: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val density = LocalDensity.current
    val compactDialog = density.fontScale > 1.3f || LocalWindowInfo.current.containerSize.height / density.density < 500
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.parse(date, recordDateFormat).atStartOfDay(ZoneOffset.UTC).toInstant()
            .toEpochMilli(),
        initialDisplayMode = if (compactDialog) DisplayMode.Input else DisplayMode.Picker
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = dateState.selectedDateMillis != null, onClick = {
                dateState.selectedDateMillis?.let {
                    onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(recordDateFormat))
                }
            }) { Text(stringResource(R.string.log_use_date)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.log_cancel)) } }
    ) { DatePicker(state = dateState) }
}

private data class MinuteGroup(val date: LocalDate, val outdoor: Int, val breaks: Int, val count: Int)

@Composable
fun TrendsScreen(sessions: List<WorkSession>) {
    var period by rememberSaveable { mutableStateOf(TrendPeriod.DAY) }
    var workType by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val allWork = stringResource(R.string.log_filter_all_work)
    val workLabels = (taskTypes + sessions.map { it.title }).distinct().associateWith { workTaskName(it) }
    val selectedWorkLabel = workType?.let { workTaskName(it) }
    // Preview shifts are a demonstration, not work: they never count towards a total.
    val filtered = sessions.filter { it.source != ShiftSource.PREVIEW && (workType == null || it.title == workType) }
    val groups = filtered.groupBy {
        if (period == TrendPeriod.WEEK) it.localDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else it.localDate()
    }.toSortedMap().map { (date, list) ->
        MinuteGroup(date, list.sumOf { it.outdoorMinutes }, list.sumOf { it.breakMinutes }, list.size)
    }
    val selected = groups.getOrNull(selectedIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    val outdoor = filtered.sumOf { it.outdoorMinutes }
    val breaks = filtered.sumOf { it.breakMinutes }
    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordCaption(pluralStringResource(R.plurals.trends_shifts_in_view, filtered.size, filtered.size,
                selectedWorkLabel ?: allWork))
            // Raw minutes, the F6 chart's own scale.
            RecordMetricPair(
                stringResource(R.string.trends_outdoor_minutes), "$outdoor",
                stringResource(R.string.trends_break_minutes), "$breaks"
            )
        }
        ChoiceField(stringResource(R.string.log_filter_work_type), selectedWorkLabel ?: allWork,
            listOf(allWork) + workLabels.values,
            { picked -> workType = workLabels.entries.firstOrNull { it.value == picked }?.key; selectedIndex = 0 })
        PeriodSelector(period) { period = it; selectedIndex = 0 }
        if (groups.isEmpty()) {
            EmptyRecords(stringResource(R.string.trends_empty_title), stringResource(R.string.trends_empty_body))
        } else {
            FormSection(stringResource(period.heading)) {
                ChartLegend()
                MinutesChart(groups, selected) { selectedIndex = it }
                if (selected != null) Column(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(periodLabel(selected.date, period, withYear = false), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.trends_group_minutes, selected.outdoor, selected.breaks),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    RecordCaption(pluralStringResource(R.plurals.trends_shifts_logged, selected.count, selected.count))
                }
            }
            Disclosure(stringResource(R.string.trends_data_values)) {
                groups.forEach { group ->
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(periodLabel(group.date, period, withYear = true), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.trends_group_minutes, group.outdoor, group.breaks),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        Disclosure(stringResource(R.string.trends_how_title)) {
            Text(stringResource(R.string.trends_how_updates), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.trends_how_scale), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.trends_how_preview), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "Mon 21 Sep" beside the chart, "Mon 21 Sep 2026" in Data values, where the year can be ambiguous. */
@Composable
private fun periodLabel(date: LocalDate, period: TrendPeriod, withYear: Boolean): String {
    val day = if (withYear) Formats.fullDate(date) else Formats.dayDate(date)
    return if (period == TrendPeriod.WEEK) stringResource(R.string.trends_week_of, day) else day
}

@Composable
private fun PeriodSelector(period: TrendPeriod, onSelect: (TrendPeriod) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendPeriod.entries.forEach { option ->
            FilterChip(
                selected = period == option, onClick = { onSelect(option) },
                label = { Text(stringResource(option.chip)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SelectedSurface,
                    selectedLabelColor = HeatShieldTheme.colors.onActionContainer
                )
            )
        }
    }
}

/** Two big numbers side by side (stacked when narrow or at large font). Each value carries its own unit or label. */
@Composable
private fun RecordMetricPair(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || scale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecordMetric(firstLabel, firstValue)
            RecordMetric(secondLabel, secondValue)
        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RecordMetric(firstLabel, firstValue, Modifier.weight(1f))
            RecordMetric(secondLabel, secondValue, Modifier.weight(1f))
        }
    }
}

/** Shows a labelled shift total in Ink so the number does not suggest an action. */
@Composable
private fun RecordMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = Ink, fontWeight = FontWeight.SemiBold)
        RecordCaption(label)
    }
}

/** Labels the solid outdoor-work bars and outlined break bars so colour is not their only distinction. */
@Composable
private fun ChartLegend() {
    val scale = LocalDensity.current.fontScale
    val outdoor = stringResource(R.string.trends_legend_outdoor)
    val breaks = stringResource(R.string.trends_legend_breaks)
    if (scale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartKey(outdoor, Ink); ChartKey(breaks, SecondarySeries, outlined = true)
    } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ChartKey(outdoor, Ink); ChartKey(breaks, SecondarySeries, outlined = true)
    }
}

/** Matches a series label to its bar's colour and solid or outlined pattern. */
@Composable
private fun ChartKey(label: String, colour: Color, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.size(12.dp), color = if (outlined) Color.Transparent else colour,
            border = if (outlined) androidx.compose.foundation.BorderStroke(2.dp, colour) else null,
            shape = RoundedCornerShape(2.dp)) {}
        RecordCaption(label)
    }
}

/**
 * Compares outdoor work and breaks in minutes, with solid and outlined bars on one scale.
 * The scale rounds up to 50 minutes: a largest total of 480 minutes gives a 500-minute axis.
 * Date buttons select a group's labelled values; the bars themselves are not actions.
 */
@Composable
private fun MinutesChart(groups: List<MinuteGroup>, selectedGroup: MinuteGroup?, onSelect: (Int) -> Unit) {
    val maximum = (ceil(groups.maxOf { maxOf(it.outdoor, it.breaks) } / 50.0) * 50).toInt().coerceAtLeast(50)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val chartHeight = 160.dp * scale.coerceAtMost(1.5f)
    // Theme colours are read here because the Canvas draw lambda is not composable.
    val selectedFill = SelectedSurface
    val gridLine = Line
    val outdoorBar = Ink
    val breakBar = SecondarySeries
    val chartDescription = stringResource(R.string.trends_chart_description)
    val groupDescriptions = groups.map {
        stringResource(R.string.trends_chart_day, Formats.fullDate(it.date), it.outdoor, it.breaks)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
            RecordCaption("$maximum"); RecordCaption("${maximum / 2}"); RecordCaption("0")
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val plotWidth = maxOf(maxWidth, 56.dp * scale * groups.size)
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(plotWidth)) {
                Canvas(Modifier.fillMaxWidth().height(chartHeight).semantics { contentDescription = chartDescription }) {
                    val slot = size.width / groups.size
                    val selectedIndex = groups.indexOf(selectedGroup)
                    if (selectedIndex >= 0) drawRect(selectedFill, Offset(slot * selectedIndex, 0f), Size(slot, size.height))
                    listOf(0f, .5f, 1f).forEach { fraction ->
                        val y = size.height * fraction
                        drawLine(gridLine, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    val barWidth = (slot * .25f).coerceAtMost(28.dp.toPx())
                    groups.forEachIndexed { index, group ->
                        val centre = slot * (index + .5f)
                        val outdoorHeight = size.height * group.outdoor / maximum
                        val breakHeight = size.height * group.breaks / maximum
                        if (outdoorHeight > 0) drawRoundRect(
                            outdoorBar, Offset(centre - barWidth - 2.dp.toPx(), size.height - outdoorHeight),
                            Size(barWidth, outdoorHeight), CornerRadius(2.dp.toPx())
                        )
                        if (breakHeight > 0) drawRoundRect(
                            breakBar, Offset(centre + 2.dp.toPx(), size.height - breakHeight),
                            Size(barWidth, breakHeight), CornerRadius(2.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = minOf(2.dp.toPx(), barWidth, breakHeight))
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    groups.forEachIndexed { index, group ->
                        TextButton(
                            onClick = { onSelect(index) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                selected = group == selectedGroup
                                contentDescription = groupDescriptions[index]
                            },
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (group == selectedGroup) SelectedSurface else Color.Transparent,
                                contentColor = if (group == selectedGroup) HeatShieldTheme.colors.onActionContainer else Muted
                            )
                        ) {
                            Text(
                                group.date.format(shortDateFormat).replace(" ", "\n"),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
}

@Composable
private fun EmptyRecords(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = Muted)
    }
}
