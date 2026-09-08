@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil

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
private val breakSeries = Color(0xFF718A97)
private fun DemoSession.localDate(): LocalDate = LocalDate.parse(date, recordDateFormat)

@Composable
fun RecordsScreen(
    sessions: List<DemoSession>, onDetail: (Int) -> Unit, onAdd: () -> Unit, onSearch: () -> Unit
) {
    val ordered = sessions.sortedWith(compareByDescending<DemoSession> { it.localDate() }.thenByDescending { it.id })
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenHeader("Your work, in context", "Review a session before planning the next shift.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onAdd,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            null,
                            Modifier.size(18.dp)
                        ); Spacer(Modifier.width(6.dp)); Text("Add record")
                    }
                    OutlinedButton(
                        onClick = onSearch,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            null,
                            Modifier.size(18.dp)
                        ); Spacer(Modifier.width(6.dp)); Text("Search")
                    }
                }
                DemoNote("Fictional records · changes last for this demo")
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${sessions.size} sessions", style = MaterialTheme.typography.titleMedium)
                    Text("Newest first", style = MaterialTheme.typography.bodySmall, color = Muted)
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
private fun SessionCard(session: DemoSession, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(
                    icon = when (session.title) {
                        "Mowing" -> Icons.Outlined.Agriculture
                        "Pruning" -> Icons.Outlined.ContentCut
                        else -> Icons.Outlined.Yard
                    }, size = 36.dp
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(session.title, style = MaterialTheme.typography.titleMedium)
                    Text("${session.site} · ${session.date}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Icon(Icons.Outlined.ChevronRight, "Open session", Modifier.size(20.dp), tint = Muted)
            }
            HorizontalDivider(color = Line)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabelValue("Outdoor work", "${session.outdoorMinutes} min", Modifier.weight(1f))
                LabelValue("Logged breaks", "${session.breakMinutes} min", Modifier.weight(1f))
                Tag(session.status, caution = session.status == "Needs review")
            }
        }
    }
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
    val hasFilters =
        query.isNotBlank() || workType != "All work" || status != "All statuses" || selectedDate != null || sort != "Newest first"

    fun reset() {
        query = ""; workType = "All work"; status = "All statuses"; sort = "Newest first"; selectedDate = null
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenHeader("Find a past session", "Combine filters to see what needs a closer look.")
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Work type or site") },
                    placeholder = { Text("e.g. Birrarung Marr") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = {
                            query = ""
                        }) { Icon(Icons.Outlined.Close, "Clear search") }
                    }) else null,
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 340.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChoiceField("Work type", workType, listOf("All work") + taskTypes, { workType = it })
                            ChoiceField(
                                "Follow-up",
                                status,
                                listOf("All statuses", "Needs review", "Reviewed"),
                                { status = it })
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoiceField(
                            "Work type",
                            workType,
                            listOf("All work") + taskTypes,
                            { workType = it },
                            Modifier.weight(1f)
                        )
                        ChoiceField(
                            "Follow-up",
                            status,
                            listOf("All statuses", "Needs review", "Reviewed"),
                            { status = it },
                            Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showDate = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(selectedDate ?: "Any date")
                    }
                    if (selectedDate != null) IconButton(
                        onClick = { selectedDate = null },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Outlined.Close, "Clear date filter") }
                }
                ChoiceField("Sort results", sort, listOf("Newest first", "Longest outdoors"), { sort = it })
                SectionHeading(
                    "${filtered.size} ${if (filtered.size == 1) "result" else "results"}",
                    subtitle = if (hasFilters) "All selected criteria apply together" else "All saved demonstration sessions",
                    action = "Reset", onAction = { reset() })
            }
        }
        if (filtered.isEmpty()) item {
            EmptyRecords("No matching sessions", "Try another work type or date, or reset the filters.")
        }
        items(filtered, key = { it.id }) { session -> SessionCard(session) { onDetail(session.id) } }
        item { DemoNote("Searches fictional session records") }
    }
    if (showDate) RecordDateDialog(
        selectedDate ?: sessions.firstOrNull()?.date ?: "12 Jan 2026",
        onDismiss = { showDate = false }) {
        selectedDate = it; showDate = false
    }
}

@Composable
fun SessionDetailScreen(session: DemoSession, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    PageColumn {
        ScreenHeader(session.title, "${session.site} · ${session.date}")
        SurfaceCard {
            Tag(session.status, session.status == "Needs review")
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RecordMetric("Outdoor work", "${session.outdoorMinutes} min", Modifier.weight(1f))
                RecordMetric("Logged breaks", "${session.breakMinutes} min", Modifier.weight(1f))
            }
        }
        session.checkInNote?.let { note ->
            SurfaceCard(tint = Mint) {
                IconLine(
                    Icons.Outlined.CheckCircle,
                    "Latest check-in",
                    note
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeading("Session summary", "Totals describe the log, not physiological recovery.")
            Text(
                "Break timing and location were not captured.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        SurfaceCard(tint = if (session.status == "Needs review") AmberPale else Color.White) {
            Text(
                if (session.status == "Needs review") "A useful follow-up" else "Review recorded",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (session.status == "Needs review") "Check whether the work and break log is complete before using it to plan a similar shift. Discuss needed changes with your supervisor."
                else "This session is marked reviewed. The label records a review, not a safety assessment.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeading("Record origin")
            Text(
                "Fictional demonstration record. Times are entered totals, not sensor measurements. No detailed event timestamps are available.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
        PrimaryButton("Edit record", onClick = onEdit)
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                null,
                Modifier.size(18.dp)
            ); Spacer(Modifier.width(8.dp)); Text("Delete record")
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, icon = { Icon(Icons.Outlined.DeleteOutline, null) },
        title = { Text("Delete this record?") },
        text = { Text("Remove ${session.title.lowercase()} at ${session.site} on ${session.date} from this demo's records and trends?") },
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
    PageColumn {
        ScreenHeader(
            if (session == null) "Record a work session" else "Keep the record accurate",
            "Required fields are labelled. Enter minutes, not clock times."
        )
        SurfaceCard {
            Text("Work details", style = MaterialTheme.typography.titleMedium)
            ChoiceField("Work type · required", workType, taskTypes, { workType = it })
            ChoiceField("Work site · required", site, listOf("Birrarung Marr", "Carlton Gardens"), { site = it })
            OutlinedButton(
                onClick = { showDate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text("Work date · required", style = MaterialTheme.typography.bodySmall)
                    Text(date, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
        SurfaceCard {
            Text("Logged time", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = outdoor, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) outdoor = it },
                label = { Text("Outdoor work · required") }, suffix = { Text("min") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                isError = attempted && outdoorError,
                supportingText = { Text(if (attempted && outdoorError) "Enter 1–960 minutes of outdoor work." else "Exclude the breaks entered below.") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                value = breaks, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) breaks = it },
                label = { Text("Logged breaks · required") }, suffix = { Text("min") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                isError = attempted && breakError,
                supportingText = { Text(if (attempted && breakError) "Enter 0–480 minutes. Leave no field blank." else "Use 0 only if you can confirm no break was logged.") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
            if (attempted && combinedError) Text(
                "The combined time cannot exceed one day.",
                color = ErrorRed,
                style = MaterialTheme.typography.bodySmall
            )
        }
        ChoiceField("Follow-up status", status, listOf("Needs review", "Reviewed"), { status = it })
        Text(
            "Reviewed means you checked this record. It does not certify that the shift was safe.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        PrimaryButton(if (session == null) "Add record" else "Save changes") {
            attempted = true
            if (!outdoorError && !breakError && !combinedError) onSave(
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
        DemoNote("Saved in this demo only · no account upload")
    }
    if (showDate) RecordDateDialog(date, onDismiss = { showDate = false }) { date = it; showDate = false }
}

@Composable
private fun RecordDateDialog(date: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.parse(date, recordDateFormat).atStartOfDay(ZoneOffset.UTC).toInstant()
            .toEpochMilli()
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
        ScreenHeader("Look back. Plan ahead.", "Compare logged outdoor work and breaks across shifts.")
        SurfaceCard {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RecordMetric("outdoor minutes", "$outdoor", Modifier.weight(1f))
                RecordMetric("break minutes", "$breaks", Modifier.weight(1f), valueColor = breakSeries)
            }
            Text(
                "${filtered.size} recorded sessions · $workType",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        ChoiceField("Work type", workType, listOf("All work") + taskTypes, { workType = it; selectedIndex = 0 })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Day", "Week").forEach { option ->
                FilterChip(
                    selected = period == option, onClick = { period = option; selectedIndex = 0 },
                    label = { Text("By ${option.lowercase()}") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Mint,
                        selectedLabelColor = Pine,
                        selectedLeadingIconColor = Pine
                    ),
                    leadingIcon = if (period == option) ({
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            Modifier.size(18.dp)
                        )
                    }) else null
                )
            }
        }
        if (groups.isEmpty()) {
            EmptyRecords("No sessions in this view", "Choose another work type or add a work record.")
        } else {
            SurfaceCard {
                SectionHeading(
                    "Logged minutes by ${period.lowercase()}",
                    if (period == "Week") "Weeks begin Monday. Both series use the same scale." else "Both series use the same minutes scale."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ChartKey("Outdoor work", Pine)
                    ChartKey("Breaks", breakSeries)
                }
                MinutesChart(groups, selected)
                Text(
                    "Select a ${period.lowercase()} to inspect its totals",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEachIndexed { index, group ->
                        FilterChip(
                            selected = selected == group,
                            onClick = { selectedIndex = index },
                            label = { Text(group.date.format(shortDateFormat)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Mint,
                                selectedLabelColor = Pine
                            )
                        )
                    }
                }
                if (selected != null) {
                    HorizontalDivider(color = Line)
                    Text(
                        if (period == "Week") "Week of ${selected.date.format(shortDateFormat)}" else selected.date.format(
                            recordDateFormat
                        ), style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabelValue("Outdoor work", "${selected.outdoor} min", Modifier.weight(1f))
                        LabelValue("Logged breaks", "${selected.breaks} min", Modifier.weight(1f))
                    }
                    Text(
                        "${selected.count} ${if (selected.count == 1) "session" else "sessions"} recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconLine(
                Icons.Outlined.Info,
                "Use the log as a conversation starter",
                "Compare similar tasks before discussing the next shift with your supervisor."
            )
            Text(
                "Totals do not show uninterrupted work or prove recovery. A missing record is not evidence that no break occurred.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        DemoNote("Fictional records · totals update with your edits")
    }
}

@Composable
private fun RecordMetric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Pine) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun ChartKey(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.size(10.dp), color = colour, shape = RoundedCornerShape(2.dp)) {}
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun MinutesChart(groups: List<MinuteGroup>, selected: MinuteGroup?) {
    val maximum = (ceil(groups.maxOf { maxOf(it.outdoor, it.breaks) } / 50.0) * 50).toInt().coerceAtLeast(50)
    val chartDescription =
        groups.joinToString(". ") { "${it.date.format(shortDateFormat)}: ${it.outdoor} outdoor minutes, ${it.breaks} break minutes" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.height(164.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("$maximum", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text("${maximum / 2}", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text("0", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val plotWidth = maxOf(maxWidth, 44.dp * groups.size)
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(plotWidth)) {
                        Canvas(
                            Modifier.fillMaxWidth().height(164.dp)
                                .semantics { contentDescription = chartDescription }) {
                            val slot = size.width / groups.size
                            val selectedIndex = groups.indexOf(selected)
                            if (selectedIndex >= 0) drawRoundRect(
                                Mint, Offset(slot * selectedIndex + 2.dp.toPx(), 0f),
                                Size(slot - 4.dp.toPx(), size.height), CornerRadius(6.dp.toPx())
                            )
                            listOf(0f, 0.5f, 1f).forEach { fraction ->
                                val y = size.height * fraction
                                drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                            val width = (slot * .28f).coerceAtMost(28.dp.toPx())
                            groups.forEachIndexed { index, group ->
                                val centre = slot * (index + .5f)
                                val outdoorHeight = size.height * group.outdoor / maximum
                                val breakHeight = size.height * group.breaks / maximum
                                if (outdoorHeight > 0) drawRoundRect(
                                    Pine,
                                    Offset(centre - width - 2.dp.toPx(), size.height - outdoorHeight),
                                    Size(width, outdoorHeight),
                                    CornerRadius(3.dp.toPx())
                                )
                                if (breakHeight > 0) drawRoundRect(
                                    breakSeries,
                                    Offset(centre + 2.dp.toPx(), size.height - breakHeight),
                                    Size(width, breakHeight),
                                    CornerRadius(3.dp.toPx())
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            groups.forEach { group ->
                                Text(
                                    group.date.format(shortDateFormat),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (group == selected) Pine else Muted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
                val rangeLabel =
                    if (groups.first().date == groups.last().date) groups.first().date.format(recordDateFormat)
                    else "${groups.first().date.format(shortDateFormat)} – ${groups.last().date.format(recordDateFormat)}"
                Text(
                    rangeLabel,
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
    }
}

@Composable
private fun EmptyRecords(title: String, description: String) {
    SurfaceCard {
        IconBadge(Icons.Outlined.SearchOff, tint = Muted, container = Paper)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
