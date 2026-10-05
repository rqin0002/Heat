@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import heatshield.data.*
import heatshield.domain.AlertRules
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.HourHeat
import heatshield.domain.OpenAnswerKind
import heatshield.domain.WorkTasks
import heatshield.domain.NextStep
import heatshield.domain.SourceLabel
import heatshield.domain.ShiftEvaluation
import heatshield.domain.ShiftRules
import heatshield.domain.TodayHeat
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import heatshield.R
import java.time.Duration
import java.time.LocalTime
import java.time.LocalDate
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

private val clothingOptions = listOf("Standard workwear", "Heavy protective clothing")

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
    onClearPlan: () -> Unit = {},
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
    // S04 keeps the usual finish from Profile until M4's Plan adds its own picker.
    val endMinute = initialContext.endMinute.coerceIn(60, 1439)
    val latestStart = endMinute - 60
    val use24Hour = rememberUse24Hour()
    val dateLabel = Formats.fullDate(Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate())
    val endLabel = Formats.time(LocalTime.of(endMinute / 60, endMinute % 60), use24Hour)
    val latestLabel = Formats.time(LocalTime.of(latestStart / 60, latestStart % 60), use24Hour)
    val selectedContext = WorkContext(
        work = work, effort = effort, clothing = clothing, dayMillis = dayMillis,
        startHour = startHour, startMinute = startMinute, site = site, endMinute = endMinute
    )
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(comparing) {
        while (comparing) { clockNow = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) }
    }
    // A fetch time is never trusted beyond the real now: a cache stamped in the future (the phone clock moved back) offers
    // no window, as savePlan would refuse it. The real time is read again on every tick and whenever the forecast changes,
    // so a refresh that lands between ticks is judged at or after its genuine fetch time and keeps the windows and the choice.
    val judgedAt = remember(clockNow, forecast) { System.currentTimeMillis() }
    val windows = ContextReasoner.compare(selectedContext, forecast, judgedAt)
    val hours = forecast?.hours.orEmpty().filter {
        it.localTime?.toLocalDate() == selectedContext.date && it.localTime!!.hour in startHour..endMinute / 60
    }
    LaunchedEffect(windows) { if (windows.none { it.label == selected }) selected = windows.firstOrNull()?.label.orEmpty() }
    val largeText = LocalDensity.current.fontScale > 1.3f
    val shortWindow = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density < 500
    val compactDialog = largeText || shortWindow
    val planScrollState = rememberScrollState()
    LaunchedEffect(comparing) { planScrollState.scrollTo(0) }
    BackHandler(enabled = comparing) { onStepChange(false) }
    PageColumn(maxWidth = 720.dp, scrollState = planScrollState) {
        Text(
            stringResource(if (comparing) R.string.shift_plan_step_compare else R.string.shift_plan_step_details),
            style = MaterialTheme.typography.bodyMedium, color = Muted
        )
        if (!comparing) {
            FormSection(stringResource(R.string.shift_plan_when_where)) {
                OutlinedButton(
                    onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = PrimaryAction)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.shift_plan_date, dateLabel), Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = { timeOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = PrimaryAction)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.shift_plan_hours, Formats.time(LocalTime.of(startHour, startMinute), use24Hour), endLabel),
                        Modifier.weight(1f)
                    )
                }
                ChoiceField(stringResource(R.string.shift_plan_park), site, sites, { site = it })
            }
            FormSection(stringResource(R.string.shift_plan_work_details)) {
                // Each option names its effort ("Push mowing · heavy work"), so effort follows the work chosen.
                WorkTaskField(work, label = stringResource(R.string.shift_plan_work_type)) {
                    work = it; effort = WorkTasks.effortFor(it).label
                }
                ChoiceField(stringResource(R.string.shift_plan_clothing), clothing, clothingOptions, { clothing = it })
            }
            PrimaryButton(stringResource(R.string.shift_plan_compare)) { onRefresh(site); onStepChange(true) }
            // Task 8: while a window is saved, Plan can take it back.
            if (acceptedWindow.isNotEmpty()) TextButton(onClick = onClearPlan, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.today_plan_clear))
            }
        } else if (windows.isEmpty()) {
            SurfaceCard {
                Text(
                    stringResource(R.string.shift_plan_none_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall, color = Attention
                )
                Text(stringResource(if (refreshing) R.string.shift_plan_loading else R.string.shift_plan_none_body))
                forecastFailureText(forecast?.error)?.let { NoticeText(it) }
            }
            PrimaryButton(
                stringResource(if (refreshing) R.string.shift_plan_refreshing else R.string.shift_plan_retry),
                enabled = !refreshing
            ) { onRefresh(site) }
            SecondaryButton(stringResource(R.string.shift_plan_change_day)) { onStepChange(false) }
            SecondaryButton(stringResource(R.string.shift_plan_return_today), onClick = onReturnToToday)
        } else {
            Text(
                stringResource(R.string.shift_plan_summary, WorkTasks.byName(work)?.let { workTaskLabel(it) } ?: workTaskName(work), clothing),
                style = MaterialTheme.typography.bodyLarge
            )
            FormSection(stringResource(R.string.shift_plan_chart_title), stringResource(R.string.shift_plan_chart_source, dateLabel)) {
                ForecastChart(hours)
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                windows.forEach { window ->
                    // window.label ("07:00–09:00") stays the stored key; the card shows the phone's own time format.
                    val time = stringResource(
                        R.string.shift_plan_window_time,
                        Formats.time(LocalTime.of(window.startHour, 0), use24Hour),
                        Formats.time(LocalTime.of(window.endHour, 0), use24Hour)
                    )
                    WindowChoice(time, window.summary, selected == window.label) { selected = window.label }
                }
            }
            Text(stringResource(R.string.shift_plan_ranking))
            PrimaryButton(stringResource(R.string.shift_plan_save), enabled = windows.any { it.label == selected }) {
                onAccept(selected, selectedContext)
            }
            SecondaryButton(stringResource(R.string.shift_plan_change_day)) { onStepChange(false) }
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
                }) { Text(stringResource(R.string.shift_plan_apply_date)) }
            },
            dismissButton = {
                TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.shift_plan_cancel)) }
            }) { DatePicker(state = dateState) }
    }
    if (timeOpen) {
        val timeState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute, is24Hour = use24Hour)
        // Locale.ROOT, not the phone's: Kotlin's format follows the default locale, which the app locale does not pin.
        var compactHour by remember { mutableStateOf(String.format(Locale.ROOT, "%02d", startHour)) }
        var compactMinute by remember { mutableStateOf(String.format(Locale.ROOT, "%02d", startMinute)) }
        val compactStart = compactHour.toIntOrNull()?.let { hour ->
            compactMinute.toIntOrNull()?.takeIf { it in 0..59 }?.let { hour * 60 + it }
        }
        val compactTimeValid = compactStart != null && compactStart in 0..latestStart
        val startTitle = stringResource(R.string.shift_plan_start_title)
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
                        .semantics { paneTitle = startTitle }, shape = UiShape.card
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
                                label = { Text(stringResource(R.string.shift_plan_hour_field, latestStart / 60)) },
                                singleLine = true,
                                isError = compactHour.toIntOrNull()?.let { it in 0..latestStart / 60 } != true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                modifier = Modifier.weight(1f),
                                shape = UiShape.control
                            )
                            OutlinedTextField(
                                compactMinute,
                                { if (it.length <= 2 && it.all(Char::isDigit)) compactMinute = it },
                                label = { Text(stringResource(R.string.shift_plan_minute_field)) },
                                singleLine = true,
                                isError = compactMinute.toIntOrNull()?.let { it in 0..59 } != true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                modifier = Modifier.weight(1f),
                                shape = UiShape.control
                            )
                        }
                        Row(
                            Modifier.width(232.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { timeOpen = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.shift_plan_cancel))
                            }
                            PrimaryButton(stringResource(R.string.shift_plan_apply_time), Modifier.weight(1.3f), enabled = compactTimeValid) {
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
            title = { Text(startTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (compactDialog) TimeInput(state = timeState) else TimePicker(state = timeState)
                    Text(
                        stringResource(R.string.shift_plan_start_limit, endLabel, latestLabel),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = timeState.hour * 60 + timeState.minute <= latestStart, onClick = applyTime) {
                    Text(stringResource(R.string.shift_plan_apply_time))
                }
            },
            dismissButton = { TextButton(onClick = { timeOpen = false }) { Text(stringResource(R.string.shift_plan_cancel)) } })
    }
}


@Composable
private fun WindowChoice(window: String, metrics: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = UiShape.control, color = if (selected) SelectedSurface else HeatShieldTheme.colors.surface,
        contentColor = HeatShieldTheme.colors.ink,
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
    val use24Hour = rememberUse24Hour()
    // Theme colours are read here because the Canvas draw lambda is not composable. The chart is data, not something to
    // tap, so it is drawn in ink: blue stays for what the gardener can tap.
    val lineColour = Ink
    fun label(hour: ForecastHour) = hour.localTime?.let { Formats.time(it.toLocalTime(), use24Hour) } ?: hour.time
    // stringResource formats in the app's locale (Step 21); String.format would use the phone's (28,3 in Indonesian).
    val description = hours.map { stringResource(R.string.shift_plan_chart_hour, label(it), it.temperature) }.joinToString()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shift_plan_chart_range, values.min(), values.max()), style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.fillMaxWidth().height(128.dp * scale).semantics { contentDescription = description }) {
            val inset = 8.dp.toPx()
            val points = values.mapIndexed { index, value ->
                Offset(
                    inset + index * (size.width - 2 * inset) / (values.size - 1).coerceAtLeast(1),
                    inset + (size.height - 2 * inset) * (maximum - value) / (maximum - minimum)
                )
            }
            points.zipWithNext().forEach { (a, b) -> drawLine(lineColour, a, b, 3.dp.toPx(), StrokeCap.Round) }
            points.forEach { drawCircle(lineColour, 4.dp.toPx(), it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label(hours.first())); Text(label(hours.last()))
        }
    }
}


/** Route `break` (parent Today): the F8 answers over the open shift. */
@Composable
fun BreakScreen(model: HeatShieldViewModel, onReturn: () -> Unit, onStartShift: () -> Unit, onOpenFirstAid: () -> Unit) {
    // The time this sheet asked for once "Can't stop yet" saved; what the header then says comes from the evaluation.
    var reminder by rememberSaveable { mutableStateOf<Instant?>(null) }
    val state = model.shiftState
    // "Remind me in 10 min" only when the reminder can post: alerts on and allowed, and this is not the second "Can't stop
    // yet" for the break, whose fire stays silent (AlertRules.MAX_CANT_STOP). Otherwise the choice is "Save my answer".
    val remindable = model.alertStatus.deliverable && model.openShift?.let { open ->
        (state as? ShiftUiState.On)?.evaluation?.let { AlertRules.cantStopCount(open.events, it.clock.since) + 1 < AlertRules.MAX_CANT_STOP }
    } == true
    BreakSheetContent(
        heat = model.todayHeat, state = state, reminder = reminder, busy = model.busy, breakRule = model.breakRule, remindable = remindable,
        onStartBreak = { reason ->
            // Spec §5.6: first aid never waits on, or depends on, saving the break.
            if (reason == BreakReason.UNWELL) { model.startBreak(reason); onOpenFirstAid() }
            else model.startBreak(reason) { onReturn() }
        },
        onCantStop = {
            val due = (state as? ShiftUiState.On)?.evaluation?.now?.plus(Duration.ofMinutes(ShiftRules.SNOOZE_MINUTES.toLong()))
            model.cantStop { reminder = due }
        },
        // The sheet closes once the break is saved, so it never asks for a break straight after one ended.
        onEndBreak = { model.endBreak(onReturn) }, onReturn = onReturn, onStartShift = onStartShift, onOpenFirstAid = onOpenFirstAid
    )
}

/**
 * Stateless break sheet. [reminder] non-null is the time the sheet asked for once "Can't stop yet" saved (now plus 10
 * minutes); its confirmation shows while that answer still stands ([cantStopConfirmationStands]), and the header itself
 * comes from the evaluation ([cantStopHeader]). A break running now comes first, whether tapped (here, on Today or on the
 * break-due notification) or a confirmed smoko or lunch ([onConfirmedScheduledBreak]): the sheet then shows that break
 * and offers nothing that needs the gardener working. The radio choice survives a trip to first aid (rememberSaveable),
 * and first aid itself saves nothing (spec §5.14).
 */
@Composable
fun BreakSheetContent(
    heat: TodayHeat, state: ShiftUiState, reminder: Instant?, busy: Boolean, breakRule: BreakRule = BreakRule.HEATSHIELD,
    onStartBreak: (String) -> Unit, onCantStop: () -> Unit, onEndBreak: () -> Unit,
    onReturn: () -> Unit, onStartShift: () -> Unit, onOpenFirstAid: () -> Unit, remindable: Boolean = true
) {
    var choice by rememberSaveable { mutableStateOf(BreakAnswer.SHADE) }
    val use24Hour = rememberUse24Hour()
    val evaluation = when (state) { is ShiftUiState.On -> state.evaluation; is ShiftUiState.OnBreak -> state.evaluation; ShiftUiState.Off -> null }
    val step = ContextReasoner.nextStep(heat, evaluation?.openAnswer, breakRule)
    PageColumn(maxWidth = 640.dp) {
        // As on Today, a running break answers the snooze, so the answer line then names no time.
        BreakHeatAdvice(heat, step, (state as? ShiftUiState.On)?.evaluation?.breakDueAgainAt, breakRule)
        when {
            state is ShiftUiState.Off -> NoShiftForBreak(onStartShift, onReturn, onOpenFirstAid)
            state is ShiftUiState.OnBreak -> {
                ScreenHeader(stringResource(R.string.shift_on_break_title))
                val seconds = Duration.between(ShiftRules.instant(state.breakEvent.startedAt), state.evaluation.now).seconds
                Text(Formats.clock(seconds), Modifier.testTag("break_clock"), style = MaterialTheme.typography.displaySmall)
                PrimaryButton(stringResource(R.string.shift_break_end), enabled = !busy, onClick = onEndBreak)
                TextButton(onClick = onReturn, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.break_check_return)) }
                BreakFirstAidLink(onOpenFirstAid)
            }
            // As Today's panel: the confirmed smoko or lunch and when it ends. A second break would overlap it, so neither
            // Start break nor "Can't stop yet" is offered until it is over.
            state is ShiftUiState.On && onConfirmedScheduledBreak(state.evaluation) -> {
                ScreenHeader(stringResource(R.string.shift_on_break_title), shiftStatusText(state.evaluation, use24Hour))
                PrimaryButton(stringResource(R.string.break_check_return), onClick = onReturn)
                BreakFirstAidLink(onOpenFirstAid)
            }
            state is ShiftUiState.On && reminder != null && cantStopConfirmationStands(state.evaluation, reminder) -> {
                ScreenHeader(cantStopHeader(evaluation, reminder, use24Hour), stringResource(R.string.break_reminder_hint))
                PrimaryButton(stringResource(R.string.break_check_return), onClick = onReturn)
                BreakFirstAidLink(onOpenFirstAid)
            }
            state is ShiftUiState.On -> {
                ScreenHeader(stringResource(R.string.break_check_prompt), shiftStatusText(state.evaluation, use24Hour))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BreakAnswer.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = choice == option,
                            role = Role.RadioButton, onClick = { choice = option }).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = choice == option, onClick = null); Text(stringResource(option.label))
                        }
                    }
                }
                val cantStop = cantStopChoice(state.evaluation, heat.hours, use24Hour, remindable)
                Text(when (choice) {
                    BreakAnswer.SHADE -> stringResource(R.string.break_check_shade_hint)
                    BreakAnswer.NO_SHADE -> stringResource(R.string.break_check_raise_hint)
                    BreakAnswer.CANT_STOP -> cantStop.hint
                })
                PrimaryButton(when (choice) {
                    BreakAnswer.SHADE -> stringResource(R.string.break_check_start)
                    BreakAnswer.NO_SHADE -> stringResource(R.string.break_start_anyway)
                    BreakAnswer.CANT_STOP -> cantStop.label
                }, enabled = !busy) {
                    when (choice) {
                        BreakAnswer.SHADE -> onStartBreak(BreakReason.SHADE)
                        BreakAnswer.NO_SHADE -> onStartBreak(BreakReason.NO_SHADE)
                        BreakAnswer.CANT_STOP -> onCantStop()
                    }
                }
                // Spec §5.6: not feeling right stops work whatever the level. The break starts and first aid opens.
                TextButton(
                    onClick = { onStartBreak(BreakReason.UNWELL) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("break_unwell"),
                    colors = ButtonDefaults.textButtonColors(contentColor = HeatShieldTheme.colors.emergency)
                ) { Text(stringResource(R.string.break_unwell)) }
                BreakFirstAidLink(onOpenFirstAid)
            }
        }
        BreakWhy(heat, step, breakRule)
    }
}

/**
 * "Why this suggestion?" under the check-in. Spec §5.10: the stop line, the cadence and the day's level that
 * [BreakHeatAdvice] shows are HeatShield's suggestions, so they carry [SourceLabel.HEATSHIELD_RULE] here as Today's
 * Why sheet labels them. An open answer alone, or a shift with no current forecast, suggests nothing and gets no label.
 */
@Composable
internal fun BreakWhy(heat: TodayHeat, step: NextStep, breakRule: BreakRule = BreakRule.HEATSHIELD) {
    Disclosure(stringResource(R.string.break_why_title)) {
        Text(stringResource(R.string.break_why_body))
        if (breakAdviceSuggests(heat, step, breakRule)) SourceChip(SourceLabel.HEATSHIELD_RULE)
    }
}

/** True when [BreakHeatAdvice] shows a HeatShield suggestion: a stop line, a cadence, or the day's level from a current forecast. */
private fun breakAdviceSuggests(heat: TodayHeat, step: NextStep, breakRule: BreakRule): Boolean {
    // The stop lines show under any answer: extremeLine, and the union table's own (unionStopLine).
    if (heat.hours.any { ShiftRules.stopNow(it, BreakRule.HEATSHIELD) }) return true
    if (breakRule == BreakRule.UNION_VTHC && heat.hours.any { ShiftRules.stopNow(it, breakRule) }) return true
    return when (step) {
        is NextStep.OpenAnswer -> false
        NextStep.StopHeavyWork, is NextStep.BreakSuggested -> true
        NextStep.None -> heat.peak != null && (heat.freshness == Freshness.FRESH || heat.freshness == Freshness.AGING)
    }
}

/** Off shift there is nothing to time: start the shift, or open first aid. */
@Composable
fun NoShiftForBreak(onStartShift: () -> Unit, onReturn: () -> Unit, onOpenFirstAid: () -> Unit) {
    ScreenHeader(stringResource(R.string.break_no_shift), stringResource(R.string.break_no_shift_hint))
    PrimaryButton(stringResource(R.string.shift_start), onClick = onStartShift)
    TextButton(onClick = onReturn, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.break_check_return)) }
    BreakFirstAidLink(onOpenFirstAid)
}

/** Spec §5.6, A10: Today's "Someone unwell? Heat first aid" link, on every state of the sheet; it saves nothing. */
@Composable
private fun BreakFirstAidLink(onOpenFirstAid: () -> Unit) {
    TextButton(
        onClick = onOpenFirstAid, modifier = Modifier.heightIn(min = 48.dp).testTag("break_first_aid"),
        colors = ButtonDefaults.textButtonColors(contentColor = HeatShieldTheme.colors.emergency)
    ) { Text(stringResource(R.string.today_unwell)) }
}

/**
 * Today's next-step line for an answer still standing; the break sheet says the same. [breakDueAgainAt] is
 * [ShiftEvaluation.breakDueAgainAt]: "Can't stop yet" names a time only while the snoozed break, and no stop, comes next.
 */
@Composable
internal fun answerLine(kind: OpenAnswerKind, breakDueAgainAt: Instant?, use24Hour: Boolean): String = when (kind) {
    OpenAnswerKind.NO_SHADE -> stringResource(R.string.shift_answer_no_shade_line)
    OpenAnswerKind.CANT_STOP -> breakDueAgainAt?.let { stringResource(R.string.shift_answer_cant_stop_line, Formats.time(it, use24Hour)) }
        ?: stringResource(R.string.break_check_answer_cant_stop)
}

/**
 * The break sheet's header once "Can't stop yet" is saved. [asked] is the time the sheet asked for (now plus 10
 * minutes); until Room's copy of the answer reaches [evaluation] (its snooze is not before [asked]) nothing is promised.
 * Then, from the evaluation as the panel reads it: the snoozed break's time ([ShiftEvaluation.breakDueAgainAt]); else
 * the stop line the shift is at or walking to ([ShiftEvaluation.stopFrom]), so no break time is ever shown past it; else
 * a break due later than the snooze (answered early) at its own time; else the answer alone, when nothing is timed.
 */
@Composable
internal fun cantStopHeader(evaluation: ShiftEvaluation?, asked: Instant, use24Hour: Boolean): String {
    val saved = evaluation?.takeIf { e -> e.snoozedUntil?.let { !it.isBefore(asked) } == true }
        ?: return stringResource(R.string.break_check_answer_cant_stop)
    val union = saved.rule == BreakRule.UNION_VTHC
    val againAt = saved.breakDueAgainAt
    val stopFrom = saved.stopFrom
    val due = saved.due
    return when {
        againAt != null -> stringResource(R.string.break_reminder_set, Formats.time(againAt, use24Hour))
        saved.stopNow -> stringResource(if (union) R.string.break_reminder_union_stop_now else R.string.break_reminder_stop_now)
        stopFrom != null -> stringResource(if (union) R.string.break_reminder_union_stop_from else R.string.break_reminder_stop_from,
            Formats.time(stopFrom, use24Hour))
        due is BreakDue.At -> stringResource(R.string.break_reminder_next, Formats.time(due.time, use24Hour))
        else -> stringResource(R.string.break_check_answer_cant_stop)
    }
}

/**
 * True while the sheet's "Can't stop yet" confirmation, asked for [asked] (the answer's time plus 10 minutes), still
 * describes the shift: Room's copy of the answer has not reached [evaluation] yet (its snooze is earlier than [asked]),
 * or it has and the answer still stands ([ShiftEvaluation.openAnswer]). A break taken since the answer closes it, so the
 * sheet asks again rather than showing the old confirmation.
 * Example: answered at 8:00 (asked 8:10), then a break from 8:11 to 8:24: at 8:25 the answer no longer stands.
 */
private fun cantStopConfirmationStands(evaluation: ShiftEvaluation, asked: Instant): Boolean {
    val arrived = evaluation.snoozedUntil?.let { !it.isBefore(asked) } == true
    return !arrived || evaluation.openAnswer == OpenAnswerKind.CANT_STOP
}

/** The "Can't stop yet" choice's button and hint, before the answer is saved. */
internal class CantStopChoice(val label: String, val hint: String)

/**
 * Where the panel's walk ([ShiftRules.nextBreakDue]) puts Break due once "Can't stop yet" is saved now: the same work
 * clock, smoko and lunch and shift end as [evaluation], with the answer's snooze ending SNOOZE_MINUTES on. The snooze
 * only moves a due time earlier than that, and a smoko or lunch within 15 minutes still covers what it lands on.
 * Example: Warm (60 min work) from 7:00, so due 8:00. Asked at 7:10 the snooze ends 7:20, earlier, so still 8:00; asked
 * at 7:55 it is 8:05.
 */
private fun dueAfterCantStop(evaluation: ShiftEvaluation, hours: List<HourHeat>): BreakDue =
    ShiftRules.nextBreakDue(evaluation.clock, hours, evaluation.rule, evaluation.pending,
        evaluation.now.plus(Duration.ofMinutes(ShiftRules.SNOOZE_MINUTES.toLong())), evaluation.shiftEnd)

/**
 * What "Can't stop yet" can honestly promise before it is saved, from where the walk puts Break due once it is saved
 * ([dueAfterCantStop]). The answer adds no break minutes. It asks again ten minutes on only when that is the result:
 * "Remind me in 10 min" when [remindable] (the In 10 min alarm posts the reminder), else "Save my answer" (alerts off or
 * blocked, or the second answer for this break, whose fire stays silent); the panel brings the break back either way.
 * A break due later than the snooze, or covered by a smoko or lunch it lands near, keeps that time: "Save my answer",
 * naming it. When the snooze would run into the stop line ([ShiftRules.snoozeStop]) the walk names the stop instead and
 * nothing reminds, so the choice says "Save my answer" and names the stop, never a break past it. With nothing timed (no
 * current forecast, or no break due this shift) nothing reminds either.
 * Example: Warm from 7:00 (due 8:00): at 7:10 "Save my answer", next break due at 8:00; at 7:55 "Remind me in 10 min".
 */
@Composable
internal fun cantStopChoice(evaluation: ShiftEvaluation, hours: List<HourHeat>, use24Hour: Boolean, remindable: Boolean = true): CantStopChoice {
    val union = evaluation.rule == BreakRule.UNION_VTHC
    val stop = ShiftRules.snoozeStop(evaluation, hours)
    val save = stringResource(R.string.break_cant_stop_save)
    val back = if (stop == null && evaluation.due is BreakDue.At) dueAfterCantStop(evaluation, hours) else null
    return when {
        // The snooze itself is the new due time: Break due comes back ten minutes on.
        back is BreakDue.At && back.snoozed && back.scheduled == null ->
            CantStopChoice(if (remindable) stringResource(R.string.break_remind) else save, stringResource(R.string.break_cant_stop_hint))
        back is BreakDue.At && back.scheduled != null -> CantStopChoice(save,
            stringResource(R.string.break_cant_stop_hint_scheduled, kindName(back.scheduled), Formats.time(back.time, use24Hour)))
        back is BreakDue.At -> CantStopChoice(save, stringResource(R.string.break_cant_stop_hint_later, Formats.time(back.time, use24Hour)))
        // A stop the walk reaches on its way to a smoko or lunch that would have covered the break.
        back is BreakDue.Stop -> CantStopChoice(save, stringResource(
            if (back.rule == BreakRule.UNION_VTHC) R.string.break_cant_stop_hint_union_stop_from else R.string.break_cant_stop_hint_stop_from,
            Formats.time(back.from, use24Hour)))
        stop == null -> CantStopChoice(save, stringResource(R.string.break_cant_stop_hint_untimed))
        evaluation.stopNow -> CantStopChoice(save,
            stringResource(if (union) R.string.break_cant_stop_hint_union_stop_now else R.string.break_cant_stop_hint_stop_now))
        stop.isAfter(evaluation.now) -> CantStopChoice(save,
            stringResource(if (union) R.string.break_cant_stop_hint_union_stop_from else R.string.break_cant_stop_hint_stop_from, Formats.time(stop, use24Hour)))
        // A stop the walk passed in an hour that has since cooled: the break is due (the panel says so), and stays due.
        else -> CantStopChoice(save, stringResource(R.string.break_cant_stop_hint_due))
    }
}

/**
 * Open answers never hide independent current heat advice. Both stop lines read the heat itself, as on Today: the
 * Extreme line, and under the union table its own stop line whenever a shift hour reaches 36° ([unionStopShown]), so
 * neither is hidden under an open answer and neither hides the other. The one that starts earlier comes first
 * ([unionStopFirst]); the crew line for work that goes ahead follows both.
 * [breakDueAgainAt] is [ShiftEvaluation.breakDueAgainAt], for the answer line. Shared by Break and its behavior test.
 */
@Composable
internal fun BreakHeatAdvice(heat: TodayHeat, step: NextStep, breakDueAgainAt: Instant? = null, breakRule: BreakRule = BreakRule.HEATSHIELD) {
    val use24Hour = rememberUse24Hour()
    val stop = extremeLine(heat, use24Hour)
    val unionStop = if (unionStopShown(heat, breakRule)) unionStopLine(heat, use24Hour) else null
    val unionFirst = unionStop != null && unionStopFirst(heat)
    if (step is NextStep.OpenAnswer) {
        Text(stringResource(R.string.today_next_answer, answerLine(step.kind, breakDueAgainAt, use24Hour)), Modifier.testTag("break_open_answer"))
    }
    if (unionFirst) unionStop?.let { Text(it, Modifier.testTag("break_union_stop")) }
    stop?.let { Text(it, Modifier.testTag("break_extreme")) }
    if (!unionFirst) unionStop?.let { Text(it, Modifier.testTag("break_union_stop")) }
    when {
        stop != null -> Text(stringResource(R.string.today_cadence_extreme), Modifier.testTag("break_extreme_crew"))
        unionStop == null && step !is NextStep.OpenAnswer -> Text(breakLevelLine(step, heat, breakRule))
    }
}

/**
 * The check-in's first line, from the same [NextStep] Today uses: the open answer, the stop line (timed as on Today),
 * or the day's rhythm in Today's own words. Without a current forecast for the shift it claims nothing.
 */
@Composable
private fun breakLevelLine(step: NextStep, heat: TodayHeat, breakRule: BreakRule): String = when (step) {
    is NextStep.OpenAnswer -> stringResource(R.string.today_next_answer, answerLine(step.kind, null, rememberUse24Hour()))
    // The union table, adapted by HeatShield, stops from a heat number of 36°; the HeatShield rule stops at Extreme. Both
    // are timed as on Today, so a cooler morning is not told to stop yet.
    NextStep.StopHeavyWork -> extremeLine(heat, rememberUse24Hour())
        ?: (if (breakRule == BreakRule.UNION_VTHC) unionStopLine(heat, rememberUse24Hour()) else null)
        ?: stringResource(if (breakRule == BreakRule.UNION_VTHC) R.string.shift_union_stop_line else R.string.today_extreme_now)
    is NextStep.BreakSuggested -> cadenceText(heat, step.cadence)
    NextStep.None -> {
        val current = heat.peak != null && (heat.freshness == Freshness.FRESH || heat.freshness == Freshness.AGING)
        stringResource(if (current) R.string.break_level_normal else R.string.break_level_missing)
    }
}

private enum class BreakAnswer(@param:StringRes val label: Int) {
    SHADE(R.string.break_check_answer_shade),
    NO_SHADE(R.string.break_check_answer_no_shade),
    CANT_STOP(R.string.break_check_answer_cant_stop)
}
