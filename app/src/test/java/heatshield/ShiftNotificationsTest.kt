package heatshield

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.background.AlertAction
import heatshield.background.DeepLinks
import heatshield.background.ShiftActionReceiver
import heatshield.background.ShiftNotifications
import heatshield.background.UnwellRequest
import heatshield.data.BreakReason
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.PersonalFactors
import heatshield.domain.ShiftRules
import heatshield.ui.FirstAid
import heatshield.ui.Palette
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the lock screen shows, built from the same evaluation Today shows (spec §4, §5.11, §5.12, §10 M3). Robolectric's phone is 12-hour. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShiftNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = ShiftNotifications(context)
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    private fun ms(at: LocalDateTime) = ShiftRules.epochMillis(at)
    private fun hour(h: Int, air: Double): HourHeat {
        val base = HeatRules.baseLevel(air)
        return HourHeat(t(h), air, 10.0, 8.0, air - 3.0, air, base, base, HeatRules.wholeDegrees(air) >= 35, 5.0, 5.0)
    }
    /** Hot all day, Very hot 11–13 at 36°: the union table's stop line, not HeatShield's. */
    private val hotDay = (6..17).map { h -> hour(h, if (h in 11..13) 36.0 else 33.0) }
    /** Hot, then Extreme 11–13 at 39°: HeatShield's stop line. */
    private val extremeDay = (6..17).map { h -> hour(h, if (h in 11..13) 39.0 else 33.0) }
    private val shift = WorkSession(id = 7, ownerId = "local", title = "Push mowing", site = "Royal Park", date = "15 Jan 2026",
        outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ms(t(7)), plannedEndAt = ms(t(15)), source = ShiftSource.TRACKED)
    private fun brk(start: LocalDateTime, end: LocalDateTime?) =
        ShiftEvent(shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(start), endedAt = end?.let(::ms), reason = BreakReason.SHADE)
    private fun evaluation(now: LocalDateTime, events: List<ShiftEvent> = emptyList(), hours: List<HourHeat> = hotDay, rule: BreakRule = BreakRule.HEATSHIELD) =
        ShiftRules.evaluate(OpenShift(shift, events), hours, rule, 570, 720, ShiftRules.instant(now))
    /** The ongoing timer at [now] on the shift's timeline; [wallNow] is the phone's clock, which the chronometer base follows. */
    private fun ongoing(now: LocalDateTime, events: List<ShiftEvent> = emptyList(), hours: List<HourHeat> = hotDay, rule: BreakRule = BreakRule.HEATSHIELD, wallNow: Long = ms(now)) =
        notifications.ongoing("local", OpenShift(shift, events), evaluation(now, events, hours, rule), rule, wallNow)
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun bigText(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text(n)
    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun actionTitles(n: Notification) = n.actions.orEmpty().map { it.title.toString() }

    /** A revised forecast can replace an At with a Stop; its time must never be described as a late break. */
    @Test
    fun aCurrentStopCanQuietlyReplaceAnAtAlertWithoutOfferingSnooze() {
        val reset = listOf(brk(t(10, 33), t(10, 45)))
        val stopped = evaluation(t(11, 3), reset, extremeDay)
        assertEquals(BreakDue.Stop(ShiftRules.instant(t(11)), BreakRule.HEATSHIELD), stopped.due)
        val rewritten = notifications.breakDue("local", 7, stopped.due, stopped, stopped.current, triggerC = 35.0,
            repeat = false, rule = BreakRule.HEATSHIELD, quiet = true, whenMillis = ms(t(10, 50)))
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(rewritten))
        assertEquals(listOf("Start break", "Unwell"), actionTitles(rewritten))
        assertFalse(bigText(rewritten).contains("Due since"))
        assertTrue(rewritten.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(ms(t(10, 50)), rewritten.`when`)
    }

    @Before
    fun channels() {
        // Robolectric grants no runtime permission by itself and post() checks POST_NOTIFICATIONS on 33+: the fixture grants it (one case denies it again).
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.ensureChannels()
    }

    @Test
    fun threeChannelsWithTheSpecifiedImportanceAndTheA2ChannelIsGone() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(ShiftNotifications.CHANNEL_STATUS).importance)
        val breakDue = manager.getNotificationChannel(ShiftNotifications.CHANNEL_BREAK_DUE)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, breakDue.importance)
        assertTrue(breakDue.shouldVibrate()); assertEquals(Notification.VISIBILITY_PUBLIC, breakDue.lockscreenVisibility)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(ShiftNotifications.CHANNEL_OUTLOOK).importance)
        assertNull(manager.getNotificationChannel(ShiftNotifications.LEGACY_CHANNEL))
        assertEquals("Break due", breakDue.name.toString())
        assertFalse(notifications.breakChannelSilent())
    }

    /** An upgraded phone can still hold the retired channel; creating it first makes cleanup observable. */
    @Test
    fun existingA2ChannelIsRemovedWhileTheThreeCurrentChannelsRemain() {
        manager.createNotificationChannel(NotificationChannel(
            ShiftNotifications.LEGACY_CHANNEL, "Planned check-ins", NotificationManager.IMPORTANCE_DEFAULT
        ))
        assertTrue(manager.getNotificationChannel(ShiftNotifications.LEGACY_CHANNEL) != null)

        notifications.ensureChannels()

        assertNull(manager.getNotificationChannel(ShiftNotifications.LEGACY_CHANNEL))
        assertEquals(setOf(ShiftNotifications.CHANNEL_STATUS, ShiftNotifications.CHANNEL_BREAK_DUE, ShiftNotifications.CHANNEL_OUTLOOK),
            manager.notificationChannels.map { it.id }.toSet())
    }

    @Test
    fun ongoingTimerIsASilentPublicChronometerFromTheClockWithThreeActions() {
        val n = ongoing(t(7, 20))
        assertEquals(ShiftNotifications.CHANNEL_STATUS, n.channelId)
        assertTrue((n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(ms(t(7)), n.`when`)                       // wall now less 20 worked minutes: the start, here
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(Notification.CATEGORY_STOPWATCH, n.category)
        // The accent Android draws on the alert is the light Action token (palette spec); the heat level is in the text.
        assertEquals(Palette.LIGHT_ACTION.toInt(), n.color)
        assertEquals("On shift · Hot 33°", title(n))
        assertEquals("Next break 7:45 am", text(n))
        assertEquals(listOf("Start break", "End shift", "Unwell"), actionTitles(n))
        // The timer is public on the lock screen: End shift (which writes the log) asks for the unlock first; the others do not.
        assertEquals(listOf(false, true, false), n.actions.map { it.isAuthenticationRequired })
        assertEquals(ShiftNotifications.TIMEOUT_MILLIS, n.timeoutAfter)
        // Delete → "Show timer again" on Today; content → Today; Unwell → the activity directly, naming the shift it was posted for.
        assertEquals(AlertAction.ONGOING_DISMISSED.name, shadowOf(n.deleteIntent).savedIntent.getStringExtra(ShiftActionReceiver.EXTRA_ACTION))
        assertEquals(DeepLinks.ACTION_OPEN_TODAY, shadowOf(n.contentIntent).savedIntent.action)
        val unwell = shadowOf(n.actions[2].actionIntent)
        assertTrue(n.actions[2].actionIntent.isActivity); assertEquals(DeepLinks.ACTION_UNWELL, unwell.savedIntent.action)
        assertEquals(MainActivity::class.java.name, unwell.savedIntent.component?.className)
        assertEquals(UnwellRequest("local", 7), DeepLinks.unwellRequest(unwell.savedIntent))
        // A 5-minute pause is deducted, as Today deducts it: 20 minutes on the clock, 15 worked, so the base sits at 7:05.
        val paused = listOf(brk(t(7, 10), t(7, 15)))
        assertEquals(ms(t(7, 5)), ongoing(t(7, 20), paused).`when`)
        // The base follows the phone's wall clock, not the shift's timeline: with the clock set 30 minutes ahead the base moves
        // with it, so the count shown stays 15 minutes and does not jump (TIME_SET reconciles, which recomputes this).
        assertEquals(ms(t(7, 35)), ongoing(t(7, 20), paused, wallNow = ms(t(7, 50))).`when`)
        // A later notification must not retarget an Unwell action retained from another owner or shift.
        val otherOwner = notifications.ongoing("other-owner", OpenShift(shift.copy(ownerId = "other-owner"), emptyList()),
            evaluation(t(7, 20)), BreakRule.HEATSHIELD, ms(t(7, 20)))
        assertNotEquals(n.actions[2].actionIntent, otherOwner.actions[2].actionIntent)
        val otherShift = notifications.ongoing("local", OpenShift(shift.copy(id = 8), emptyList()),
            evaluation(t(7, 20)), BreakRule.HEATSHIELD, ms(t(7, 20)))
        assertNotEquals(n.actions[2].actionIntent, otherShift.actions[2].actionIntent)
        assertEquals(UnwellRequest("local", 7), DeepLinks.unwellRequest(shadowOf(n.actions[2].actionIntent).savedIntent))
    }

    @Test
    fun ongoingTimerOnBreakCountsTheBreakAndOffersEndBreak() {
        val open = listOf(brk(t(8), null))
        val n = ongoing(t(8, 3), open)
        assertEquals(ms(t(8)), n.`when`)                       // wall now less the 3 minutes on this break
        assertEquals("On break", title(n))
        assertEquals("10 min resets your clock, at 8:10 am", text(n))
        assertEquals(listOf("End break", "End shift", "Unwell"), actionTitles(n))
        assertEquals(listOf(false, true, false), n.actions.map { it.isAuthenticationRequired })
        assertEquals("Your clock resets when you end this break", text(ongoing(t(8, 12), open)))
    }

    @Test
    fun ongoingTimerDuringAConfirmedSmokoOffersNoBreakOverItAndCountsFromItsEnd() {
        // "Yes" on the 9:30 smoko writes its whole 20 minutes, so at 9:35 the gardener is on it: no Start break over it (the
        // panel hides it and startBreak refuses it, M2 C07), the text says when the clock resets, as Today does, and the base
        // sits at 9:50, so the chronometer reaches zero when the clock resets and counts worked time after it unaided.
        val smoko = listOf(ShiftEvent(shiftId = 7, type = ShiftEventType.SMOKO, startedAt = ms(t(9, 30)), endedAt = ms(t(9, 50))))
        val n = ongoing(t(9, 35), smoko)
        assertEquals("On shift · Hot 33°", title(n))
        assertEquals("On a break until 9:50 am · your clock resets then", text(n))
        assertEquals(listOf("End shift", "Unwell"), actionTitles(n))
        assertEquals(ms(t(9, 50)), n.`when`)
        assertEquals(ms(t(10, 20)), ongoing(t(9, 35), smoko, wallNow = ms(t(10, 5))).`when`)   // a wall-clock change moves it with the clock
        // Once it has ended the timer is the ordinary one again, counted from the smoko's end.
        val after = ongoing(t(10, 5), smoko)
        assertEquals(ms(t(9, 50)), after.`when`)
        assertEquals("Next break 10:35 am", text(after))
        assertEquals(listOf("Start break", "End shift", "Unwell"), actionTitles(after))
    }

    @Test
    fun aStopThePassedWalkReachedIsTheStopWhileTheHourIsAtItThenABreakDueWithNoStopTime() {
        // A 12-minute break ending 10:45 resets the clock; the walk reaches Extreme at 11:00 before the budget drains (a Stop).
        val reset = listOf(brk(t(10, 33), t(10, 45)))
        val during = ongoing(t(11, 30), reset, extremeDay)
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(during))
        assertEquals("Stop heavy work outdoors. Check your crew's hot-day rule.", text(during))
        // At 14:05 the hour is Hot again and no break was taken: Today says the break is due, and so does the timer, without
        // showing the stop's 11:00 as a break's due time. The chronometer counts the minutes; the text names none.
        val cooled = ongoing(t(14, 5), reset, extremeDay)
        assertEquals("Break due · Hot 33°", title(cooled))
        assertEquals("Tap Start break", text(cooled))
        assertEquals(listOf("Start break", "End shift", "Unwell"), actionTitles(cooled))
    }

    @Test
    fun ongoingTimerSaysBreakDueAndOverdueWithoutAlerting() {
        // No count of minutes in the text: it would stand still while the chronometer beside it counts on (47 min against 1:10:00).
        val due = ongoing(t(7, 45))
        assertEquals("Break due · Hot 33°", title(due))
        assertEquals("Tap Start break", text(due))
        val overdue = ongoing(t(7, 57))
        assertEquals("Due since 7:45 am · tap Start break", text(overdue))
        assertEquals(ms(t(7)), overdue.`when`)                 // the chronometer: wall now less 57 worked minutes
        assertTrue((overdue.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        // The stop line is judged on this hour's heat, never on the break walk (Global Constraint 3). A 12-minute break ending
        // 10:00 resets the clock; the Hot break fell due 10:45 and was never taken; at 11:03 the hour is Extreme (39°), so the
        // title is the stop under the HeatShield rule; under the union table the same hour at 36° stops work.
        val reset = listOf(brk(t(9, 48), t(10)))
        val extreme = ongoing(t(11, 3), reset, extremeDay)
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(extreme))
        assertEquals("Due since 10:45 am · tap Start break", text(extreme))
        assertEquals("Stop work outdoors · Very hot 36°", title(ongoing(t(11, 3), reset, hotDay, BreakRule.UNION_VTHC)))
        // A stop ahead is a time, never a current instruction: at 10:50 the walk from a 10:45 reset reaches Extreme at 11:00.
        val ahead = ongoing(t(10, 50), listOf(brk(t(10, 33), t(10, 45))), extremeDay)
        assertEquals("On shift · Hot 33°", title(ahead))
        assertEquals("From 11 am: stop heavy work outdoors", text(ahead))
    }

    @Test
    fun breakDueCarriesLevelHeatMinutesWaterMateAndTheRuleWithThreeActions() {
        val hot = evaluation(t(7, 45))
        val n = notifications.breakDue("local", 7, hot.due as BreakDue.At, hot, hot.current, triggerC = 35.0, repeat = false, rule = BreakRule.HEATSHIELD)
        assertEquals(ShiftNotifications.CHANNEL_BREAK_DUE, n.channelId)
        assertEquals("Break due · Hot 33°", title(n))
        assertEquals("45 min since you started.", text(n))
        assertEquals("45 min since you started. Drink a cup of water.", bigText(n))
        assertEquals(Notification.CATEGORY_ALARM, n.category); assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue((n.flags and Notification.FLAG_AUTO_CANCEL) != 0); assertEquals(0, n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(listOf("Start break", "In 10 min", "Unwell"), actionTitles(n))
        assertEquals(DeepLinks.ACTION_OPEN_BREAK, shadowOf(n.contentIntent).savedIntent.action)
        assertEquals(AlertAction.SNOOZE.name, shadowOf(n.actions[1].actionIntent).savedIntent.getStringExtra(ShiftActionReceiver.EXTRA_ACTION))
        assertTrue(n.actions[2].actionIntent.isActivity)
        assertEquals(UnwellRequest("local", 7), DeepLinks.unwellRequest(shadowOf(n.actions[2].actionIntent).savedIntent))
        // A 12-minute break ending 10:45 resets the clock; 15 Hot minutes (1/3 of a 45-minute budget) then Very hot at 30/15
        // drains the rest by 11:20. Posted 3 minutes late as the repeat, over the 35° rule: due time, mate line and rule line.
        val reset = listOf(brk(t(10, 33), t(10, 45)))
        val veryHot = evaluation(t(11, 23), reset)
        assertEquals(ShiftRules.instant(t(11, 20)), veryHot.due.dueAt)
        val late = notifications.breakDue("local", 7, veryHot.due as BreakDue.At, veryHot, veryHot.current, triggerC = 35.0, repeat = true, rule = BreakRule.HEATSHIELD)
        assertEquals("Break due · Very hot 36°", title(late))
        assertEquals("38 min since your break. Due since 11:20 am. Drink a cup of water. Check on your mate. Air over your 35° rule this hour.", bigText(late))
        assertEquals("Asked again", late.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertNull(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        // The stop alert (Global Constraint 3): the same kind of Hot break, due 10:45 and still untaken when the hour turned Extreme,
        // is announced from this hour's heat as a stop, Today's stop line first, with Start break and Unwell only.
        val untaken = listOf(brk(t(9, 48), t(10)))
        val stopped = evaluation(t(11, 3), untaken, extremeDay)
        val stop = notifications.breakDue("local", 7, stopped.due as BreakDue.At, stopped, stopped.current, triggerC = 35.0, repeat = false, rule = BreakRule.HEATSHIELD)
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(stop))
        assertEquals("Stop heavy work outdoors. Check your crew's hot-day rule. 1 h 3 min since your break. Due since 10:45 am. Drink a cup of water. Check on your mate. Air over your 35° rule this hour.", bigText(stop))
        assertEquals(listOf("Start break", "Unwell"), actionTitles(stop))
        val unionDue = evaluation(t(11, 3), untaken, hotDay, BreakRule.UNION_VTHC)
        val unionStop = notifications.breakDue("local", 7, unionDue.due as BreakDue.At, unionDue, unionDue.current, triggerC = 35.0, repeat = false, rule = BreakRule.UNION_VTHC)
        assertEquals("Stop work outdoors · Very hot 36°", title(unionStop))
        assertTrue(bigText(unionStop), bigText(unionStop).startsWith("Stop work outdoors. The union table, adapted by HeatShield, stops from 36°."))
        assertEquals(listOf("Start break", "Unwell"), actionTitles(unionStop))
        // The coordinator's in-place rewrite of a Hot alert still showing when the hour crossed the line: the same stop content,
        // alerting once at most on the update, so the stop never buzzes (deviations table); the ordinary post alerts.
        val rewritten = notifications.breakDue("local", 7, stopped.due, stopped, stopped.current, triggerC = 35.0, repeat = false, rule = BreakRule.HEATSHIELD, quiet = true)
        assertEquals(title(stop), title(rewritten)); assertEquals(bigText(stop), bigText(rewritten)); assertEquals(actionTitles(stop), actionTitles(rewritten))
        assertTrue((rewritten.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0); assertEquals(0, stop.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        // "In 10 min" only when the reminder will come (spec §8). A Hot break due 10:55 with Extreme from 11:00: the snooze would
        // meet the stop line, which never buzzes, so the alert names the stop and offers Start break and Unwell only.
        val beforeStop = evaluation(t(10, 55), listOf(brk(t(9, 58), t(10, 10))), extremeDay)
        assertEquals(ShiftRules.instant(t(10, 55)), beforeStop.due.dueAt)
        val stopFrom = ShiftRules.snoozeStop(beforeStop, extremeDay)
        assertEquals(ShiftRules.instant(t(11)), stopFrom)
        val noSnooze = notifications.breakDue("local", 7, beforeStop.due as BreakDue.At, beforeStop, beforeStop.current, triggerC = 35.0, repeat = false,
            rule = BreakRule.HEATSHIELD, offerSnooze = stopFrom == null, stopFrom = stopFrom)
        assertEquals("Break due · Hot 33°", title(noSnooze))
        assertEquals(listOf("Start break", "Unwell"), actionTitles(noSnooze))
        assertEquals("45 min since your break. From 11 am: stop heavy work outdoors. Drink a cup of water.", bigText(noSnooze))
        // The re-ask after a first "Can't stop yet": a second answer would reach the cap, whose fire stays silent, so no In 10 min.
        val atCap = notifications.breakDue("local", 7, hot.due as BreakDue.At, hot, hot.current, triggerC = 35.0, repeat = false,
            rule = BreakRule.HEATSHIELD, offerSnooze = false)
        assertEquals(listOf("Start break", "Unwell"), actionTitles(atCap))
        assertEquals(bigText(n), bigText(atCap))
        // A break that waited for smoko buzzes at smoko's time when smoko is still unanswered, and says why it came then.
        val waited = evaluation(t(9, 30), listOf(brk(t(8, 28), t(8, 40))))
        assertEquals(heatshield.domain.ScheduledKind.SMOKO, (waited.due as BreakDue.At).scheduled)
        val smokoAlert = notifications.breakDue("local", 7, waited.due as BreakDue.At, waited, waited.current, triggerC = 35.0, repeat = false, rule = BreakRule.HEATSHIELD)
        assertEquals("50 min since your break. Smoko at 9:30 am isn't confirmed. Drink a cup of water.", bigText(smokoAlert))
        assertEquals(listOf("Start break", "In 10 min", "Unwell"), actionTitles(smokoAlert))
        // No exclamation marks, no "safe", no "now" (spec §8; Global Constraint 5).
        listOf(n, late, stop, unionStop, noSnooze, smokoAlert).forEach { assertFalse(bigText(it), Regex("!|\\bsafe\\b|\\bnow\\b").containsMatchIn(bigText(it) + title(it))) }
    }

    @Test
    fun nudgeOverrunAutoEndedAndPreviewReadAsSpecified() {
        val heat = heatshield.domain.ContextReasoner.todayHeat(null, heatshield.domain.HeatProfile("Royal Park", "Push mowing", 420, 900, 35.0, PersonalFactors()),
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(ms(t(6, 40))), heatshield.data.MelbourneZone))
        val nudge = notifications.startNudge(heat.copy(site = "Royal Park", peak = hour(14, 36.0), dayLevel = HeatLevel.VERY_HOT,
            crewRule = heatshield.domain.CrewRuleLine(35.0, java.time.LocalTime.of(13, 0), 2, 36.0)))
        assertEquals(ShiftNotifications.CHANNEL_OUTLOOK, nudge.channelId)
        assertEquals("Hot day at Royal Park", title(nudge))
        assertEquals("36° by 2 pm (Very hot). Air forecast to reach 35° from 1 pm. Start your shift when you clock on.", bigText(nudge))
        assertEquals(listOf("Start shift"), actionTitles(nudge))
        assertEquals(DeepLinks.ACTION_START_SHIFT, shadowOf(nudge.actions[0].actionIntent).savedIntent.action)

        val overrun = notifications.stillOnShift("local", 7, ShiftRules.instant(t(15)))
        assertEquals("Still on shift?", title(overrun))
        assertEquals("Your shift was set to finish at 3 pm. End your shift, or keep going.", text(overrun))
        assertEquals(listOf("End shift", "Still working"), actionTitles(overrun))
        assertTrue(overrun.actions[0].isAuthenticationRequired)
        assertFalse(overrun.actions[1].isAuthenticationRequired)

        val ended = notifications.autoEnded(ShiftRules.instant(t(15)), plannedEnd = ShiftRules.instant(t(15)))
        assertEquals(ShiftNotifications.CHANNEL_STATUS, ended.channelId)
        assertEquals("Your shift ended at 3 pm, your planned finish. Fix the times in your log if that's wrong.", text(ended))
        // M2 C06: the automatic end can be a break or answer logged after the planned finish; that time is not "your planned finish".
        assertEquals("Your shift ended at 4:10 pm. Fix the times in your log if that's wrong.",
            text(notifications.autoEnded(ShiftRules.instant(t(16, 10)), plannedEnd = ShiftRules.instant(t(15)))))
        assertEquals("Your shift ended at 3 pm. Fix the times in your log if that's wrong.", text(notifications.autoEnded(ShiftRules.instant(t(15)))))
        assertEquals("Hot day forecast. Start your shift when you clock on.",
            bigText(notifications.startNudge(heat.copy(site = "Royal Park", peak = null, dayLevel = HeatLevel.HOT))))

        val due = evaluation(t(7, 45))
        val preview = notifications.previewBreakDue(due.due as BreakDue.At, due, due.current, BreakRule.HEATSHIELD)
        assertEquals("Preview · Break due · Hot 33°", title(preview))
        assertTrue(preview.actions.isNullOrEmpty())
        assertEquals(DeepLinks.ACTION_OPEN_PREVIEW, shadowOf(preview.contentIntent).savedIntent.action)
        assertNotEquals(ShiftNotifications.ID_BREAK_DUE, ShiftNotifications.ID_PREVIEW)
    }

    @Test
    fun postIsFalseWhenNotificationsAreBlockedAndDeepLinksMapEveryAction() {
        val n = notifications.autoEnded(ShiftRules.instant(t(15)))
        assertTrue(notifications.post(ShiftNotifications.ID_AUTO_ENDED, n))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertTrue(notifications.isShown(ShiftNotifications.ID_AUTO_ENDED)); assertFalse(notifications.isShown(ShiftNotifications.ID_BREAK_DUE))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifications.notificationsAllowed())
        assertFalse(notifications.post(ShiftNotifications.ID_AUTO_ENDED, n))
        // Notifications enabled but the runtime permission denied (API 33+): the same honest false, and no SecurityException.
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifications.notificationsAllowed())
        assertFalse(notifications.post(ShiftNotifications.ID_AUTO_ENDED, n))
        notifications.cancel(ShiftNotifications.ID_AUTO_ENDED)
        assertFalse(notifications.isShown(ShiftNotifications.ID_AUTO_ENDED))
        assertEquals(mapOf(FirstAid.ACTION to FirstAid.ROUTE, DeepLinks.ACTION_UNWELL to FirstAid.ROUTE, DeepLinks.ACTION_OPEN_TODAY to "today",
            DeepLinks.ACTION_OPEN_BREAK to "break", DeepLinks.ACTION_START_SHIFT to "start-shift", DeepLinks.ACTION_OPEN_PREVIEW to "preview"),
            listOf(FirstAid.ACTION, DeepLinks.ACTION_UNWELL, DeepLinks.ACTION_OPEN_TODAY, DeepLinks.ACTION_OPEN_BREAK, DeepLinks.ACTION_START_SHIFT, DeepLinks.ACTION_OPEN_PREVIEW)
                .associateWith { DeepLinks.routeFor(it) })
        assertNull(DeepLinks.routeFor(null)); assertNull(DeepLinks.routeFor("android.intent.action.MAIN"))
        // An Unwell intent without its shift is no request: the launcher shortcut and a stale intent start nothing.
        assertNull(DeepLinks.unwellRequest(Intent(DeepLinks.ACTION_UNWELL))); assertNull(DeepLinks.unwellRequest(null))
    }
}
