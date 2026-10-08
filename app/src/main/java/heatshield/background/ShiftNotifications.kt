package heatshield.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import heatshield.MainActivity
import heatshield.R
import heatshield.domain.AlertRules
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatLevel
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftEvaluation
import heatshield.domain.ShiftRules
import heatshield.domain.TodayHeat
import heatshield.ui.FirstAid
import heatshield.ui.Formats
import heatshield.ui.Palette
import heatshield.withAppLocale
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** Intent actions a notification sends MainActivity, and the route each one opens. FirstAid.ACTION (the launcher shortcut) keeps working. */
object DeepLinks {
    const val ACTION_OPEN_TODAY = "heatshield.action.TODAY"
    const val ACTION_OPEN_BREAK = "heatshield.action.BREAK"
    const val ACTION_START_SHIFT = "heatshield.action.START_SHIFT"
    const val ACTION_OPEN_PREVIEW = "heatshield.action.PREVIEW"
    /** Unwell: first aid opens and the shift's break starts with reason `unwell` (MainActivity, Task 7). A direct activity intent, no trampoline. */
    const val ACTION_UNWELL = "heatshield.action.UNWELL"
    /** On the Unwell intent: the owner and shift it was posted for, so a cold start waits for that shift to load and refuses another's. */
    const val EXTRA_OWNER = "owner"
    const val EXTRA_SHIFT = "shift"

    fun routeFor(action: String?): String? = when (action) {
        FirstAid.ACTION, ACTION_UNWELL -> FirstAid.ROUTE
        ACTION_OPEN_TODAY -> "today"
        ACTION_OPEN_BREAK -> "break"
        ACTION_START_SHIFT -> "start-shift"
        ACTION_OPEN_PREVIEW -> "preview"
        else -> null
    }

    /** The Unwell action's shift; null for any other intent, or an Unwell intent without one (nothing to start). */
    fun unwellRequest(intent: Intent?): UnwellRequest? {
        if (intent?.action != ACTION_UNWELL) return null
        val owner = intent.getStringExtra(EXTRA_OWNER) ?: return null
        val shiftId = intent.getIntExtra(EXTRA_SHIFT, 0).takeIf { it > 0 } ?: return null
        return UnwellRequest(owner, shiftId)
    }
}

/** Which shift a notification's Unwell was for (MainActivity → HeatShieldApp → ViewModel.startUnwellBreak). */
data class UnwellRequest(val ownerId: String, val shiftId: Int)

/** What Profile › Alerts and the Today banner show. [asked] is whether the first-Start-shift prompt has been answered. */
data class AlertStatus(val alertsOn: Boolean, val notificationsAllowed: Boolean, val exactAllowed: Boolean, val breakChannelSilent: Boolean, val asked: Boolean) {
    val deliverable: Boolean get() = alertsOn && notificationsAllowed
}

/**
 * Every notification HeatShield posts, from the same evaluation Today shows. Copy comes from strings_alerts.xml in the
 * app's English (AppLocale); times follow the phone's 12/24-hour setting. Nothing here reads Room or arms alarms.
 */
class ShiftNotifications(context: Context) {
    private val appContext = context.applicationContext
    private val english = appContext.withAppLocale()
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    private fun s(@StringRes id: Int, vararg args: Any): String = if (args.isEmpty()) english.getString(id) else english.getString(id, *args)
    private fun time(at: Instant): String = Formats.time(at, DateFormat.is24HourFormat(appContext))
    private fun time(at: LocalDateTime): String = Formats.time(at.toLocalTime(), DateFormat.is24HourFormat(appContext))
    private fun time(at: LocalTime): String = Formats.time(at, DateFormat.is24HourFormat(appContext))

    fun ensureChannels() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, s(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
            description = s(R.string.channel_status_description); setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_BREAK_DUE, s(R.string.channel_break_due), NotificationManager.IMPORTANCE_HIGH).apply {
            description = s(R.string.channel_break_due_description)
            enableVibration(true); vibrationPattern = BREAK_VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_OUTLOOK, s(R.string.channel_outlook), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = s(R.string.channel_outlook_description)
        })
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
    }

    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** The user turned the break-due channel down to silent or off in Android settings; Profile says so. */
    fun breakChannelSilent(): Boolean =
        manager.getNotificationChannel(CHANNEL_BREAK_DUE)?.let { it.importance <= NotificationManager.IMPORTANCE_LOW } == true

    /**
     * The lock-screen timer: silent, public, a chronometer whose base is the phone's wall clock ([wallNow]) less the worked
     * time (pauses deducted, as Today counts it) or less the time on the current break. Recomputed on every reconcile, so a
     * short break is deducted and a clock change (TIME_SET reconciles) moves base and count together. While a confirmed
     * smoko or lunch runs, the base is its end and Start break is not offered. Never alerts on update.
     */
    fun ongoing(ownerId: String, open: OpenShift, evaluation: ShiftEvaluation, rule: BreakRule, wallNow: Long): Notification {
        val shiftId = open.shift.id
        val builder = base(CHANNEL_STATUS)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true).setUsesChronometer(true).setTimeoutAfter(TIMEOUT_MILLIS)
            .setContentIntent(activity(DeepLinks.ACTION_OPEN_TODAY, REQUEST_TODAY))
            .setDeleteIntent(action(ownerId, AlertAction.ONGOING_DISMISSED, shiftId))
        val onBreak = evaluation.openBreak
        if (onBreak != null) {
            val resets = ShiftRules.instant(onBreak.startedAt).plus(Duration.ofMinutes(ShiftRules.RESET_MINUTES.toLong()))
            builder.setWhen(wallNow - (evaluation.now.toEpochMilli() - onBreak.startedAt).coerceAtLeast(0L))
                .setContentTitle(s(R.string.ongoing_break_title))
                .setContentText(if (evaluation.now.isBefore(resets)) s(R.string.ongoing_break_resets, ShiftRules.RESET_MINUTES, time(resets)) else s(R.string.ongoing_break_reset_done))
                .addAction(0, s(R.string.shift_break_end), action(ownerId, AlertAction.END_BREAK, shiftId))
        } else {
            builder.setWhen(wallNow - workedMillis(evaluation))
                .setContentTitle(ongoingTitle(evaluation, rule))
                .setContentText(ongoingText(evaluation, rule))
            // A confirmed smoko or lunch still running: the gardener is on that break, so no other break is offered over it
            // (the panel hides Start break the same way; ShiftRepository.startBreak would refuse it with BREAK_OPEN).
            if (!scheduledBreakRunning(evaluation)) builder.addAction(0, s(R.string.shift_break_start), action(ownerId, AlertAction.START_BREAK, shiftId))
        }
        // In the app End shift asks first; on the public lock screen one stray tap would end the shift and write the log, so
        // Android asks for the unlock before sending it (API 31+; ignored below, where the shade needs an unlock anyway).
        val endShift = NotificationCompat.Action.Builder(0, s(R.string.shift_end), action(ownerId, AlertAction.END_SHIFT, shiftId))
            .setAuthenticationRequired(true).build()
        return builder
            .addAction(endShift)
            .addAction(0, s(R.string.alert_action_unwell), unwell(ownerId, shiftId))
            .build()
    }

    /** "Yes" on a smoko or lunch writes its whole span, so while it runs the clock's reset ([WorkClock.since]) is still ahead. */
    private fun scheduledBreakRunning(evaluation: ShiftEvaluation): Boolean = evaluation.clock.since.isAfter(evaluation.now)

    /**
     * Worked time since the clock reset less the pauses so far, in millis: the minutes Today shows, with their seconds.
     * While a confirmed smoko or lunch runs the reset is still ahead, and this is minus the time left on it: the base sits
     * at the break's end, so the chronometer reaches zero when the clock resets and counts the worked time from there
     * without waiting for another reconcile.
     */
    private fun workedMillis(evaluation: ShiftEvaluation): Long {
        val clock = evaluation.clock
        if (clock.since.isAfter(evaluation.now)) return -Duration.between(evaluation.now, clock.since).toMillis()
        val paused = clock.pauses.sumOf { Duration.between(it.start, minOf(it.end, evaluation.now)).toMillis().coerceAtLeast(0L) }
        return (Duration.between(clock.since, evaluation.now).toMillis() - paused).coerceAtLeast(0L)
    }

    private fun heatPart(current: HourHeat?): String? = current?.let { s(R.string.shift_level_heat, s(heatshield.ui.heatLevelResource(it.effective)), Formats.temperature(it.heat)) }

    /** This hour when it is at the stop line (AlertRules.stopNow), else null. Judged on the hour's heat alone, never on the break walk. */
    private fun stopHour(current: HourHeat?, rule: BreakRule): HourHeat? = current?.takeIf { AlertRules.stopNow(it, rule) }

    /** HeatShield rule: "Stop heavy work outdoors · Extreme 39°"; union table: "Stop work outdoors · Very hot 36°". */
    private fun stopTitle(hour: HourHeat, rule: BreakRule): String =
        s(if (rule == BreakRule.UNION_VTHC) R.string.alert_union_stop_title else R.string.alert_stop_title, requireNotNull(heatPart(hour)))

    /** Today's own stop line, so the lock screen and the screen say the same thing. */
    private fun stopLine(rule: BreakRule): String = s(if (rule == BreakRule.UNION_VTHC) R.string.shift_union_stop_line else R.string.today_extreme_now)

    private fun ongoingTitle(evaluation: ShiftEvaluation, rule: BreakRule): String {
        stopHour(evaluation.current, rule)?.let { return stopTitle(it, rule) }
        val heat = heatPart(evaluation.current)
        // A stop ahead is not due (isDue is false until its minute). A stop the walk passed with no break, in an hour now
        // below the line, is a break due, as Today's panel says (shiftStatusText); its text names no time (ongoingText).
        val dueNow = evaluation.isDue
        return when {
            dueNow && heat != null -> s(R.string.ongoing_title_due, heat)
            dueNow -> s(R.string.ongoing_title_due_no_heat)
            heat != null -> s(R.string.ongoing_title_on, heat)
            else -> s(R.string.ongoing_title_on_no_heat)
        }
    }

    private fun sinceLine(evaluation: ShiftEvaluation, @StringRes sinceBreak: Int, @StringRes sinceStart: Int): String {
        val since = Formats.duration(evaluation.clock.workedMinutes)
        return s(if (evaluation.clock.since == evaluation.shiftStart) sinceStart else sinceBreak, since)
    }

    /**
     * The timer's line under its title. It names times, never a count of minutes: the chronometer beside it counts on
     * between reconciles, and a count written here would stand still and disagree with it.
     */
    private fun ongoingText(evaluation: ShiftEvaluation, rule: BreakRule): String {
        // A running smoko or lunch says when it ends, as Today does; the chronometer counts down to that minute (workedMillis).
        if (scheduledBreakRunning(evaluation)) return s(R.string.ongoing_text_break_until, time(evaluation.clock.since))
        return when (val due = evaluation.due) {
            is BreakDue.At -> when {
                evaluation.overdueMinutes >= 1 -> s(R.string.ongoing_text_overdue, time(due.time))
                evaluation.isDue -> s(R.string.ongoing_text_due)
                due.scheduled != null -> s(R.string.ongoing_text_scheduled, kindName(due.scheduled), time(due.time))
                else -> s(R.string.ongoing_text_next, time(due.time))
            }
            // A stop ahead is a time, never a current instruction. A stop the walk already reached is in the title while this
            // hour is at the line (stopHour), with Today's stop line under it; once the hour has cooled the break is due (the
            // title says so), and the stop's minute is never shown as a break's due time.
            is BreakDue.Stop -> when {
                due.from.isAfter(evaluation.now) ->
                    s(if (due.rule == BreakRule.UNION_VTHC) R.string.ongoing_text_union_stop_from else R.string.ongoing_text_stop_from, time(due.from))
                AlertRules.stopNow(evaluation.current, rule) -> stopLine(rule)
                else -> s(R.string.ongoing_text_due)
            }
            BreakDue.NoneToday -> s(R.string.ongoing_text_none)
            BreakDue.NoForecast -> s(R.string.ongoing_text_no_forecast)
        }
    }

    private fun kindName(kind: ScheduledKind) = s(if (kind == ScheduledKind.SMOKO) R.string.shift_kind_smoko else R.string.shift_kind_lunch)

    /**
     * The buzz (spec §4, §5.11, §5.12): level and heat in the title; minutes since the break, the due time when late, water
     * from Hot, "check on your mate" from Very hot, and the crew-rule crossing when this hour's air is over the rule.
     * [repeat] posts the same id again so it alerts once more; the content is the same. When this hour is at the stop line
     * (judged on its heat under [rule], not on the walk that made the break due) the same post is a stop alert: the stop
     * title, Today's stop line first, and no "In 10 min", which would defer a stop. [quiet] is the coordinator's in-place
     * rewrite of an alert still showing when the hour crossed the line: the same content, alerting once at most, so no buzz;
     * [whenMillis] keeps the time of the alert it rewrites. "In 10 min" also goes when [offerSnooze] is false (the coordinator:
     * the snooze would meet the stop line, or a second "Can't stop yet" would reach the cap and its fire stays silent), and a
     * stop within the snooze's ten minutes ([stopFrom], ahead of now) is named with its time. A revised forecast can
     * change [due] from At to Stop: an 11 am stop at 11:03 uses stop words without calling 11 am a late break time.
     */
    fun breakDue(ownerId: String, shiftId: Int, due: BreakDue, evaluation: ShiftEvaluation, current: HourHeat?, triggerC: Double, repeat: Boolean, rule: BreakRule,
                 quiet: Boolean = false, offerSnooze: Boolean = true, stopFrom: Instant? = null, whenMillis: Long? = null): Notification {
        val content = breakDueContent(due, evaluation, current, rule, triggerC, stopFrom)
        val builder = base(CHANNEL_BREAK_DUE)
            .setContentTitle(content.title).setContentText(content.lines.first())
            .setSubText(if (repeat) s(R.string.alert_repeat_label) else null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.lines.joinToString(" ")))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVibrate(BREAK_VIBRATION)
            .setAutoCancel(true).setOnlyAlertOnce(quiet)
            .setContentIntent(activity(DeepLinks.ACTION_OPEN_BREAK, REQUEST_BREAK))
            .addAction(0, s(R.string.shift_break_start), action(ownerId, AlertAction.START_BREAK, shiftId))
        if (whenMillis != null) builder.setWhen(whenMillis)
        if (!content.stop && offerSnooze) builder.addAction(0, s(R.string.alert_action_snooze), action(ownerId, AlertAction.SNOOZE, shiftId))
        return builder.addAction(0, s(R.string.alert_action_unwell), unwell(ownerId, shiftId)).build()
    }

    private class BreakDueContent(val title: String, val lines: List<String>, val stop: Boolean)

    /**
     * The break-due words, shared by the live alert and the Preview's: the stop title and Today's stop line first while this
     * hour is at the stop line, else "Break due" with the level and heat; then the minutes since the break, the due time
     * when late, a stop line coming within the snooze ([stopFrom]), water from Hot, the mate check from Very hot, and the
     * crew-rule crossing when [triggerC] is known.
     */
    private fun breakDueContent(due: BreakDue, evaluation: ShiftEvaluation, current: HourHeat?, rule: BreakRule, triggerC: Double?,
                                stopFrom: Instant? = null): BreakDueContent {
        val level = current?.effective ?: (due as? BreakDue.At)?.level
        val stop = stopHour(current, rule)
        val title = if (stop != null) stopTitle(stop, rule)
            else heatPart(current)?.let { s(R.string.alert_break_title, it) }
                ?: level?.let { s(R.string.alert_break_title_no_heat, s(heatshield.ui.heatLevelResource(it))) } ?: s(R.string.ongoing_title_due_no_heat)
        val lines = mutableListOf<String>()
        if (stop != null) lines += stopLine(rule)
        lines += sinceLine(evaluation, R.string.alert_break_since, R.string.alert_break_since_start)
        if (due is BreakDue.At) {
            if (evaluation.overdueMinutes >= 1) lines += s(R.string.alert_break_due_since, time(due.time))
            // A break that waited for a smoko or lunch buzzes at its time when it is still unanswered: say why it came then.
            due.scheduled?.let { lines += s(R.string.alert_scheduled_unconfirmed, kindName(it), time(due.time)) }
        }
        if (stop == null && stopFrom != null && stopFrom.isAfter(evaluation.now)) {
            lines += s(if (rule == BreakRule.UNION_VTHC) R.string.alert_union_stop_from else R.string.alert_stop_from, time(stopFrom))
        }
        if (level?.atLeast(HeatLevel.HOT) == true) lines += s(R.string.shift_water)
        if (level?.atLeast(HeatLevel.VERY_HOT) == true) lines += s(R.string.alert_check_mate)
        if (triggerC != null && current?.overTrigger == true) lines += s(R.string.alert_over_rule, Formats.temperature(triggerC))
        return BreakDueContent(title, lines, stop != null)
    }

    /** The hot-day start nudge: the day's peak, its level, the crew-rule line when it applies. Inexact, so it never says "now". */
    fun startNudge(heat: TodayHeat): Notification {
        // The nudge fires only on a Hot day or hotter (AlertRules.nudgeDue), which has a peak hour; without one the line names the level alone.
        val peak = heat.peak
        val text = if (peak == null) s(R.string.nudge_text_no_peak, s(heatshield.ui.heatLevelResource(heat.dayLevel)))
            else heat.crewRule.firstHour?.takeIf { heat.crewRule.hoursInShift > 0 }
                ?.let { s(R.string.nudge_text_rule, Formats.temperature(peak.heat), time(peak.time), s(heatshield.ui.heatLevelResource(heat.dayLevel)), Formats.temperature(heat.crewRule.triggerC), time(it)) }
                ?: s(R.string.nudge_text, Formats.temperature(peak.heat), time(peak.time), s(heatshield.ui.heatLevelResource(heat.dayLevel)))
        return base(CHANNEL_OUTLOOK)
            .setContentTitle(s(R.string.nudge_title, heat.site)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            .setContentIntent(activity(DeepLinks.ACTION_START_SHIFT, REQUEST_START_SHIFT))
            .addAction(0, s(R.string.shift_start), activity(DeepLinks.ACTION_START_SHIFT, REQUEST_START_SHIFT))
            .build()
    }

    fun stillOnShift(ownerId: String, shiftId: Int, plannedEnd: Instant): Notification = base(CHANNEL_OUTLOOK)
        .setContentTitle(s(R.string.overrun_title)).setContentText(s(R.string.overrun_text, time(plannedEnd)))
        .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
        .setContentIntent(activity(DeepLinks.ACTION_OPEN_TODAY, REQUEST_TODAY))
        .addAction(NotificationCompat.Action.Builder(0, s(R.string.shift_end), action(ownerId, AlertAction.END_SHIFT, shiftId))
            .setAuthenticationRequired(true).build())
        .addAction(0, s(R.string.overrun_action_still_working), action(ownerId, AlertAction.STILL_WORKING, shiftId))
        .build()

    /**
     * The automatic end's notice. [endedAt] is the stored end (`ShiftRepository.autoEndIfOverdue`): the planned finish, or
     * a break or answer logged after it (`ShiftRules.autoEndAt`). The text calls it "your planned finish" only when the
     * caller passes a [plannedEnd] it equals; otherwise it names the time alone, which is always true.
     */
    fun autoEnded(endedAt: Instant, plannedEnd: Instant? = null): Notification {
        val text = s(if (endedAt == plannedEnd) R.string.auto_ended_text else R.string.auto_ended_text_at, time(endedAt))
        return base(CHANNEL_STATUS)
            .setContentTitle(s(R.string.auto_ended_title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true)
            .setContentIntent(activity(DeepLinks.ACTION_OPEN_TODAY, REQUEST_TODAY))
            .build()
    }

    /** The Preview screen's virtual shift, through the same builder (the stop line included), prefixed and without actions (its events live in memory). */
    fun previewBreakDue(due: BreakDue.At, evaluation: ShiftEvaluation, current: HourHeat?, rule: BreakRule): Notification {
        val content = breakDueContent(due, evaluation, current, rule, triggerC = null)
        return base(CHANNEL_BREAK_DUE)
            .setContentTitle(s(R.string.alert_preview_prefix, content.title)).setContentText(content.lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.lines.joinToString(" ")))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVibrate(BREAK_VIBRATION).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(activity(DeepLinks.ACTION_OPEN_PREVIEW, REQUEST_PREVIEW))
            .build()
    }

    /** False when notifications are blocked or the runtime permission is missing; never throws out of a receiver. */
    fun post(id: Int, notification: Notification): Boolean {
        if (!notificationsAllowed()) return false
        return try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false   // the permission was revoked between the check and the post
        } catch (_: RuntimeException) {
            false   // the notification service refused or is restarting: nothing posted, nothing thrown
        }
    }

    fun cancel(id: Int) {
        try { NotificationManagerCompat.from(appContext).cancel(id) } catch (_: RuntimeException) { }
    }

    /** True while the notification posted under [id] is still in the shade: neither dismissed nor cancelled. */
    fun isShown(id: Int): Boolean = shown(id) != null

    /** The notification in the shade under [id], as Android holds it; null when none is showing (or the service can't say). */
    fun shown(id: Int): Notification? = try { manager.activeNotifications.firstOrNull { it.id == id }?.notification } catch (_: RuntimeException) { null }

    private fun base(channel: String): NotificationCompat.Builder = NotificationCompat.Builder(appContext, channel)
        .setSmallIcon(R.drawable.ic_stat_heatshield).setColor(ACCENT)

    private fun action(ownerId: String, action: AlertAction, shiftId: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext, REQUEST_ACTION_BASE + action.ordinal, ShiftActionReceiver.intent(appContext, ownerId, action, shiftId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Unwell names the shift it was posted for (DeepLinks.unwellRequest), so a cold start waits for that shift and refuses another's. */
    private fun unwell(ownerId: String, shiftId: Int): PendingIntent = activity(DeepLinks.ACTION_UNWELL, REQUEST_UNWELL) {
        // Extras do not identify a PendingIntent: keep an old action bound to its original owner and shift.
        data = Uri.Builder().scheme("heatshield").authority("unwell").appendPath(ownerId).appendPath(shiftId.toString()).build()
        putExtra(DeepLinks.EXTRA_OWNER, ownerId); putExtra(DeepLinks.EXTRA_SHIFT, shiftId)
    }

    private fun activity(deepLink: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent = PendingIntent.getActivity(
        appContext, requestCode,
        Intent(appContext, MainActivity::class.java).setAction(deepLink)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL_STATUS = "shift_status"
        const val CHANNEL_BREAK_DUE = "break_due"
        const val CHANNEL_OUTLOOK = "heat_outlook"
        const val LEGACY_CHANNEL = "planned_check_ins"
        const val ID_ONGOING = 10
        const val ID_BREAK_DUE = 20
        const val ID_NUDGE = 30
        const val ID_OVERRUN = 40
        const val ID_AUTO_ENDED = 41
        const val ID_PREVIEW = 50
        const val TIMEOUT_MILLIS = 14 * 60 * 60 * 1000L
        /** One long buzz, a pause, one long buzz: distinct from a message (spec direction notes). */
        val BREAK_VIBRATION = longArrayOf(0, 900, 400, 900)
        /** The light Action token (palette spec): the heat level in the text, not this colour, carries the meaning. */
        private const val ACCENT = Palette.LIGHT_ACTION.toInt()
        private const val REQUEST_TODAY = 100
        private const val REQUEST_BREAK = 101
        private const val REQUEST_START_SHIFT = 102
        private const val REQUEST_PREVIEW = 103
        private const val REQUEST_UNWELL = 104
        private const val REQUEST_ACTION_BASE = 200
    }
}
