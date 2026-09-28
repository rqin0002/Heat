package heatshield.background

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import heatshield.MainActivity
import heatshield.R
import heatshield.data.HeatShieldDatabase
import heatshield.data.ShiftPlan
import heatshield.data.firebaseAppOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One replaceable, inexact check-in per owner. The caller persists the plan before scheduling. */
class ReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("reminder_session", Context.MODE_PRIVATE)
    private val alarms = appContext.getSystemService(AlarmManager::class.java)
    private val notifications = appContext.getSystemService(NotificationManager::class.java)

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Planned check-ins", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun activateOwner(ownerId: String?) {
        val previous = activeOwner()
        if (previous != null && previous != ownerId) cancelForOwner(previous)
        preferences.edit().apply {
            if (ownerId == null) remove(OWNER) else putString(OWNER, ownerId)
        }.apply()
    }

    fun notificationsAllowed(): Boolean = notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun schedule(plan: ShiftPlan): Boolean {
        cancelForOwner(plan.ownerId)
        val time = plan.reminderAt ?: return false
        if (activeOwner() != plan.ownerId || !notificationsAllowed() || time <= System.currentTimeMillis()) return false
        val operation = PendingIntent.getBroadcast(appContext, 0, intent(plan.ownerId).apply {
            putExtra(REVISION, plan.revision)
            putExtra(DUE, time)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
        return true
    }

    fun cancelForOwner(ownerId: String) {
        PendingIntent.getBroadcast(appContext, 0, intent(ownerId), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
        notifications.cancel(ownerId, NOTIFICATION_ID)
    }

    suspend fun restoreForActiveOwner() {
        val ownerId = activeOwner() ?: return
        val dao = HeatShieldDatabase.get(appContext).dao()
        if (dao.getPreferences(ownerId)?.reminders != true || !ownerIsAuthenticated(ownerId)) {
            cancelForOwner(ownerId)
            return
        }
        dao.getPlan(ownerId)?.let(::schedule) ?: cancelForOwner(ownerId)
    }

    internal fun activeOwner(): String? = preferences.getString(OWNER, null)

    internal fun ownerIsAuthenticated(ownerId: String): Boolean = activeOwner() == ownerId &&
        (ownerId == "local" || firebaseAppOrNull(appContext)?.let { FirebaseAuth.getInstance(it).currentUser?.uid } == ownerId)

    private fun intent(ownerId: String) = Intent(appContext, ReminderReceiver::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("heatshield").authority("check-in").appendPath(ownerId).build()
        putExtra(OWNER, ownerId)
    }

    internal companion object {
        const val CHANNEL = "planned_check_ins"
        const val ACTION = "heatshield.PLANNED_CHECK_IN"
        const val OWNER = "owner"
        const val REVISION = "revision"
        const val DUE = "due"
        const val NOTIFICATION_ID = 1
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION) return
        val ownerId = intent.getStringExtra(ReminderScheduler.OWNER) ?: return
        val revision = intent.getLongExtra(ReminderScheduler.REVISION, -1L)
        val expectedDue = intent.getLongExtra(ReminderScheduler.DUE, -1L)
        if (revision < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val scheduler = ReminderScheduler(context)
                    if (!scheduler.ownerIsAuthenticated(ownerId) || !scheduler.notificationsAllowed()) return@withTimeout
                    val dao = HeatShieldDatabase.get(context).dao()
                    val plan = dao.getPlan(ownerId)
                    if (!reminderEligible(plan, ownerId, revision, expectedDue,
                            dao.getPreferences(ownerId)?.reminders == true, System.currentTimeMillis())) return@withTimeout
                    if (!scheduler.ownerIsAuthenticated(ownerId)) return@withTimeout
                    val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = Notification.Builder(context, ReminderScheduler.CHANNEL)
                        .setSmallIcon(R.drawable.heatshield_icon)
                        .setContentTitle("Planned work check-in")
                        .setContentText("Review current conditions and discuss any work adjustment.")
                        .setContentIntent(openApp)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setAutoCancel(true)
                        .build()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withTimeout
                    context.getSystemService(NotificationManager::class.java)
                        .notify(ownerId, ReminderScheduler.NOTIFICATION_ID, notification)
                }
            } catch (_: Exception) {
                // A cancelled/blocked reminder must never crash the receiving process or invent completion.
            } finally {
                pending.finish()
            }
        }
    }
}

/** A queued alarm is only a request; the current persisted plan and preference remain authoritative. */
internal fun reminderEligible(
    plan: ShiftPlan?, ownerId: String, revision: Long, expectedDue: Long,
    remindersEnabled: Boolean, now: Long
): Boolean = plan != null && remindersEnabled && plan.ownerId == ownerId &&
    plan.revision == revision && plan.reminderAt == expectedDue && expectedDue > 0 &&
    now >= expectedDue && now - expectedDue <= 60 * 60 * 1000L
