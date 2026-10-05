package heatshield

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.BreakReason
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.StartShiftInput
import heatshield.data.WorkContext
import heatshield.data.WorkSession
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatProfile
import heatshield.domain.OpenShift
import heatshield.domain.PersonalFactors
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.TodayHeat
import heatshield.ui.Formats
import heatshield.ui.HeatShieldTheme
import heatshield.ui.ShiftAction
import heatshield.ui.ShiftPanel
import heatshield.ui.ShiftUiState
import heatshield.ui.StartShiftScreen
import heatshield.ui.startShiftInitial
import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shift clock as a gardener sees it: every panel state from fixed evaluations, Start shift, Break, Fix times and Add a past shift. */
@RunWith(AndroidJUnit4::class)
class ShiftScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val use24Hour = DateFormat.is24HourFormat(context)
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    private fun ms(time: LocalDateTime) = ShiftRules.epochMillis(time)
    /** The shift's timeline is real instants; this day has no daylight-saving change, so [t] converts one to one. */
    private fun i(time: LocalDateTime) = ShiftRules.instant(time)
    private fun at(time: LocalDateTime) = Formats.time(time.toLocalTime(), use24Hour)
    private val profile = HeatProfile("Birrarung Marr", "Push mowing", 420, 900, 35.0, PersonalFactors(heavyWork = true))
    /** A real reasoner day: 33° air all day (Hot; Very hot for heavy work), read at [now]. */
    private fun heat(now: LocalDateTime, airC: Double = 33.0): TodayHeat = ContextReasoner.todayHeat(
        ForecastSnapshot("Birrarung Marr", ms(now) - 5 * 60_000L, (0..23).map { ForecastHour(date.atTime(it, 0).toString(), airC, 10.0, 5.0, 8.0, 5.0) }),
        profile, Clock.fixed(java.time.Instant.ofEpochMilli(ms(now)), heatshield.data.MelbourneZone))
    private val row = WorkSession(id = 7, ownerId = "local", title = "Push mowing", site = "Birrarung Marr", date = "15 Jan 2026", outdoorMinutes = 0,
        breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ms(t(7)), plannedEndAt = ms(t(15)), source = ShiftSource.TRACKED, heavyWork = true)
    private fun brk(start: LocalDateTime, end: LocalDateTime?, reason: String = BreakReason.SHADE) =
        ShiftEvent(id = 1, shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(start), endedAt = end?.let(::ms), reason = reason)
    private fun state(now: LocalDateTime, vararg events: ShiftEvent, rule: BreakRule = BreakRule.HEATSHIELD): ShiftUiState {
        val heat = heat(now)
        val evaluation = ShiftRules.evaluate(OpenShift(row, events.toList()), heat.hours, rule, 570, 720, i(now))
        return evaluation.openBreak?.let { ShiftUiState.OnBreak(row, evaluation, it) } ?: ShiftUiState.On(row, evaluation)
    }
    private val actions = mutableListOf<ShiftAction>()
    private var panelState by mutableStateOf<ShiftUiState>(ShiftUiState.Off)
    private var panelNow by mutableStateOf(t(7))
    /**
     * setContent runs once per test; later states arrive through [update]. The host scrolls, as Today's page does, so a
     * panel taller than the screen at double font can still be scrolled to.
     */
    private fun showPanel(state: ShiftUiState, now: LocalDateTime, fontScale: Float = 1f) {
        panelState = state; panelNow = now
        compose.setEnglishContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                HeatShieldTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { ShiftPanel(panelState, onAction = { actions += it }) }
                }
            }
        }
    }
    private fun update(state: ShiftUiState, now: LocalDateTime) = compose.runOnIdle { panelState = state; panelNow = now }

    @Test
    fun offShiftOffersOnePrimaryStartShift() {
        showPanel(ShiftUiState.Off, t(7))
        compose.onNodeWithTag("shift_start").assertIsEnabled().assertTextEquals("Start shift").performClick()
        compose.onNodeWithTag("shift_status").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf<ShiftAction>(ShiftAction.OpenStart), actions) }
    }

    @Test
    fun onShiftShowsMinutesSinceTheStartHeatAndTheNextBreak() {
        // Very hot for heavy work: 30 min work, so from a 7:00 start the first break is due 7:30.
        showPanel(state(t(7, 12)), t(7, 12))
        compose.onNodeWithTag("shift_status").assertTextEquals("12 min since you started · Very hot 33° · next break ${at(t(7, 30))}")
        compose.onNodeWithTag("shift_water").assertDoesNotExist()
        compose.onNodeWithTag("shift_break_start").assertTextEquals("Start break").performClick()
        compose.onNodeWithTag("shift_break_sheet").assertTextEquals("Can't stop, or no shade?").performClick()
        // End shift asks first: the clock stops at once and a shift cannot be reopened. Keep working changes nothing.
        compose.onNodeWithTag("shift_end").assertTextEquals("End shift").performClick()
        compose.onNodeWithText("End shift at ${at(t(7, 12))}?").assertExists()
        compose.onNodeWithText("The clock stops and the shift goes in your log. Fix times can change it later.").assertExists()
        compose.onNodeWithTag("shift_end_keep").assertTextEquals("Keep working").performClick()
        compose.onNodeWithTag("shift_end_confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(ShiftAction.StartBreak, ShiftAction.OpenBreakSheet), actions) }
        compose.onNodeWithTag("shift_end").performClick()
        compose.onNode(hasText("End shift") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("This shift") and hasClickAction()).performClick()
        compose.onNodeWithTag("shift_finish_note").assertTextEquals("If the shift is still running 2 hours after its planned finish, HeatShield " +
            "ends it and saves the planned finish as its end. A break or answer logged after the planned finish counts instead: " +
            "it is saved as the end, and the 2 hours count from it.")
        compose.onNode(hasText("Heavy work this shift") and hasClickAction()).assertIsOn()
            .assertTextContains("brings your breaks forward", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(listOf(ShiftAction.StartBreak, ShiftAction.OpenBreakSheet, ShiftAction.EndShift, ShiftAction.HeavyWork(false)), actions)
        }
    }

    @Test
    fun breakDueSaysSoWithTheNumbersAndWater() {
        // Reset by a 12-minute break ending 8:12; due 8:42; now 8:50 → 8 minutes overdue.
        showPanel(state(t(8, 50), brk(t(8), t(8, 12))), t(8, 50))
        compose.onNodeWithTag("shift_status").assertTextEquals("Break due 8 min ago · Very hot 33°")
        compose.onNodeWithTag("shift_water").assertTextEquals("Drink a cup of water.")
        // A break due exactly now, before a whole minute passes, names the minutes since the break.
        update(state(t(8, 42), brk(t(8), t(8, 12))), t(8, 42))
        compose.onNodeWithTag("shift_status").assertTextEquals("Break due · Very hot 33° · 30 min since your break")
    }

    @Test
    fun smokoProposalOffersYesOrNotYet() {
        showPanel(state(t(9, 31), brk(t(9), t(9, 12))), t(9, 31))
        compose.onNodeWithTag("shift_proposal").assertTextContains("Smoko at ${at(t(9, 30))} — taken?", substring = true)
        compose.onNodeWithTag("proposal_took").assertTextEquals("Yes").performClick()
        compose.onNodeWithTag("proposal_not_yet").assertTextEquals("Not yet").performClick()
        compose.runOnIdle { assertEquals(listOf(ShiftAction.Scheduled(ScheduledKind.SMOKO, true), ShiftAction.Scheduled(ScheduledKind.SMOKO, false)), actions) }
        // An unanswered proposal changes nothing on the clock: 19 minutes since the 9:12 reset, no pause, no reset.
        compose.runOnIdle { assertEquals(i(t(9, 12)), (panelState as ShiftUiState.On).evaluation.clock.since) }
        // Smoko within 15 minutes covers the next break: the status says so instead of a time.
        update(state(t(9, 2), brk(t(8, 35), t(8, 47))), t(9, 2))
        compose.onNodeWithTag("shift_status").assertTextEquals("15 min since your break · Very hot 33° · Smoko at ${at(t(9, 30))} covers your next break")
    }

    @Test
    fun onBreakShowsTheClockAndWhenItResets() {
        showPanel(state(t(10, 4), brk(t(10), null)), t(10, 4))
        compose.onNodeWithTag("break_card").assertExists()
        compose.onNodeWithTag("break_clock").assertTextEquals("04:00")
        compose.onNodeWithText("10 min resets your clock, at ${at(t(10, 10))}").assertExists()
        compose.onNodeWithText("Suggested: 15 min in the shade").assertExists()
        compose.onNodeWithTag("shift_break_end").assertTextEquals("End break").performClick()
        compose.onNodeWithTag("shift_break_start").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf<ShiftAction>(ShiftAction.EndBreak), actions) }
        update(state(t(10, 11), brk(t(10), null, reason = BreakReason.UNWELL)), t(10, 11))
        compose.onNodeWithText("Your clock resets when you end this break").assertExists()
        compose.onNodeWithText("You stopped because you don't feel right.", substring = true).assertExists()
        // The card says to open Heat first aid if it gets worse, so it offers it.
        compose.onNodeWithTag("shift_first_aid").assertTextEquals("Someone unwell? Heat first aid").performClick()
        compose.runOnIdle { assertEquals(listOf(ShiftAction.EndBreak, ShiftAction.OpenFirstAid), actions) }
    }

    @Test
    fun stopLinesReplaceTheCadenceAtExtremeAndFromThirtySixOnTheUnionTable() {
        val extreme = ShiftRules.evaluate(OpenShift(row, emptyList()), heat(t(9), airC = 41.0).hours, BreakRule.HEATSHIELD, 570, 720, i(t(9)))
        showPanel(ShiftUiState.On(row, extreme), t(9))
        compose.onNodeWithTag("shift_status").assertTextEquals("Extreme 41° · stop heavy work outdoors · 2 h since you started")
        // Union table, adapted by HeatShield: a heat number of 36° stops work, whatever the level word for heavy work says.
        val union = ShiftRules.evaluate(OpenShift(row, emptyList()), heat(t(9), airC = 36.0).hours, BreakRule.UNION_VTHC, 570, 720, i(t(9)))
        update(ShiftUiState.On(row, union), t(9))
        compose.onNodeWithTag("shift_status").assertTextEquals("Extreme 36° · stop work outdoors · 2 h since you started")
        // No current forecast: nothing is timed and nothing says "none due".
        update(ShiftUiState.On(row, ShiftRules.evaluate(OpenShift(row, emptyList()), emptyList(), BreakRule.HEATSHIELD, 570, 720, i(t(9)))), t(9))
        compose.onNodeWithTag("shift_status")
            .assertTextEquals("2 h since you started · no current forecast for this hour · can't time breaks without a current forecast")
    }

    // A stop the walk reaches later in the shift is named with its time; the hour now is not called Extreme.
    @Test
    fun aStopLaterInTheShiftIsTimedAndTheCurrentHourKeepsItsOwnLevel() {
        // A cool morning (Normal drains nothing) and Extreme from 2 pm: at 1:20 pm the walk reaches the 2 pm stop.
        val coolThenExtreme = ContextReasoner.todayHeat(
            ForecastSnapshot("Birrarung Marr", ms(t(13, 20)) - 5 * 60_000L, (0..23).map { ForecastHour(date.atTime(it, 0).toString(), if (it >= 14) 41.0 else 26.0, 10.0, 5.0, 8.0, 5.0) }),
            profile, Clock.fixed(java.time.Instant.ofEpochMilli(ms(t(13, 20))), heatshield.data.MelbourneZone))
        val ahead = ShiftRules.evaluate(OpenShift(row, emptyList()), coolThenExtreme.hours, BreakRule.HEATSHIELD, 570, 720, i(t(13, 20)))
        assertEquals(heatshield.domain.BreakDue.Stop(i(t(14)), BreakRule.HEATSHIELD), ahead.due)
        showPanel(ShiftUiState.On(row, ahead), t(13, 20))
        compose.onNodeWithTag("shift_status").assertTextEquals("6 h 20 min since you started · Normal 26° · stop heavy work outdoors from ${at(t(14))}")
        // The same stop ahead with this hour Very hot at 35°: its own level, the stop's time, and no "Extreme".
        val veryHot = heatshield.domain.HourHeat(t(13), 35.0, 10.0, 8.0, 32.0, 35.0, heatshield.domain.HeatLevel.VERY_HOT,
            heatshield.domain.HeatLevel.VERY_HOT, true, 5.0, 5.0)
        update(ShiftUiState.On(row, ahead.copy(current = veryHot)), t(13, 20))
        compose.onNodeWithTag("shift_status").assertTextEquals("6 h 20 min since you started · Very hot 35° · stop heavy work outdoors from ${at(t(14))}")
        compose.onNodeWithTag("shift_status").assert(!hasText("Extreme", substring = true))
        // Under the union table the stop is a heat number of 36°, timed the same way.
        update(ShiftUiState.On(row, ahead.copy(current = veryHot, due = heatshield.domain.BreakDue.Stop(i(t(14)), BreakRule.UNION_VTHC))), t(13, 20))
        compose.onNodeWithTag("shift_status").assertTextEquals("6 h 20 min since you started · Very hot 35° · stop work outdoors from ${at(t(14))}")
        // A stop the walk passed with no break, in an hour now below it, is a break due, not a stop.
        update(ShiftUiState.On(row, ahead.copy(current = veryHot, now = i(t(14, 30)))), t(14, 30))
        compose.onNodeWithTag("shift_status").assertTextEquals("Break due 30 min ago · Very hot 35°")
    }

    // Astra M2 #1: a break that fell due at Hot is a stop once this hour is at the stop line. The status judges this hour
    // on its own, never by the earlier due time, under either break table.
    @Test
    fun thisHourAtTheStopLineIsTheStopWhateverTheEarlierDueTimeSaid() {
        // Very hot for heavy work at 33°: due 7:30, so at 8:20 the walk says "Break due 50 min ago".
        val due = ShiftRules.evaluate(OpenShift(row, emptyList()), heat(t(8, 20)).hours, BreakRule.HEATSHIELD, 570, 720, i(t(8, 20)))
        assertTrue("${due.due}", due.due is heatshield.domain.BreakDue.At)
        val extremeHour = heatshield.domain.HourHeat(t(8), 41.0, 10.0, 8.0, 38.0, 41.0, heatshield.domain.HeatLevel.EXTREME,
            heatshield.domain.HeatLevel.EXTREME, true, 5.0, 5.0)
        showPanel(ShiftUiState.On(row, due.copy(current = extremeHour)), t(8, 20))
        compose.onNodeWithTag("shift_status").assertTextEquals("Extreme 41° · stop heavy work outdoors · 1 h 20 min since you started")
        // Union table: 33° is a 30/30 row (due 7:30); this hour at 36° stops work, whatever the walk said.
        val unionDue = ShiftRules.evaluate(OpenShift(row, emptyList()), heat(t(8, 20)).hours, BreakRule.UNION_VTHC, 570, 720, i(t(8, 20)))
        val hot36 = heatshield.domain.HourHeat(t(8), 36.0, 10.0, 8.0, 33.0, 36.0, heatshield.domain.HeatLevel.VERY_HOT,
            heatshield.domain.HeatLevel.VERY_HOT, true, 5.0, 5.0)
        update(ShiftUiState.On(row, unionDue.copy(current = hot36)), t(8, 20))
        compose.onNodeWithTag("shift_status").assertTextEquals("Very hot 36° · stop work outdoors · 1 h 20 min since you started")
    }

    // Astra M2 #2: the union table's stop line stays on the break sheet under an open "Can't stop yet", as the Extreme
    // line does.
    @Test
    fun theUnionStopLineStaysOnTheBreakSheetUnderAnOpenAnswer() {
        // 36° for light work: Very hot, not Extreme, so only the union table stops work.
        val light = ContextReasoner.todayHeat(
            ForecastSnapshot("Birrarung Marr", ms(t(8, 50)) - 5 * 60_000L, (0..23).map { ForecastHour(date.atTime(it, 0).toString(), 36.0, 10.0, 5.0, 8.0, 5.0) }),
            profile.copy(factors = PersonalFactors()), Clock.fixed(java.time.Instant.ofEpochMilli(ms(t(8, 50))), heatshield.data.MelbourneZone))
        val answered = ShiftEvent(id = 4, shiftId = 7, type = ShiftEventType.CANT_STOP, startedAt = ms(t(8, 45)), endedAt = ms(t(8, 45)))
        val evaluation = ShiftRules.evaluate(OpenShift(row, listOf(answered)), light.hours, BreakRule.UNION_VTHC, 570, 720, i(t(8, 50)))
        var reminder by mutableStateOf<java.time.Instant?>(null)
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(light, ShiftUiState.On(row, evaluation), reminder = reminder, busy = false, breakRule = BreakRule.UNION_VTHC,
                    onStartBreak = {}, onCantStop = {}, onEndBreak = {}, onReturn = {}, onStartShift = {}, onOpenFirstAid = {})
            }
        }
        // The whole line: this hour is at the union stop line, so no break time is promised past it (fix round 1, H1).
        compose.onNodeWithTag("break_open_answer").assertTextEquals("You said: Can't stop yet")
        compose.onNodeWithText("Stop work outdoors. The union table, adapted by HeatShield, stops from 36°.").assertExists()
        compose.onNodeWithTag("break_extreme").assertDoesNotExist()
        // The stop line is HeatShield's adaptation, so its Why carries the rule label under the answer too.
        compose.onNode(hasText("Why this suggestion?") and hasClickAction()).performScrollTo().performClick()
        compose.onAllNodesWithContentDescription("Source: ${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(heatshield.domain.SourceLabel.HEATSHIELD_RULE))}").assertCountEquals(1)
        // The sheet's confirmation once the 8:45 answer is saved (asked for 8:55): the stop, never "Break due again at 8:55".
        compose.runOnIdle { reminder = i(t(8, 55)) }
        compose.onNodeWithText("Stop work outdoors now").assertExists()
        compose.onNodeWithText("Break due again", substring = true).assertDoesNotExist()
    }

    // Astra M2 fix round 1, H1: "Can't stop yet" at 7:55 snoozes the break to 8:05, but 8 am is Extreme, so the walk's
    // answer is the stop at 8:00. No line promises a break at 8:05: not Today's next step, not the sheet's answer line, not
    // its confirmation, which names the stop. Over Hot hours the same snooze keeps its time on all three; answered before
    // the break was due, the confirmation names the break's own time, as the panel does.
    @Test
    fun aSnoozeIntoAnExtremeHourPromisesNoBreakTimePastTheStopLine() {
        val started = row.copy(startedAt = ms(t(7, 25)))   // Very hot for heavy work at 33°: 30 min, so due 7:55
        fun answer(at: LocalDateTime) = ShiftEvent(id = 4, shiftId = 7, type = ShiftEventType.CANT_STOP, startedAt = ms(at), endedAt = ms(at))
        fun dayOf(extremeFromEight: Boolean) = ContextReasoner.todayHeat(
            ForecastSnapshot("Birrarung Marr", ms(t(7, 25)) - 5 * 60_000L,
                (0..23).map { ForecastHour(date.atTime(it, 0).toString(), if (extremeFromEight && it >= 8) 41.0 else 33.0, 10.0, 5.0, 8.0, 5.0) }),
            profile, Clock.fixed(java.time.Instant.ofEpochMilli(ms(t(7, 55))), heatshield.data.MelbourneZone))
        var heat by mutableStateOf(dayOf(extremeFromEight = true))
        var now by mutableStateOf(t(7, 55))
        var sheet by mutableStateOf(false)
        var reminder by mutableStateOf<java.time.Instant?>(null)
        fun state() = ShiftUiState.On(started, ShiftRules.evaluate(OpenShift(started, listOf(answer(now))), heat.hours, BreakRule.HEATSHIELD, 570, 720, i(now)))
        assertEquals(heatshield.domain.BreakDue.Stop(i(t(8)), BreakRule.HEATSHIELD), state().evaluation.due)
        compose.setEnglishContent {
            HeatShieldTheme {
                if (sheet) heatshield.ui.BreakSheetContent(heat, state(), reminder = reminder, busy = false,
                    onStartBreak = {}, onCantStop = { reminder = i(t(8, 5)) }, onEndBreak = {}, onReturn = {}, onStartShift = {}, onOpenFirstAid = {})
                else heatshield.ui.TodayContent(heat = heat, sites = listOf("Birrarung Marr"), refreshing = false, nowMillis = ms(now),
                    onSelectSite = {}, onRefresh = {}, onOpenPreview = {}, onOpenPlan = {}, onOpenFirstAid = {}, shift = state(),
                    nextStep = ContextReasoner.nextStep(heat, heatshield.domain.OpenAnswerKind.CANT_STOP))
            }
        }
        compose.onNodeWithTag("shift_status").assertTextEquals("30 min since you started · Very hot 33° · stop heavy work outdoors from ${at(t(8))}")
        compose.onNodeWithTag("today_line_next").performScrollTo().assertTextEquals("You said: Can't stop yet")
        compose.runOnIdle { sheet = true }
        compose.onNodeWithTag("break_open_answer").assertTextEquals("You said: Can't stop yet")
        compose.onNodeWithTag("break_extreme").assertTextEquals("From ${at(t(8))}: stop heavy work outdoors. Check your crew's hot-day rule.")
        // Before the tap the choice itself promises no break past the stop line, and no reminder that will not come: the
        // walk names the stop at 8:00, so nothing is armed for 8:05 (M3 Task 6, the carried H1 minor).
        compose.onNode(hasText("Can't stop yet") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Break due again in 10 min", substring = true).assertDoesNotExist()
        compose.onNodeWithText("comes back in 10 minutes", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Remind me in 10 min", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Nothing is added to your breaks. Stop heavy work outdoors from ${at(t(8))}.").performScrollTo().assertExists()
        // The sheet's confirmation (asked at 7:55 for 8:05) names the stop, not the snoozed break.
        compose.onNode(hasText("Save my answer") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Stop heavy work outdoors from ${at(t(8))}").assertExists()
        compose.onNodeWithText("Break due again", substring = true).assertDoesNotExist()
        // Before Room's copy of the answer reaches the evaluation (its snooze is earlier than asked), it promises nothing.
        compose.runOnIdle { reminder = i(t(8, 6)) }
        compose.onNodeWithText("Can't stop yet").assertExists()
        compose.onNodeWithText("Stop heavy work outdoors from", substring = true).assertDoesNotExist()
        // Over Hot hours the snooze is the next thing, so its time shows on all three.
        compose.runOnIdle { heat = dayOf(extremeFromEight = false); reminder = null; sheet = false }
        compose.onNodeWithTag("today_line_next").performScrollTo().assertTextEquals("You said: Can't stop yet. Break due again at ${at(t(8, 5))}.")
        compose.runOnIdle { sheet = true }
        compose.onNodeWithTag("break_open_answer").assertTextEquals("You said: Can't stop yet. Break due again at ${at(t(8, 5))}.")
        compose.runOnIdle { reminder = i(t(8, 5)) }
        compose.onNodeWithText("Break due again at ${at(t(8, 5))}").assertExists()
        // Answered at 7:30, before the 7:55 break was due: the snooze ends at 7:40, so no "again" time; the confirmation
        // names the break's own 7:55.
        compose.runOnIdle { now = t(7, 30); reminder = i(t(7, 40)) }
        compose.onNodeWithTag("break_open_answer").assertTextEquals("You said: Can't stop yet")
        compose.onNodeWithText("Next break due at ${at(t(7, 55))}").assertExists()
        compose.onNodeWithText("Break due again", substring = true).assertDoesNotExist()
    }

    // M3 Task 6 (the carried H1 minor): the "Can't stop yet" choice, before it is saved, offers the 10-minute reminder only
    // when a timed break is what comes back. Due at 7:55 with 8 am Extreme, the snooze to 8:05 would run into the stop, so
    // the choice names the stop from 8:00; at 8:01, inside the Extreme hour, it says stop now; over Hot hours it reminds;
    // with no current forecast nothing is timed, so nothing is promised.
    @Test
    fun theCantStopChoiceRemindsOnlyWhenABreakComesBackBeforeAnyStop() {
        val started = row.copy(startedAt = ms(t(7, 25)))   // Very hot for heavy work at 33°: 30 min, so due 7:55
        fun dayOf(at: LocalDateTime, extremeFromEight: Boolean, ageMinutes: Long = 5) = ContextReasoner.todayHeat(
            ForecastSnapshot("Birrarung Marr", ms(at) - ageMinutes * 60_000L,
                (0..23).map { ForecastHour(date.atTime(it, 0).toString(), if (extremeFromEight && it >= 8) 41.0 else 33.0, 10.0, 5.0, 8.0, 5.0) }),
            profile, Clock.fixed(java.time.Instant.ofEpochMilli(ms(at)), heatshield.data.MelbourneZone))
        var now by mutableStateOf(t(7, 55))
        var heat by mutableStateOf(dayOf(t(7, 55), extremeFromEight = true))
        var timed by mutableStateOf(true)
        var remindable by mutableStateOf(true)
        fun state() = ShiftUiState.On(started, ShiftRules.evaluate(OpenShift(started, emptyList()), if (timed) heat.hours else emptyList(),
            BreakRule.HEATSHIELD, 570, 720, i(now)))
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(heat, state(), reminder = null, busy = false,
                    onStartBreak = {}, onCantStop = {}, onEndBreak = {}, onReturn = {}, onStartShift = {}, onOpenFirstAid = {}, remindable = remindable)
            }
        }
        compose.onNode(hasText("Can't stop yet") and hasClickAction()).performScrollTo().performClick()
        // 7:55: due now, and the snooze would end at 8:05, past the 8:00 stop.
        assertEquals(heatshield.domain.BreakDue.At::class, state().evaluation.due::class)
        compose.onNodeWithText("Nothing is added to your breaks. Stop heavy work outdoors from ${at(t(8))}.").performScrollTo().assertExists()
        compose.onNode(hasText("Save my answer") and hasClickAction()).assertExists()
        compose.onNodeWithText("Remind me in 10 min").assertDoesNotExist()
        // 8:01: this hour is at the stop line.
        compose.runOnIdle { now = t(8, 1); heat = dayOf(t(8, 1), extremeFromEight = true) }
        compose.onNodeWithText("Nothing is added to your breaks. Stop heavy work outdoors now.").performScrollTo().assertExists()
        compose.onNode(hasText("Save my answer") and hasClickAction()).assertExists()
        // Hot all day: the break comes back ten minutes on, so the reminder is real.
        compose.runOnIdle { now = t(7, 55); heat = dayOf(t(7, 55), extremeFromEight = false) }
        compose.onNodeWithText("Nothing is added to your breaks. Break due comes back in 10 minutes, and this answer stays on today's shift.")
            .performScrollTo().assertExists()
        compose.onNode(hasText("Remind me in 10 min") and hasClickAction()).assertExists()
        compose.onNodeWithText("Save my answer").assertDoesNotExist()
        // The same break when no reminder can post (alerts off or blocked, or this would be the second answer, whose fire stays
        // silent): the panel still brings the break back, but nothing promises a reminder.
        compose.runOnIdle { remindable = false }
        compose.onNode(hasText("Save my answer") and hasClickAction()).performScrollTo().assertExists()
        compose.onNodeWithText("Remind me", substring = true).assertDoesNotExist()
        compose.runOnIdle { remindable = true }
        // No current forecast: nothing is timed, so nothing reminds.
        compose.runOnIdle { timed = false }
        compose.onNodeWithText("Nothing is added to your breaks, and this answer stays on today's shift.").performScrollTo().assertExists()
        compose.onNode(hasText("Save my answer") and hasClickAction()).assertExists()
        compose.onNodeWithText("Remind me in 10 min").assertDoesNotExist()
    }

    // Astra M2 #8: End shift cut the 9:30–9:50 smoko at a 9:35 finish, so Fix times opens on valid rows and a note saves.
    @Test
    fun fixTimesOpensValidOnAShiftEndedDuringItsSmoko() {
        val ended = closedRow.copy(endedAt = ms(t(9, 35)), autoEnded = false)
        val smoko = ShiftEvent(id = 31, shiftId = 7, type = ShiftEventType.SMOKO, startedAt = ms(t(9, 30)), endedAt = ms(t(9, 35)))
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.FixTimesScreen(ended, listOf(smoko), 570, 720, ms(t(10)), saving = false, heatRecalculated = true) { saved += it } }
        }
        compose.onNodeWithTag("break_row_1").performScrollTo().assert(!SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.onNodeWithTag("fix_note").performScrollTo().performTextReplacement("Called off at 9:35")
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val edit = saved.single()
            assertEquals(t(9, 35), edit.end); assertEquals(listOf(t(9, 30) to t(9, 35)), edit.breaks.map { it.start to it.end })
        }
    }

    // "Yes" on smoko writes 9:30–9:50, still running when confirmed: the panel says so and offers no break over it.
    @Test
    fun aConfirmedSmokoStillRunningShowsAsABreakAndOffersNoOther() {
        val smoko = ShiftEvent(id = 2, shiftId = 7, type = ShiftEventType.SMOKO, startedAt = ms(t(9, 30)), endedAt = ms(t(9, 50)))
        showPanel(state(t(9, 35), smoko), t(9, 35))
        compose.onNodeWithTag("shift_status").assertTextEquals("On a break until ${at(t(9, 50))} · Very hot 33°")
        compose.onNodeWithTag("shift_break_start").assertDoesNotExist()
        compose.onNodeWithTag("shift_break_sheet").assertExists()
        // Once it ends, the clock runs from 9:50 and Start break is back.
        update(state(t(9, 55), smoko), t(9, 55))
        compose.onNodeWithTag("shift_status").assertTextEquals("5 min since your break · Very hot 33° · next break ${at(t(10, 20))}")
        compose.onNodeWithTag("shift_break_start").assertExists()
    }

    @Test
    fun panelStaysUsableAtDoubleFont() {
        showPanel(state(t(8, 50), brk(t(8), t(8, 12))), t(8, 50), fontScale = 2f)
        compose.onNodeWithTag("shift_status").assertIsDisplayed()
        compose.onNodeWithTag("shift_break_start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shift_end").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun startShiftPrefillsFromTodaysContextAndSendsTheInput() {
        val started = mutableListOf<StartShiftInput>()
        val now = t(7, 2)
        val initial = StartShiftInput("Royal Park", "Irrigation & checks", heavyWork = false, heavyClothing = true, startedAt = now, plannedEnd = t(15))
        compose.setEnglishContent {
            HeatShieldTheme { StartShiftScreen(listOf("Birrarung Marr", "Carlton Gardens", "Royal Park"), initial, ms(now), saving = false) { started += it } }
        }
        compose.onNode(hasText("Heavy work this shift") and hasClickAction()).assertIsOff()
        compose.onNode(hasText("Heavy protective clothing") and hasClickAction()).assertIsOn()
        compose.onNodeWithTag("start_shift_started").assertDoesNotExist()
        // Choosing heavier work turns the heavy-work switch on; the gardener can still turn it off.
        compose.onNodeWithText("Today's work").performClick()
        compose.onNode(hasText("Push mowing · heavy work") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Heavy work this shift") and hasClickAction()).assertIsOn()
        compose.onNode(hasText("Started earlier?") and hasClickAction()).performClick()
        compose.onNodeWithTag("start_shift_started").assertTextContains(at(now), substring = true)
        compose.onNodeWithTag("start_shift_finish").assertTextContains(at(t(15)), substring = true)
        compose.onNodeWithTag("start_shift_confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            val input = started.single()
            assertEquals("Royal Park", input.site); assertEquals("Push mowing", input.task)
            assertTrue(input.heavyWork); assertTrue(input.heavyClothing)
            assertEquals(now, input.startedAt); assertEquals(t(15), input.plannedEnd)
        }
    }

    @Test
    fun startShiftBlocksAFinishBeforeTheStart() {
        val now = t(14, 0)
        val initial = StartShiftInput("Birrarung Marr", "Push mowing", true, false, now, t(15))
        compose.setEnglishContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                HeatShieldTheme { StartShiftScreen(listOf("Birrarung Marr"), initial, ms(now), saving = false) { } }
            }
        }
        compose.onNodeWithTag("start_shift_finish").performScrollTo().performClick()
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields.assertCountEquals(2)
        // 14:00 is the start, so a 14:00 finish wraps to tomorrow and runs 24 hours: too long.
        fields[0].performTextReplacement(if (use24Hour) "14" else "2")
        if (!use24Hour) compose.onNode((hasText("PM") or hasText("p.m.")) and hasAnyAncestor(isDialog())).performClick()
        fields[1].performTextReplacement("00")
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        compose.onNodeWithText("Finish must be after the start and within 16 hours.").assertExists()
        compose.onNodeWithTag("start_shift_confirm").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun startShiftOpenedAfterTheUsualFinishIsReadyInOneTap() {
        // The route's own pre-fill on the default 7:00–15:00 day, opened at 16:00: the finish is the usual 8 hours on
        // (midnight), not tomorrow's 15:00 a day away, so no error shows and Confirm starts the shift.
        val now = t(16, 0)
        val midnight = date.plusDays(1).atTime(0, 0)
        val started = mutableListOf<StartShiftInput>()
        compose.setEnglishContent {
            HeatShieldTheme { StartShiftScreen(listOf("Birrarung Marr"), startShiftInitial(WorkContext(), ms(now)), ms(now), saving = false) { started += it } }
        }
        compose.onNodeWithTag("start_shift_finish").performScrollTo().assertTextContains(at(midnight), substring = true)
        compose.onNodeWithText("Finish must be after the start and within 16 hours.").assertDoesNotExist()
        compose.onNodeWithTag("start_shift_confirm").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val input = started.single()
            assertEquals("Birrarung Marr", input.site); assertTrue(input.heavyWork)
            assertEquals(now, input.startedAt); assertEquals(midnight, input.plannedEnd)
        }
    }

    @Test
    fun breakSheetRadiosStartFlagOrRemind() {
        val calls = mutableListOf<String>()
        var sheetState by mutableStateOf<ShiftUiState>(state(t(8, 50), brk(t(8), t(8, 12))))
        var reminder by mutableStateOf<java.time.Instant?>(null)
        // Saving "Can't stop yet" at 8:50: Room's copy of the answer reaches the evaluation, and the sheet asked for 9:00.
        val answered = ShiftEvent(id = 2, shiftId = 7, type = ShiftEventType.CANT_STOP, startedAt = ms(t(8, 50)), endedAt = ms(t(8, 50)))
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(heat(t(8, 50)), sheetState, reminder = reminder, busy = false,
                    onStartBreak = { calls += "break:$it" },
                    onCantStop = { calls += "cant_stop"; sheetState = state(t(8, 50), brk(t(8), t(8, 12)), answered); reminder = i(t(9)) },
                    onEndBreak = { calls += "end" }, onReturn = { calls += "return" }, onStartShift = { calls += "start" }, onOpenFirstAid = { calls += "first_aid" })
            }
        }
        compose.onNodeWithText("Time for a break in the shade?").assertExists()
        compose.onNode(hasText("Yes, there's shade nearby") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Start break") and hasClickAction()).performClick()
        compose.onNode(hasText("No shade here") and hasClickAction()).performClick()
        compose.onNodeWithText("That's a normal answer.", substring = true).assertExists()
        compose.onNode(hasText("Start break anyway") and hasClickAction()).performClick()
        compose.onNodeWithTag("break_first_aid").performClick()
        compose.onNode(hasText("No shade here") and hasClickAction()).assertIsSelected()   // first aid saved nothing and kept the choice
        compose.onNode(hasText("Can't stop yet") and hasClickAction()).performClick()
        compose.onNodeWithText("Nothing is added to your breaks.", substring = true).assertExists()
        compose.onNodeWithText("Nothing is added to your breaks. Break due comes back in 10 minutes, and this answer stays on today's shift.").assertExists()
        compose.onNode(hasText("Remind me in 10 min") and hasClickAction()).performClick()
        compose.onNodeWithText("Break due again at ${at(t(9))}").assertExists()
        compose.onNode(hasText("Return to Today") and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals(listOf("break:shade", "break:no_shade", "first_aid", "cant_stop", "return"), calls)
            reminder = null; sheetState = state(t(9, 5), brk(t(8), t(8, 12)), brk(t(9), null))
        }
        compose.onNodeWithTag("break_clock").assertTextEquals("05:00")
        compose.onNode(hasText("End break") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("end", calls.last()); sheetState = ShiftUiState.Off }
        compose.onNodeWithText("No shift running").assertExists()
        compose.onNode(hasText("Start shift") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("start", calls.last()) }
    }

    @Test
    fun iDontFeelRightStopsTheClockAndOpensFirstAid() {
        val calls = mutableListOf<String>()
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(heat(t(8, 50)), state(t(8, 50)), reminder = null, busy = false,
                    onStartBreak = { calls += "break:$it" }, onCantStop = {}, onEndBreak = {}, onReturn = {}, onStartShift = {}, onOpenFirstAid = { calls += "first_aid" })
            }
        }
        compose.onNodeWithTag("break_unwell").assertTextEquals("I don't feel right").performClick()
        compose.runOnIdle { assertEquals(listOf("break:unwell"), calls) }
    }

    // Smoko answered "Not yet" (skipped, unconfirmed), a no-shade break, lunch confirmed: 15 + 30 break minutes.
    private val closedRow = row.copy(endedAt = ms(t(15)), outdoorMinutes = 435, breakMinutes = 45, status = ShiftStatus.TO_RAISE,
        peakHeat = 36.0, peakLevel = heatshield.domain.HeatLevel.VERY_HOT.name, longestStretchMinutes = 150, autoEnded = true)
    private val closedEvents = listOf(
        ShiftEvent(id = 11, shiftId = 7, type = ShiftEventType.SMOKO, startedAt = ms(t(9, 30)), endedAt = ms(t(9, 30)), skipped = true),
        ShiftEvent(id = 12, shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(t(10)), endedAt = ms(t(10, 15)), reason = BreakReason.NO_SHADE),
        ShiftEvent(id = 13, shiftId = 7, type = ShiftEventType.LUNCH, startedAt = ms(t(12)), endedAt = ms(t(12, 30)))
    )

    @Test
    fun summaryLeadsWithConditionsListsEventsAndOffersFixTimes() {
        val calls = mutableListOf<String>()
        val hours = heat(t(15), airC = 36.0).hours
        var shownEvents by mutableStateOf(closedEvents)
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.ShiftSummaryScreen(closedRow, shownEvents, hours, 35.0, 570, 720, onFixTimes = { calls += "fix" }, onDone = { calls += "done" }) }
        }
        compose.onNodeWithTag("summary_worked").assertTextEquals("7 h 15 min worked · 45 min on breaks")
        compose.onNodeWithTag("summary_peak").assertTextEquals("Peak Very hot 36° at ${at(t(7))}")
        compose.onNodeWithText("Longest stretch without a break: 2 h 30 min").assertExists()
        compose.onNodeWithTag("summary_auto").assertTextEquals("Ended automatically at ${at(t(15))}. Fix the times if that's wrong.")
        compose.onNodeWithTag("summary_scheduled_note").assertTextEquals("Scheduled breaks count once you confirm them. In Fix times, turn on \"I took this break\" for any you took.")
        compose.onNodeWithTag("heat_ribbon").assertExists()
        compose.onNodeWithTag("event_11").assertTextEquals("Smoko ${at(t(9, 30))} · not confirmed")
        compose.onNodeWithTag("event_12").assertTextEquals("Break ${at(t(10))} – ${at(t(10, 15))} · no shade here")
        compose.onNodeWithTag("event_13").assertTextEquals("Lunch ${at(t(12))} – ${at(t(12, 30))} · scheduled")
        compose.onNodeWithText("Something to raise with your team leader.").assertExists()
        compose.onNodeWithTag("summary_fix").performScrollTo().performClick()
        compose.onNodeWithTag("summary_done").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("fix", "done"), calls) }
        // A smoko that was never answered is listed the same way, from the preferences, with no event behind it.
        compose.runOnIdle { shownEvents = closedEvents.drop(1) }
        compose.onNodeWithTag("event_11").assertDoesNotExist()
        compose.onNodeWithTag("event_pending_smoko").assertTextEquals("Smoko ${at(t(9, 30))} · not confirmed")
        compose.onNodeWithTag("summary_scheduled_note").assertExists()
    }

    @Test
    fun fixTimesTogglesScheduledBreaksAddsRowsValidatesAndSaves() {
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                HeatShieldTheme { heatshield.ui.FixTimesScreen(closedRow, closedEvents, 570, 720, ms(t(16)), saving = false, heatRecalculated = true) { saved += it } }
            }
        }
        compose.onNodeWithTag("fix_start").performScrollTo().assertTextContains(at(t(7)), substring = true)
        compose.onNodeWithTag("fix_end").performScrollTo().assertTextContains(at(t(15)), substring = true)
        compose.onNodeWithText("Heat not recalculated", substring = true).assertDoesNotExist()
        // The time the app ended the shift, as stored (C10), and the tapped break between smoko and lunch is Break 1.
        compose.onNodeWithTag("fix_auto").performScrollTo().assertTextEquals("Ended automatically at ${at(t(15))}. Fix the times if that's wrong.")
        compose.onNodeWithText("Break 1").performScrollTo().assertExists()
        compose.onNodeWithText("Break 2").assertDoesNotExist()
        // The unconfirmed smoko is listed unticked; ticking it confirms it at its scheduled times. Lunch, confirmed, is unticked here.
        fun switches() = compose.onAllNodes(hasText("I took this break") and hasClickAction())
        switches()[0].performScrollTo().assertIsOff().performClick()
        switches()[0].assertIsOn()
        switches()[1].performScrollTo().assertIsOn().performClick()
        switches()[1].assertIsOff()
        // Add a break an hour after the last taken one (10:15 → 11:15–11:30), then push its end before its start.
        compose.onNodeWithTag("add_break").performScrollTo().performClick()
        compose.onNodeWithTag("break_row_4_end").performScrollTo().performClick()
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields[0].performTextReplacement("11")
        fields[1].performTextReplacement("00")
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        compose.onNodeWithTag("break_row_4").performScrollTo().assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.onNodeWithText("Finish must be after the start.").assertExists()
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsNotEnabled()
        // Remove the bad row; save the rest.
        compose.onAllNodes(hasText("Remove") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.onNodeWithTag("fix_note").performScrollTo().performTextReplacement("Mower down at 10")
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val edit = saved.single()
            assertEquals(t(7), edit.start); assertEquals(t(15), edit.end); assertEquals("Mower down at 10", edit.note)
            assertEquals(listOf(ScheduledKind.SMOKO to true, null to true, ScheduledKind.LUNCH to false), edit.breaks.map { it.kind to it.taken })
            assertEquals(listOf(11, 12, 13), edit.breaks.map { it.eventId })   // every row keeps its event: the repository updates in place
            assertEquals(t(9, 30), edit.breaks[0].start); assertEquals(t(9, 50), edit.breaks[0].end)
            assertEquals(t(10), edit.breaks[1].start); assertEquals(t(10, 15), edit.breaks[1].end)
        }
    }

    @Test
    fun aNightShiftFinishEarlierThanItsStartIsTomorrow() {
        val night = row.copy(startedAt = ms(t(20)), endedAt = ms(date.plusDays(1).atTime(4, 0)), plannedEndAt = null)
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.FixTimesScreen(night, emptyList(), 570, 720, ms(date.plusDays(1).atTime(9, 0)), saving = false, heatRecalculated = false) { saved += it } }
        }
        compose.onNodeWithText("Heat not recalculated: no forecast is saved for this shift's park and day.").assertExists()
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(date.plusDays(1).atTime(4, 0), saved.single().end) }
    }

    // A night shift's break after midnight is on the next day, and Add a break goes an hour after it, not after the
    // largest minute of the day.
    @Test
    fun aNightShiftBreakAfterMidnightSavesOnTheNextDay() {
        val tomorrow = date.plusDays(1)
        val night = row.copy(startedAt = ms(t(20)), endedAt = ms(tomorrow.atTime(4, 0)), plannedEndAt = null)
        val events = listOf(
            ShiftEvent(id = 21, shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(t(22)), endedAt = ms(t(22, 10)), reason = BreakReason.SHADE),
            ShiftEvent(id = 22, shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(tomorrow.atTime(1, 0)), endedAt = ms(tomorrow.atTime(1, 15)), reason = BreakReason.SHADE)
        )
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.FixTimesScreen(night, events, 570, 720, ms(tomorrow.atTime(9, 0)), saving = false, heatRecalculated = false) { saved += it } }
        }
        compose.onNodeWithText("Break 1").assertExists(); compose.onNodeWithText("Break 2").assertExists()
        compose.onNodeWithTag("add_break").performScrollTo().performClick()
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val breaks = saved.single().breaks
            assertEquals(listOf(t(22) to t(22, 10), tomorrow.atTime(1, 0) to tomorrow.atTime(1, 15), tomorrow.atTime(2, 15) to tomorrow.atTime(2, 30)),
                breaks.map { it.start to it.end })
            assertEquals(listOf(21, 22, null), breaks.map { it.eventId })
        }
    }

    // Under the union table the breaks go by the heat number alone, so the heavy-work switch says it does not move them.
    @Test
    fun heavyWorkSaysWhatItChangesUnderEachBreakTable() {
        var rule by mutableStateOf(BreakRule.UNION_VTHC)
        val now = t(7, 2)
        compose.setEnglishContent {
            HeatShieldTheme {
                StartShiftScreen(listOf("Birrarung Marr"), StartShiftInput("Birrarung Marr", "Push mowing", true, false, now, t(15)), ms(now), saving = false,
                    breakRule = rule) { }
            }
        }
        compose.onNode(hasText("Heavy work this shift") and hasClickAction())
            .assertTextContains("The union table times breaks on the heat number alone, so your breaks don't move.", substring = true)
        compose.onNodeWithTag("start_shift_finish_note").performScrollTo()
            .assertTextEquals("If the shift is still running 2 hours after its planned finish, HeatShield ends it and saves the " +
                "planned finish as its end. A break or answer logged after the planned finish counts instead: it is saved as the end, " +
                "and the 2 hours count from it.")
        compose.runOnIdle { rule = BreakRule.HEATSHIELD }
        compose.onNode(hasText("Heavy work this shift") and hasClickAction()).assertTextContains("brings your breaks forward", substring = true)
    }

    @Test
    fun fixTimesForATypedShiftLeavesTimesBlankAndSavesANoteAlone() {
        val typed = WorkSession(id = 3, ownerId = "local", title = "Mowing", site = "Royal Park", date = "14 Jan 2026", outdoorMinutes = 95, breakMinutes = 15, status = ShiftStatus.NONE)
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.FixTimesScreen(typed, emptyList(), 570, 720, ms(t(16)), saving = false, heatRecalculated = false) { saved += it } }
        }
        compose.onNodeWithText("This shift was typed in. Its minutes stay until you enter both times.").assertExists()
        compose.onNodeWithTag("fix_start").assertTextContains("Choose", substring = true)
        compose.onNodeWithTag("fix_end").assertTextContains("Choose", substring = true)
        compose.onNodeWithTag("add_break").assertDoesNotExist()
        compose.onNodeWithTag("fix_note").performTextReplacement("Hot afternoon")
        compose.onNodeWithTag("fix_save").assertIsEnabled().performClick()
        compose.runOnIdle { val edit = saved.single(); assertNull(edit.start); assertNull(edit.end); assertEquals("Hot afternoon", edit.note) }
        // One time alone is not a shift: Save waits for both, and no finish is invented.
        compose.onNodeWithTag("fix_start").performClick()
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        compose.onNodeWithTag("fix_end").assertTextContains("Choose", substring = true)
        compose.onNodeWithTag("fix_save").assertIsNotEnabled()
    }

    @Test
    fun addPastShiftNeedsDateAndTimesRefusesTheFutureAndAllowsNoFutureDate() {
        val drafts = mutableListOf<heatshield.data.PastShiftDraft>()
        val now = t(12)
        compose.setEnglishContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                HeatShieldTheme { heatshield.ui.AddPastShiftScreen(listOf("Birrarung Marr", "Royal Park"), "Royal Park", ms(now), saving = false) { drafts += it } }
            }
        }
        compose.onNodeWithTag("past_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("past_date").performScrollTo().performClick()
        compose.onNode(hasText("Use date") and hasClickAction()).performClick()      // today, the default selection
        compose.onNodeWithTag("past_date").assertTextContains(Formats.fullDate(date), substring = true)
        fun setTime(tag: String, hour24: Int, minute: String) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
            fields[0].performTextReplacement(if (use24Hour) hour24.toString() else ((hour24 + 11) % 12 + 1).toString())
            // The dialog reopens on the field's current time, so a 12-hour pick taps AM or PM either way.
            val half = if (hour24 >= 12) hasText("PM") or hasText("p.m.") else hasText("AM") or hasText("a.m.")
            if (!use24Hour) compose.onNode(half and hasAnyAncestor(isDialog())).performClick()
            fields[1].performTextReplacement(minute)
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        }
        setTime("past_start", 6, "30")
        setTime("past_end", 14, "30")
        compose.onNodeWithText("This can't end in the future.").assertExists()
        compose.onNodeWithTag("past_save").performScrollTo().assertIsNotEnabled()
        setTime("past_end", 11, "30")
        compose.onNodeWithTag("add_break").performScrollTo().performClick()          // 7:30–7:45 by default
        compose.onNodeWithTag("past_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val draft = drafts.single()
            assertEquals("Royal Park", draft.site); assertEquals(t(6, 30), draft.start); assertEquals(t(11, 30), draft.end)
            assertEquals(listOf(t(7, 30) to t(7, 45)), draft.breaks.map { it.start to it.end })
        }
        // The DatePicker's rule, checked directly: today is the last selectable day.
        val rule = heatshield.ui.pastDatesOnly(date)
        val todayUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(rule.isSelectableDate(todayUtc)); assertFalse(rule.isSelectableDate(todayUtc + 86_400_000L))
        assertTrue(rule.isSelectableYear(2026)); assertFalse(rule.isSelectableYear(2027))
    }

    // A Warm day (31° air, light work): 60 min work, so a 7:00 start has its break due at 8:00.
    private val lightRow = row.copy(heavyWork = false)
    private fun warmDay(now: LocalDateTime): TodayHeat = ContextReasoner.todayHeat(
        ForecastSnapshot("Birrarung Marr", ms(now) - 5 * 60_000L, (0..23).map { ForecastHour(date.atTime(it, 0).toString(), 31.0, 10.0, 5.0, 8.0, 5.0) }),
        profile.copy(factors = PersonalFactors()), Clock.fixed(java.time.Instant.ofEpochMilli(ms(now)), heatshield.data.MelbourneZone))

    // "Can't stop yet" only moves a due time earlier than ten minutes on, so the sheet offers the ten-minute reminder only
    // when saving would bring the break back then. At 7:10 the 8:00 break stays at 8:00; at 9:05, after a break that
    // ended 8:12, the 9:12 break would move to 9:15, inside the 15 minutes before the 9:30 smoko, which then covers it;
    // at 7:55 the 8:00 break does come back at 8:05.
    @Test
    fun cantStopOffersTheTenMinuteReminderOnlyWhenSavingBringsTheBreakBackThen() {
        var now by mutableStateOf(t(7, 10))
        var events by mutableStateOf(emptyList<ShiftEvent>())
        fun state() = ShiftUiState.On(lightRow, ShiftRules.evaluate(OpenShift(lightRow, events), warmDay(now).hours, BreakRule.HEATSHIELD, 570, 720, i(now)))
        assertEquals(i(t(8)), state().evaluation.due.dueAt)
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(warmDay(now), state(), reminder = null, busy = false,
                    onStartBreak = {}, onCantStop = {}, onEndBreak = {}, onReturn = {}, onStartShift = {}, onOpenFirstAid = {}, remindable = true)
            }
        }
        compose.onNode(hasText("Can't stop yet") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Nothing is added to your breaks. Your next break is due at ${at(t(8))}.").performScrollTo().assertExists()
        compose.onNode(hasText("Save my answer") and hasClickAction()).assertExists()
        compose.onNodeWithText("Remind me in 10 min").assertDoesNotExist()
        compose.onNodeWithText("comes back in 10 minutes", substring = true).assertDoesNotExist()
        // 9:05, the clock reset by a break ending 8:12: due 9:12, and a snooze to 9:15 lands in the smoko's 15 minutes.
        compose.runOnIdle { now = t(9, 5); events = listOf(brk(t(8), t(8, 12))) }
        assertEquals(i(t(9, 12)), state().evaluation.due.dueAt)
        compose.onNodeWithText("Nothing is added to your breaks. Smoko at ${at(t(9, 30))} covers your next break.").performScrollTo().assertExists()
        compose.onNode(hasText("Save my answer") and hasClickAction()).assertExists()
        compose.onNodeWithText("Remind me in 10 min").assertDoesNotExist()
        // 8:58, due at 9:12: a snooze to 9:08 is earlier, so the break stays 9:12, outside the smoko's 15 minutes.
        compose.runOnIdle { now = t(8, 58) }
        compose.onNodeWithText("Nothing is added to your breaks. Your next break is due at ${at(t(9, 12))}.").performScrollTo().assertExists()
        // 7:55 with no break yet, the 8:00 break is within ten minutes: saving brings it back at 8:05, so the reminder is real.
        compose.runOnIdle { now = t(7, 55); events = emptyList() }
        compose.onNodeWithText("Nothing is added to your breaks. Break due comes back in 10 minutes, and this answer stays on today's shift.")
            .performScrollTo().assertExists()
        compose.onNode(hasText("Remind me in 10 min") and hasClickAction()).assertExists()
    }

    // The sheet still shows the 8:00 answer's confirmation when the break-due notification's Start break starts a break at
    // 8:11. The running break, its clock and End break replace it, and nothing still promises 8:10; once that break has
    // ended the sheet asks again.
    @Test
    fun aBreakStartedElsewhereReplacesTheEarlierAnswersConfirmation() {
        val answered = ShiftEvent(id = 2, shiftId = 7, type = ShiftEventType.CANT_STOP, startedAt = ms(t(8)), endedAt = ms(t(8)))
        var sheetState by mutableStateOf(state(t(8), answered))
        val calls = mutableListOf<String>()
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(heat(t(8)), sheetState, reminder = i(t(8, 10)), busy = false,
                    onStartBreak = { calls += "break:$it" }, onCantStop = {}, onEndBreak = { calls += "end" }, onReturn = {},
                    onStartShift = {}, onOpenFirstAid = {})
            }
        }
        compose.onNodeWithText("Break due again at ${at(t(8, 10))}").assertExists()
        compose.runOnIdle { sheetState = state(t(8, 11), answered, brk(t(8, 11), null)) }
        compose.onNodeWithTag("break_clock").assertTextEquals("00:00")
        compose.onNode(hasText("End break") and hasClickAction()).performClick()
        compose.onNodeWithText("Break due again", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("end"), calls) }
        // Ended at 8:24 from the notification or Today: the answer is behind the break, so the sheet asks again.
        compose.runOnIdle { sheetState = state(t(8, 25), answered, brk(t(8, 11), t(8, 24))) }
        compose.onNodeWithText("Time for a break in the shade?").assertExists()
        compose.onNodeWithText("Break due again", substring = true).assertDoesNotExist()
    }

    // At 12:05 the confirmed 12:00–12:30 lunch is running. Today hides Start break, and so does the sheet: it shows the
    // lunch and when it ends, with First aid and the way back, and asks again once the lunch is over.
    @Test
    fun theBreakSheetDuringAConfirmedLunchShowsTheLunchAndOffersNoOtherBreak() {
        val lunch = ShiftEvent(id = 3, shiftId = 7, type = ShiftEventType.LUNCH, startedAt = ms(t(12)), endedAt = ms(t(12, 30)))
        var sheetState by mutableStateOf(state(t(12, 5), lunch))
        val calls = mutableListOf<String>()
        compose.setEnglishContent {
            HeatShieldTheme {
                heatshield.ui.BreakSheetContent(heat(t(12, 5)), sheetState, reminder = null, busy = false,
                    onStartBreak = { calls += "break:$it" }, onCantStop = { calls += "cant_stop" }, onEndBreak = {},
                    onReturn = { calls += "return" }, onStartShift = {}, onOpenFirstAid = { calls += "first_aid" })
            }
        }
        compose.onNodeWithText("On a break until ${at(t(12, 30))} · Very hot 33°").assertExists()
        compose.onNodeWithText("Time for a break in the shade?").assertDoesNotExist()
        compose.onAllNodes(hasText("Start break", substring = true) and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(hasText("Can't stop yet") and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithTag("break_unwell").assertDoesNotExist()
        compose.onNodeWithTag("break_first_aid").performScrollTo().performClick()
        compose.onNode(hasText("Return to Today") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("first_aid", "return"), calls) }
        compose.runOnIdle { sheetState = state(t(12, 31), lunch) }
        compose.onNodeWithText("Time for a break in the shade?").assertExists()
        compose.onNode(hasText("Start break") and hasClickAction()).assertExists()
    }

    // The stored peak (Very hot 36°) came from the forecast End shift read. A later refresh revises the shift's hottest
    // hour to 34° at 12:00; that time belongs to another forecast, so the summary keeps the stored peak without a time.
    @Test
    fun aSavedPeakIsNotPairedWithATimeFromARevisedForecast() {
        val revised = heat(t(15), airC = 30.0).hours.map { hour ->
            if (hour.time.hour == 12) hour.copy(airC = 34.0, heat = 34.0) else hour
        }
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.ShiftSummaryScreen(closedRow, closedEvents, revised, 35.0, 570, 720, onFixTimes = {}, onDone = {}) }
        }
        compose.onNodeWithTag("summary_peak").assertTextEquals("Peak Very hot 36°")
    }

    // Lunch at 23:50 on a 22:00–06:00 shift, answered "Not yet": ticked in Fix times with its times untouched, it saves
    // 23:50 to 00:20 the next morning, its full 30 minutes, as an unanswered lunch does.
    @Test
    fun aSkippedLunchTickedInFixTimesKeepsItsThirtyMinutesPastMidnight() {
        val tomorrow = date.plusDays(1)
        val night = closedRow.copy(startedAt = ms(t(22)), endedAt = ms(tomorrow.atTime(6, 0)), plannedEndAt = ms(tomorrow.atTime(6, 0)), autoEnded = false)
        val skipped = ShiftEvent(id = 51, shiftId = 7, type = ShiftEventType.LUNCH, startedAt = ms(t(23, 50)), endedAt = ms(t(23, 50)), skipped = true)
        val saved = mutableListOf<heatshield.data.ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme { heatshield.ui.FixTimesScreen(night, listOf(skipped), 570, 1430, ms(tomorrow.atTime(9, 0)), saving = false, heatRecalculated = false) { saved += it } }
        }
        compose.onNode(hasText("I took this break") and hasClickAction()).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("break_row_1_end").performScrollTo().assertTextContains(at(tomorrow.atTime(0, 20)), substring = true)
        compose.onNodeWithTag("fix_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val lunch = saved.single().breaks.single()
            assertEquals(t(23, 50) to tomorrow.atTime(0, 20), lunch.start to lunch.end)
        }
    }
}
