@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class WorkContext(
    val work: String = "Planting beds", val effort: String = "Heavy effort",
    val clothing: String = "Standard workwear", val dayMillis: Long = 1768176000000L,
    val startHour: Int = 7, val startMinute: Int = 0
)

@Composable
fun TodayScreen(model: PrototypeViewModel, onPlan: () -> Unit, onBreak: () -> Unit, onRecord: () -> Unit) {
    var stale by model::contextUnavailable
    val active = model.sessions.find { it.id == 1 }
    val pending = active != null && !stale && !model.checkInDone
    val largeText = LocalDensity.current.fontScale > 1.3f
    PageColumn(maxWidth = 840.dp) {
        ScreenHeader(active?.site ?: "Birrarung Marr", eyebrow = "Monday, 12 January")
        SurfaceCard {
            Text(
                when {
                    active == null -> "No active session"
                    stale -> "Conditions unavailable"
                    model.checkInDone -> "Check-in recorded"
                    model.checkInDeferred -> "Follow-up needed"
                    else -> "Check-in due"
                },
                Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.headlineSmall,
                color = if (pending || stale) Attention else Ink
            )
            Text(
                when {
                    active == null -> "Add a work session to keep a record of your shift."
                    stale -> "Refresh conditions before comparing work windows."
                    model.checkInDone -> "Your response is saved. Continue to follow your workplace heat procedure."
                    model.checkInDeferred -> "${model.pauseConstraint}. Discuss a lighter task or a cooler location."
                    else -> "${active.title} · ${active.outdoorMinutes} min outdoors"
                }, style = MaterialTheme.typography.bodyLarge
            )
            PrimaryButton(
                when {
                    active == null -> "Open work records"
                    stale -> "Retry forecast"
                    model.checkInDone -> "View session record"
                    else -> "Check in now"
                }
            ) {
                when {
                    active == null -> onRecord()
                    stale -> stale = false
                    model.checkInDone -> onRecord()
                    else -> onBreak()
                }
            }
        }
        FormSection("Conditions", if (stale) "Sample unavailable" else "Sample conditions · 11:30") {
            val metrics: @Composable () -> Unit = {
                Column(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(if (stale) "—" else "31°C", style = MaterialTheme.typography.displaySmall)
                    Text("Air temperature", style = MaterialTheme.typography.bodyLarge)
                    if (!stale) Text("48% humidity", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            val ultraviolet: @Composable () -> Unit = {
                Column(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(if (stale) "—" else "7", style = MaterialTheme.typography.displaySmall)
                    Text("UV index", style = MaterialTheme.typography.bodyLarge)
                    if (!stale) Text(
                        "High · sun protection",
                        style = MaterialTheme.typography.bodySmall,
                        color = Attention
                    )
                }
            }
            if (largeText) Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { metrics(); ultraviolet() }
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { metrics() }
                Box(Modifier.weight(1f)) { ultraviolet() }
            }
        }
        HorizontalDivider(color = Line)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Your shift", action = "Review", onAction = onPlan)
            Text(
                "%02d:%02d–15:00".format(model.preferredWorkContext.startHour, model.preferredWorkContext.startMinute),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${model.preferredWorkContext.work} · ${model.acceptedWindow}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Preferred window · discuss before changing work",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Text(active?.let { "${it.outdoorMinutes} min work · ${it.breakMinutes} min logged breaks" }
                ?: "No session recorded", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
fun ShiftPlanScreen(
    acceptedWindow: String,
    contextUnavailable: Boolean = false,
    initialContext: WorkContext = WorkContext(),
    comparing: Boolean,
    onStepChange: (Boolean) -> Unit,
    onReturnToToday: () -> Unit = {},
    onAccept: (String, WorkContext) -> Unit
) {
    var work by rememberSaveable { mutableStateOf(initialContext.work) }
    var effort by rememberSaveable { mutableStateOf(initialContext.effort) }
    var clothing by rememberSaveable { mutableStateOf(initialContext.clothing) }
    var selected by rememberSaveable { mutableStateOf(acceptedWindow) }
    var dayMillis by rememberSaveable { mutableLongStateOf(initialContext.dayMillis) }
    var startHour by rememberSaveable { mutableIntStateOf(initialContext.startHour) }
    var startMinute by rememberSaveable { mutableIntStateOf(initialContext.startMinute) }
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val dateLabel =
        Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    val earlyFits = startHour * 60 + startMinute <= 480
    val lateFits = startHour * 60 + startMinute <= 720
    val demoDate = Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() == "2026-01-12"
    val largeText = LocalDensity.current.fontScale > 1.3f
    val shortWindow = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density < 500
    val compactDialog = largeText || shortWindow
    val planScrollState = rememberScrollState()
    LaunchedEffect(comparing) { planScrollState.scrollTo(0) }
    BackHandler(enabled = comparing) { onStepChange(false) }
    PageColumn(maxWidth = 720.dp, scrollState = planScrollState) {
        Text(
            if (comparing) "Step 2 of 2 · Compare and choose" else "Step 1 of 2 · Work context",
            style = MaterialTheme.typography.bodyMedium, color = Muted
        )
        if (!comparing) {
            FormSection("When and where", "All fields required") {
                OutlinedButton(
                    onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = PrimaryAction)
                    Spacer(Modifier.width(12.dp))
                    Text("Work date · $dateLabel", Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = { timeOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = PrimaryAction)
                    Spacer(Modifier.width(12.dp))
                    Text("Shift · %02d:%02d–15:00".format(startHour, startMinute), Modifier.weight(1f))
                }
                ChoiceField("Work site", "Birrarung Marr", listOf("Birrarung Marr"), {})
            }
            FormSection("Work details") {
                ChoiceField("Work type", work, listOf("Planting beds", "Mowing", "Pruning"), { work = it })
                ChoiceField(
                    "Effort",
                    effort,
                    listOf("Light effort", "Moderate effort", "Heavy effort"),
                    { effort = it })
                ChoiceField(
                    "Protective clothing",
                    clothing,
                    listOf("Standard workwear", "Heavy protective clothing"),
                    { clothing = it })
            }
            PrimaryButton("Compare work windows") { onStepChange(true) }
        } else if (contextUnavailable || !demoDate || !lateFits) {
            SurfaceCard {
                Text(
                    "No comparison available",
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Attention
                )
                Text(
                    if (contextUnavailable) "Refresh conditions on Today, then try again."
                    else if (!demoDate) "Sample forecasts are available for 12 January 2026 only."
                    else "Neither two-hour window fits after your shift starts."
                )
            }
            PrimaryButton(if (contextUnavailable) "Return to Today" else "Change work context") {
                if (contextUnavailable) onReturnToToday() else onStepChange(false)
            }
            if (contextUnavailable) SecondaryButton("Change work context") { onStepChange(false) }
        } else {
            Text("$work · $effort", style = MaterialTheme.typography.bodyLarge)
            FormSection("Air temperature", "Sample forecast · 12 January · °C") { ForecastChart() }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (earlyFits) WindowChoice("08:00–10:00", "24–27°C · UV 3–5", selected == "08:00–10:00") {
                    selected = "08:00–10:00"
                }
                WindowChoice("12:00–14:00", "31–34°C · UV 7–8", selected == "12:00–14:00") { selected = "12:00–14:00" }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (earlyFits) "Discuss the earlier window" else "Review the later window",
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (!earlyFits) "The cooler window is outside your shift. Discuss lighter work or a different arrangement."
                    else if (effort == "Heavy effort" || clothing == "Heavy protective clothing") "Heavy effort or protective clothing makes a cooler work arrangement especially relevant."
                    else "Plan lighter tasks around the conditions and available shade.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Sun protection applies in both windows. Agree changes with your supervisor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            PrimaryButton("Save preferred window", enabled = earlyFits || selected == "12:00–14:00") {
                onAccept(selected, WorkContext(work, effort, clothing, dayMillis, startHour, startMinute))
            }
            SecondaryButton("Change work context") { onStepChange(false) }
        }
    }
    if (dateOpen) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = dayMillis,
            initialDisplayMode = if (compactDialog) DisplayMode.Input else DisplayMode.Picker
        )
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(enabled = dateState.selectedDateMillis != null, onClick = {
                    dateState.selectedDateMillis?.let { dayMillis = it }; dateOpen = false
                }) { Text("Apply date") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dateOpen = false
                }) { Text("Cancel") }
            }) { DatePicker(state = dateState) }
    }
    if (timeOpen) {
        val timeState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute, is24Hour = true)
        var compactHour by remember { mutableStateOf("%02d".format(startHour)) }
        var compactMinute by remember { mutableStateOf("%02d".format(startMinute)) }
        val compactTimeValid = compactHour.toIntOrNull() in 0..14 && compactMinute.toIntOrNull() in 0..59
        val applyTime = {
            startHour = timeState.hour; startMinute = timeState.minute; timeOpen = false
        }
        if (shortWindow) Dialog(
            onDismissRequest = { timeOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)
                        .semantics { paneTitle = "Shift start" }, shape = UiShape.card
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                compactHour,
                                { if (it.length <= 2 && it.all(Char::isDigit)) compactHour = it },
                                label = { Text("Hour (0–14)") },
                                singleLine = true,
                                isError = compactHour.toIntOrNull() !in 0..14,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.weight(1f),
                                shape = UiShape.control
                            )
                            OutlinedTextField(
                                compactMinute,
                                { if (it.length <= 2 && it.all(Char::isDigit)) compactMinute = it },
                                label = { Text("Minute (0–59)") },
                                singleLine = true,
                                isError = compactMinute.toIntOrNull() !in 0..59,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                modifier = Modifier.weight(1f),
                                shape = UiShape.control
                            )
                        }
                        Row(
                            Modifier.width(232.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { timeOpen = false },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("Cancel") }
                            PrimaryButton("Apply time", Modifier.weight(1.3f), enabled = compactTimeValid) {
                                startHour = compactHour.toInt(); startMinute = compactMinute.toInt(); timeOpen = false
                            }
                        }
                    }
                }
            }
        } else AlertDialog(
            modifier = Modifier.safeDrawingPadding().imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            onDismissRequest = { timeOpen = false },
            title = { Text("Shift start") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (compactDialog) TimeInput(state = timeState) else TimePicker(state = timeState)
                    Text(
                        "Shift ends at 15:00. Select a start before 15:00.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = timeState.hour < 15,
                    onClick = applyTime
                ) { Text("Apply time") }
            },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("Cancel") } })
    }
}

@Composable
private fun WindowChoice(window: String, metrics: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = UiShape.control, color = if (selected) SelectedSurface else Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PrimaryAction else Line)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(window, style = MaterialTheme.typography.titleMedium)
                Text(metrics, style = MaterialTheme.typography.bodyLarge, color = Muted)
            }
        }
    }
}

@Composable
private fun ForecastChart() {
    val values = listOf(24f, 27f, 31f, 34f, 32f)
    val times = listOf("08:00", "10:00", "12:00", "14:00", "16:00")
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, 300.dp * scale)
        val plotHeight = 112.dp * scale
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(chartWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(40.dp * scale))
                    values.forEach { value ->
                        Text(
                            "${value.toInt()}°",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = PrimaryAction,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.width(40.dp * scale).height(plotHeight),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("36°", "29°", "22°").forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                    Canvas(Modifier.weight(1f).height(plotHeight).padding(vertical = 8.dp).semantics {
                        contentDescription =
                            "Sample air temperature: 24 degrees Celsius at 08:00, 27 at 10:00, 31 at 12:00, 34 at 14:00 and 32 at 16:00."
                    }) {
                        val left = size.width / (values.size * 2f)
                        val right = size.width - left
                        for (fraction in listOf(0f, 0.5f, 1f)) {
                            val y = size.height * fraction
                            drawLine(Line, Offset(left, y), Offset(right, y), 1.dp.toPx())
                        }
                        val points = values.mapIndexed { index, value ->
                            Offset(
                                left + index * (right - left) / (values.size - 1),
                                size.height * (36f - value) / 14f
                            )
                        }
                        points.zipWithNext()
                            .forEach { (a, b) -> drawLine(PrimaryAction, a, b, 3.dp.toPx(), StrokeCap.Round) }
                        points.forEach { drawCircle(PrimaryAction, 4.dp.toPx(), it) }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(40.dp * scale))
                    times.forEach {
                        Text(
                            it,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BreakScreen(
    outdoorMinutes: Int = 95,
    workTitle: String = "Planting beds",
    onRecord: (Boolean, String) -> Unit,
    onReturn: () -> Unit
) {
    var choice by rememberSaveable { mutableStateOf("Cooler place available") }
    var started by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    PageColumn(maxWidth = 640.dp) {
        if (!started && !confirmed) {
            ScreenHeader("Can you take a cooler pause?", "$outdoorMinutes min logged · $workTitle")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Cooler place available", "No cooler place available", "Cannot pause yet").forEach { option ->
                    Surface(
                        shape = UiShape.control, color = if (choice == option) SelectedSurface else Color.White,
                        border = BorderStroke(
                            if (choice == option) 2.dp else 1.dp,
                            if (choice == option) PrimaryAction else Line
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .selectable(
                                    selected = choice == option,
                                    role = Role.RadioButton,
                                    onClick = { choice = option })
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == option, onClick = null)
                            Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Text(
                if (choice == "Cooler place available") "Move to a cooler area and follow your workplace rest and hydration guidance."
                else "Discuss a lighter task or a cooler location with your supervisor. Your follow-up will stay open.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge
            )
            PrimaryButton(if (choice == "Cooler place available") "Start break preview" else "Record this response") {
                if (choice == "Cooler place available") started = true else {
                    onRecord(false, choice)
                    confirmed = true
                }
            }
        } else if (started && !confirmed) {
            ScreenHeader("Break in progress")
            Text("00:00", style = MaterialTheme.typography.displaySmall, color = PrimaryAction)
            Text("Timer preview · no recovery target", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(
                "Follow your workplace heat procedure. A check-in cannot tell you when it is safe to return to work.",
                style = MaterialTheme.typography.bodyLarge
            )
            PrimaryButton("Finish break preview") {
                onRecord(true, choice)
                confirmed = true
            }
            OutlinedButton(
                onClick = { started = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = UiShape.control
            ) { Text("Change my response") }
        } else {
            SurfaceCard {
                Text(
                    if (started) "Break check-in completed" else "Follow-up remains open",
                    Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (started) Ink else Attention
                )
                Text(
                    if (started) "Your response is recorded. No break minutes were added." else "$choice. A work adjustment still needs discussion.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            PrimaryButton("Return to Today", onClick = onReturn)
        }
        Disclosure("Why check in?") {
            Text(
                "The planned check-in is due after heavy work in changing conditions. Use your workplace heat procedure; this app does not measure body temperature or certify recovery.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}
