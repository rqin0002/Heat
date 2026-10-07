// Shows the labelled historical hot day and practice shift, separate from today's forecast.
// The PreviewViewModel keeps an unsaved completed shift available while this screen retries its save.
package heatshield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import heatshield.R
import heatshield.background.ShiftNotifications
import heatshield.data.BreakReason
import heatshield.data.PreviewShiftResult
import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftRepository
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.data.WorkSite
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import java.time.temporal.ChronoUnit

private val BannerShape = RoundedCornerShape(8.dp)

/**
 * Route `preview`: the labelled 9 Jan 2026 sensor day. Parent is Today; the bottom bar is hidden. The preview shift runs
 * on the virtual clock through the same [ShiftRules.evaluate] as Today; End preview shift hands it to [onSavePreviewShift],
 * whose success callback clears the retained result. A failed save stays retryable; [saving] blocks repeat submissions
 * and Exit. A new practice shift cannot start while a real shift is open.
 */
@Composable
fun PreviewScreen(profile: HeatProfile, onExit: () -> Unit, preview: PreviewViewModel = viewModel(), realShiftOpen: Boolean = false,
                  breakRule: BreakRule = BreakRule.HEATSHIELD, smokoMinute: Int = 570, lunchMinute: Int = 720,
                  saving: Boolean = false, onSavePreviewShift: (PreviewShiftResult, () -> Unit) -> Unit = { _, _ -> }) {
    LifecycleStartEffect(profile) {
        preview.start(profile)
        onStopOrDispose { preview.stop() }
    }
    val state by preview.state.collectAsStateWithLifecycle()
    val unavailable by preview.unavailable.collectAsStateWithLifecycle()
    val shift by preview.shift.collectAsStateWithLifecycle()
    val pendingSave by preview.pendingSave.collectAsStateWithLifecycle()
    val savePreview: (PreviewShiftResult) -> Unit = { result ->
        if (!saving) onSavePreviewShift(result, preview::discardShift)
    }
    PreviewContent(
        state, unavailable, onNext = preview::next, onExit = { if (!saving) { preview.discardShift(); onExit() } },
        shift = shift, profile = profile, realShiftOpen = realShiftOpen, breakRule = breakRule,
        smokoMinute = smokoMinute, lunchMinute = lunchMinute, dayHours = preview::dayHours,
        pendingSave = pendingSave, saving = saving, onRetrySave = { pendingSave?.let(savePreview) },
        onShiftAction = { action ->
            when (action) {
                ShiftAction.OpenStart -> preview.startShift(profile.factors.heavyWork)
                ShiftAction.StartBreak -> preview.startBreak(BreakReason.SHADE)
                ShiftAction.EndBreak -> preview.endBreak()
                ShiftAction.OpenBreakSheet -> preview.cantStop()
                ShiftAction.EndShift -> if (!saving) preview.endShift()?.let(savePreview)
                is ShiftAction.Scheduled -> preview.confirmScheduled(action.kind, action.taken, if (action.kind == ScheduledKind.SMOKO) smokoMinute else lunchMinute)
                is ShiftAction.HeavyWork -> preview.setHeavyWork(action.on)
                ShiftAction.OpenFirstAid -> Unit   // the preview starts no "I don't feel right" break
            }
        }
    )
}

/**
 * Shows the sensor reading and practice shift, or its unsaved result with a retry action. It also manages the preview
 * notification: a due alert is refreshed silently as heat changes. Tags include preview_banner, preview_sensor_chip,
 * preview_save_retry and the shared shift-panel tags.
 */
@Composable
fun PreviewContent(state: PreviewState?, unavailable: Boolean, onNext: () -> Unit, onExit: () -> Unit,
                   modifier: Modifier = Modifier, shift: PreviewShift? = null, profile: HeatProfile? = null,
                   realShiftOpen: Boolean = false, breakRule: BreakRule = BreakRule.HEATSHIELD, smokoMinute: Int = 570,
                   lunchMinute: Int = 720, dayHours: (Boolean) -> List<HourHeat> = { emptyList() },
                   pendingSave: PreviewShiftResult? = null, saving: Boolean = false, onRetrySave: () -> Unit = {},
                   onShiftAction: (ShiftAction) -> Unit = {}) {
    // Preview alerts (spec §10 M3): the same presenter, prefixed, posted from the virtual clock; no alarm, no ledger row.
    // Declared here, not inside the `state != null` branch, because Exit below and the dispose effect cancel it from this scope.
    // It creates its own channels (idempotent): Android drops a post to a missing channel while post() still returns true,
    // so without them `alertedFor` would record a due time whose notification never appeared.
    val context = LocalContext.current
    val notifications = remember(context) { ShiftNotifications(context).also { it.ensureChannels() } }
    // Leaving the screen clears the preview alert; a rotation does not (the saved alertedFor would not post it again).
    val activity = LocalActivity.current
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) notifications.cancel(ShiftNotifications.ID_PREVIEW) } }
    val colors = HeatShieldTheme.colors
    val use24Hour = rememberUse24Hour()
    PageColumn(modifier, maxWidth = 720.dp) {
        PreviewBanner()
        when {
            state != null -> {
                val heat = state.heat
                val level = heat.current.effective
                val clock = Formats.time(heat.current.time.toLocalTime().truncatedTo(ChronoUnit.MINUTES), use24Hour)
                val chipDescription = stringResource(R.string.preview_sensor_chip_description,
                    clock, heat.reading.temperature, heat.reading.humidity)
                Text(
                    stringResource(R.string.preview_sensor_chip, clock, heat.reading.temperature, heat.reading.humidity),
                    Modifier.testTag("preview_sensor_chip").semantics { contentDescription = chipDescription }
                        .border(1.dp, colors.line, BannerShape).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = AtkinsonMono), color = colors.ink
                )
                val eyebrow = stringResource(R.string.preview_eyebrow, Formats.fullDate(SensorReplaySimulator.PREVIEW_DATE),
                    Formats.time(state.virtualTime.toLocalTime().truncatedTo(ChronoUnit.MINUTES), use24Hour))
                val numeral = Formats.temperature(heat.current.heat)
                val air = Formats.temperature(heat.current.airC)
                HeatHero(
                    level = level,
                    numeral = numeral,
                    levelQualifier = if (heat.current.effective != heat.current.base) stringResource(R.string.preview_level_qualifier) else null,
                    caption = stringResource(R.string.preview_caption),
                    eyebrow = {
                        Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = HeatPalette.heroContent(level, colors.isDark))
                    },
                    // As on Today, compare the digits shown, so "feels like" never labels a number that reads the same as air.
                    numeralLabel = if (numeral != air) stringResource(R.string.preview_numeral_feels, air)
                        else stringResource(R.string.preview_numeral_air)
                )
                // Spec §5.10 and §12, as on Today: at Extreme, stop heavy work outdoors, directly under the hero, then
                // what to do if the crew keeps working. No work/rest cadence at Extreme.
                if (level == HeatLevel.EXTREME) {
                    Text(
                        stringResource(R.string.preview_extreme),
                        Modifier.fillMaxWidth().testTag("preview_extreme").semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium, color = colors.ink
                    )
                    Text(
                        stringResource(R.string.preview_extreme_keep_working),
                        Modifier.fillMaxWidth().testTag("preview_extreme_keep_working"),
                        style = MaterialTheme.typography.bodyLarge, color = colors.ink
                    )
                }
                // Every hour of the sensor day has a reading, so no cell is ever missing. A cell is hatched when any
                // reading in its hour reached the rule (previewHeat), even if the reading shown is not that one.
                HeatRibbon(heat.dayHours, state.virtualTime, heat.crewRule.triggerC, sunProtection = null)
                val trigger = Formats.temperature(heat.crewRule.triggerC)
                // The reasoner includes whole intersecting hours: 09:30–10:30 covers 09:00–11:00, so the
                // scope must include a crossing at 10:52 even though it follows the chosen finish minute.
                val crewHours = heat.dayHours.takeIf { it.isNotEmpty() }?.let { hours ->
                    stringResource(R.string.preview_crew_hours,
                        Formats.time(hours.first().time.toLocalTime(), use24Hour),
                        Formats.time(hours.last().time.plusHours(1).toLocalTime(), use24Hour))
                } ?: stringResource(R.string.preview_crew_usual_hours)
                val crewLine = heat.crewRule.firstHour
                    ?.let { stringResource(R.string.preview_crew_passed, trigger, Formats.time(it, use24Hour), crewHours) }
                    ?: stringResource(R.string.preview_crew_under, trigger, crewHours)
                Text(crewLine, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                // The virtual shift, evaluated on its own virtual clock against the preview day's hour-max heat (spec §10 M2).
                val shiftState: ShiftUiState = if (shift != null && profile != null) {
                    val row = previewShiftRow(shift, profile)
                    val evaluation = ShiftRules.evaluate(OpenShift(row, shift.events), dayHours(shift.heavyWork), breakRule, smokoMinute, lunchMinute,
                        ShiftRules.instant(shift.nowAt(state.virtualTime)))
                    evaluation.openBreak?.let { ShiftUiState.OnBreak(row, evaluation, it) } ?: ShiftUiState.On(row, evaluation)
                } else ShiftUiState.Off
                if (pendingSave != null) {
                    SurfaceCard {
                        Text(stringResource(if (saving) R.string.preview_saving else R.string.preview_save_pending),
                            style = MaterialTheme.typography.bodyLarge)
                        PrimaryButton(stringResource(R.string.preview_save_retry), Modifier.testTag("preview_save_retry"),
                            enabled = !saving, onClick = onRetrySave)
                    }
                } else {
                    ShiftPanel(shiftState, onAction = onShiftAction, preview = true, startEnabled = !realShiftOpen && !saving,
                        blockedReason = if (realShiftOpen) stringResource(R.string.preview_shift_blocked) else null, breakRule = breakRule)
                }
                var alertedFor by rememberSaveable { mutableStateOf<Long?>(null) }
                val working = (shiftState as? ShiftUiState.On)?.evaluation
                val due = working?.takeIf { it.isDue }?.due as? BreakDue.At
                LaunchedEffect(due, working?.current, breakRule) {
                    // As the live reconcile does: a break no longer due (started, or answered "Can't stop yet") takes its alert away.
                    if (due == null) { notifications.cancel(ShiftNotifications.ID_PREVIEW); return@LaunchedEffect }
                    val evaluation = working ?: return@LaunchedEffect
                    // The live policy's quiet rules: no buzz at Normal, by the due's level or this hour's. A break waiting for a
                    // smoko or lunch is due at its time and posts then, as live.
                    if (due.level == HeatLevel.NORMAL || evaluation.current?.effective == HeatLevel.NORMAL) {
                        notifications.cancel(ShiftNotifications.ID_PREVIEW)
                        return@LaunchedEffect
                    }
                    val key = due.time.toEpochMilli()
                    // A posted 10:30 due becomes a silent stop instruction at 11:00 Extreme; the builder uses
                    // only-alert-once. A dismissed alert stays dismissed even when that same due's heat changes.
                    if (alertedFor == key && !notifications.isShown(ShiftNotifications.ID_PREVIEW)) return@LaunchedEffect
                    if (notifications.post(ShiftNotifications.ID_PREVIEW, notifications.previewBreakDue(due, evaluation, evaluation.current, breakRule))) alertedFor = key
                }
                Text(stringResource(R.string.preview_speed_note), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                // The shift panel owns the screen's one filled action (spec §7), so Next reading is tonal; Exit preview
                // below is a text button.
                TonalButton(stringResource(R.string.preview_next), onClick = onNext)
            }
            unavailable -> Text(stringResource(R.string.preview_unavailable), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.preview_loading), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            }
        }
        TextButton(onClick = { notifications.cancel(ShiftNotifications.ID_PREVIEW); onExit() }, enabled = !saving,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.preview_exit)) }
        Text(stringResource(R.string.preview_credit), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}

/**
 * The virtual shift as the rules see it: the labelled day, the Birrarung Marr sensor, and the planned finish Start shift
 * would pre-fill at the preview's start (the usual finish while it is ahead, else the usual length on), so it is never
 * before the start.
 */
internal fun previewShiftRow(shift: PreviewShift, profile: HeatProfile): WorkSession = WorkSession(
    id = 0, ownerId = "preview", title = profile.taskName, site = WorkSite.defaults.first().name,
    date = shift.start.toLocalDate().format(ShiftRepository.RECORD_DATE), outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE,
    startedAt = ShiftRules.epochMillis(shift.start),
    plannedEndAt = ShiftRules.epochMillis(ShiftRules.defaultFinish(shift.start, profile.shiftStartMinute, profile.shiftEndMinute)),
    source = ShiftSource.PREVIEW, heavyWork = shift.heavyWork
)

/** Hatched, so a screenshot of Preview can never pass for today's heat. */
@Composable
private fun PreviewBanner() {
    val colors = HeatShieldTheme.colors
    val stripe = colors.ink.copy(alpha = 0.18f)
    Box(
        Modifier.fillMaxWidth().clip(BannerShape).background(colors.paper).border(1.dp, colors.ink, BannerShape)
            .testTag("preview_banner"),
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.matchParentSize()) {
            val gap = 12.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 4.dp.toPx())
                x += gap
            }
        }
        Text(
            stringResource(R.string.preview_banner, Formats.fullDate(SensorReplaySimulator.PREVIEW_DATE)),
            Modifier.padding(12.dp).background(colors.surface, BannerShape).padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge, color = colors.ink
        )
    }
}
