package heatshield.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A notification action, carried in the intent to [ShiftActionReceiver]. Distinct from the panel's ui.ShiftAction. */
enum class AlertAction { START_BREAK, END_BREAK, END_SHIFT, SNOOZE, STILL_WORKING, ONGOING_DISMISSED }

/**
 * Where every receiver hands its intent: the alert coordinator, so a receiver started in a cold process (a boot, an alarm
 * with the app closed) works with no screen alive. Robolectric tests install a recorder; null means nothing is wired, and
 * a receiver then finishes without doing anything.
 */
object AlertDispatch {
    @Volatile var handler: (suspend (Context, Intent) -> Unit)? = { context, intent -> AlertCoordinator(context).handle(intent) }

    /**
     * goAsync + an 8-second budget, as the A2 receiver did: a blocked or failing handler never crashes the receiving process.
     * goAsync has no result to hand back when onReceive is called directly (a test), so the finish is skipped then.
     */
    fun receive(receiver: BroadcastReceiver, context: Context, intent: Intent) {
        val handle = handler ?: return
        val pending: BroadcastReceiver.PendingResult? = receiver.goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { handle(context.applicationContext, intent) } } catch (_: Exception) { } finally { pending?.finish() }
        }
    }
}

/** Alarms from AlertScheduler (Task 5). exported=false: only this app's PendingIntents reach it. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlertScheduler.ACTION_FIRE) AlertDispatch.receive(this, context, intent)
    }
}

/** Notification actions and the ongoing notification's delete intent. exported=false. */
class ShiftActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) AlertDispatch.receive(this, context, intent)
    }

    companion object {
        const val ACTION = "heatshield.SHIFT_ACTION"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_ACTION = "action"
        const val EXTRA_SHIFT = "shift"

        fun intent(context: Context, ownerId: String, action: AlertAction, shiftId: Int): Intent =
            Intent(context, ShiftActionReceiver::class.java).setAction(ACTION)
                .setData(Uri.Builder().scheme("heatshield").authority("action").appendPath(ownerId).appendPath(action.name).appendPath(shiftId.toString()).build())
                .putExtra(EXTRA_OWNER, ownerId).putExtra(EXTRA_ACTION, action.name).putExtra(EXTRA_SHIFT, shiftId)
    }
}

/**
 * System broadcasts that invalidate armed alarms. exported=true is required for manifest intent filters on API 31+; the actions
 * are protected. The exact-alarm broadcast arrives on grant only: a revocation stops the app and cancels its exact alarms with
 * no broadcast, so the inexact pair is rebuilt by the next reconcile trigger (app start, forecast success, an inexact alarm, boot).
 */
class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) AlertDispatch.receive(this, context, intent)
    }

    companion object {
        val ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"   // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31); sent on grant, never on revocation
        )
    }
}
