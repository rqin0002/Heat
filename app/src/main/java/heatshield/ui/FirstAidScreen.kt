package heatshield.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heatshield.R

/** Route, launcher action and phone numbers for the offline first-aid card. No Android types, so JVM tests can check them. */
object FirstAid {
    const val ROUTE = "first-aid"
    const val ACTION = "heatshield.action.FIRST_AID"
    const val EMERGENCY_NUMBER = "000"
    const val NURSE_ON_CALL_NUMBER = "1300606024"
    fun routeFor(action: String?): String? = if (action == ACTION) ROUTE else null
    fun dialUri(number: String): String = "tel:$number"
}

/** Opens the phone app with the number typed in. ACTION_DIAL never places the call, so it needs no permission. */
@Composable
fun rememberDialer(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { number: String ->
            try {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(FirstAid.dialUri(number))))
                true
            } catch (missing: ActivityNotFoundException) {
                false
            } catch (blocked: SecurityException) {
                false
            }
        }
    }
}

/**
 * Offline heat first aid (spec 5.14): Call 000, the unresponsive airway/breathing branch, then cooling;
 * early signs and the steps for someone awake follow
 * (the only place the 10-minute rule appears), then Nurse-On-Call.
 */
@Composable
fun FirstAidScreen(onDial: (String) -> Boolean, modifier: Modifier = Modifier) {
    val colors = HeatShieldTheme.colors
    var failedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    val dial = { number: String -> failedNumber = if (onDial(number)) null else number }
    PageColumn(modifier, maxWidth = 640.dp) {
        Text(stringResource(R.string.first_aid_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text(stringResource(R.string.first_aid_check_response), style = MaterialTheme.typography.bodyLarge)
        // Keep the emergency action immediately available.
        // onEmergency is the ON_EMERGENCY token, the pair ThemeContrastTest checks against emergency in both themes.
        Button(
            onClick = { dial(FirstAid.EMERGENCY_NUMBER) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("first_aid_call_000"),
            shape = UiShape.control,
            colors = ButtonDefaults.buttonColors(containerColor = colors.emergency, contentColor = colors.onEmergency)
        ) {
            Icon(Icons.Outlined.Call, contentDescription = null)
            Spacer(Modifier.width(UiSpace.small))
            Text(stringResource(R.string.first_aid_call_000), style = MaterialTheme.typography.titleMedium)
        }
        if (failedNumber == FirstAid.EMERGENCY_NUMBER) Text(stringResource(R.string.first_aid_no_phone_app),
            style = MaterialTheme.typography.bodyLarge, color = colors.emergency)
        Card(
            Modifier.fillMaxWidth(), shape = UiShape.card,
            // The first-aid banner: Emergency container behind Ink steps and Emergency headings (palette spec).
            colors = CardDefaults.cardColors(containerColor = colors.emergencyContainer, contentColor = colors.ink),
            border = BorderStroke(2.dp, colors.emergency)
        ) {
            Column(Modifier.padding(UiSpace.related), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
                // 1. Brain or collapse signs: Call 000 now.
                Text(stringResource(R.string.first_aid_call_now_heading), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = colors.emergency)
                Column(Modifier.testTag("first_aid_call_now_signs"), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
                    stringArrayResource(R.array.first_aid_call_now_signs).forEach { BulletLine(it) }
                }
                // Sweating is no all-clear: exertional heatstroke in a worker often comes with wet skin.
                Text(stringResource(R.string.first_aid_still_sweating), style = MaterialTheme.typography.bodyLarge)
                // Response, airway and breathing precede detailed cooling.
                Column(Modifier.testTag("first_aid_unresponsive"), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
                    Text(stringResource(R.string.first_aid_unresponsive_heading), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium, color = colors.emergency)
                    stringArrayResource(R.array.first_aid_unresponsive_steps).forEach { BulletLine(it) }
                }
                // Cooling follows the airway/breathing branch while help is on the way.
                Text(stringResource(R.string.first_aid_while_you_wait), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Column(Modifier.testTag("first_aid_cooling"), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
                    stringArrayResource(R.array.first_aid_cooling_steps).forEach { BulletLine(it) }
                }

            }
        }
        // 4. Someone awake with early signs. Only these steps carry the 10-minute rule, as their last step.
        FormSection(stringResource(R.string.first_aid_early_signs_heading)) {
            Column(Modifier.testTag("first_aid_early_signs"), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
                stringArrayResource(R.array.first_aid_early_signs).forEach { BulletLine(it) }
            }
        }
        FormSection(stringResource(R.string.first_aid_steps_heading), stringResource(R.string.first_aid_steps_subtitle)) {
            stringArrayResource(R.array.first_aid_steps).forEachIndexed { index, step -> StepLine(index + 1, step) }
        }
        HorizontalDivider(color = colors.line)
        Text(stringResource(R.string.first_aid_stop_work), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold, color = colors.ink)
        Column(verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            Text(stringResource(R.string.first_aid_nurse_on_call), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            TonalButton(stringResource(R.string.first_aid_nurse_button), Modifier.testTag("first_aid_nurse")) {
                dial(FirstAid.NURSE_ON_CALL_NUMBER)
            }
            if (failedNumber == FirstAid.NURSE_ON_CALL_NUMBER) Text(
                stringResource(R.string.first_aid_no_advice_phone_app),
                style = MaterialTheme.typography.bodyLarge, color = colors.emergency)
        }
        Text(stringResource(R.string.first_aid_team_leader), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
        Column(verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            Text(stringResource(R.string.first_aid_attribution), style = MaterialTheme.typography.labelLarge, color = colors.muted)
            Text(stringResource(R.string.first_aid_disclaimer), style = MaterialTheme.typography.labelLarge, color = colors.muted)
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    val style = MaterialTheme.typography.bodyLarge
    // Centre the dot on the first line at any font scale.
    val lineHeight = with(LocalDensity.current) { if (style.lineHeight.isSp) style.lineHeight.toDp() else 24.dp }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)) {
        Box(Modifier.padding(top = (lineHeight - 6.dp) / 2).size(6.dp).background(HeatShieldTheme.colors.ink, CircleShape))
        Text(text, Modifier.weight(1f), style = style, color = HeatShieldTheme.colors.ink)
    }
}

/** Shows a numbered first-aid instruction in Ink, without suggesting the number can be tapped. */
@Composable
private fun StepLine(number: Int, text: String) {
    val colors = HeatShieldTheme.colors
    // One merged node per step, so a screen reader says the number with its step.
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("first_aid_step_$number"),
        horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)
    ) {
        Text(number.toString(), Modifier.widthIn(min = 20.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AtkinsonMono, fontWeight = FontWeight.SemiBold),
            color = colors.ink)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
}
