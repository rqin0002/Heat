package heatshield.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import heatshield.domain.AlarmKind
import heatshield.domain.AlertPlan
import heatshield.domain.AlertRules
import heatshield.domain.PlannedAlarm
import java.time.Instant

/** One armed PendingIntent, as recorded so the next apply can cancel exactly it. */
data class ArmedAlarm(val code: Int, val kind: String, val shiftId: Int, val atMillis: Long, val dueAtMillis: Long?, val exact: Boolean)

/**
 * The AlarmManager calls the scheduler makes, behind one seam: production arms the phone's AlarmManager; the device test
 * records, so a fake clock's elapsed triggers never reach the real AlarmManager or the production receivers.
 */
interface AlarmSink {
    fun exactAllowed(): Boolean
    /** May throw SecurityException when precise timing was revoked between the check and the call. */
    fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)
    fun setWindow(type: Int, windowStartMillis: Long, windowLengthMillis: Long, operation: PendingIntent)
    fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)
    fun cancel(operation: PendingIntent)
}

class AndroidAlarmSink(context: Context) : AlarmSink {
    private val manager = context.applicationContext.getSystemService(AlarmManager::class.java)
    override fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) = manager.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
    override fun setWindow(type: Int, windowStartMillis: Long, windowLengthMillis: Long, operation: PendingIntent) = manager.setWindow(type, windowStartMillis, windowLengthMillis, operation)
    override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) = manager.setAndAllowWhileIdle(type, triggerAtMillis, operation)
    override fun cancel(operation: PendingIntent) = manager.cancel(operation)
}

/**
 * Arms a plan on AlarmManager (through [alarms]) and remembers what it armed (SharedPreferences, per owner). Break alarms
 * are exact when the user allows precise timing; otherwise a 10-minute window plus an allow-while-idle alarm at the same
 * trigger, which the receiver de-duplicates through the ledger. Delivery is best effort: Doze rations allow-while-idle
 * wakes to about one per nine minutes per app and may hold a windowed alarm past its window, so no caller treats a trigger
 * time as a promise. Shift alarms ride the elapsed clock, so editing the wall clock on the same boot moves nothing; the
 * nudge rides the wall clock because no shift is running.
 */
class AlertScheduler(context: Context, private val alarms: AlarmSink = AndroidAlarmSink(context)) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun exactAllowed(): Boolean = alarms.exactAllowed()

    /** Replaces everything armed for [ownerId] with [plan]. The same plan twice leaves the same alarms. */
    fun apply(ownerId: String, plan: AlertPlan, timelineNow: Instant, elapsedNow: Long): List<ArmedAlarm> {
        cancelAll(ownerId)
        val exact = exactAllowed()
        val armed = plan.alarms.flatMap { arm(ownerId, it, timelineNow, elapsedNow, exact) }
        prefs.edit().putString(key(ownerId), gson.toJson(armed)).apply()
        return armed
    }

    fun armed(ownerId: String): List<ArmedAlarm> = prefs.getString(key(ownerId), null)
        ?.let { json -> runCatching { gson.fromJson(json, Array<ArmedAlarm>::class.java).toList() }.getOrNull() }.orEmpty()

    fun cancelAll(ownerId: String) {
        armed(ownerId).forEach { record ->
            val kind = runCatching { AlarmKind.valueOf(record.kind) }.getOrNull() ?: return@forEach
            PendingIntent.getBroadcast(appContext, record.code, fireIntent(appContext, ownerId, kind, record.shiftId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarms.cancel(it); it.cancel() }
        }
        prefs.edit().remove(key(ownerId)).apply()
    }

    private fun arm(ownerId: String, alarm: PlannedAlarm, timelineNow: Instant, elapsedNow: Long, exact: Boolean): List<ArmedAlarm> {
        val atMillis = alarm.at.toEpochMilli()
        val dueMillis = alarm.dueAt?.toEpochMilli()
        fun operation(pair: Int): PendingIntent = PendingIntent.getBroadcast(
            appContext, requestCode(alarm.kind, alarm.shiftId, pair),
            fireIntent(appContext, ownerId, alarm.kind, alarm.shiftId).putExtra(EXTRA_DUE, dueMillis ?: -1L).putExtra(EXTRA_PAIR, pair),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun record(pair: Int, isExact: Boolean) = ArmedAlarm(requestCode(alarm.kind, alarm.shiftId, pair), alarm.kind.name, alarm.shiftId, atMillis, dueMillis, isExact)

        if (alarm.kind == AlarmKind.START_NUDGE) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation(0))
            return listOf(record(0, false))
        }
        val trigger = elapsedNow + (atMillis - timelineNow.toEpochMilli()).coerceAtLeast(0L)
        if (alarm.kind == AlarmKind.REFRESH) {
            // Only the lock screen's words: a plain window, never allow-while-idle, so it never spends Doze's ration of
            // allow-while-idle wakes ahead of a break alarm. In Doze it waits for the next maintenance window.
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, WINDOW_MILLIS, operation(0))
            return listOf(record(0, false))
        }
        if (alarm.exactWanted && exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation(0))
                return listOf(record(0, true))
            } catch (_: SecurityException) {
                // Precise timing was revoked between the check and the call: fall through to the pair.
            }
        }
        if (alarm.exactWanted) {
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, WINDOW_MILLIS, operation(0))
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation(1))
            return listOf(record(0, false), record(1, false))
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation(0))
        return listOf(record(0, false))
    }

    private fun key(ownerId: String) = "$KEY_ARMED$ownerId"

    companion object {
        /** Shared with AlertCoordinator: the owner key, the asked flag and the dismissed shift live in the same file. */
        const val PREFS = "alert_session"
        private const val KEY_ARMED = "armed_"
        const val ACTION_FIRE = "heatshield.ALERT_FIRE"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SHIFT = "shift"
        const val EXTRA_DUE = "due"
        const val EXTRA_PAIR = "pair"
        val WINDOW_MILLIS = AlertRules.INEXACT_WINDOW_MINUTES * 60_000L

        /** Stable per (kind, shift, pair member), so an update replaces and a cancel finds the same PendingIntent. */
        fun requestCode(kind: AlarmKind, shiftId: Int, pair: Int): Int = shiftId * 100 + kind.ordinal * 2 + pair

        fun fireIntent(context: Context, ownerId: String, kind: AlarmKind, shiftId: Int): Intent =
            Intent(context.applicationContext, AlertReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.Builder().scheme("heatshield").authority("alert").appendPath(ownerId).appendPath(kind.name).appendPath(shiftId.toString()).build())
                .putExtra(EXTRA_OWNER, ownerId).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_SHIFT, shiftId)
    }
}
