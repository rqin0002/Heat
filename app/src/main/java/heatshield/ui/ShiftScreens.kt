@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(model: PrototypeViewModel, onPlan: () -> Unit, onBreak: () -> Unit, onRecord: () -> Unit) {
    var stale by rememberSaveable { mutableStateOf(false) }
    val active = model.sessions.find { it.id == 1 }
    val needsReview = active != null && !stale && !model.checkInDone
    PageColumn {
        ScreenHeader(
            title = "Make room for shade.",
            subtitle = "Birrarung Marr · Grounds maintenance",
            eyebrow = "MONDAY, 12 JANUARY"
        )
        SurfaceCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = if (active == null) Icons.Outlined.EventNote else if (stale) Icons.Outlined.CloudOff else if (model.checkInDone) Icons.Outlined.CheckCircle else Icons.Outlined.Shield,
                    tint = if (needsReview) Amber else if (active == null || stale) Muted else Pine,
                    container = if (needsReview) AmberPale else if (active == null || stale) MaterialTheme.colorScheme.surfaceVariant else Mint
                )
                if (active == null || stale) {
                    Text(
                        if (active == null) "NO ACTIVE SESSION" else "CONTEXT UNAVAILABLE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted
                    )
                } else {
                    Tag(if (model.checkInDone) "CHECK-IN RECORDED" else "REVIEW YOUR NEXT STEP", needsReview)
                }
            }
            Text(
                if (active == null) "Plan your next session" else if (stale) "Forecast needs a refresh" else if (model.checkInDone) "Your response is recorded" else if (model.checkInDeferred) "A task change may help" else "Time for a cooler pause",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                if (active == null) "The active demo record has been removed. Open your records to review or add a session." else if (stale) "Cached information is out of date. Check local conditions before comparing work windows." else if (model.checkInDone) "Keep following your workplace procedure. A logged check-in does not establish recovery." else if (model.checkInDeferred) "A cooler pause was not available. Discuss a lighter task or cooler location with your supervisor." else "Your planned check-in is due after heavy planting work. Review your options before the next block.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            if (needsReview && active != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Heavy effort", true)
                Tag("${active.outdoorMinutes} min outdoors", true)
            }
            PrimaryButton(if (active == null) "Open work records" else if (stale) "Retry demo forecast" else if (model.checkInDone) "View session record" else "Check in now") {
                if (active == null) onRecord() else if (stale) stale =
                    false else if (model.checkInDone) onRecord() else onBreak()
            }
        }
        SectionHeading("Conditions at a glance", "Illustrative conditions · 11:30")
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
                IconBadge(Icons.Outlined.Thermostat, size = 32.dp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (stale) "—" else "31°", style = MaterialTheme.typography.displaySmall, color = Ink)
                    if (!stale) Text(
                        "C",
                        Modifier.padding(bottom = 3.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Muted
                    )
                }
                Text("Air temperature", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (stale) "Stale sample" else "Humidity 48%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
                IconBadge(Icons.Outlined.WbSunny, size = 32.dp)
                Text(if (stale) "—" else "7", style = MaterialTheme.typography.displaySmall, color = Ink)
                Text("UV index", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (stale) "Forecast unavailable" else "High · sun protection",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) Muted else Amber
                )
            }
        }
        SurfaceCard {
            SectionHeading("Your shift", "07:00–15:00 · illustrative plan", "Review", onPlan)
            IconLine(
                Icons.Outlined.Schedule,
                "Heavy work · ${model.acceptedWindow}",
                "Discuss schedule changes with your supervisor."
            )
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue(
                    "Outdoor work",
                    active?.let { "${it.outdoorMinutes} min" } ?: "No record",
                    Modifier.weight(1f)
                )
                LabelValue(
                    "Logged breaks",
                    active?.let { "${it.breakMinutes} min" } ?: "No record",
                    Modifier.weight(1f)
                )
                LabelValue(
                    "Next step",
                    if (active == null) "View records" else if (model.checkInDone) "Recorded" else "Check-in",
                    Modifier.weight(1f)
                )
            }
        }
        Text(
            "Sun protection is still needed for outdoor work when the UV index is low.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        DemoNote()
        TextButton(
            onClick = { stale = !stale },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(if (stale) "Restore demo conditions" else "Preview unavailable data") }
    }
}

@Composable
fun ShiftPlanScreen(acceptedWindow: String, onAccept: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var work by rememberSaveable { mutableStateOf("Planting beds") }
    var effort by rememberSaveable { mutableStateOf("Heavy effort") }
    var clothing by rememberSaveable { mutableStateOf("Standard workwear") }
    var selected by rememberSaveable { mutableStateOf(acceptedWindow) }
    var dayMillis by rememberSaveable { mutableLongStateOf(1768176000000L) }
    var startHour by rememberSaveable { mutableIntStateOf(7) }
    var startMinute by rememberSaveable { mutableIntStateOf(0) }
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val dateLabel =
        Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    val earlyFits = startHour * 60 + startMinute <= 480
    val lateFits = startHour * 60 + startMinute <= 720
    val demoDate = Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate().toString() == "2026-01-12"
    PageColumn {
        ScreenHeader(
            "A better window for heavy work",
            "Compare the conditions within your shift before agreeing a plan."
        )
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = Color.White,
            contentColor = Pine,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Work context") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Compare windows") })
        }
        if (tab == 0) {
            SurfaceCard {
                SectionHeading("When and where", "All fields are needed for this comparison")
                OutlinedButton(
                    onClick = { dateOpen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = Pine)
                    Spacer(Modifier.width(12.dp))
                    Text("Work date · $dateLabel", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ExpandMore, null, tint = Muted)
                }
                OutlinedButton(
                    onClick = { timeOpen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = Pine)
                    Spacer(Modifier.width(12.dp))
                    Text("Shift · %02d:%02d–15:00".format(startHour, startMinute), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ExpandMore, null, tint = Muted)
                }
                ChoiceField("Work site", "Birrarung Marr", listOf("Birrarung Marr"), {})
            }
            SurfaceCard {
                SectionHeading("Work demands", "Only context that changes the recommendation")
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
            PrimaryButton("Compare work windows") { tab = 1 }
            DemoNote("Demo forecasts illustrate 12 Jan 2026 only. Your chosen date is a form preview.")
        } else if (!demoDate || !lateFits) {
            SurfaceCard(tint = AmberPale) {
                Text("No comparison available", style = MaterialTheme.typography.titleLarge)
                Text(if (!demoDate) "This prototype has a forecast example for 12 Jan 2026 only. No forecast is available for the selected date." else "Neither example has a complete two-hour work block after your shift start.")
            }
            PrimaryButton("Change work context") { tab = 0 }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tag(work); Tag(effort, effort == "Heavy effort") }
            SurfaceCard {
                SectionHeading("Hourly air temperature", "Illustrative forecast · °C · 12 Jan")
                ForecastChart()
                Text(
                    "Keep UV separate: sun protection applies in both windows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            if (earlyFits) WindowChoice(
                "08:00–10:00",
                "Earlier heavy work",
                "24–27°C · UV 3–5",
                "Lower forecast temperature within this demo shift.",
                selected == "08:00–10:00"
            ) { selected = "08:00–10:00" }
            WindowChoice(
                "12:00–14:00",
                "Later heavy work",
                "31–34°C · UV 7–8",
                "Higher forecast temperature and UV in this example.",
                selected == "12:00–14:00"
            ) { selected = "12:00–14:00" }
            SurfaceCard(tint = Mint) {
                IconLine(
                    Icons.Outlined.Lightbulb,
                    if (earlyFits) "Discuss the earlier window" else "Review the later window",
                    if (!earlyFits) "The cooler example is outside your shift. Discuss lighter work or a different arrangement." else if (effort == "Heavy effort" || clothing == "Heavy protective clothing") "Effort and clothing make a cooler work arrangement especially relevant." else "Plan lighter tasks around the conditions and available shade."
                )
                Text(
                    "Saving records your preferred plan. Your supervisor still agrees changes to work or required PPE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            PrimaryButton(
                "Save preferred window",
                enabled = earlyFits || selected == "12:00–14:00"
            ) { onAccept(selected) }
            DemoNote("Window comparison is a demonstration, not a safe-work limit.")
        }
    }
    if (dateOpen) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = dayMillis)
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(onClick = {
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
        AlertDialog(
            onDismissRequest = { timeOpen = false },
            title = { Text("Shift start") },
            text = {
                Column {
                    TimePicker(state = timeState); Text(
                    "Demo shift ends at 15:00. Select a start before 15:00.",
                    style = MaterialTheme.typography.bodySmall
                )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = timeState.hour < 15,
                    onClick = {
                        startHour = timeState.hour; startMinute = timeState.minute; timeOpen = false
                    }) { Text("Apply time") }
            },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text("Cancel") } })
    }
}

@Composable
private fun WindowChoice(
    window: String,
    title: String,
    metrics: String,
    reason: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    SurfaceCard(
        modifier = Modifier.clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Pine else Line), shape),
        tint = if (selected) Mint else Color.White
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = Pine, unselectedColor = Muted)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(window, style = MaterialTheme.typography.titleLarge, color = if (selected) Pine else Ink)
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        Text(metrics, style = MaterialTheme.typography.labelLarge, color = Ink)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun ForecastChart() {
    val values = listOf(24f, 27f, 31f, 34f, 32f)
    val times = listOf("08:00", "10:00", "12:00", "14:00", "16:00")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(32.dp))
            values.forEach { value ->
                Text(
                    "${value.toInt()}°",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = Pine,
                    textAlign = TextAlign.Center
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(32.dp).height(112.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("36°", "29°", "22°").forEach { tick ->
                    Text(tick, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Canvas(Modifier.weight(1f).height(112.dp).padding(vertical = 8.dp).semantics {
                contentDescription =
                    "Illustrative air temperature in degrees Celsius: 24 at 08:00, 27 at 10:00, 31 at 12:00, 34 at 14:00 and 32 at 16:00."
            }) {
                // Half a label column at each edge aligns ticks and protects end points.
                val left = size.width / (values.size * 2f)
                val right = size.width - left
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    val y = size.height * fraction
                    drawLine(Line, Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                val points = values.mapIndexed { index, value ->
                    Offset(left + index * (right - left) / (values.size - 1), size.height * (36f - value) / 14f)
                }
                points.zipWithNext().forEach { (a, b) -> drawLine(Pine, a, b, 3.dp.toPx(), StrokeCap.Round) }
                points.forEach { point ->
                    drawCircle(Color.White, 5.dp.toPx(), point)
                    drawCircle(Pine, 3.5.dp.toPx(), point)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(32.dp))
            times.forEach { time ->
                Text(
                    time,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BreakScreen(outdoorMinutes: Int = 95, onDone: (Boolean) -> Unit) {
    var choice by rememberSaveable { mutableStateOf("Cooler place available") }
    var started by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    PageColumn {
        ScreenHeader(
            title = if (confirmed) "Your response is ready" else if (started) "Take the pause you need" else "What is possible right now?",
            subtitle = "Heavy planting work · $outdoorMinutes minutes logged outdoors",
            eyebrow = "PLANNED CHECK-IN"
        )
        SurfaceCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                IconBadge(Icons.Outlined.Info, tint = Amber, container = AmberPale)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Why this check-in?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your agreed check-in is due, with heavy effort and changing environmental conditions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                }
            }
            Text(
                "Use your workplace heat procedure. This app does not measure body temperature or certify recovery.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        if (!started && !confirmed) {
            SurfaceCard {
                Text("Choose your next step", style = MaterialTheme.typography.titleMedium)
                listOf("Cooler place available", "No cooler place available", "Cannot pause yet").forEach { option ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (choice == option) Mint else Color.White,
                        border = BorderStroke(1.dp, if (choice == option) Pine else Line)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(
                                    selected = choice == option,
                                    role = Role.RadioButton,
                                    onClick = { choice = option })
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = choice == option,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Pine, unselectedColor = Muted)
                            )
                            Text(
                                option,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (choice == option) Pine else Ink
                            )
                        }
                    }
                }
            }
            SurfaceCard(tint = if (choice == "Cooler place available") Mint else AmberPale) {
                IconLine(
                    Icons.Outlined.Lightbulb,
                    if (choice == "Cooler place available") "Move to the cooler place" else "Discuss a work adjustment",
                    if (choice == "Cooler place available") "Pause in a suitable cooler area and follow your workplace hydration and rest guidance." else "Tell your supervisor what is preventing a pause. Ask about a lighter task or a cooler location."
                )
            }
            PrimaryButton(if (choice == "Cooler place available") "Start break preview" else "Record this response") {
                if (choice == "Cooler place available") started = true else confirmed = true
            }
        } else if (started && !confirmed) {
            SurfaceCard {
                Text("Break in progress", style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconBadge(Icons.Outlined.Timer)
                    Text("00:00", style = MaterialTheme.typography.displaySmall, color = Pine)
                    Text(
                        "Timer preview · no recovery target",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
                Text(
                    "Finish when appropriate under your workplace procedure. Logging this event does not mean it is safe to return to work.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            PrimaryButton("Finish break preview") { confirmed = true }
            OutlinedButton(
                onClick = { started = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Change my response") }
        } else {
            SurfaceCard(tint = if (started) Mint else AmberPale) {
                IconLine(
                    if (started) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule,
                    if (started) "Break check-in completed" else "Follow-up remains open",
                    if (started) "The demo overview will show that you checked in." else "The demo overview will prompt a task-change discussion.",
                    iconTint = if (started) Pine else Amber
                )
                Text(
                    "No notification has been scheduled by this prototype.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            PrimaryButton("Return to Today") { onDone(started) }
        }
        SectionHeading("Reminders support your plan")
        Text(
            "Background reminders may arrive late. Keep following scheduled workplace breaks even when no reminder appears.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        DemoNote("This flow previews responses. It is not an emergency monitor.")
    }
}
