@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.data.MelbourneZone
import heatshield.domain.BreakRule
import heatshield.domain.Citation
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatRules
import heatshield.domain.HeatSources
import heatshield.domain.SourceLabel
import heatshield.domain.WorkTasks
import java.time.Instant
import java.time.LocalDate

private const val HEAVY_CLOTHING = "Heavy protective clothing"
private const val STANDARD_CLOTHING = "Standard workwear"

/** Your work, hot-day rule, breaks, alerts, preview, account, then sources. Every change saves immediately. */
@Composable
fun ProfileScreen(model: HeatShieldViewModel, onOpenPreview: () -> Unit, onSignIn: () -> Unit, onCreateAccount: () -> Unit) {
    val prefs = model.preferences
    // Today's Melbourne date from the app's ticking clock, so "back from leave" turns off at midnight while Profile stays
    // open, and turning it on counts from the real day. Profile recomposes when the date changes, not every second.
    val today by remember(model) { derivedStateOf { Instant.ofEpochMilli(model.now).atZone(MelbourneZone).toLocalDate() } }
    val acclimatising = ContextReasoner.profileOf(prefs, today).factors.acclimatising
    // The stored end is exclusive (midnight starting day +7), so the last day that counts is the day before it.
    val lastAcclimatisingDay = prefs.acclimatisingUntil?.let {
        Instant.ofEpochMilli(it).atZone(MelbourneZone).toLocalDate().minusDays(1)
    }
    val days = HeatRules.ACCLIMATISING_DAYS.toInt()
    PageColumn {
        FormSection(stringResource(R.string.profile_group_work)) {
            ChoiceField(stringResource(R.string.setup_field_park), prefs.site, model.sites.map { it.name }, { model.selectSite(it) })
            WorkTaskField(prefs.task) { name ->
                model.updatePreferences { it.copy(task = name, effort = WorkTasks.effortFor(name).label) }
            }
            // A valid change also deletes a plan saved for today or later and re-plans the alerts (persistPreferences).
            ProfileHoursFields(prefs.ownerId, prefs.shiftStartMinute, prefs.shiftEndMinute) { newStart, newEnd ->
                model.updatePreferences { it.copy(shiftStartMinute = newStart, shiftEndMinute = newEnd) }
            }
            // Proposal times only: changing them never invalidates a saved plan (persistPreferences compares
            // effort, clothing and usual hours).
            TimeButton(stringResource(R.string.setup_field_smoko), stringResource(R.string.setup_dialog_smoko), prefs.smokoMinute,
                { minute -> model.updatePreferences { it.copy(smokoMinute = minute) } }, Modifier.testTag("hours_smoko"))
            TimeButton(stringResource(R.string.setup_field_lunch), stringResource(R.string.setup_dialog_lunch), prefs.lunchMinute,
                { minute -> model.updatePreferences { it.copy(lunchMinute = minute) } }, Modifier.testTag("hours_lunch"))
            Text(stringResource(R.string.setup_scheduled_note), style = MaterialTheme.typography.bodyMedium, color = Muted)
            SettingSwitch(stringResource(R.string.profile_heavy_clothing), prefs.clothing == HEAVY_CLOTHING, { on ->
                model.updatePreferences { it.copy(clothing = if (on) HEAVY_CLOTHING else STANDARD_CLOTHING) }
            }, supporting = stringResource(R.string.profile_heavy_clothing_supporting))
            SettingSwitch(stringResource(R.string.profile_acclimatising), acclimatising, { on ->
                model.updatePreferences { it.copy(acclimatisingUntil = if (on) SetupRules.acclimatisingUntil(today) else null) }
            }, supporting = if (acclimatising && lastAcclimatisingDay != null)
                stringResource(R.string.profile_acclimatising_until, Formats.dayDate(lastAcclimatisingDay))
            else pluralStringResource(R.plurals.setup_acclimatising_supporting, days, days))
        }
        FormSection(stringResource(R.string.profile_group_rule)) {
            HotDayRuleSection(prefs.hotDayTrigger, { value -> model.updatePreferences { it.copy(hotDayTrigger = value) } })
        }
        FormSection(stringResource(R.string.profile_group_breaks)) {
            BreakRuleSection(BreakRule.fromStored(prefs.breakRule), { rule -> model.updatePreferences { it.copy(breakRule = rule.stored) } })
        }
        // onRefresh reconciles: a notification grant from the permission dialog arms the shift's alarm and timer at once.
        AlertsGroup(model.alertStatus, onAlertsChange = model::setAlerts, onRefresh = model::refreshAlerts)
        PreviewRow(onOpenPreview)
        AccountGroup(local = model.ownerId == "local", email = model.email, configured = model.firebaseConfigured,
            parkListStatus = model.catalogueMessage, busy = model.busy,
            onSignIn = onSignIn, onCreateAccount = onCreateAccount,
            onRefreshParks = model::loadSites, onSignOut = model::signOut)
        AboutGroup()
    }
}

/**
 * Profile's usual start and finish (minutes of the day) for [ownerId]. The fields show the stored hours [savedStart]
 * and [savedEnd], except while the gardener holds a pair that is not valid yet (finish under an hour after start): that
 * draft stays on screen with its inline error and nothing is saved. A valid pair clears the draft and goes to [onSave],
 * so from then on the fields show whatever the stored row says, however quickly a failed save puts it back.
 * Example: stored 07:00–15:00. Picking a 07:30 finish shows 07:00–07:30 with the error and saves nothing. Picking an
 * 08:00 start instead saves 08:00–15:00; if that write fails and the model restores 07:00–15:00, the start shows 07:00.
 */
@Composable
internal fun ProfileHoursFields(ownerId: String, savedStart: Int, savedEnd: Int, onSave: (Int, Int) -> Unit) {
    // Only a pair that is not valid yet lives here; null means "show the stored hours".
    var draft by rememberSaveable(ownerId) { mutableStateOf<Pair<Int, Int>?>(null) }
    val (start, end) = draft ?: (savedStart to savedEnd)
    val pick: (Int, Int) -> Unit = { newStart, newEnd ->
        if (SetupRules.hoursValid(newStart, newEnd)) {
            draft = null
            onSave(newStart, newEnd)
        } else draft = newStart to newEnd
    }
    UsualHoursFields(start, end, { pick(it, end) }, { pick(start, it) })
}

/**
 * The crew rule as RadioButtons: the 35° preset, 38°, or a custom value checked by [HeatRules.isValidTrigger].
 * One chip names who set the rule in force (spec §5.7): a preset is a workplace-rule example, a custom rule is
 * the user's own setting. The examples below it are references, so they carry no chip of their own.
 */
@Composable
fun HotDayRuleSection(triggerC: Double, onTriggerChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    var custom by rememberSaveable { mutableStateOf(triggerC !in HeatRules.TRIGGER_PRESETS) }
    var text by rememberSaveable { mutableStateOf(SetupRules.formatTrigger(triggerC)) }
    val errorText = if (custom && SetupRules.parseTrigger(text) == null) stringResource(R.string.profile_rule_custom_error) else null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        Column(Modifier.selectableGroup()) {
            HeatRules.TRIGGER_PRESETS.forEach { preset ->
                val label = if (preset == HeatRules.DEFAULT_TRIGGER_C)
                    stringResource(R.string.profile_rule_default, Formats.temperature(preset)) else Formats.temperature(preset)
                RuleOption(label, selected = !custom && triggerC == preset) {
                    custom = false; text = SetupRules.formatTrigger(preset); onTriggerChange(preset)
                }
            }
            RuleOption(stringResource(R.string.profile_rule_custom), selected = custom) { custom = true }
        }
        if (custom) OutlinedTextField(
            value = text,
            // Invalid text never reaches the saved rule; the previous rule stays in force.
            onValueChange = { value -> text = value; SetupRules.parseTrigger(value)?.let(onTriggerChange) },
            modifier = Modifier.fillMaxWidth().testTag("rule_custom").semantics { if (errorText != null) error(errorText) },
            label = { Text(stringResource(R.string.profile_rule_custom_label)) },
            // The only typed field here, so it carries the asterisk; the legend gives way to the error.
            supportingText = { Text(errorText ?: stringResource(R.string.profile_rule_required_legend)) },
            isError = errorText != null,
            // Whole degrees only, so the number pad has no decimal key.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true, shape = UiShape.control
        )
        // A draft custom value may be invalid; label the saved rule that is actually in force.
        SourceChip(if (triggerC in HeatRules.TRIGGER_PRESETS) SourceLabel.WORKPLACE_RULE_EXAMPLE else SourceLabel.YOUR_SETTING)
        Text(stringResource(R.string.profile_rule_helper), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(stringResource(R.string.profile_rule_examples), style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

/**
 * Which rhythm times the breaks (spec §5.10). HeatShield's suggestion runs on the heat number with personal adjustments;
 * the union option adapts VTHC's rows to the heat number and the work clock, so both carry [HeatShield rule] and the
 * option's text credits VTHC.
 */
@Composable
fun BreakRuleSection(rule: BreakRule, onRuleChange: (BreakRule) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        Text(stringResource(R.string.profile_break_rule), style = MaterialTheme.typography.labelLarge, color = Muted)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            BreakRule.entries.forEach { option ->
                val heatShield = option == BreakRule.HEATSHIELD
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = rule == option, role = Role.RadioButton, onClick = { onRuleChange(option) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)
                ) {
                    RadioButton(selected = rule == option, onClick = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
                        Text(stringResource(if (heatShield) R.string.profile_break_rule_heatshield else R.string.profile_break_rule_vthc), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(if (heatShield) R.string.profile_break_rule_heatshield_supporting else R.string.profile_break_rule_vthc_supporting),
                            style = MaterialTheme.typography.bodyMedium, color = Muted)
                    }
                }
            }
        }
        SourceChip(rule.source)
    }
}

@Composable
private fun RuleOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreviewRow(onOpenPreview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("profile_preview")
            .clickable(role = Role.Button, onClick = onOpenPreview).padding(vertical = UiSpace.small),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.related)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            Text(stringResource(R.string.profile_preview_title), style = MaterialTheme.typography.titleMedium, color = PrimaryAction)
            Text(stringResource(R.string.profile_preview_supporting), style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = PrimaryAction)
    }
}

@Composable
private fun AccountGroup(
    local: Boolean, email: String?, configured: Boolean, parkListStatus: String, busy: Boolean,
    onSignIn: () -> Unit, onCreateAccount: () -> Unit, onRefreshParks: () -> Unit, onSignOut: () -> Unit
) {
    FormSection(stringResource(R.string.profile_group_account)) {
        if (local) {
            Text(stringResource(R.string.profile_account_local), style = MaterialTheme.typography.bodyLarge)
            if (configured) {
                Text(stringResource(R.string.profile_account_local_supporting), style = MaterialTheme.typography.bodyMedium, color = Muted)
                TonalButton(stringResource(R.string.profile_sign_in), enabled = !busy, onClick = onSignIn)
                TextButton(onClick = onCreateAccount, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profile_create_account))
                }
            } else {
                // No account buttons that lead nowhere in a build without Firebase config.
                Text(stringResource(R.string.auth_unavailable), style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        } else {
            Text(email ?: stringResource(R.string.profile_account_signed_in), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onRefreshParks, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profile_refresh_parks))
            }
            // The result of the last park-list load, so a refresh always shows what happened.
            Text(parkListStatus, Modifier.testTag("park_list_status"), style = MaterialTheme.typography.bodyMedium, color = Muted)
            TonalButton(stringResource(R.string.profile_sign_out), enabled = !busy, onClick = onSignOut)
        }
    }
}

/**
 * The one place the general limits statement appears (spec §5.15), followed by every citation. Context a decision
 * depends on (partial coverage, the forecast stamp, the crew rule's "confirm with your team leader", the Extreme
 * stop line) stays beside that decision on Today, not here.
 */
@Composable
private fun AboutGroup() {
    FormSection(stringResource(R.string.profile_group_about)) {
        Text(stringResource(R.string.profile_about_limits), Modifier.testTag("about_limits"), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.setup_park_note), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Disclosure(stringResource(R.string.profile_about_sources)) {
            HeatSources.all.forEach { citation -> SourceRow(citation) }
        }
        Text(stringResource(R.string.profile_about_data), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.profile_about_fonts), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SourceRow(citation: Citation) {
    Column(Modifier.fillMaxWidth().padding(vertical = UiSpace.tiny), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
        Text(citation.title, style = MaterialTheme.typography.bodyLarge)
        // Only the rule bodies carry a label (Task 2); data and first-aid sources show no chip.
        citation.label?.let { SourceChip(it) }
        SelectionContainer { Text(citation.url, style = MaterialTheme.typography.bodyMedium, color = Muted) }
    }
}
