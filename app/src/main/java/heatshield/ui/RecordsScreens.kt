@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package heatshield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.launch

data class DemoSession(
    val id: Int, val title: String, val site: String, val date: String,
    val outdoorMinutes: Int, val breakMinutes: Int, val status: String, val checkInNote: String? = null
)

val initialSessions = listOf(
    DemoSession(1, "Planting beds", "Birrarung Marr", "12 Jan 2026", 95, 15, "Needs review"),
    DemoSession(2, "Mowing", "Carlton Gardens", "11 Jan 2026", 120, 20, "Reviewed"),
    DemoSession(3, "Pruning", "Birrarung Marr", "10 Jan 2026", 80, 15, "Reviewed"),
    DemoSession(4, "Planting beds", "Birrarung Marr", "9 Jan 2026", 140, 10, "Needs review"),
    DemoSession(5, "Mowing", "Carlton Gardens", "8 Jan 2026", 105, 20, "Reviewed"),
    DemoSession(6, "Pruning", "Birrarung Marr", "7 Jan 2026", 70, 15, "Reviewed")
)

private val recordDateFormat = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
private val shortDateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val taskTypes = listOf("Planting beds", "Mowing", "Pruning")
private fun DemoSession.localDate(): LocalDate = LocalDate.parse(date, recordDateFormat)

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
    sessions: List<DemoSession>, onDetail: (Int) -> Unit, onAdd: () -> Unit, onSearch: () -> Unit
) {
    val ordered = sessions.sortedWith(compareByDescending<DemoSession> { it.localDate() }.thenByDescending { it.id })
    val largeText = LocalDensity.current.fontScale > 1.3f
    RecordList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (largeText) {
                    RecordCount(sessions.size)
                    PrimaryButton("Add record", onClick = onAdd)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordCount(sessions.size, Modifier.weight(1f))
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.heightIn(min = 52.dp),
                        shape = UiShape.control
                    ) { Text("Add record") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Newest first",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                    TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("Search")
                    }
                }
            }
        }
        if (ordered.isEmpty()) item {
            EmptyRecords("No work records yet", "Add a session to begin your work and break log.")
        }
        items(ordered, key = { it.id }) { session -> SessionCard(session) { onDetail(session.id) } }
    }
}

@Composable
private fun RecordCount(count: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$count sessions", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SessionCard(session: DemoSession, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            RecordCaption("${session.site} · ${session.date}")
            Text(
                "${session.outdoorMinutes} min outdoors · ${session.breakMinutes} min breaks",
                style = MaterialTheme.typography.bodyLarge
            )
            RecordStatus(session.status)
        }
    }
}

@Composable
private fun RecordStatus(status: String) {
    Text(
        status, style = MaterialTheme.typography.bodyMedium,
        color = if (status == "Needs review") Attention else Muted,
        fontWeight = if (status == "Needs review") FontWeight.SemiBold else FontWeight.Normal
    )
}

@Composable
fun SearchScreen(sessions: List<DemoSession>, onDetail: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var workType by rememberSaveable { mutableStateOf("All work") }
    var status by rememberSaveable { mutableStateOf("All statuses") }
    var sort by rememberSaveable { mutableStateOf("Newest first") }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    val filtered = sessions.filter {
        (query.isBlank() || "${it.title} ${it.site}".contains(query.trim(), ignoreCase = true)) &&
                (workType == "All work" || it.title == workType) &&
                (status == "All statuses" || it.status == status) &&
                (selectedDate == null || it.date == selectedDate)
    }.let { found ->
        if (sort == "Longest outdoors") found.sortedWith(compareByDescending<DemoSession> { it.outdoorMinutes }.thenByDescending { it.localDate() })
        else found.sortedWith(compareByDescending<DemoSession> { it.localDate() }.thenByDescending { it.id })
    }
    val activeFilters = listOfNotNull(
        workType.takeIf { it != "All work" }, status.takeIf { it != "All statuses" }, selectedDate
    )
    val hasFilters = query.isNotBlank() || activeFilters.isNotEmpty() || sort != "Newest first"
    val largeText = LocalDensity.current.fontScale > 1.3f
    fun reset() {
        query = ""; workType = "All work"; status = "All statuses"; sort = "Newest first"; selectedDate = null
    }
    RecordList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Work type or site") },
                    placeholder = { Text("e.g. Birrarung Marr") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                    }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("search_query"),
                    singleLine = true,
                    shape = UiShape.control
                )
                Disclosure(if (activeFilters.isEmpty()) "Filters" else "Filters (${activeFilters.size})") {
                    ChoiceField("Work type", workType, listOf("All work") + taskTypes, { workType = it })
                    ChoiceField(
                        "Follow-up",
                        status,
                        listOf("All statuses", "Needs review", "Reviewed"),
                        { status = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showDate = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = UiShape.control
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text(selectedDate ?: "Any date")
                        }
                        if (selectedDate != null) IconButton(onClick = { selectedDate = null }) {
                            Icon(Icons.Outlined.Close, "Clear date filter")
                        }
                    }
                }
                if (activeFilters.isNotEmpty()) RecordCaption(activeFilters.joinToString(" · "))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${filtered.size} ${if (filtered.size == 1) "result" else "results"}",
                        Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (hasFilters) TextButton(onClick = { reset() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            "Reset"
                        )
                    }
                    if (!largeText) RecordSort(sort) { sort = it }
                }
                if (largeText) RecordSort(sort) { sort = it }
            }
        }
        if (filtered.isEmpty()) item {
            EmptyRecords("No matching sessions", "Try another work type or date, or reset the filters.")
        }
        items(filtered, key = { it.id }) { session -> SessionCard(session) { onDetail(session.id) } }
    }
    if (showDate) RecordDateDialog(
        selectedDate ?: sessions.firstOrNull()?.date ?: "12 Jan 2026", onDismiss = { showDate = false }
    ) { selectedDate = it; showDate = false }
}

@Composable
private fun RecordSort(sort: String, onSort: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Sort results: $sort" }
        ) {
            Text(sort); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("Newest first", "Longest outdoors").forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSort(option); expanded = false })
            }
        }
    }
}

@Composable
fun SessionDetailScreen(session: DemoSession, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    PageColumn {
        ScreenHeader(session.title, "${session.site} · ${session.date}")
        RecordStatus(session.status)
        RecordMetricPair("Outdoor work", "${session.outdoorMinutes}", "Logged breaks", "${session.breakMinutes}")
        session.checkInNote?.let { note ->
            FormSection("Latest check-in") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (session.status == "Needs review") Attention else Ink
                )
            }
        }
        if (session.status == "Needs review") {
            Text(
                "Check this log is complete. Discuss any work or break adjustments with your supervisor.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        PrimaryButton("Edit record", onClick = onEdit)
        TextButton(
            onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
        ) { Text("Delete record") }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Delete this record?") },
        text = { Text("Remove ${session.title.lowercase()} at ${session.site} on ${session.date} from your records and trends?") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text(
                    "Delete record",
                    color = ErrorRed
                )
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep record") } }
    )
}

@Composable
fun EditRecordScreen(session: DemoSession?, onSave: (DemoSession) -> Unit) {
    var workType by rememberSaveable(session?.id) { mutableStateOf(session?.title ?: "Planting beds") }
    var site by rememberSaveable(session?.id) { mutableStateOf(session?.site ?: "Birrarung Marr") }
    var date by rememberSaveable(session?.id) { mutableStateOf(session?.date ?: "12 Jan 2026") }
    var outdoor by rememberSaveable(session?.id) { mutableStateOf(session?.outdoorMinutes?.toString() ?: "") }
    var breaks by rememberSaveable(session?.id) { mutableStateOf(session?.breakMinutes?.toString() ?: "") }
    var status by rememberSaveable(session?.id) { mutableStateOf(session?.status ?: "Needs review") }
    var attempted by rememberSaveable(session?.id) { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val outdoorValue = outdoor.toIntOrNull()
    val breakValue = breaks.toIntOrNull()
    val outdoorError = outdoorValue == null || outdoorValue !in 1..960
    val breakError = breakValue == null || breakValue !in 0..480
    val combinedError = !outdoorError && !breakError && outdoorValue!! + breakValue!! > 1440
    val outdoorFocus = remember { FocusRequester() }
    val breaksFocus = remember { FocusRequester() }
    val outdoorView = remember { BringIntoViewRequester() }
    val breaksView = remember { BringIntoViewRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun save() {
        attempted = true
        if (outdoorError || breakError || combinedError) {
            scope.launch {
                withFrameNanos { }
                (if (outdoorError) outdoorFocus else breaksFocus).requestFocus()
                withFrameNanos { }
                (if (outdoorError) outdoorView else breaksView).bringIntoView()
            }
        } else {
            focusManager.clearFocus()
            onSave(
                DemoSession(
                    session?.id ?: 0,
                    workType,
                    site,
                    date,
                    outdoorValue!!,
                    breakValue!!,
                    status,
                    session?.checkInNote
                )
            )
        }
    }
    PageColumn {
        FormSection("Work details") {
            ChoiceField("Work type · required", workType, taskTypes, { workType = it })
            ChoiceField("Work site · required", site, listOf("Birrarung Marr", "Carlton Gardens"), { site = it })
            OutlinedButton(
                onClick = { showDate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = UiShape.control
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    RecordCaption("Work date · required")
                    Text(date, style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(24.dp))
            }
        }
        FormSection("Logged time", "Enter minutes. Outdoor work excludes breaks.") {
            RecordMinuteField(
                "Outdoor work · required", outdoor, { outdoor = it }, "record_outdoor",
                attempted && outdoorError, "Enter 1–960 minutes of outdoor work.", outdoorFocus, outdoorView,
                ImeAction.Next, { breaksFocus.requestFocus() }
            )
            RecordMinuteField(
                "Logged breaks · required", breaks, { breaks = it }, "record_breaks",
                attempted && breakError, "Enter 0–480 minutes. Leave no field blank.", breaksFocus, breaksView,
                ImeAction.Done, { save() }
            )
            if (attempted && combinedError) Text(
                "The combined time cannot exceed one day.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = ErrorRed, style = MaterialTheme.typography.bodyMedium
            )
        }
        ChoiceField("Follow-up status", status, listOf("Needs review", "Reviewed"), { status = it })
        RecordCaption("Reviewed records are checked logs, not safety assessments.")
        PrimaryButton(if (session == null) "Add record" else "Save changes", onClick = { save() })
    }
    if (showDate) RecordDateDialog(date, onDismiss = { showDate = false }) { date = it; showDate = false }
}

@Composable
private fun RecordMinuteField(
    label: String, value: String, onChange: (String) -> Unit, tag: String,
    invalid: Boolean, errorMessage: String, focus: FocusRequester, view: BringIntoViewRequester,
    imeAction: ImeAction, onIme: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        suffix = { Text("min") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        isError = invalid,
        shape = UiShape.control,
        supportingText = if (invalid) ({
            Text(
                errorMessage,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium
            )
        }) else null,
        modifier = Modifier.fillMaxWidth().testTag(tag).focusRequester(focus).bringIntoViewRequester(view)
            .semantics { if (invalid) error(errorMessage) }
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
                    onSelect(
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(recordDateFormat)
                    )
                }
            }) { Text("Use date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = dateState) }
}

private data class MinuteGroup(val date: LocalDate, val outdoor: Int, val breaks: Int, val count: Int)

@Composable
fun TrendsScreen(sessions: List<DemoSession>) {
    var period by rememberSaveable { mutableStateOf("Day") }
    var workType by rememberSaveable { mutableStateOf("All work") }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val filtered = sessions.filter { workType == "All work" || it.title == workType }
    val groups = filtered.groupBy {
        if (period == "Week") it.localDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else it.localDate()
    }.toSortedMap().map { (date, list) ->
        MinuteGroup(
            date,
            list.sumOf { it.outdoorMinutes },
            list.sumOf { it.breakMinutes },
            list.size
        )
    }
    val selected = groups.getOrNull(selectedIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    val outdoor = filtered.sumOf { it.outdoorMinutes }
    val breaks = filtered.sumOf { it.breakMinutes }
    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordCaption("${filtered.size} recorded sessions · $workType")
            RecordMetricPair("outdoor minutes", "$outdoor", "break minutes", "$breaks", showUnit = false)
        }
        ChoiceField("Work type", workType, listOf("All work") + taskTypes, { workType = it; selectedIndex = 0 })
        PeriodSelector(period) { period = it; selectedIndex = 0 }
        if (groups.isEmpty()) {
            EmptyRecords("No sessions in this view", "Choose another work type or add a work record.")
        } else {
            FormSection("Logged minutes by ${period.lowercase()}") {
                ChartLegend()
                MinutesChart(groups, selected) { selectedIndex = it }
                if (selected != null) Column(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (period == "Week") "Week of ${selected.date.format(shortDateFormat)}" else selected.date.format(
                            recordDateFormat
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Outdoor work ${selected.outdoor} min · Logged breaks ${selected.breaks} min",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    RecordCaption("${selected.count} ${if (selected.count == 1) "session" else "sessions"} recorded")
                }
            }
            Disclosure("Data values") {
                groups.forEach { group ->
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            if (period == "Week") "Week of ${group.date.format(recordDateFormat)}" else group.date.format(
                                recordDateFormat
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Outdoor work ${group.outdoor} min · Logged breaks ${group.breaks} min",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        Disclosure("How totals are calculated") {
            Text("Totals update when records change. Weeks begin Monday.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "The two series share a minutes scale. Totals do not show uninterrupted work or prove recovery. A missing record does not mean no break occurred.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun PeriodSelector(period: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Day", "Week").forEach { option ->
            FilterChip(
                selected = period == option, onClick = { onSelect(option) },
                label = { Text("By ${option.lowercase()}") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SelectedSurface,
                    selectedLabelColor = PrimaryAction
                )
            )
        }
    }
}

@Composable
private fun RecordMetricPair(
    firstLabel: String, firstValue: String, secondLabel: String, secondValue: String, showUnit: Boolean = true
) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || scale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecordMetric(firstLabel, firstValue, showUnit = showUnit)
            RecordMetric(secondLabel, secondValue, valueColor = SecondarySeries, showUnit = showUnit)
        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RecordMetric(firstLabel, firstValue, Modifier.weight(1f), showUnit = showUnit)
            RecordMetric(secondLabel, secondValue, Modifier.weight(1f), SecondarySeries, showUnit)
        }
    }
}

@Composable
private fun RecordMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PrimaryAction,
    showUnit: Boolean = true
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = valueColor, fontWeight = FontWeight.SemiBold)
        RecordCaption(if (showUnit) "$label · min" else label)
    }
}

@Composable
private fun ChartLegend() {
    val scale = LocalDensity.current.fontScale
    if (scale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartKey("Outdoor work", PrimaryAction); ChartKey("Breaks", SecondarySeries)
    } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ChartKey("Outdoor work", PrimaryAction); ChartKey("Breaks", SecondarySeries)
    }
}

@Composable
private fun ChartKey(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.size(12.dp), color = colour, shape = RoundedCornerShape(2.dp)) {}
        RecordCaption(label)
    }
}

@Composable
private fun MinutesChart(groups: List<MinuteGroup>, selectedGroup: MinuteGroup?, onSelect: (Int) -> Unit) {
    val maximum = (ceil(groups.maxOf { maxOf(it.outdoor, it.breaks) } / 50.0) * 50).toInt().coerceAtLeast(50)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val chartHeight = 160.dp * scale.coerceAtMost(1.5f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
            RecordCaption("$maximum"); RecordCaption("${maximum / 2}"); RecordCaption("0")
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val plotWidth = maxOf(maxWidth, 56.dp * scale * groups.size)
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(plotWidth)) {
                Canvas(
                    Modifier.fillMaxWidth().height(chartHeight).semantics {
                        contentDescription =
                            "Outdoor work and breaks, in minutes. Select a date below or expand Data values."
                    }
                ) {
                    val slot = size.width / groups.size
                    val selectedIndex = groups.indexOf(selectedGroup)
                    if (selectedIndex >= 0) drawRect(
                        SelectedSurface,
                        Offset(slot * selectedIndex, 0f),
                        Size(slot, size.height)
                    )
                    listOf(0f, .5f, 1f).forEach { fraction ->
                        val y = size.height * fraction
                        drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    val barWidth = (slot * .25f).coerceAtMost(28.dp.toPx())
                    groups.forEachIndexed { index, group ->
                        val centre = slot * (index + .5f)
                        val outdoorHeight = size.height * group.outdoor / maximum
                        val breakHeight = size.height * group.breaks / maximum
                        if (outdoorHeight > 0) drawRoundRect(
                            PrimaryAction, Offset(centre - barWidth - 2.dp.toPx(), size.height - outdoorHeight),
                            Size(barWidth, outdoorHeight), CornerRadius(2.dp.toPx())
                        )
                        if (breakHeight > 0) drawRoundRect(
                            SecondarySeries, Offset(centre + 2.dp.toPx(), size.height - breakHeight),
                            Size(barWidth, breakHeight), CornerRadius(2.dp.toPx())
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
                                contentDescription =
                                    "${group.date.format(recordDateFormat)}, ${group.outdoor} outdoor minutes, ${group.breaks} break minutes"
                            },
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (group == selectedGroup) SelectedSurface else Color.Transparent,
                                contentColor = if (group == selectedGroup) PrimaryAction else Muted
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
