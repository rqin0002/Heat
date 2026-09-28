@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import heatshield.data.*
import heatshield.domain.ContextEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

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

@Composable
fun TodayScreen(model: HeatShieldViewModel, onPlan: () -> Unit, onBreak: () -> Unit, onRecord: () -> Unit) {
    val active = model.activeSession
    val snapshot = model.forecast
    val fresh = snapshot?.isFresh(model.now) == true
    val currentHour = Instant.ofEpochMilli(model.now).atZone(MelbourneZone).toLocalDateTime().withMinute(0).withSecond(0).withNano(0)
    val current = snapshot?.hours?.find { it.localTime == currentHour }
    PageColumn(maxWidth = 840.dp) {
        ScreenHeader(model.context.site, eyebrow = LocalDate.now(MelbourneZone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)))
        SurfaceCard {
            Text(if (active == null) "No work record for today" else model.decision.title,
                Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall)
            Text(if (active == null) "Log your outdoor work before recording a break response." else model.decision.explanation)
            PrimaryButton(if (active == null) "Add today's record" else "Check in now", onClick = if (active == null) onRecord else onBreak)
            if (active != null) {
                Text("${active.title} · ${active.outdoorMinutes} min work · ${active.breakMinutes} min breaks")
                SecondaryButton("View session record", onClick = onRecord)
            }
        }
        FormSection("Site forecast", if (fresh) "Open-Meteo · hourly forecast" else "Forecast unavailable or older than 60 minutes") {
            if (fresh && current != null) {
                Text("%.1f°C · %.0f%% humidity · UV %.1f".format(current.temperature, current.humidity, current.uv), style = MaterialTheme.typography.titleLarge)
            }
            if (snapshot != null && snapshot.fetchedAt > 0) Text("Last success: ${Instant.ofEpochMilli(snapshot.fetchedAt).atZone(MelbourneZone).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}", style = MaterialTheme.typography.bodySmall)
            snapshot?.error?.let { Text(it, color = Attention) }
            PrimaryButton(if (model.refreshing) "Refreshing…" else "Refresh forecast", enabled = !model.refreshing) { model.refreshForecast() }
        }
        FormSection("Your shift") {
            Text(model.plan?.let { "${it.context.date} · ${it.work} · ${it.window}" } ?: "No preferred work window saved.")
            PrimaryButton("Plan work windows", onClick = onPlan)
            if (model.plan != null) SecondaryButton("Cancel saved plan", onClick = model::cancelPlan)
            Text("Compare forecast conditions and discuss changes with your supervisor.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        FormSection("Historical sensor replay", "City of Melbourne · accelerated every 20 seconds") {
            val reading = model.reading
            if (reading == null) Text("No sensor reading available.") else {
                Text("%.1f°C · %.1f%% humidity".format(reading.temperature, reading.humidity), style = MaterialTheme.typography.titleLarge)
                Text("Observed ${reading.observedAtLocal} at ${reading.sourceLocation}", style = MaterialTheme.typography.bodySmall)
                Text("Row ${model.replayIndex + 1} of ${model.replayCount} · emitted ${Instant.ofEpochMilli(model.emittedAt).atZone(MelbourneZone).format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", style = MaterialTheme.typography.bodySmall)
                Text("Historical observations for exploring context responses. These are not today's measurements.", color = Muted)
                SecondaryButton(if (model.replayEnabled) "Pause replay" else "Resume replay", onClick = model::toggleReplay)
                SecondaryButton("Next sensor reading", onClick = model::advanceReplay)
                SecondaryButton("Replay from shift start", onClick = model::jumpReplayToShift)
            }
        }
    }
}

@Composable
fun ShiftPlanScreen(
    acceptedWindow: String,
    forecast: ForecastSnapshot? = null,
    refreshing: Boolean = false,
    sites: List<String> = WorkSite.defaults.map { it.name },
    onRefresh: (String) -> Unit = {},
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
    var site by rememberSaveable { mutableStateOf(initialContext.site) }
    var dayMillis by rememberSaveable { mutableLongStateOf(initialContext.dayMillis) }
    var startHour by rememberSaveable { mutableIntStateOf(initialContext.startHour) }
    var startMinute by rememberSaveable { mutableIntStateOf(initialContext.startMinute) }
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val dateLabel =
        Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    val selectedContext = WorkContext(work, effort, clothing, dayMillis, startHour, startMinute, site)
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(comparing) {
        while (comparing) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) }
    }
    val windows = ContextEngine.compare(selectedContext, forecast, clockNow)
    val hours = forecast?.hours.orEmpty().filter { it.localTime?.toLocalDate() == selectedContext.date && it.localTime!!.hour in startHour..15 }
    LaunchedEffect(windows) { if (windows.none { it.label == selected }) selected = windows.firstOrNull()?.label.orEmpty() }
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
                ChoiceField("Work site", site, sites, { site = it })
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
            PrimaryButton("Compare work windows") { onRefresh(site); onStepChange(true) }
        } else if (windows.isEmpty()) {
            SurfaceCard {
                Text("No comparison available", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = Attention)
                Text(if (refreshing) "Loading the site's forecast…" else
                    "A complete, fresh two-hour forecast is needed for the selected date, site and remaining shift. Choose an upcoming date or refresh conditions.")
                forecast?.error?.let { Text(it, color = Attention) }
            }
            PrimaryButton(if (refreshing) "Refreshing…" else "Retry forecast", enabled = !refreshing) { onRefresh(site) }
            SecondaryButton("Change work context") { onStepChange(false) }
            SecondaryButton("Return to Today", onClick = onReturnToToday)
        } else {
            Text("$work · $effort · $clothing", style = MaterialTheme.typography.bodyLarge)
            FormSection("Air temperature", "Open-Meteo · $dateLabel · °C") { ForecastChart(hours) }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                windows.forEach { window -> WindowChoice(window.label, window.summary, selected == window.label) { selected = window.label } }
            }
            Text("The first window has the lowest relative forecast burden for your work context. This comparison does not establish safe exposure or replace workplace procedures.")
            PrimaryButton("Save preferred window", enabled = windows.any { it.label == selected }) { onAccept(selected, selectedContext) }
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
private fun ForecastChart(hours: List<ForecastHour>) {
    if (hours.isEmpty()) return
    val values = hours.map { it.temperature.toFloat() }
    val minimum = values.min() - 2f
    val maximum = values.max() + 2f
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("%.1f–%.1f°C".format(values.min(), values.max()), style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.fillMaxWidth().height(128.dp * scale).semantics {
            contentDescription = hours.joinToString { "${it.time.substringAfter('T')}: ${it.temperature} degrees Celsius" }
        }) {
            val inset = 8.dp.toPx()
            val points = values.mapIndexed { index, value -> Offset(
                inset + index * (size.width - 2 * inset) / (values.size - 1).coerceAtLeast(1),
                inset + (size.height - 2 * inset) * (maximum - value) / (maximum - minimum)) }
            points.zipWithNext().forEach { (a, b) -> drawLine(PrimaryAction, a, b, 3.dp.toPx(), StrokeCap.Round) }
            points.forEach { drawCircle(PrimaryAction, 4.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hours.first().time.substringAfter('T')); Text(hours.last().time.substringAfter('T'))
        }
    }
}

@Composable
fun BreakScreen(model: HeatShieldViewModel, onReturn: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf("Cooler place available") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val active = model.activeSession
    val started = model.breakStartedAt > 0
    PageColumn(maxWidth = 640.dp) {
        if (active == null && !started) {
            ScreenHeader("No work record for today", "Add a work record before checking in.")
            PrimaryButton("Return to Today", onClick = onReturn)
        } else if (confirmed) {
            ScreenHeader("Response saved", "Your session record and trends have been updated.")
            PrimaryButton("Return to Today", onClick = onReturn)
        } else if (started) {
            ScreenHeader("Break in progress")
            val seconds = model.breakElapsedSeconds
            Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displaySmall)
            Text("Measured time only. Finishing adds whole elapsed minutes to this session.")
            PrimaryButton("Finish break", enabled = !model.busy) {
                model.recordResponse(true, choice) { confirmed = true }
            }
            SecondaryButton("Cancel timer", onClick = model::clearBreak)
        } else {
            ScreenHeader("Can you take a cooler pause?", "${active?.outdoorMinutes} min logged · ${active?.title}")
            Text(model.decision.explanation)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Cooler place available", "No cooler place available", "Cannot pause yet").forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = choice == option,
                        role = Role.RadioButton, onClick = { choice = option }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = choice == option, onClick = null); Text(option)
                    }
                }
            }
            Text(if (choice == "Cooler place available") "Move to a cooler area and follow workplace rest and hydration guidance."
                else "Discuss a lighter task or a cooler location. This response keeps the follow-up open.")
            PrimaryButton(if (choice == "Cooler place available") "Start break" else "Record this response", enabled = !model.busy) {
                if (choice == "Cooler place available") model.startBreak()
                else model.recordResponse(false, choice) { confirmed = true }
            }
        }
        Disclosure("Why check in?") {
            Text("HeatShield combines a labelled historical sensor replay with your work context and response. It does not measure body temperature, diagnose heat illness or certify recovery. Follow your workplace heat procedure.")
        }
    }
}
