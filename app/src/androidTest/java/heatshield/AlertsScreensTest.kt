package heatshield

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import heatshield.background.AlertStatus
import heatshield.ui.AlertsBanner
import heatshield.ui.AlertsGroup
import heatshield.ui.AlertsIntroScreen
import heatshield.ui.HeatShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The honest rows (spec §10 M3 "denied permissions degrade honestly") and the one-time intro. */
class AlertsScreensTest {
    @get:Rule val compose = createComposeRule()
    private fun status(on: Boolean = true, allowed: Boolean = true, exact: Boolean = true, silent: Boolean = false, asked: Boolean = false) =
        AlertStatus(alertsOn = on, notificationsAllowed = allowed, exactAllowed = exact, breakChannelSilent = silent, asked = asked)

    @Test
    fun introPreviewsTheAlertAndOffersAllowOrNotNow() {
        val calls = mutableListOf<String>()
        compose.setEnglishContent { HeatShieldTheme { AlertsIntroScreen(onAllow = { calls += "allow:$it" }, onNotNow = { calls += "not_now" }, busy = false) } }
        compose.onNodeWithTag("alerts_intro_card").assertExists()
        compose.onNodeWithText("Break due · Hot 33°").assertExists()
        compose.onNodeWithText("47 min since your break. Drink a cup of water.").assertExists()
        // No lead time for the inexact nudge, and nothing claimed for cool days beyond the policy (no buzz at Normal).
        compose.onNodeWithText("Also a nudge before your usual start on hot days, and a check if you forget to end your shift. No buzz at Normal.").assertExists()
        compose.onNodeWithTag("alerts_allow").assertTextEquals("Turn on break alerts").assertIsEnabled()
        compose.onNodeWithTag("alerts_not_now").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("not_now"), calls) }
    }

    @Test
    fun alertsGroupShowsRowsOnlyWhenOnAndNamesEachLimit() {
        var current by mutableStateOf(status(on = false))
        compose.setEnglishContent { HeatShieldTheme { AlertsGroup(current, onAlertsChange = { current = current.copy(alertsOn = it) }, onRefresh = {}) } }
        compose.onNodeWithTag("alerts_switch").assertIsOff()
        compose.onNodeWithTag("alerts_row_notifications").assertDoesNotExist()
        compose.onNodeWithTag("alerts_switch").performClick()
        compose.onNodeWithTag("alerts_switch").assertIsOn()
        compose.onNodeWithTag("alerts_row_notifications").assertTextEquals("Notifications: allowed")
        compose.onNodeWithTag("alerts_row_exact").assertTextEquals("Precise timing: on")
        compose.onNodeWithTag("alerts_row_channel").assertDoesNotExist()
        compose.runOnIdle { current = status(allowed = false, exact = false, silent = true) }
        compose.onNodeWithTag("alerts_row_notifications").assertTextEquals("Notifications: blocked for HeatShield. Your timer still runs on Today.")
        compose.onNode(hasText("Allow notifications") and hasClickAction()).assertExists()
        compose.onNodeWithTag("alerts_row_exact").assertTextEquals("Precise timing: off. Alerts can arrive late.")
        compose.onNode(hasText("Allow precise timing") and hasClickAction()).assertExists()
        compose.onNodeWithTag("alerts_row_channel").assertTextEquals("Break due alerts are set to silent in Android settings.")
        compose.onNode(hasText("Open alert settings") and hasClickAction()).assertExists()
    }

    @Test
    fun bannerShowsOnlyWhenAlertsAreOnAndBlocked() {
        var current by mutableStateOf(status(allowed = false))
        compose.setEnglishContent { HeatShieldTheme { AlertsBanner(current) } }
        // The Surface does not merge its children's semantics, so the text is asserted on the node that carries it, inside the tagged container.
        compose.onNodeWithTag("alerts_banner").assertExists()
        compose.onNode(hasTestTag("alerts_banner") and hasAnyDescendant(hasText("Alerts are blocked for HeatShield. Your timer still runs here."))).assertExists()
        compose.onNode(hasText("Open settings") and hasClickAction()).assertExists()
        compose.runOnIdle { current = status(allowed = true) }
        compose.onNodeWithTag("alerts_banner").assertDoesNotExist()
        compose.runOnIdle { current = status(on = false, allowed = false) }
        compose.onNodeWithTag("alerts_banner").assertDoesNotExist()
    }
}
