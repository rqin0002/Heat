package heatshield.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.BreakReason
import heatshield.data.ShiftEvent
import heatshield.data.WorkSession
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatLevel
import heatshield.domain.ScheduledBreak
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftEvaluation
import heatshield.domain.ShiftRules
import java.time.Duration

/** What Today shows about the shift. Derived by the ViewModel each tick; Preview builds it from its virtual clock. */
sealed interface ShiftUiState {
    data object Off : ShiftUiState
    data class On(val shift: WorkSession, val evaluation: ShiftEvaluation) : ShiftUiState
    data class OnBreak(val shift: WorkSession, val evaluation: ShiftEvaluation, val breakEvent: ShiftEvent) : ShiftUiState
}

sealed interface ShiftAction {
    data object OpenStart : ShiftAction
    data object StartBreak : ShiftAction
    data object EndBreak : ShiftAction
    data object OpenBreakSheet : ShiftAction
    data object EndShift : ShiftAction
    data class Scheduled(val kind: ScheduledKind, val taken: Boolean) : ShiftAction
    data class HeavyWork(val on: Boolean) : ShiftAction
    /** "Someone unwell? Heat first aid" on the card of a break started with "I don't feel right". Preview has none. */
    data object OpenFirstAid : ShiftAction
}

/**
 * Today's state machine (spec §10 M2): off shift → on shift → break due → on break. One filled action, 56 dp off shift
 * and 64 dp on shift; End shift is a text button that asks first, because the clock stops at once and a shift cannot
 * be reopened. [preview] relabels for the Preview screen's virtual shift. [breakRule] is the break table in use, for
 * what the heavy-work switch says it changes.
 */
@Composable
fun ShiftPanel(
    state: ShiftUiState, onAction: (ShiftAction) -> Unit, modifier: Modifier = Modifier,
    preview: Boolean = false, startEnabled: Boolean = true, blockedReason: String? = null, onStatusClick: (() -> Unit)? = null,
    breakRule: BreakRule = BreakRule.HEATSHIELD
) {
    val use24Hour = rememberUse24Hour()
    // The End shift question survives a rotation, and closes by itself once no shift is running.
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val running = state !is ShiftUiState.Off
    LaunchedEffect(running) { if (!running) confirmEnd = false }
    when (state) {
        ShiftUiState.Off -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            PrimaryButton(stringResource(if (preview) R.string.preview_shift_start else R.string.shift_start), Modifier.testTag("shift_start"),
                enabled = startEnabled) { onAction(ShiftAction.OpenStart) }
            Text(blockedReason ?: stringResource(if (preview) R.string.preview_shift_start_supporting else R.string.shift_start_supporting), Modifier.testTag("shift_start_note"),
                style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        is ShiftUiState.On -> OnShiftCard(state, use24Hour, preview, breakRule, onAction, onStatusClick, onEndShift = { confirmEnd = true }, modifier)
        is ShiftUiState.OnBreak -> OnBreakCard(state, use24Hour, preview, onAction, onEndShift = { confirmEnd = true }, modifier)
    }
    val endsAt = when (state) {
        is ShiftUiState.On -> state.evaluation.now
        is ShiftUiState.OnBreak -> state.evaluation.now
        ShiftUiState.Off -> null
    }
    if (confirmEnd && endsAt != null) {
        val endLabel = stringResource(if (preview) R.string.preview_shift_end else R.string.shift_end)
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.shift_end_confirm_title, Formats.time(endsAt, use24Hour))) },
            text = { Text(stringResource(R.string.shift_end_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; onAction(ShiftAction.EndShift) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("shift_end_confirm")) { Text(endLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("shift_end_keep")) {
                    Text(stringResource(R.string.shift_end_keep))
                }
            }
        )
    }
}

@Composable
private fun OnShiftCard(state: ShiftUiState.On, use24Hour: Boolean, preview: Boolean, breakRule: BreakRule, onAction: (ShiftAction) -> Unit,
                        onStatusClick: (() -> Unit)?, onEndShift: () -> Unit, modifier: Modifier) {
    val evaluation = state.evaluation
    val whyLabel = stringResource(R.string.today_why_open)
    // A confirmed smoko or lunch still running: the gardener is on that break, so no other break is offered over it.
    val scheduledBreakRunning = onConfirmedScheduledBreak(evaluation)
    SurfaceCard(modifier.testTag("shift_card")) {
        if (preview) Text(stringResource(R.string.preview_shift_label), style = MaterialTheme.typography.labelLarge, color = Muted)
        Text(
            shiftStatusText(evaluation, use24Hour),
            Modifier.fillMaxWidth().testTag("shift_status")
                .then(if (evaluation.isDue) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                .then(if (onStatusClick != null) Modifier.clickable(onClickLabel = whyLabel, role = Role.Button, onClick = onStatusClick) else Modifier),
            style = if (evaluation.isDue) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
        )
        // Spec §5.11: water rides inside every break-due from Hot up; no separate water reminders.
        if (evaluation.isDue && (evaluation.current?.effective ?: HeatLevel.NORMAL).atLeast(HeatLevel.HOT)) {
            Text(stringResource(R.string.shift_water), Modifier.testTag("shift_water"), style = MaterialTheme.typography.bodyLarge)
        }
        evaluation.proposal?.let { ScheduledProposal(it, use24Hour, onAction) }
        if (!scheduledBreakRunning) {
            ShiftPrimaryButton(stringResource(R.string.shift_break_start), Modifier.testTag("shift_break_start")) { onAction(ShiftAction.StartBreak) }
        }
        // The preview has no break sheet, so its "Can't stop" and "No shade" are not offered.
        if (!preview) TonalButton(stringResource(R.string.shift_other_answer), Modifier.testTag("shift_break_sheet")) { onAction(ShiftAction.OpenBreakSheet) }
        TextButton(onClick = onEndShift, modifier = Modifier.heightIn(min = 48.dp).testTag("shift_end")) {
            Text(stringResource(if (preview) R.string.preview_shift_end else R.string.shift_end))
        }
        Disclosure(stringResource(R.string.shift_details)) {
            Text(
                stringResource(R.string.shift_details_line, workTaskName(state.shift.title), state.shift.site,
                    Formats.time(evaluation.shiftStart, use24Hour),
                    state.shift.plannedEndAt?.let { Formats.time(ShiftRules.instant(it), use24Hour) } ?: "–"),
                style = MaterialTheme.typography.bodyMedium, color = Muted
            )
            if (state.shift.plannedEndAt != null) {
                Text(stringResource(R.string.shift_finish_supporting), Modifier.testTag("shift_finish_note"),
                    style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            SettingSwitch(stringResource(R.string.shift_heavy_work), state.shift.heavyWork, { onAction(ShiftAction.HeavyWork(it)) },
                supporting = stringResource(heavyWorkSupporting(breakRule)))
        }
    }
}

/**
 * True while a confirmed smoko or lunch is still running. "Yes" writes the break's whole scheduled span, so the work
 * clock resets at its end, still ahead of now ([heatshield.domain.WorkClock.since]). Today's panel and the break sheet
 * both read this, so neither offers a second break over it (the repository would refuse one).
 * Example: lunch confirmed for 12:00–12:30 → true at 12:05, false from 12:30.
 */
internal fun onConfirmedScheduledBreak(evaluation: ShiftEvaluation): Boolean = evaluation.clock.since.isAfter(evaluation.now)

/** What the heavy-work switch changes under [rule]: under the union table the breaks go by the heat number alone. */
@StringRes
internal fun heavyWorkSupporting(rule: BreakRule): Int =
    if (rule == BreakRule.UNION_VTHC) R.string.shift_heavy_work_supporting_union else R.string.shift_heavy_work_supporting

/**
 * A proposal, not a break: Yes writes the scheduled break; Not yet records nothing on the clock (spec §4). The question
 * is one node for TalkBack; Yes and Not yet stay their own buttons.
 */
@Composable
private fun ScheduledProposal(proposal: ScheduledBreak, use24Hour: Boolean, onAction: (ShiftAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("shift_proposal").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        Text(stringResource(R.string.shift_proposal, kindName(proposal.kind), Formats.time(proposal.start, use24Hour)),
            style = MaterialTheme.typography.bodyLarge)
        TonalButton(stringResource(R.string.shift_took), Modifier.testTag("proposal_took")) {
            onAction(ShiftAction.Scheduled(proposal.kind, true))
        }
        TextButton(onClick = { onAction(ShiftAction.Scheduled(proposal.kind, false)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("proposal_not_yet")) {
            Text(stringResource(R.string.shift_not_yet))
        }
    }
}

@Composable
private fun OnBreakCard(state: ShiftUiState.OnBreak, use24Hour: Boolean, preview: Boolean, onAction: (ShiftAction) -> Unit,
                        onEndShift: () -> Unit, modifier: Modifier) {
    val colors = HeatShieldTheme.colors
    val start = ShiftRules.instant(state.breakEvent.startedAt)
    val seconds = Duration.between(start, state.evaluation.now).seconds.coerceAtLeast(0)
    val resetsAt = start.plus(Duration.ofMinutes(ShiftRules.RESET_MINUTES.toLong()))
    val suggested = (state.evaluation.due as? BreakDue.At)?.cadence?.breakMinutes
    // Spec §7 motion: starting a break wipes the Action container down from the top. The animator scale is honoured by Compose.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = shown, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()) {
        SurfaceCard(modifier.testTag("break_card"), tint = colors.actionContainer) {
            Text(stringResource(if (preview) R.string.preview_break_title else R.string.shift_on_break_title),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(Formats.clock(seconds), Modifier.testTag("break_clock"), style = MaterialTheme.typography.displaySmall)
            Text(
                if (state.evaluation.now.isBefore(resetsAt)) stringResource(R.string.shift_on_break_resets, ShiftRules.RESET_MINUTES, Formats.time(resetsAt, use24Hour))
                else stringResource(R.string.shift_on_break_reset_done),
                style = MaterialTheme.typography.bodyLarge
            )
            if (suggested != null) Text(stringResource(R.string.shift_on_break_suggested, suggested), style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (state.breakEvent.reason == BreakReason.UNWELL) {
                Text(stringResource(R.string.shift_on_break_unwell), style = MaterialTheme.typography.bodyLarge, color = colors.emergency)
                // The card says to open Heat first aid if it gets worse, so first aid is one tap from here.
                TextButton(
                    onClick = { onAction(ShiftAction.OpenFirstAid) }, modifier = Modifier.heightIn(min = 48.dp).testTag("shift_first_aid"),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.emergency)
                ) { Text(stringResource(R.string.today_unwell)) }
            }
            ShiftPrimaryButton(stringResource(R.string.shift_break_end), Modifier.testTag("shift_break_end")) { onAction(ShiftAction.EndBreak) }
            TextButton(onClick = onEndShift, modifier = Modifier.heightIn(min = 48.dp).testTag("shift_end")) {
                Text(stringResource(if (preview) R.string.preview_shift_end else R.string.shift_end))
            }
        }
    }
}

/** The on-shift action: Action, full width, 64 dp, for gloves (spec §7). */
@Composable
private fun ShiftPrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp), shape = UiShape.control,
        colors = ButtonDefaults.buttonColors(containerColor = HeatShieldTheme.colors.action, contentColor = HeatShieldTheme.colors.onAction),
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.compact)
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/**
 * "47 min since your break · Hot 33° · next break 11:40". Numbers first (spec §8). The heat part is this hour's
 * effective level and heat number, or says there is no current forecast for the hour. A confirmed smoko or lunch still
 * running says when it ends. The stop line replaces the whole status while this hour is at it (Extreme, or a heat
 * number of 36° or more under the union table: [ShiftEvaluation.stopNow]), judged on this hour alone, so a break that
 * fell due at Hot is a stop once the hour reaches the line; a stop later in the shift is named with its time; a stop
 * the walk passed without a break, in an hour now below it, is a break due. A missing or stale forecast says breaks
 * can't be timed, never that none are due.
 */
@Composable
internal fun shiftStatusText(evaluation: ShiftEvaluation, use24Hour: Boolean): String {
    val worked = Formats.duration(evaluation.clock.workedMinutes)
    val since = stringResource(if (evaluation.clock.since == evaluation.shiftStart) R.string.shift_since_start else R.string.shift_since_break, worked)
    val heatPart = evaluation.current?.let { stringResource(R.string.shift_level_heat, it.effective.word, Formats.temperature(it.heat)) }
        ?: stringResource(R.string.shift_level_unknown)
    // The clock resets when a running smoko or lunch ends, and nothing falls due before then.
    if (onConfirmedScheduledBreak(evaluation)) {
        return stringResource(R.string.shift_status_break_until, Formats.time(evaluation.clock.since, use24Hour), heatPart)
    }
    // This hour at the stop line is the stop, whatever an earlier due time said.
    if (evaluation.stopNow) {
        return stringResource(if (evaluation.rule == BreakRule.UNION_VTHC) R.string.shift_status_union_stop else R.string.shift_status_extreme, heatPart, since)
    }
    val next = when (val due = evaluation.due) {
        is BreakDue.At -> if (due.scheduled != null) stringResource(R.string.shift_next_scheduled, kindName(due.scheduled), Formats.time(due.time, use24Hour))
            else stringResource(R.string.shift_next_break, Formats.time(due.time, use24Hour))
        is BreakDue.Stop -> {
            if (due.from.isAfter(evaluation.now)) {
                return stringResource(if (due.rule == BreakRule.UNION_VTHC) R.string.shift_status_union_stop_from else R.string.shift_status_stop_from,
                    since, heatPart, Formats.time(due.from, use24Hour))
            }
            null   // a stop the walk passed with no break, in an hour now below it: the break is due, below
        }
        BreakDue.NoneToday -> stringResource(R.string.shift_next_none)
        BreakDue.NoForecast -> stringResource(R.string.shift_next_no_forecast)
    }
    return when {
        evaluation.overdueMinutes >= 1 -> stringResource(R.string.shift_status_overdue, Formats.duration(evaluation.overdueMinutes), heatPart)
        evaluation.isDue || next == null -> stringResource(R.string.shift_status_due, heatPart, since)
        else -> stringResource(R.string.shift_status_on, since, heatPart, next)
    }
}

@Composable
internal fun kindName(kind: ScheduledKind): String =
    stringResource(if (kind == ScheduledKind.SMOKO) R.string.shift_kind_smoko else R.string.shift_kind_lunch)
