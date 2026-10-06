@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.AlertReason
import heatshield.data.BreakEdit
import heatshield.data.BreakReason
import heatshield.data.PastShiftDraft
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftRepository
import heatshield.data.ShiftStatus
import heatshield.data.ShiftTimesEdit
import heatshield.data.WorkSession
import heatshield.data.isBreak
import heatshield.domain.BreakTimes
import heatshield.domain.Effort
import heatshield.domain.HeatLevel
import heatshield.domain.HourHeat
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.TimesError
import heatshield.domain.WorkTasks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** One editable break in Fix times and Add a past shift. [kind] marks a scheduled smoko/lunch; [taken] false keeps it listed as not taken. */
data class BreakRow(val key: Int, val eventId: Int?, val kind: ScheduledKind?, val startMinute: Int, val endMinute: Int, val taken: Boolean)

internal val BreakRowsSaver = listSaver<List<BreakRow>, Any>(
    save = { rows -> rows.flatMap { listOf(it.key, it.eventId ?: -1, it.kind?.name ?: "", it.startMinute, it.endMinute, it.taken) } },
    restore = { flat ->
        flat.chunked(6).map {
            BreakRow(it[0] as Int, (it[1] as Int).takeIf { id -> id >= 0 }, (it[2] as String).takeIf { k -> k.isNotEmpty() }?.let(ScheduledKind::valueOf),
                it[3] as Int, it[4] as Int, it[5] as Boolean)
        }
    }
)

internal fun minuteOf(epochMillis: Long): Int = ShiftRules.localTime(epochMillis).let { it.hour * 60 + it.minute }

/** A minute of the day on the shift's date; earlier than the shift start means the next day (a night shift). */
internal fun timeOn(date: LocalDate, minute: Int, shiftStart: LocalDateTime): LocalDateTime =
    date.atTime(minute / 60, minute % 60).let { if (it.isBefore(shiftStart)) it.plusDays(1) else it }

/**
 * A picked [minute] as Fix times' wall-clock time: the [stored] time's own minute while it is unchanged (so a time stored
 * on the next day, or in the repeated autumn hour, keeps its day), else [timeOn]. The repository then keeps the stored
 * instant itself ([heatshield.domain.ShiftRules.keptSpan]).
 */
internal fun pickedTime(date: LocalDate, minute: Int, shiftStart: LocalDateTime, stored: Long?): LocalDateTime =
    stored?.let { ShiftRules.localTime(it).truncatedTo(ChronoUnit.MINUTES) }?.takeIf { it.hour * 60 + it.minute == minute }
        ?: timeOn(date, minute, shiftStart)

/**
 * What Fix times saves for [shift] from its picked minutes and [rows]: the shift's start on its own date, its finish
 * after it (the next day on a night shift), and every row through [pickedTime] against its stored event in [events].
 * FixTimesScreen saves and validates this ([ShiftTimesEdit.validation]); its tests build Fix times' rows the same way.
 */
internal fun fixTimesEdit(shift: WorkSession, events: List<ShiftEvent>, date: LocalDate, startMinute: Int?, endMinute: Int?,
                          rows: List<BreakRow>, note: String?): ShiftTimesEdit {
    // The date is the stored start's own, so a start left as stored is already that stored minute.
    val start = startMinute?.let { date.atTime(it / 60, it % 60) }
    val end = if (start == null) null else endMinute?.let { pickedTime(date, it, start, shift.endedAt) }
    val byId = events.associateBy { it.id }
    val breaks = if (start == null) emptyList() else rows.map { row ->
        val stored = row.eventId?.let(byId::get)
        BreakEdit(row.key, row.eventId, row.kind, pickedTime(date, row.startMinute, start, stored?.startedAt),
            pickedTime(date, row.endMinute, start, stored?.endedAt), row.taken)
    }
    return ShiftTimesEdit(start, end, breaks, note)
}

/**
 * Break and scheduled events as rows, then every smoko/lunch proposal the shift never answered (no event, from the
 * preferences), unticked. An unticked row shows its scheduled length so ticking it gives sensible times: its end is that
 * many real minutes after its stored start, so a skipped 23:50 lunch ends at 00:20, which Fix times places on the next
 * morning, as it does an unanswered one. Rows keep their event id so Fix times updates in place. They are numbered in
 * time order, so a night shift's breaks after midnight follow the ones before it.
 */
internal fun List<ShiftEvent>.toBreakRows(shift: WorkSession, smokoMinute: Int, lunchMinute: Int): List<BreakRow> {
    val fromEvents = filter { it.isBreak }.map { e ->
        val kind = ScheduledKind.ofType(e.type)
        val start = minuteOf(e.startedAt)
        val end = if (e.skipped || e.endedAt == null) minuteOf(e.startedAt + (kind?.minutes ?: 15) * 60_000L) else minuteOf(requireNotNull(e.endedAt))
        e.startedAt to BreakRow(key = 0, eventId = e.id, kind = kind, startMinute = start, endMinute = end, taken = !e.skipped)
    }
    val start = shift.startedAt
    val end = shift.endedAt
    // Placed on the shift's own calendar day (ShiftRules.scheduledStart), as the proposal was.
    val pending = if (start != null && end != null) ShiftRules.pendingScheduled(smokoMinute, lunchMinute, ShiftRules.instant(start), ShiftRules.instant(end), this).map { p ->
        p.start.toEpochMilli() to BreakRow(key = 0, eventId = null, kind = p.kind, startMinute = minuteOf(p.start.toEpochMilli()),
            endMinute = minuteOf(p.end.toEpochMilli()), taken = false)
    } else emptyList()
    return (fromEvents + pending).sortedBy { it.first }.mapIndexed { index, (_, row) -> row.copy(key = index + 1) }
}

@Composable
internal fun timesErrorText(error: TimesError): String = stringResource(when (error) {
    TimesError.END_BEFORE_START -> R.string.times_error_end_before_start
    TimesError.TOO_LONG -> R.string.times_error_too_long
    TimesError.IN_FUTURE -> R.string.times_error_future
    TimesError.OUTSIDE_SHIFT -> R.string.times_error_outside
    TimesError.OVERLAPS -> R.string.times_error_overlap
})

/** The breaks group shared by Fix times and Add a past shift. Errors sit on the row they belong to. */
@Composable
internal fun BreakRowsEditor(date: LocalDate, shiftStart: LocalDateTime, rows: List<BreakRow>, errors: Map<Int, TimesError>, onChange: (List<BreakRow>) -> Unit) {
    fun List<BreakRow>.replace(row: BreakRow) = map { if (it.key == row.key) row else it }
    FormSection(stringResource(R.string.fix_group_breaks)) {
        rows.forEachIndexed { index, row ->
            val error = errors[row.key]?.let { timesErrorText(it) }
            Column(
                Modifier.fillMaxWidth().testTag("break_row_${row.key}").semantics { if (error != null) error(error) },
                verticalArrangement = Arrangement.spacedBy(UiSpace.small)
            ) {
                // Tapped breaks are numbered among themselves: a break between smoko and lunch is Break 1.
                Text(row.kind?.let { kindName(it) } ?: stringResource(R.string.fix_break_n, rows.take(index + 1).count { it.kind == null }),
                    style = MaterialTheme.typography.titleSmall)
                if (row.kind != null) SettingSwitch(stringResource(R.string.fix_took_scheduled), row.taken, { taken -> onChange(rows.replace(row.copy(taken = taken))) })
                if (row.taken) {
                    TimeButton(stringResource(R.string.fix_break_start), stringResource(R.string.fix_break_start), row.startMinute,
                        { onChange(rows.replace(row.copy(startMinute = it))) }, Modifier.testTag("break_row_${row.key}_start"))
                    TimeButton(stringResource(R.string.fix_break_end), stringResource(R.string.fix_break_end), row.endMinute,
                        { onChange(rows.replace(row.copy(endMinute = it))) }, Modifier.testTag("break_row_${row.key}_end"))
                }
                if (error != null) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (row.kind == null) TextButton(onClick = { onChange(rows.filterNot { it.key == row.key }) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.fix_remove_break))
                }
            }
        }
        TonalButton(stringResource(R.string.fix_add_break), Modifier.testTag("add_break")) {
            // An hour after the last taken break on the shift's timeline, so on a night shift a break after midnight
            // counts as the later one; with none taken, an hour after the start.
            val lastEnd = rows.filter { it.taken }.maxOfOrNull { timeOn(date, it.endMinute, shiftStart) } ?: shiftStart
            val start = lastEnd.plusMinutes(60).let { it.hour * 60 + it.minute }.coerceAtMost(1420)
            onChange(rows + BreakRow(key = (rows.maxOfOrNull { it.key } ?: 0) + 1, eventId = null, kind = null, startMinute = start,
                endMinute = (start + 15).coerceAtMost(1439), taken = true))
        }
    }
}

/** One line per event, as the summary and the shift page list them. */
@Composable
internal fun EventLine(event: ShiftEvent, use24Hour: Boolean) {
    val from = Formats.time(ShiftRules.localTime(event.startedAt).toLocalTime(), use24Hour)
    val to = event.endedAt?.let { Formats.time(ShiftRules.localTime(it).toLocalTime(), use24Hour) }
    val kind = ScheduledKind.ofType(event.type)
    val text = when {
        event.type == ShiftEventType.ALERT -> stringResource(
            if (event.reason == AlertReason.REPEAT) R.string.event_alert_repeat else R.string.event_alert,
            event.level?.let { level -> HeatLevel.valueOf(level).word } ?: "", event.heatC?.let(Formats::temperature) ?: "", from)
        event.type == ShiftEventType.CANT_STOP -> stringResource(R.string.event_cant_stop, from)
        kind != null && event.skipped -> stringResource(R.string.event_scheduled_unconfirmed, kindName(kind), from)
        kind != null -> stringResource(R.string.event_scheduled, kindName(kind), from, to ?: from)
        to == null -> stringResource(R.string.event_break_open, from)
        event.reason == BreakReason.NO_SHADE -> stringResource(R.string.event_break_no_shade, from, to)
        event.reason == BreakReason.UNWELL -> stringResource(R.string.event_break_unwell, from, to)
        else -> stringResource(R.string.event_break, from, to)
    }
    Text(text, Modifier.fillMaxWidth().testTag("event_${event.id}"), style = MaterialTheme.typography.bodyLarge)
}

/**
 * Route `summary/{id}` (parent Today), opened by End shift over the row it just wrote. Conditions first; break totals
 * are shown but never framed as behaviour. [hours] is the shift park's cached forecast through the shift's profile, or
 * empty. The peak's number is the stored one; its time is read from [hours] only while their hottest shift hour carries
 * exactly that number, so a refresh that revised the forecast never pairs a stored peak with another forecast's hour.
 * Smoko/lunch proposals that were never confirmed are listed "not confirmed": they count once ticked in Fix times.
 */
@Composable
fun ShiftSummaryScreen(shift: WorkSession, events: List<ShiftEvent>, hours: List<HourHeat>, triggerC: Double, smokoMinute: Int, lunchMinute: Int,
                       onFixTimes: () -> Unit, onDone: () -> Unit) {
    val use24Hour = rememberUse24Hour()
    val startAt = shift.startedAt?.let { ShiftRules.instant(it) }
    val endAt = shift.endedAt?.let { ShiftRules.instant(it) }
    val start = startAt?.let(ShiftRules::local)
    val end = endAt?.let(ShiftRules::local)
    val date = start?.toLocalDate() ?: LocalDate.parse(shift.date, ShiftRepository.RECORD_DATE)
    val unconfirmed = if (startAt != null && endAt != null) ShiftRules.pendingScheduled(smokoMinute, lunchMinute, startAt, endAt, events) else emptyList()
    PageColumn(maxWidth = 720.dp) {
        ScreenHeader(
            workTaskName(shift.title),
            if (start != null && end != null) stringResource(R.string.summary_subtitle, shift.site, Formats.time(start.toLocalTime(), use24Hour), Formats.time(end.toLocalTime(), use24Hour)) else shift.site,
            eyebrow = Formats.fullDate(date)
        )
        if (shift.autoEnded && end != null) NoticeText(stringResource(R.string.shift_auto_ended, Formats.time(end.toLocalTime(), use24Hour)),
            Modifier.testTag("summary_auto"))
        Text(stringResource(R.string.summary_worked, Formats.duration(shift.outdoorMinutes), Formats.duration(shift.breakMinutes)),
            Modifier.testTag("summary_worked"), style = MaterialTheme.typography.titleMedium)
        val peakLevel = shift.peakLevel?.let { name -> HeatLevel.entries.firstOrNull { it.name == name } }
        // The hours ShiftRules.summarise reads the peak from: the hour ending exactly as the shift starts held no shift minute.
        // Its time goes with the stored number only while that hour still carries it. Example: stored 36° from a 13:00
        // peak, then a refresh revises it to 34° at 12:00, so the summary says "Peak Very hot 36°" with no time.
        val peakHour = if (startAt != null && endAt != null) ShiftRules.hoursOverlapping(hours, startAt, endAt).maxByOrNull { it.heat }
            ?.takeIf { it.heat == shift.peakHeat } else null
        Text(
            when {
                peakLevel == null || shift.peakHeat == null -> stringResource(R.string.summary_peak_unknown)
                peakHour == null -> stringResource(R.string.summary_peak_no_time, peakLevel.word, Formats.temperature(shift.peakHeat))
                else -> stringResource(R.string.summary_peak, peakLevel.word, Formats.temperature(shift.peakHeat), Formats.time(peakHour.time.toLocalTime(), use24Hour))
            },
            Modifier.testTag("summary_peak"), style = MaterialTheme.typography.bodyLarge
        )
        Text(stringResource(R.string.summary_longest, Formats.duration(shift.longestStretchMinutes)), style = MaterialTheme.typography.bodyLarge)
        if (hours.isNotEmpty()) HeatRibbon(hours, nowTime = null, triggerC = triggerC, sunProtection = null, height = 72.dp, breaks = ShiftRules.confirmedBreaks(events))
        if (unconfirmed.isNotEmpty() || events.any { it.skipped }) {
            Text(stringResource(R.string.summary_scheduled_note), Modifier.testTag("summary_scheduled_note"), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (events.isNotEmpty() || unconfirmed.isNotEmpty()) FormSection(stringResource(R.string.summary_events)) {
            events.forEach { EventLine(it, use24Hour) }
            unconfirmed.forEach { proposal ->
                Text(stringResource(R.string.event_scheduled_unconfirmed, kindName(proposal.kind), Formats.time(proposal.start, use24Hour)),
                    Modifier.fillMaxWidth().testTag("event_pending_${proposal.kind.name.lowercase()}"), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (shift.status == ShiftStatus.TO_RAISE) NoticeText(stringResource(R.string.log_raise_hint))
        shift.note?.let { FormSection(stringResource(R.string.log_note)) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        PrimaryButton(stringResource(R.string.summary_done), Modifier.testTag("summary_done"), onClick = onDone)
        TonalButton(stringResource(R.string.summary_fix_times), Modifier.testTag("summary_fix"), onClick = onFixTimes)
    }
}

/**
 * Route `fix/{id}`: TimePickers for the shift and every break, validated as the gardener edits. Unconfirmed smoko/lunch
 * proposals are listed unticked. A legacy row (typed minutes, no times) opens with both times blank: a note saves on its
 * own and keeps the minutes; entering both times converts it to the measured model. [heatRecalculated] is false when no
 * forecast hours exist for the shift's day, so the stored heat columns will stay as they are.
 */
@Composable
fun FixTimesScreen(shift: WorkSession, events: List<ShiftEvent>, smokoMinute: Int, lunchMinute: Int, nowMillis: Long, saving: Boolean,
                   heatRecalculated: Boolean, onSave: (ShiftTimesEdit) -> Unit) {
    val use24Hour = rememberUse24Hour()
    val legacy = shift.startedAt == null
    val date = shift.startedAt?.let { ShiftRules.localTime(it).toLocalDate() } ?: LocalDate.parse(shift.date, ShiftRepository.RECORD_DATE)
    var startMinute by rememberSaveable(shift.id) { mutableStateOf(shift.startedAt?.let(::minuteOf)) }
    var endMinute by rememberSaveable(shift.id) { mutableStateOf(shift.endedAt?.let(::minuteOf)) }
    var rows by rememberSaveable(shift.id, stateSaver = BreakRowsSaver) { mutableStateOf(events.toBreakRows(shift, smokoMinute, lunchMinute)) }
    var note by rememberSaveable(shift.id) { mutableStateOf(shift.note.orEmpty()) }
    // One calculation for what is shown, validated and saved: times left as stored are the stored instants, so Fix times
    // opens valid on what End shift wrote (a break cut to under a minute, a time in the repeated autumn hour).
    val edit = fixTimesEdit(shift, events, date, startMinute, endMinute, rows, note)
    val start = edit.start
    val validation = edit.validation(shift, events, Instant.ofEpochMilli(nowMillis))
    val shiftError = validation?.shift?.let { timesErrorText(it) }
    // A legacy row saves with both times blank (note only); otherwise both times and every row must be valid.
    val canSave = !saving && if (startMinute == null && endMinute == null) legacy else validation?.ok == true
    PageColumn(maxWidth = 640.dp) {
        if (legacy) Text(stringResource(R.string.fix_legacy_hint), style = MaterialTheme.typography.bodyLarge)
        if (!heatRecalculated) Text(stringResource(R.string.fix_heat_not_recalculated), style = MaterialTheme.typography.bodyMedium, color = Muted)
        // The time the app ended the shift, as stored; never the finish being edited here.
        if (shift.autoEnded) shift.endedAt?.let {
            NoticeText(stringResource(R.string.shift_auto_ended, Formats.time(ShiftRules.localTime(it).toLocalTime(), use24Hour)),
                Modifier.testTag("fix_auto"))
        }
        FormSection(stringResource(R.string.fix_group_shift), Formats.fullDate(date)) {
            OptionalTimeButton(stringResource(R.string.fix_started), startMinute, { startMinute = it }, Modifier.testTag("fix_start"))
            OptionalTimeButton(stringResource(R.string.fix_finished), endMinute, { endMinute = it },
                Modifier.testTag("fix_end").semantics { if (shiftError != null) error(shiftError) })
            if (shiftError != null) Text(shiftError, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (start != null) BreakRowsEditor(date, start, rows, validation?.breaks.orEmpty(), onChange = { rows = it })
        OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text(stringResource(R.string.fix_note)) },
            modifier = Modifier.fillMaxWidth().testTag("fix_note"), shape = UiShape.control, minLines = 2)
        PrimaryButton(stringResource(if (saving) R.string.fix_saving else R.string.fix_save), Modifier.testTag("fix_save"), enabled = canSave) {
            onSave(edit)
        }
    }
}

/** No future dates: the DatePicker's rule, kept as a function so it can be checked without a dialog. */
internal fun pastDatesOnly(today: LocalDate): SelectableDates {
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtc
        override fun isSelectableYear(year: Int): Boolean = year <= today.year
    }
}

/**
 * Route `add-shift` (parent Log): the F4 create path for a shift the clock did not see. Pickers only; the date and times
 * start empty, so they are the required fields (asterisk and legend, spec §6 Week 6 F2). Minutes are derived.
 */
@Composable
fun AddPastShiftScreen(sites: List<String>, defaultSite: String, nowMillis: Long, saving: Boolean, onSave: (PastShiftDraft) -> Unit) {
    val now = ShiftRules.localTime(nowMillis)
    var site by rememberSaveable { mutableStateOf(defaultSite) }
    var task by rememberSaveable { mutableStateOf(WorkTasks.DEFAULT_TASK_NAME) }
    var heavyWork by rememberSaveable { mutableStateOf(WorkTasks.default.effort == Effort.HEAVY) }
    var heavyClothing by rememberSaveable { mutableStateOf(false) }
    var dateMillis by rememberSaveable { mutableStateOf<Long?>(null) }   // the DatePicker's UTC midnight
    var startMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var endMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var rows by rememberSaveable(stateSaver = BreakRowsSaver) { mutableStateOf(emptyList<BreakRow>()) }
    var note by rememberSaveable { mutableStateOf("") }
    var dateOpen by remember { mutableStateOf(false) }
    val date = dateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val start = startMinute?.let { m -> date?.atTime(m / 60, m % 60) }
    // A finish earlier than the start is the next morning (a night shift).
    val end = endMinute?.let { m -> if (date == null || start == null) null else date.atTime(m / 60, m % 60).let { if (it.isBefore(start)) it.plusDays(1) else it } }
    // Row fields named through `row`: the screen's own startMinute/endMinute would otherwise shadow them.
    fun times(row: BreakRow, day: LocalDate, from: LocalDateTime) = BreakTimes(row.key, row.kind, timeOn(day, row.startMinute, from), timeOn(day, row.endMinute, from))
    val validation = if (date != null && start != null && end != null) ShiftRules.validateTimes(start, end, rows.filter { it.taken }.map { times(it, date, start) }, Instant.ofEpochMilli(nowMillis)) else null
    val shiftError = validation?.shift?.let { timesErrorText(it) }
    PageColumn(maxWidth = 640.dp) {
        Text(stringResource(R.string.log_required_legend), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(stringResource(R.string.add_past_hint), style = MaterialTheme.typography.bodyLarge)
        FormSection(stringResource(R.string.shift_start_group_where)) {
            ChoiceField(stringResource(R.string.log_field_park), site, (sites + site).distinct(), { site = it })
            WorkTaskField(task, label = stringResource(R.string.log_field_work_type)) { task = it; heavyWork = WorkTasks.effortFor(it) == Effort.HEAVY }
            SettingSwitch(stringResource(R.string.shift_heavy_work), heavyWork, { heavyWork = it })
            SettingSwitch(stringResource(R.string.profile_heavy_clothing), heavyClothing, { heavyClothing = it })
        }
        FormSection(stringResource(R.string.add_past_group_when)) {
            OutlinedButton(
                onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("past_date"),
                shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.small)
            ) {
                // As OptionalTimeButton below, so the date and both times line up.
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_past_date), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(date?.let(Formats::fullDate) ?: stringResource(R.string.add_past_choose), style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Outlined.CalendarMonth, null, tint = PrimaryAction)
            }
            OptionalTimeButton(stringResource(R.string.add_past_started), startMinute, { startMinute = it }, Modifier.testTag("past_start"))
            OptionalTimeButton(stringResource(R.string.add_past_finished), endMinute, { endMinute = it },
                Modifier.testTag("past_end").semantics { if (shiftError != null) error(shiftError) })
            if (shiftError != null) Text(shiftError, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (date != null && start != null) BreakRowsEditor(date, start, rows, validation?.breaks.orEmpty(), onChange = { rows = it })
        OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text(stringResource(R.string.fix_note)) },
            modifier = Modifier.fillMaxWidth().testTag("past_note"), shape = UiShape.control, minLines = 2)
        PrimaryButton(stringResource(if (saving) R.string.log_saving else R.string.add_past_save), Modifier.testTag("past_save"),
            enabled = !saving && date != null && start != null && end != null && validation?.ok == true) {
            if (date != null && start != null && end != null) {
                onSave(PastShiftDraft(site, task, heavyWork, heavyClothing, start, end, rows.filter { it.taken }.map { times(it, date, start) }, note))
            }
        }
    }
    if (dateOpen) {
        val density = LocalDensity.current
        // As the Log's date filter: typed date entry at large font or on a short window, the calendar otherwise.
        val compactDialog = density.fontScale > 1.3f || LocalWindowInfo.current.containerSize.height / density.density < 500
        val todayUtc = now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = dateMillis ?: todayUtc, selectableDates = pastDatesOnly(now.toLocalDate()),
            initialDisplayMode = if (compactDialog) DisplayMode.Input else DisplayMode.Picker)
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = { dateMillis = state.selectedDateMillis; dateOpen = false }) {
                    Text(stringResource(R.string.log_use_date))
                }
            },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(stringResource(R.string.log_cancel)) } }
        ) { DatePicker(state = state) }
    }
}

/** A time field that may be empty ("Choose"): the required fields of Add a past shift, and a legacy row's blank times. */
@Composable
internal fun OptionalTimeButton(label: String, minute: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val use24Hour = rememberUse24Hour()
    // As TimeButton: typed fields above 130 % font, the dial below.
    val textInput = LocalDensity.current.fontScale > 1.3f
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true }, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.small)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(minute?.let { Formats.time(LocalTime.of(it / 60, it % 60), use24Hour) } ?: stringResource(R.string.add_past_choose),
                style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Outlined.Schedule, null, tint = PrimaryAction)
    }
    if (open) TimeDialog(label, minute ?: 420, use24Hour, textInput, onDismiss = { open = false }) { onPick(it); open = false }
}
