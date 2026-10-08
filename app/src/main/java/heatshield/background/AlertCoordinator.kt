// Makes alarms and lock-screen notices follow the saved shift, settings and weather.
// Screens and background receivers share this coordinator so delivery and owner checks stay together.
package heatshield.background

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import heatshield.data.AlertReason
import heatshield.data.AndroidShiftClock
import heatshield.data.BreakReason
import heatshield.data.EventContext
import heatshield.data.ForecastSnapshot
import heatshield.data.HeatShieldDatabase
import heatshield.data.MelbourneZone
import heatshield.data.ShiftClock
import heatshield.data.ShiftFailure
import heatshield.data.ShiftRepository
import heatshield.data.ShiftSource
import heatshield.data.UserPreferences
import heatshield.data.WeatherRepository
import heatshield.data.firebaseAppOrNull
import heatshield.domain.AlarmKind
import heatshield.domain.AlertInput
import heatshield.domain.AlertPlan
import heatshield.domain.AlertRules
import heatshield.domain.BreakAlertDecision
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.ShiftEvaluation
import heatshield.domain.ShiftRules
import heatshield.domain.TodayHeat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One owner's alert-relevant Room state at one instant. [now] is on the open shift's own timeline (wall time off shift).
 * [hours] are every hour of the shift park's cached forecast (End shift and the automatic end summarise from them);
 * [timing] are the same hours from a current forecast only, the ones breaks are timed with, as Today times them (spec
 * §5.13: a stale or missing forecast times nothing). [heat] is Today's reading of the same forecast: its freshness for
 * the policy, and the day's level for the hot-day nudge.
 */
data class AlertState(
    val ownerId: String, val preferences: UserPreferences, val open: OpenShift?, val forecast: ForecastSnapshot?,
    val heat: TodayHeat, val hours: List<HourHeat>, val timing: List<HourHeat>, val evaluation: ShiftEvaluation?, val now: Instant
) {
    val rule: BreakRule get() = BreakRule.fromStored(preferences.breakRule)
}

/**
 * The one reconcile (Global Constraint 2). Reads preferences, the open shift, its events and the cached forecast from Room;
 * performs M2's automatic end when it is overdue; runs the M2 evaluation on a current forecast's hours only and the M3
 * plan; arms alarms through [AlertScheduler]; posts or cancels the ongoing timer; delivers eligible start and overrun
 * reminders once and posts a break alert that is due now; keeps background forecast refresh on today's work park. Called on app start, after every shift or preference write, when the owner's forecast changes, on ForecastWorker
 * success, from the receivers and after every alarm or notification action. Idempotent: the same Room state leaves the
 * same alarms, the same notifications and the same refreshed park.
 *
 * The owner boundary: [activateOwner], [reconcile], [onAlarm], [onAction] and M4's `OutlookCoordinator.evaluate` hold
 * [ownerLock], so an owner switch waits for a reconcile that is inside its Room reads and then undoes what it armed. A
 * Firebase sign-out waits for nothing, so after those reads the coordinator asks again whether the owner is still the
 * phone's signed-in one before it arms or posts, and releases one who is not: alarms and every notification cancelled.
 */
class AlertCoordinator(
    context: Context,
    private val database: HeatShieldDatabase = HeatShieldDatabase.get(context),
    private val clock: ShiftClock = AndroidShiftClock(context),
    private val scheduler: AlertScheduler = AlertScheduler(context),
    private val notifications: ShiftNotifications = ShiftNotifications(context),
    /** "local", or the Firebase user this process is signed in as. Injectable so the device test's throw-away owner passes it. */
    private val isSignedIn: (String) -> Boolean = { id ->
        id == "local" || firebaseAppOrNull(context.applicationContext)?.let { FirebaseAuth.getInstance(it).currentUser?.uid } == id
    },
    /**
     * Points background forecast refresh at a park, or stops it (null). Injectable so the device test records the park
     * instead of moving the phone's real forecast work.
     */
    private val backgroundRefresh: (heatshield.data.WorkSite?) -> Unit = { site ->
        if (site == null) ForecastWorker.cancel(context) else ForecastWorker.retarget(context, site)
    }
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dao = database.dao()
    private val shifts = ShiftRepository(database, clock)

    init { notifications.ensureChannels() }

    /**
     * The owner whose alarms this phone carries. Switching owners, or signing out (null), releases the previous owner: its
     * alarms and every notification go. Under [ownerLock], so a reconcile already inside its Room reads finishes first and
     * is then undone; nothing it armed or posted survives the switch. The shift itself stays in Room for its owner.
     */
    suspend fun activateOwner(ownerId: String?) {
        ownerLock.withLock {
            val previous = activeOwner()
            if (previous != null && previous != ownerId) releaseOwner(previous)
            prefs.edit { if (ownerId == null) remove(KEY_OWNER) else putString(KEY_OWNER, ownerId) }
        }
    }

    fun activeOwner(): String? = prefs.getString(KEY_OWNER, null)

    /**
     * The active owner, still signed in on this phone. Asked at the gate of every locked block and again before each arm
     * or post, after the Room reads, so a stale alarm for a signed-out account does nothing.
     */
    fun ownerIsAuthenticated(ownerId: String): Boolean = activeOwner() == ownerId && isSignedIn(ownerId)

    /**
     * What Profile › Alerts and Today's banner show. [alertsOn] is the switch as the screen holds it; nothing here reads
     * Room. [AlertStatus.asked] is the active owner's own: the switch belongs to each owner, so each is asked once.
     */
    fun status(alertsOn: Boolean): AlertStatus =
        AlertStatus(alertsOn, notifications.notificationsAllowed(), scheduler.exactAllowed(), notifications.breakChannelSilent(),
            activeOwner()?.let(::asked) ?: false)

    /** Whether [owner] has answered the alerts intro. */
    fun asked(owner: String): Boolean = prefs.getBoolean(askedKey(owner), false)

    /** [owner] (the active one by default) has answered the alerts intro. Nothing is stored without an owner. */
    fun markAsked(owner: String? = activeOwner()) { owner?.let { prefs.edit { putBoolean(askedKey(it), true) } } }
    fun ongoingDismissed(shiftId: Int): Boolean = prefs.getInt(KEY_DISMISSED, -1) == shiftId
    fun clearDismissed() = prefs.edit { remove(KEY_DISMISSED) }

    /** Null when no authenticated owner is active; otherwise the plan that was applied. Never throws. */
    suspend fun reconcile(): AlertPlan? = ownerLock.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): AlertPlan? = try {
        val owner = activeOwner()
        when {
            owner == null -> null
            !ownerIsAuthenticated(owner) -> { releaseOwner(owner); null }
            else -> reconcileOwner(owner)
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null   // a receiver must never crash; the next trigger reconciles again
    }

    /**
     * Delivers reminders owed now before replacing the owner's alarms, and keeps the timer matched to Room.
     * Automatic end runs first: returning at 17:02 after a 15:00 finish closes the shift instead of asking about overtime.
     */
    private suspend fun reconcileOwner(owner: String): AlertPlan? {
        // The overdue end first, idempotently: a shift the phone slept through closes before anything is planned for it.
        var state = endOverdueShift(load(owner))
        postDueReminders(state)
        var plan = AlertRules.plan(input(state), state.timing)
        val decision = plan.breakAlert
        // A break due right now, or a repeat owed because its alarm was held (Doze) or lost (a reboot): posted here, the
        // same path as an alarm's, so the alarms this reconcile cancels never drop it.
        val postedNow = decision is BreakAlertDecision.Post && postBreakAlert(state, decision)
        if (postedNow) {
            // The ledger changed: plan again, so the repeat is armed and nothing is posted twice.
            state = load(owner)
            plan = AlertRules.plan(input(state), state.timing)
        }
        // Today's work park, read before the check below so nothing suspends between that check and pointing refresh there.
        val workPark = dao.getSite(state.heat.site) ?: heatshield.data.WorkSite.byName(state.heat.site)
        // The Room reads suspended. An owner switch waited behind ownerLock, but a sign-out did not: an owner who is no
        // longer the phone's signed-in one gets nothing armed or shown and is released here, not by the next trigger.
        if (!ownerIsAuthenticated(owner)) { releaseOwner(owner); return null }
        // Background refresh follows today's work park with the app closed too: the screen's clock stops when it is hidden.
        // Example: Birrarung Marr is the usual park and a plan for 9 Oct names Royal Park; the first worker run or alarm
        // after midnight moves refresh to Royal Park, and the runs after it keep that cache current for the 06:40 nudge.
        workPark?.let(backgroundRefresh)
        scheduler.apply(owner, plan, state.now, clock.elapsedMillis())
        val open = state.open
        val evaluation = state.evaluation
        // The break alert first, then the timer: when Android sheds a burst of updates, the later one goes.
        when {
            open == null || evaluation == null -> {
                dismiss(ShiftNotifications.ID_BREAK_DUE); dismiss(ShiftNotifications.ID_OVERRUN)
                if (prefs.contains(KEY_DISMISSED)) clearDismissed()
            }
            evaluation.openBreak != null || (!evaluation.isDue && !evaluation.stopNow) -> dismiss(ShiftNotifications.ID_BREAK_DUE)
            !postedNow -> rewriteStopAlert(owner, state, open, evaluation)
        }
        if (plan.ongoing && open != null && evaluation != null && !ongoingDismissed(open.shift.id)) {
            // The chronometer base is the phone's wall clock less the worked time, recomputed on every trigger (TIME_SET included).
            show(owner, ShiftNotifications.ID_ONGOING, notifications.ongoing(owner, open, evaluation, state.rule, clock.wallMillis()))
        } else dismiss(ShiftNotifications.ID_ONGOING)
        // A shift is running: the "start your shift" nudge has done its job.
        if (open != null) dismiss(ShiftNotifications.ID_NUDGE)
        if (!state.preferences.reminders) OWNER_NOTIFICATIONS.forEach(::dismiss)
        return plan
    }

    /**
     * An alarm fired. Eligibility is decided again from Room (Global Constraint 1): the alarm carries nothing the decision
     * trusts, so a due time that moved, a shift that ended or alerts switched off post nothing. Then everything is
     * reconciled. Under [ownerLock]; every post re-checks the owner after the read. [dueAtMillis] is what the alarm was
     * armed for, kept for logs and tests; the decision reads the due time from the evaluation, never from the intent.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun onAlarm(ownerId: String, kind: AlarmKind, shiftId: Int, dueAtMillis: Long?) {
        ownerLock.withLock {
            if (!ownerIsAuthenticated(ownerId)) return@withLock
            try {
                val state = load(ownerId)
                val open = state.open?.takeIf { it.shift.id == shiftId }
                when (kind) {
                    // The inexact pair's second fire, or a repeat a reconcile already posted, finds the ledger row and stays quiet.
                    AlarmKind.BREAK_DUE, AlarmKind.BREAK_REPEAT -> if (open != null) {
                        val decision = AlertRules.decideBreakAlert(input(state), state.timing, repeat = kind == AlarmKind.BREAK_REPEAT)
                        if (decision is BreakAlertDecision.Post) postBreakAlert(state, decision)
                    }
                    // Reconciliation delivers these once, after closing any overdue shift, before replacing alarms.
                    AlarmKind.STILL_ON_SHIFT, AlarmKind.START_NUDGE -> Unit
                    // The alarm only wakes the process: the reconcile below performs the overdue end, as every trigger does.
                    AlarmKind.AUTO_END -> Unit
                    // The lock screen's words change (the next hour, a stop, a smoko's end): the reconcile below rebuilds the
                    // timer and rewrites a break alert still showing.
                    AlarmKind.REFRESH -> Unit
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A ShiftFailure (the shift moved on) or a failed read: the reconcile below shows what is true.
            }
            reconcileLocked()
        }
    }

    /**
     * A notification action: one repository write, then reconcile. The state may have moved on since the notification was
     * posted (the break already started, the shift already ended); the ShiftFailure that follows is not an error here.
     * An action for another owner or another shift writes nothing. Under [ownerLock].
     */
    suspend fun onAction(ownerId: String, action: AlertAction, shiftId: Int) {
        ownerLock.withLock {
            if (!ownerIsAuthenticated(ownerId)) return@withLock
            try {
                val state = load(ownerId)
                val current = state.open?.takeIf { it.shift.id == shiftId }
                when (action) {
                    // A confirmed smoko or lunch still running refuses a second break (BREAK_OPEN), as the panel does.
                    AlertAction.START_BREAK -> if (current != null) {
                        shifts.startBreak(ownerId, BreakReason.SHADE, eventContext(state)); notifications.cancel(ShiftNotifications.ID_BREAK_DUE)
                    }
                    AlertAction.END_BREAK -> if (current != null) shifts.endBreak(ownerId)
                    AlertAction.END_SHIFT -> if (current != null) {
                        shifts.endShift(ownerId, state.hours)
                        notifications.cancel(ShiftNotifications.ID_BREAK_DUE); notifications.cancel(ShiftNotifications.ID_OVERRUN)
                    }
                    // In 10 min: the "Can't stop yet" answer, zero minutes; the walk then asks again ten minutes on, or names the stop.
                    AlertAction.SNOOZE -> if (current != null) {
                        shifts.cantStop(ownerId, eventContext(state)); notifications.cancel(ShiftNotifications.ID_BREAK_DUE)
                    }
                    // The shift runs on past its finish: the confirmed time becomes the planned finish, so the next check and the
                    // automatic end follow from it and a forgotten shift keeps the overtime just confirmed.
                    AlertAction.STILL_WORKING -> {
                        if (current != null) shifts.keepWorking(ownerId, shiftId)
                        notifications.cancel(ShiftNotifications.ID_OVERRUN)
                    }
                    AlertAction.ONGOING_DISMISSED -> if (current != null) prefs.edit { putInt(KEY_DISMISSED, shiftId) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
            reconcileLocked()
        }
    }

    /** The AlertDispatch handler: every receiver intent ends here. */
    suspend fun handle(intent: Intent) {
        when (intent.action) {
            AlertScheduler.ACTION_FIRE -> {
                val owner = intent.getStringExtra(AlertScheduler.EXTRA_OWNER) ?: return
                val kind = intent.getStringExtra(AlertScheduler.EXTRA_KIND)?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() } ?: return
                onAlarm(owner, kind, intent.getIntExtra(AlertScheduler.EXTRA_SHIFT, 0), intent.getLongExtra(AlertScheduler.EXTRA_DUE, -1L).takeIf { it > 0 })
            }
            ShiftActionReceiver.ACTION -> {
                val owner = intent.getStringExtra(ShiftActionReceiver.EXTRA_OWNER) ?: return
                val action = intent.getStringExtra(ShiftActionReceiver.EXTRA_ACTION)?.let { runCatching { AlertAction.valueOf(it) }.getOrNull() } ?: return
                onAction(owner, action, intent.getIntExtra(ShiftActionReceiver.EXTRA_SHIFT, 0))
            }
            in ReconcileReceiver.ACTIONS -> reconcile()
        }
    }

    /**
     * Everything from Room at this instant, read as the M2 ViewModel reads it (`shiftState`, `endShift`): the shift's own
     * profile, the cached forecast of its park, every hour of it for summaries and only a current forecast's hours for
     * timing. A brand-new owner has default preferences and no shift.
     */
    internal suspend fun load(ownerId: String): AlertState {
        val preferences = dao.getPreferences(ownerId) ?: UserPreferences(ownerId = ownerId)
        val shift = dao.getOpenShift(ownerId)
        val open = shift?.let { OpenShift(it, dao.getEvents(it.id)) }
        val now = shifts.now(shift)
        val wall = clock.wallMillis()
        val fixed = Clock.fixed(Instant.ofEpochMilli(wall), MelbourneZone)
        val profile = ContextReasoner.effectiveProfile(preferences, dao.getPlan(ownerId), LocalDate.now(fixed), shift)
        val forecast = dao.getForecast(profile.site)?.let(WeatherRepository::snapshotOf)
        val heat = ContextReasoner.todayHeat(forecast, profile, fixed)
        val hours = if (open == null) emptyList() else ContextReasoner.shiftHours(forecast, profile, wall)
        val timing = if (open == null) emptyList() else ContextReasoner.shiftTimingHours(forecast, profile, wall)
        val evaluation = open?.let {
            ShiftRules.evaluate(it, timing, BreakRule.fromStored(preferences.breakRule), preferences.smokoMinute, preferences.lunchMinute, now)
        }
        return AlertState(ownerId, preferences, open, forecast, heat, hours, timing, evaluation, now)
    }

    /**
     * Delivers eligible reminders before replacing alarms, so an inexact alarm arriving late is not lost.
     * Remembers successful delivery per owner and workday, or shift and planned finish, even after dismissal or restart.
     * Example: a pending 06:40 nudge is delivered at a 06:42 refresh once; a 15:00 finish is checked from 15:30.
     */
    private suspend fun postDueReminders(state: AlertState) {
        if (!deliverable(state)) return
        val owner = state.ownerId
        val open = state.open
        if (open != null) {
            val plannedEnd = open.shift.plannedEndAt?.let(ShiftRules::instant) ?: return
            if (state.now.isBefore(plannedEnd.plusSeconds(AlertRules.OVERRUN_CHECK_MINUTES * 60))) return
            val key = "delivered_overrun:$owner"
            val delivery = "${open.shift.id}:${plannedEnd.toEpochMilli()}"
            if (prefs.getString(key, null) != delivery &&
                post(owner, ShiftNotifications.ID_OVERRUN, notifications.stillOnShift(owner, open.shift.id, plannedEnd))) {
                prefs.edit { putString(key, delivery) }
            }
            return
        }
        val day = AlertRules.nudgeDay(state.now, state.preferences.shiftStartMinute) ?: return
        val key = "delivered_nudge:$owner"
        if (prefs.getString(key, null) == day.toString()) return
        val heat = nudgeHeat(state, day)
        val date = day.format(ShiftRepository.RECORD_DATE)
        val trackedToday = dao.getTimedSessions(owner).any { it.source == ShiftSource.TRACKED && it.date == date }
        if (AlertRules.nudgeDue(state.now, state.preferences.shiftStartMinute, heat.dayLevel, heat.freshness,
                openShift = false, trackedToday = trackedToday) &&
            post(owner, ShiftNotifications.ID_NUDGE, notifications.startNudge(heat))) {
            prefs.edit { putString(key, day.toString()) }
        }
    }

    /**
     * Reads [day]'s work and forecast using the real clock for cache age; today's loaded heat is reused.
     * Example: at 23:50 a 00:10 start uses tomorrow's hours, but an 18:00 fetch stays 5 h 50 min old.
     */
    private suspend fun nudgeHeat(state: AlertState, day: LocalDate): TodayHeat {
        if (day == state.heat.date) return state.heat
        val profile = ContextReasoner.effectiveProfile(state.preferences, dao.getPlan(state.ownerId), day)
        val at = Instant.ofEpochMilli(clock.wallMillis())
        return ContextReasoner.todayHeat(dao.getForecast(profile.site)?.let(WeatherRepository::snapshotOf), profile,
            Clock.fixed(at, MelbourneZone), date = day)
    }

    /**
     * M2's automatic end, performed here so it never depends on an alarm that a reboot, a revoked permission or Doze
     * dropped: `autoEndIfOverdue` (one transaction, idempotent) closes the shift at its automatic end once the grace has
     * passed; the LOW notice names the stored end and posts when alerts are on; the state is reloaded so nothing is
     * planned for a closed shift.
     */
    private suspend fun endOverdueShift(state: AlertState): AlertState {
        val open = state.open ?: return state
        val endedAt = (try { shifts.autoEndIfOverdue(state.ownerId, state.hours) } catch (_: ShiftFailure) { null }) ?: return state
        // The screen, when open, says so too: this reconcile may have ended the shift before the ViewModel's own check did.
        autoEndedEvents.tryEmit(AutoEndedShift(state.ownerId, open.shift.id, endedAt))
        if (state.preferences.reminders) {
            post(state.ownerId, ShiftNotifications.ID_AUTO_ENDED, notifications.autoEnded(endedAt, open.shift.plannedEndAt?.let(ShiftRules::instant)))
        }
        return load(state.ownerId)
    }

    /**
     * Posts the break alert (a first alert or the owed repeat), then writes its ledger row; false when notifications are
     * blocked or the owner is no longer the phone's signed-in one (nothing is recorded then). Posted first, so a failed
     * write costs at most a second buzz, never a missed one.
     */
    private suspend fun postBreakAlert(state: AlertState, decision: BreakAlertDecision.Post): Boolean {
        val open = state.open ?: return false
        val evaluation = state.evaluation ?: return false
        val snooze = snoozeOffer(state, open, evaluation)
        val notification = notifications.breakDue(state.ownerId, open.shift.id, decision.due, evaluation, evaluation.current,
            state.preferences.hotDayTrigger, decision.repeat, state.rule, offerSnooze = snooze.offer, stopFrom = snooze.stopFrom)
        if (!post(state.ownerId, ShiftNotifications.ID_BREAK_DUE, notification)) return false
        try {
            shifts.recordAlert(state.ownerId, open.shift.id, if (decision.repeat) AlertReason.REPEAT else AlertReason.BREAK_DUE,
                EventContext(evaluation.current?.effective ?: decision.due.level, evaluation.current?.heat, decision.due.time), state.now)
        } catch (_: ShiftFailure) {
            // The shift ended between the read and the write; the reconcile that follows clears the alert.
        }
        return true
    }

    /**
     * The hour crossed the stop line after a break alert posted, or a stop came within the snooze's ten minutes (Global
     * Constraint 3): while a break or stop is due and the alert is still showing, it is rewritten in place, silently (the stop
     * never buzzes), as the stop alert or with the stop's time, so the lock screen no longer offers "In 10 min". A dismissed
     * alert is not brought back; the ledger is untouched. It keeps the posted alert's time, so a reconcile that finds the
     * same words and actions changes nothing. For example, revising the 7 am forecast to Extreme replaces a posted
     * 7:47 break with stop guidance even when the new walk returns Stop instead of At.
     */
    private fun rewriteStopAlert(owner: String, state: AlertState, open: OpenShift, evaluation: ShiftEvaluation) {
        val due = evaluation.due
        val shown = notifications.shown(ShiftNotifications.ID_BREAK_DUE) ?: return
        val snooze = snoozeOffer(state, open, evaluation)
        if (!AlertRules.stopNow(evaluation.current, state.rule) && snooze.stopFrom == null) return
        val dueMillis = due.dueAt?.toEpochMilli()
        val repeated = AlertRules.alertsPosted(open.events, evaluation.clock.since).any { it.reason == AlertReason.REPEAT && it.dueAt == dueMillis }
        show(owner, ShiftNotifications.ID_BREAK_DUE, notifications.breakDue(owner, open.shift.id, due, evaluation, evaluation.current,
            state.preferences.hotDayTrigger, repeated, state.rule, quiet = true, offerSnooze = snooze.offer, stopFrom = snooze.stopFrom,
            whenMillis = shown.`when`))
    }

    /**
     * Whether a break alert may offer "In 10 min" (spec §8: no promise the app cannot keep). Not when the snooze would meet
     * the stop line ([ShiftRules.snoozeStop], which a stop never buzzes for; the alert then names the stop's time), and not
     * on the re-ask after a first "Can't stop yet": a second answer reaches [AlertRules.MAX_CANT_STOP] and its fire is silent.
     */
    private fun snoozeOffer(state: AlertState, open: OpenShift, evaluation: ShiftEvaluation): SnoozeOffer {
        val stop = ShiftRules.snoozeStop(evaluation, state.timing)
        val atCap = AlertRules.cantStopCount(open.events, evaluation.clock.since) >= AlertRules.MAX_CANT_STOP - 1
        return SnoozeOffer(offer = stop == null && !atCap, stopFrom = stop)
    }

    private class SnoozeOffer(val offer: Boolean, val stopFrom: Instant?)

    private fun input(state: AlertState) = AlertInput(
        alertsOn = state.preferences.reminders, notificationsAllowed = notifications.notificationsAllowed(),
        open = state.open, evaluation = state.evaluation, freshness = state.heat.freshness, now = state.now,
        usualStartMinute = state.preferences.shiftStartMinute
    )

    private fun deliverable(state: AlertState) = state.preferences.reminders && notifications.notificationsAllowed()

    /** What the panel would have recorded: this hour's level and heat, and the due time the answer is about. */
    private fun eventContext(state: AlertState): EventContext {
        val evaluation = state.evaluation
        val due = evaluation?.takeIf { it.openBreak == null }?.due?.dueAt
        return EventContext(evaluation?.current?.effective, evaluation?.current?.heat, due)
    }

    /** Every post follows a Room read, so it asks again whether [ownerId] is still the phone's signed-in owner (Global Constraint 2). */
    private fun post(ownerId: String, id: Int, notification: Notification): Boolean =
        ownerIsAuthenticated(ownerId) && notifications.post(id, notification)

    /**
     * [post] for what a reconcile keeps on screen (the timer, the stop rewrite), skipped when the same content is already
     * showing. A reconcile runs on every write, forecast change and resume, and Android drops a package's updates past
     * about five a second, so re-posting unchanged content could cost the one update that mattered.
     */
    private fun show(ownerId: String, id: Int, notification: Notification): Boolean {
        if (!ownerIsAuthenticated(ownerId)) return false
        val shown = notifications.shown(id)
        return if (shown != null && sameContent(shown, notification)) true else notifications.post(id, notification)
    }

    /** What the gardener sees and can tap: the channel, the words, the timer's base (to the second) and the actions. */
    private fun sameContent(shown: Notification, next: Notification): Boolean {
        fun words(n: Notification) = WORD_EXTRAS.map { n.extras.getCharSequence(it)?.toString() }
        fun taps(n: Notification) = n.actions.orEmpty().map { it.title?.toString() to it.actionIntent } + listOf(null to n.contentIntent, null to n.deleteIntent)
        return shown.channelId == next.channelId && words(shown) == words(next) && abs(shown.`when` - next.`when`) < 1_000L &&
            shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == next.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) &&
            taps(shown) == taps(next)
    }

    /** Cancels [id] only while it shows: a reconcile's routine clean-up, so repeated cancels of nothing are not sent. */
    private fun dismiss(id: Int) { if (notifications.isShown(id)) notifications.cancel(id) }

    /**
     * A signed-out or replaced owner leaves nothing behind: its alarms, every notification (the automatic-end notice
     * included) and the background refresh of its park; the next owner's first reconcile points refresh at theirs. M4 adds
     * its outlook work here.
     */
    private fun releaseOwner(ownerId: String) {
        scheduler.cancelAll(ownerId); OWNER_NOTIFICATIONS.forEach(notifications::cancel); backgroundRefresh(null)
    }

    companion object {
        const val PREFS = AlertScheduler.PREFS
        const val KEY_OWNER = "owner"
        const val KEY_ASKED = "asked"

        /** Where the alerts intro's answer is kept for [owner]: per owner, as the Break-due alerts switch is. */
        fun askedKey(owner: String) = "$KEY_ASKED:$owner"
        const val KEY_DISMISSED = "dismissed_shift"

        /** The alert family: every one goes when the owner is released, and when break-due alerts are switched off. */
        private val OWNER_NOTIFICATIONS = listOf(
            ShiftNotifications.ID_ONGOING, ShiftNotifications.ID_BREAK_DUE, ShiftNotifications.ID_NUDGE,
            ShiftNotifications.ID_OVERRUN, ShiftNotifications.ID_AUTO_ENDED
        )
        private val WORD_EXTRAS = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)

        /**
         * The owner boundary, one per process and fair (first come, first served): activateOwner, reconcile, every alarm and
         * notification action and M4's OutlookCoordinator.evaluate hold it, so an owner switch never interleaves a Room read
         * with an arm or a post, and a receiver and the ViewModel never interleave an apply.
         */
        internal val ownerLock = Mutex()

        private val autoEndedEvents = MutableSharedFlow<AutoEndedShift>(extraBufferCapacity = 16)

        /**
         * Shifts a reconcile in this process ended automatically, as they happen (no replay). The ViewModel shows its
         * in-app notice from these, so a forgotten shift that the coordinator closes as the app opens is announced on
         * screen as when the ViewModel's own check closes it; the repository ends a shift once, so only one of the two
         * ever reports it.
         */
        val autoEnded: SharedFlow<AutoEndedShift> = autoEndedEvents.asSharedFlow()
    }
}

/** A shift [AlertCoordinator] ended automatically: whose, which, and the stored end. */
data class AutoEndedShift(val ownerId: String, val shiftId: Int, val endedAt: Instant)
