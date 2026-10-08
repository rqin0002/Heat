@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import heatshield.R
import heatshield.background.AlertStatus
import heatshield.background.ShiftNotifications

/** Asks for POST_NOTIFICATIONS on API 33+; below it, notifications need no runtime grant, so [onResult] gets true at once. */
@Composable
private fun rememberNotificationPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else onResult(true)
    }
}

/**
 * Opens an Android settings page, falling back to HeatShield's App info page, and to nothing, when a phone's build has no
 * such page or refuses it (Global Constraint 6: permissions degrade, never crash).
 */
private fun Context.startSettings(intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    for (candidate in listOf(intent, fallback)) {
        try {
            startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

private fun openNotificationSettings(context: Context) = context.startSettings(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

private fun openChannelSettings(context: Context) = context.startSettings(
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, ShiftNotifications.CHANNEL_BREAK_DUE))

/** HeatShield's own Alarms & reminders page: without the `package:` URI the intent opens the general list. */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) context.startSettings(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
}

/**
 * Route `alerts-intro` (parent Today): the owner's first Start shift, once. A card previews the exact notification before
 * any prompt. A refusal is answered by the caller, in the app's message bar, as the screen closes.
 */
@Composable
fun AlertsIntroScreen(onAllow: (granted: Boolean) -> Unit, onNotNow: () -> Unit, busy: Boolean) {
    val request = rememberNotificationPermission(onAllow)
    PageColumn(maxWidth = 640.dp) {
        Text(stringResource(R.string.alerts_intro_text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.alerts_intro_preview_label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        SurfaceCard(Modifier.testTag("alerts_intro_card")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)) {
                Icon(painterResource(R.drawable.ic_stat_heatshield), null, Modifier.size(20.dp), tint = HeatShieldTheme.colors.action)
                Text(stringResource(R.string.app_name_short), style = MaterialTheme.typography.labelLarge, color = Muted)
            }
            Text(stringResource(R.string.alerts_intro_sample_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.alerts_intro_sample_text), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.related)) {
                listOf(R.string.shift_break_start, R.string.alert_action_snooze, R.string.alert_action_unwell).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.labelLarge, color = PrimaryAction)
                }
            }
        }
        Text(stringResource(R.string.alerts_intro_also), style = MaterialTheme.typography.bodyMedium, color = Muted)
        PrimaryButton(stringResource(R.string.alerts_intro_allow), Modifier.testTag("alerts_allow"), enabled = !busy) { request() }
        TextButton(onClick = onNotNow, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("alerts_not_now")) { Text(stringResource(R.string.alerts_intro_not_now)) }
    }
}

/**
 * Profile › Alerts: the Switch, then one honest row per Android limit while it is on. [onRefresh] runs on every resume and
 * after the notification permission dialog answers. The dialog only pauses the activity, so the caller must reconcile
 * there as well as re-read the rows: a grant has to arm the shift's alarm and timer at once.
 */
@Composable
fun AlertsGroup(status: AlertStatus, onAlertsChange: (Boolean) -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onRefresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val activity = LocalActivity.current
    // After a permanent denial Android answers "denied" at once with no dialog: the app's notification settings are then the
    // only place left to allow them, so the button opens them rather than doing nothing.
    val request = rememberNotificationPermission { granted ->
        onRefresh()
        if (!granted && Build.VERSION.SDK_INT >= 33 && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings(context)
    }
    FormSection(stringResource(R.string.profile_group_alerts)) {
        // Turning the switch on asks for the permission it needs, as the intro's Allow does.
        SettingSwitch(stringResource(R.string.profile_alerts_switch), status.alertsOn, { on ->
            onAlertsChange(on)
            if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) request()
        }, supporting = stringResource(R.string.profile_alerts_switch_supporting), modifier = Modifier.testTag("alerts_switch"))
        if (status.alertsOn) {
            StatusRow(stringResource(if (status.notificationsAllowed) R.string.alerts_row_notifications_on else R.string.alerts_row_notifications_off), "alerts_row_notifications",
                action = if (status.notificationsAllowed) null else stringResource(R.string.alerts_action_allow_notifications)) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) request()
                else openNotificationSettings(context)
            }
            StatusRow(stringResource(if (status.exactAllowed) R.string.alerts_row_exact_on else R.string.alerts_row_exact_off), "alerts_row_exact",
                action = if (status.exactAllowed) null else stringResource(R.string.alerts_action_allow_exact)) { openExactAlarmSettings(context) }
            if (status.breakChannelSilent) StatusRow(stringResource(R.string.alerts_row_channel_silent), "alerts_row_channel",
                action = stringResource(R.string.alerts_action_channel)) { openChannelSettings(context) }
        }
    }
}

@Composable
private fun StatusRow(text: String, tag: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
        // A row that needs the user's action is a notice: Ink text with an icon, never a warm colour (palette spec).
        if (action == null) Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = Ink)
        else NoticeText(text, Modifier.testTag(tag))
        if (action != null) TonalButton(action, onClick = onAction)
    }
}

/** Today's banner when alerts are on but Android blocks HeatShield's notifications. Nothing when alerts are off. */
@Composable
fun AlertsBanner(status: AlertStatus, modifier: Modifier = Modifier) {
    if (!status.alertsOn || status.notificationsAllowed) return
    val context = LocalContext.current
    // A neutral notice card (palette spec): Attention surface with a Line border, and the icon carries the meaning.
    Surface(modifier.fillMaxWidth().testTag("alerts_banner"), color = HeatShieldTheme.colors.attentionSurface,
        shape = UiShape.control, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(UiSpace.related), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            NoticeText(stringResource(R.string.alerts_banner_blocked), icon = Icons.Outlined.NotificationsOff)
            TonalButton(stringResource(R.string.alerts_banner_settings)) { openNotificationSettings(context) }
        }
    }
}
