package heatshield.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.StartShiftInput
import heatshield.data.WorkContext
import heatshield.domain.BreakRule
import heatshield.domain.Effort
import heatshield.domain.ShiftRules
import heatshield.domain.WorkTasks
import java.time.Duration
import java.time.Instant

/**
 * The route's pre-fill from today's effective context at [nowMillis]: park, work, both switches, and a finish the
 * 16-hour cap allows ([ShiftRules.defaultFinish], from the real instant), so Confirm is ready whatever the hour.
 */
internal fun startShiftInitial(today: WorkContext, nowMillis: Long): StartShiftInput = StartShiftInput(
    today.site, today.work, Effort.fromLabel(today.effort) == Effort.HEAVY, today.clothing == "Heavy protective clothing",
    ShiftRules.localTime(nowMillis), ShiftRules.defaultFinish(Instant.ofEpochMilli(nowMillis), today.startHour * 60 + today.startMinute, today.endMinute)
)

/**
 * Route `start-shift` (parent Today). Everything is pre-filled from today's effective context, so the usual tap is one:
 * Start shift. "Started earlier?" opens a start TimePicker; the finish TimePicker sets when auto-end may apply, and its
 * supporting line says so. [breakRule] is the break table in use, for what the heavy-work switch says it changes.
 */
@Composable
fun StartShiftScreen(sites: List<String>, initial: StartShiftInput, nowMillis: Long, saving: Boolean,
                     breakRule: BreakRule = BreakRule.HEATSHIELD, onStart: (StartShiftInput) -> Unit) {
    val now = ShiftRules.localTime(nowMillis)
    var site by rememberSaveable { mutableStateOf(initial.site) }
    var task by rememberSaveable { mutableStateOf(initial.task) }
    var heavyWork by rememberSaveable { mutableStateOf(initial.heavyWork) }
    var heavyClothing by rememberSaveable { mutableStateOf(initial.heavyClothing) }
    var startedEarlier by rememberSaveable { mutableStateOf(false) }
    var startMinute by rememberSaveable { mutableIntStateOf(now.hour * 60 + now.minute) }
    var finishMinute by rememberSaveable { mutableIntStateOf(initial.plannedEnd.hour * 60 + initial.plannedEnd.minute) }
    val start = if (startedEarlier) now.toLocalDate().atTime(startMinute / 60, startMinute % 60) else now
    // Judged as ShiftRepository.startShift judges them: real instants, so a daylight-saving night is its real length.
    val startAt = ShiftRules.instantNotAfter(start, Instant.ofEpochMilli(nowMillis))
    // A repeated autumn finish still ahead belongs to today; otherwise a passed finish is tomorrow's night shift.
    val finish = ShiftRules.finishAfter(startAt, finishMinute)
    val startError = startedEarlier && startAt.isAfter(Instant.ofEpochMilli(nowMillis))
    val shiftMinutes = Duration.between(startAt, ShiftRules.instantAfter(finish, startAt)).toMinutes()
    val finishError = shiftMinutes <= 0 || shiftMinutes > ShiftRules.MAX_SHIFT_MINUTES
    val startErrorText = stringResource(R.string.shift_error_start_future)
    val finishErrorText = stringResource(R.string.shift_error_finish)
    PageColumn(maxWidth = 640.dp) {
        Text(stringResource(R.string.shift_start_intro), style = MaterialTheme.typography.bodyLarge)
        FormSection(stringResource(R.string.shift_start_group_where)) {
            ChoiceField(stringResource(R.string.setup_field_park), site, sites, { site = it })
            // Each option names its effort; choosing heavier work turns the switch on, and the gardener can still turn it off.
            WorkTaskField(task, label = stringResource(R.string.shift_field_work)) { task = it; heavyWork = WorkTasks.effortFor(it) == Effort.HEAVY }
            SettingSwitch(stringResource(R.string.shift_heavy_work), heavyWork, { heavyWork = it }, supporting = stringResource(heavyWorkSupporting(breakRule)))
            SettingSwitch(stringResource(R.string.profile_heavy_clothing), heavyClothing, { heavyClothing = it },
                supporting = stringResource(R.string.profile_heavy_clothing_supporting))
        }
        FormSection(stringResource(R.string.shift_start_group_when)) {
            SettingSwitch(stringResource(R.string.shift_started_earlier), startedEarlier, { startedEarlier = it },
                supporting = stringResource(R.string.shift_started_earlier_supporting))
            if (startedEarlier) {
                TimeButton(stringResource(R.string.shift_field_started), stringResource(R.string.shift_field_started), startMinute, { startMinute = it },
                    Modifier.testTag("start_shift_started").semantics { if (startError) error(startErrorText) })
                if (startError) Text(startErrorText, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            TimeButton(stringResource(R.string.shift_field_finish), stringResource(R.string.shift_field_finish), finishMinute, { finishMinute = it },
                Modifier.testTag("start_shift_finish").semantics { if (finishError) error(finishErrorText) })
            Text(stringResource(R.string.shift_finish_supporting), Modifier.testTag("start_shift_finish_note"),
                style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (finishError) Text(finishErrorText, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(stringResource(if (saving) R.string.shift_starting else R.string.shift_start), Modifier.testTag("start_shift_confirm"),
            enabled = !saving && !startError && !finishError) {
            onStart(StartShiftInput(site, task, heavyWork, heavyClothing, start, finish))
        }
    }
}
