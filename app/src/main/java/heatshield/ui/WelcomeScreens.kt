@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.UserPreferences
import heatshield.domain.Effort
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.WorkTask
import heatshield.domain.ContextReasoner
import heatshield.domain.WorkTasks
import java.time.LocalTime

/** First screen on a signed-out phone: the promise, one way in without an account, and sign-in one tap away. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onSignIn: () -> Unit, onCreateAccount: () -> Unit, busy: Boolean = false) {
    PageColumn(maxWidth = 480.dp) {
        Column(Modifier.padding(top = UiSpace.large), verticalArrangement = Arrangement.spacedBy(UiSpace.related)) {
            HeatShieldMark(size = 64.dp)
            // A heading, not a link: ink, since blue is kept for what can be tapped.
            Text(stringResource(R.string.welcome_title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium, color = Ink)
            Text(stringResource(R.string.welcome_line), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.welcome_supporting), style = MaterialTheme.typography.bodyLarge, color = Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            PrimaryButton(stringResource(R.string.welcome_get_started), enabled = !busy, onClick = onGetStarted)
            Text(stringResource(R.string.welcome_small_print), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Column {
            TextButton(onClick = onSignIn, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.welcome_sign_in), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onCreateAccount, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.welcome_create_account), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * One screen of first-run choices. Every field changes what Today shows. [upgrading] is an owner whose settings came
 * from the previous version (HeatShieldViewModel.upgradingOwner): the suggested job then matches the effort they chose
 * before, and one line says so. A new owner starts from the default job and sees no such line.
 */
@Composable
fun SetupScreen(
    sites: List<String>, initial: UserPreferences, busy: Boolean, upgrading: Boolean = false,
    onDone: (site: String, task: String, startMinute: Int, endMinute: Int, smokoMinute: Int, lunchMinute: Int, acclimatising: Boolean) -> Unit
) {
    val confirmingUpgrade = upgrading && !initial.setupDone
    var site by rememberSaveable { mutableStateOf(initial.site) }
    var task by rememberSaveable {
        mutableStateOf(if (confirmingUpgrade) WorkTasks.all.first { it.effort == ContextReasoner.usualEffort(initial) }.name
            else WorkTasks.byName(initial.task)?.name ?: WorkTasks.DEFAULT_TASK_NAME)
    }
    var start by rememberSaveable { mutableIntStateOf(initial.shiftStartMinute) }
    var end by rememberSaveable { mutableIntStateOf(initial.shiftEndMinute) }
    // Always filled (9:30 and 12:00 by default), so first run still needs no extra tap.
    var smoko by rememberSaveable { mutableIntStateOf(initial.smokoMinute) }
    var lunch by rememberSaveable { mutableIntStateOf(initial.lunchMinute) }
    var acclimatising by rememberSaveable { mutableStateOf(initial.acclimatisingUntil != null) }
    val days = HeatRules.ACCLIMATISING_DAYS.toInt()
    PageColumn(maxWidth = 640.dp) {
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
        if (confirmingUpgrade) Text(stringResource(R.string.setup_confirm_conditions),
            style = MaterialTheme.typography.bodyMedium)
        FormSection(stringResource(R.string.setup_group_where)) {
            ChoiceField(stringResource(R.string.setup_field_park), site, sites, { site = it })
            Text(stringResource(R.string.setup_park_note), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        FormSection(stringResource(R.string.setup_group_hours)) {
            UsualHoursFields(start, end, { start = it }, { end = it })
            TimeButton(stringResource(R.string.setup_field_smoko), stringResource(R.string.setup_dialog_smoko), smoko, { smoko = it }, Modifier.testTag("hours_smoko"))
            TimeButton(stringResource(R.string.setup_field_lunch), stringResource(R.string.setup_dialog_lunch), lunch, { lunch = it }, Modifier.testTag("hours_lunch"))
            Text(stringResource(R.string.setup_scheduled_note), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        FormSection(stringResource(R.string.setup_group_work)) {
            WorkTaskField(task) { task = it }
            SettingSwitch(stringResource(R.string.setup_acclimatising), acclimatising, { acclimatising = it },
                supporting = pluralStringResource(R.plurals.setup_acclimatising_supporting, days, days))
        }
        PrimaryButton(stringResource(R.string.setup_done), Modifier.testTag("setup_done"),
            enabled = !busy && SetupRules.hoursValid(start, end)) { onDone(site, task, start, end, smoko, lunch, acclimatising) }
    }
}

/**
 * Start and finish as two time-picker buttons, with the one-hour rule shown inline under them. The rule is an ordinary
 * notice ([NoticeText], ink with its icon), never red, which is kept for first aid, feeling unwell and deleting; the
 * finish field still carries the error for TalkBack, and the notice is announced when it appears.
 */
@Composable
internal fun UsualHoursFields(startMinute: Int, endMinute: Int, onStartChange: (Int) -> Unit, onEndChange: (Int) -> Unit) {
    val errorText = stringResource(R.string.setup_hours_error)
    val valid = SetupRules.hoursValid(startMinute, endMinute)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        TimeButton(stringResource(R.string.setup_field_start), stringResource(R.string.setup_dialog_start), startMinute,
            onStartChange, Modifier.testTag("hours_start"))
        TimeButton(stringResource(R.string.setup_field_finish), stringResource(R.string.setup_dialog_finish), endMinute,
            onEndChange, Modifier.testTag("hours_finish").semantics { if (!valid) error(errorText) })
        if (!valid) NoticeText(errorText, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun TimeButton(label: String, dialogTitle: String, minute: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val use24Hour = rememberUse24Hour()
    // Read with the screen's font scale and handed to the dialog: typed fields above 130 %, the dial below.
    val textInput = LocalDensity.current.fontScale > 1.3f
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true }, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = UiShape.control, colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.small)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(Formats.time(LocalTime.of(minute / 60, minute % 60), use24Hour), style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Outlined.Schedule, null, tint = PrimaryAction)
    }
    if (open) TimeDialog(dialogTitle, minute, use24Hour, textInput, onDismiss = { open = false }) { picked -> onPick(picked); open = false }
}

@Composable
internal fun TimeDialog(
    title: String, minute: Int, use24Hour: Boolean, textInput: Boolean,
    onDismiss: () -> Unit, onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = use24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (textInput) TimeInput(state = state) else TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.setup_set_time)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel)) } }
    )
}

/** Usual work dropdown; each option names the effort it implies, e.g. "Push mowing · heavy work". */
@Composable
internal fun WorkTaskField(
    taskName: String, label: String = stringResource(R.string.setup_field_work), onChange: (String) -> Unit
) {
    val options = WorkTasks.all.map { it to workTaskLabel(it) }
    val current = options.firstOrNull { it.first.name == taskName }?.second ?: workTaskName(taskName)
    ChoiceField(label, current, options.map { it.second }, { picked ->
        options.firstOrNull { it.second == picked }?.let { onChange(it.first.name) }
    })
}

/** Shows the resource-based job and effort wording while the selector keeps the saved task key. */
@Composable
internal fun workTaskLabel(task: WorkTask): String = stringResource(R.string.setup_work_option, workTaskName(task.name),
    stringResource(when (task.effort) {
        Effort.LIGHT -> R.string.setup_effort_light
        Effort.MODERATE -> R.string.setup_effort_moderate
        Effort.HEAVY -> R.string.setup_effort_heavy
    }))

/** A full-row Switch with an optional supporting line, at least 56 dp tall. */
@Composable
internal fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.related)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
