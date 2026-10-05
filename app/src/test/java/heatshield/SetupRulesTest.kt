package heatshield

import heatshield.data.MelbourneZone
import heatshield.data.UserPreferences
import heatshield.domain.ContextReasoner
import heatshield.ui.SetupRules
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-run routing and Profile input rules, independent of Compose. */
class SetupRulesTest {
    @Test
    fun startRouteWelcomesSignedOutThenSetsUpThenOpensToday() {
        assertEquals("welcome", SetupRules.startRoute(null, needsSetup = true))
        assertEquals("welcome", SetupRules.startRoute(null, needsSetup = false))
        assertEquals("setup", SetupRules.startRoute("local", needsSetup = true))
        assertEquals("today", SetupRules.startRoute("local", needsSetup = false))
        assertEquals("today", SetupRules.startRoute("uid-abc", needsSetup = false))
    }

    @Test
    fun setupIsNeededUntilPreferencesSayDone() {
        assertTrue(SetupRules.needsSetup(null))
        assertTrue(SetupRules.needsSetup(UserPreferences(ownerId = "local")))
        assertFalse(SetupRules.needsSetup(UserPreferences(ownerId = "local", setupDone = true)))
    }

    @Test
    fun finishMustBeAtLeastAnHourAfterStartWithinOneDay() {
        assertTrue(SetupRules.hoursValid(420, 900))
        assertTrue(SetupRules.hoursValid(420, 480))
        assertTrue(SetupRules.hoursValid(0, 1439))
        assertFalse(SetupRules.hoursValid(420, 479))
        assertFalse(SetupRules.hoursValid(420, 420))
        assertFalse(SetupRules.hoursValid(900, 420))
        assertFalse(SetupRules.hoursValid(1400, 1440))
        assertFalse(SetupRules.hoursValid(-1, 120))
    }

    // A crew rule is whole degrees (HeatRules.isValidTrigger), so it shows exactly as typed and compares cleanly
    // with the whole air degrees on screen. 36.5 would display as "37°", so it is rejected however it is typed.
    @Test
    fun customTriggerAcceptsOnlyWholeNumbersFrom25To50() {
        listOf("", " ", "abc", "10", "70", "24.9", "50.1", "36.5", " 36.5 ", "36,5", "NaN", "Infinity").forEach { bad ->
            assertNull("'$bad' must be rejected", SetupRules.parseTrigger(bad))
        }
        assertEquals(25.0, SetupRules.parseTrigger("25")!!, 0.0)
        assertEquals(36.0, SetupRules.parseTrigger("36")!!, 0.0)
        assertEquals(36.0, SetupRules.parseTrigger(" 36 ")!!, 0.0)
        assertEquals(50.0, SetupRules.parseTrigger("50")!!, 0.0)
    }

    @Test
    fun triggerTextDropsATrailingZero() {
        assertEquals("35", SetupRules.formatTrigger(35.0))
        assertEquals("38", SetupRules.formatTrigger(38.0))
    }

    @Test
    fun backFromLeaveRunsSevenDaysAndEndsAtLocalMidnightAcrossDst() {
        // 2 Oct 2026 + 7 days crosses the 4 Oct 2026 daylight-saving change.
        val today = LocalDate.of(2026, 10, 2)
        val until = Instant.ofEpochMilli(SetupRules.acclimatisingUntil(today)).atZone(MelbourneZone)
        assertEquals(LocalDate.of(2026, 10, 9), until.toLocalDate())
        assertEquals(0, until.hour)
    }

    // "For the next 7 days" with an exclusive end: switched on today (day 0), days 0–6 count and day +7 does not,
    // because the reasoner counts it while acclimatisingUntil > the start of today (spec §5.5).
    @Test
    fun backFromLeaveMatchesTheReasonerWindow() {
        val today = LocalDate.of(2026, 10, 2)
        val preferences = UserPreferences(ownerId = "local", acclimatisingUntil = SetupRules.acclimatisingUntil(today))
        assertTrue(ContextReasoner.profileOf(preferences, today).factors.acclimatising)
        assertTrue(ContextReasoner.profileOf(preferences, today.plusDays(6)).factors.acclimatising)
        assertFalse(ContextReasoner.profileOf(preferences, today.plusDays(7)).factors.acclimatising)
    }
}
