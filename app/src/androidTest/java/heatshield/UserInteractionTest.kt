package heatshield

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.MelbourneZone
import heatshield.data.WorkContext
import heatshield.data.WorkSession
import heatshield.data.WorkSite
import heatshield.ui.EditRecordScreen
import heatshield.ui.HeatShieldTheme
import heatshield.ui.LoginScreen
import heatshield.ui.RecordsScreen
import heatshield.ui.SearchScreen
import heatshield.ui.SessionDetailScreen
import heatshield.ui.ShiftPlanScreen
import heatshield.ui.SignUpScreen
import heatshield.ui.TrendsScreen
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User-facing form, planning and record workflows; persistence is covered separately. */
@RunWith(AndroidJUnit4::class)
class UserInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loginValidatesCredentialsAndUserControlsPasswordVisibility() {
        var credentials: Pair<String, String>? = null
        compose.setContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { email, password -> credentials = email to password },
                    onSignUp = {}, onLocal = {}, onReset = {}, configured = true,
                    busy = false, message = null
                )
            }
        }
        click("Log in")
        compose.onNodeWithTag("auth_email").assert(hasError())
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.runOnIdle { assertEquals(null, credentials) }
        replace("auth_email", " worker@example.com ")
        replace("auth_password", "A careful long passphrase")
        compose.onNodeWithTag("auth_password").assert(isPassword())
        assertPasswordRendering("A careful long passphrase", visible = false)
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        assertPasswordRendering("A careful long passphrase", visible = true)
        compose.onNodeWithTag("auth_password").assertTextContains("A careful long passphrase")
        compose.onNodeWithContentDescription("Hide password").performScrollTo().performClick()
        compose.onNodeWithTag("auth_password").assert(isPassword())
        assertPasswordRendering("A careful long passphrase", visible = false)
        click("Log in")
        compose.runOnIdle { assertEquals("worker@example.com" to "A careful long passphrase", credentials) }
    }

    @Test
    fun signUpRejectsShortPasswordsAndInvalidEmailBeforeRequestingAccount() {
        var credentials: Pair<String, String>? = null
        var configured by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var loginRequests = 0
        compose.setContent {
            HeatShieldTheme {
                SignUpScreen(
                    onCreated = { email, password -> credentials = email to password },
                    onLogin = { loginRequests++ }, configured = configured, busy = busy
                )
            }
        }
        replace("auth_password", "abcdefghijk")
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        replace("auth_password", "abcdefghijkl")
        replace("auth_email", "invalid")
        click("Create account")
        compose.onNodeWithTag("auth_email").assert(hasError())
        compose.runOnIdle { assertEquals(null, credentials) }
        replace("auth_email", " new-worker@example.com ")
        compose.runOnIdle { configured = false }
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle { assertEquals(null, credentials); configured = true; busy = true }
        compose.onNode(hasText("Creating account…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Log in") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle { assertEquals(null, credentials); assertEquals(0, loginRequests); busy = false }
        click("Create account")
        compose.runOnIdle { assertEquals("new-worker@example.com" to "abcdefghijkl", credentials) }
    }

    @Test
    fun cloudAvailabilityAndBusyStateGuardLoginWhileLocalModeRemainsAvailable() {
        var configured by mutableStateOf(false)
        var busy by mutableStateOf(false)
        val credentials = mutableListOf<Pair<String, String>>()
        var localRequests = 0
        var signUpRequests = 0
        compose.setContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { email, password -> credentials += email to password },
                    onSignUp = { signUpRequests++ }, onLocal = { localRequests++ }, onReset = {},
                    configured = configured, busy = busy, message = null
                )
            }
        }
        replace("auth_email", "worker@example.com")
        replace("auth_password", "A careful long passphrase")
        compose.onNode(hasText("Log in") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        click("Continue on this device")
        compose.runOnIdle {
            assertTrue(credentials.isEmpty())
            assertEquals(1, localRequests)
            configured = true
            busy = true
        }
        compose.onNode(hasText("Please wait…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Continue on this device") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle {
            assertTrue(credentials.isEmpty())
            assertEquals(1, localRequests)
            assertEquals(0, signUpRequests)
            busy = false
        }
        click("Log in")
        compose.runOnIdle {
            assertEquals(listOf("worker@example.com" to "A careful long passphrase"), credentials)
        }
    }

    @Test
    fun passwordResetRequiresValidEmailAndAvailableCloudAndCancelDoesNotSend() {
        var configured by mutableStateOf(true)
        var busy by mutableStateOf(false)
        val resetRequests = mutableListOf<String>()
        compose.setContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { _, _ -> }, onSignUp = {}, onLocal = {}, onReset = { resetRequests += it },
                    configured = configured, busy = busy, message = null
                )
            }
        }
        click("Forgot password?")
        val send = compose.onNodeWithText("Send reset email")
        val dialogEmail = compose.onNode(hasTestTag("auth_email") and hasAnyAncestor(isDialog()))
        send.assertIsNotEnabled()
        replace(dialogEmail, "invalid")
        dialogEmail.assert(hasError())
        send.assertIsNotEnabled()
        replace(dialogEmail, " worker@example.com ")
        send.assertIsEnabled()
        compose.runOnIdle { busy = true }
        send.assertIsNotEnabled()
        compose.runOnIdle { busy = false; configured = false }
        send.assertIsNotEnabled()
        compose.runOnIdle { configured = true }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Reset password").assertDoesNotExist()
        compose.runOnIdle { assertTrue(resetRequests.isEmpty()) }
        click("Forgot password?")
        compose.onNodeWithText("Send reset email").assertIsEnabled().performClick()
        compose.onNodeWithText("Reset password").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("worker@example.com"), resetRequests) }
    }

    @Test
    fun missingForecastCannotBeSavedAndReturningToInputsRetainsContext() {
        var comparing by mutableStateOf(true)
        var accepted = 0
        val context = WorkContext(work = "Mowing", effort = "Light effort", clothing = "Heavy protective clothing")
        compose.setContent {
            HeatShieldTheme {
                ShiftPlanScreen(
                    acceptedWindow = "", initialContext = context, comparing = comparing,
                    onStepChange = { comparing = it }, onReturnToToday = {},
                    forecast = null, refreshing = false, sites = WorkSite.defaults.map { it.name },
                    onRefresh = {}, onAccept = { _, _ -> accepted++ }
                )
            }
        }
        compose.onNodeWithText("No comparison available").assertExists()
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        click("Change work context")
        compose.onNode(hasText("Work type") and hasText("Mowing")).assertExists()
        compose.onNode(hasText("Effort") and hasText("Light effort")).assertExists()
        compose.onNode(hasText("Protective clothing") and hasText("Heavy protective clothing")).assertExists()
        choose("Work type", "Pruning")
        click("Compare work windows")
        compose.onNodeWithText("No comparison available").assertExists()
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        click("Change work context")
        compose.onNode(hasText("Work type") and hasText("Pruning")).assertExists()
        compose.onNode(hasText("Effort") and hasText("Light effort")).assertExists()
        compose.runOnIdle { assertEquals(0, accepted) }
    }

    @Test
    fun choosingAWindowSavesEditedContextAndUnavailableForecastWithdrawsTheAction() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        val initial = WorkContext(dayMillis = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        var snapshot by mutableStateOf<ForecastSnapshot?>(ForecastSnapshot(
            site = "Carlton Gardens", fetchedAt = System.currentTimeMillis(),
            hours = (7..10).map { hour -> ForecastHour(tomorrow.atTime(hour, 0).toString(), 20.0 + hour, 50.0, 2.0) }
        ))
        var comparing by mutableStateOf(false)
        val refreshRequests = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, WorkContext>>()
        var returns = 0
        compose.setContent {
            HeatShieldTheme {
                ShiftPlanScreen(
                    acceptedWindow = "", initialContext = initial, forecast = snapshot,
                    comparing = comparing, onStepChange = { comparing = it },
                    onRefresh = { refreshRequests += it }, onReturnToToday = { returns++ },
                    onAccept = { window, context -> saved += window to context }
                )
            }
        }
        choose("Work site", "Carlton Gardens")
        choose("Work type", "Pruning")
        choose("Effort", "Light effort")
        choose("Protective clothing", "Heavy protective clothing")
        click("Compare work windows")
        reach(hasText("07:00–09:00") and hasClickAction()).assertIsSelected()
        click("08:00–10:00")
        reach(hasText("08:00–10:00") and hasClickAction()).assertIsSelected()
        reach(hasText("07:00–09:00") and hasClickAction()).assertIsNotSelected()
        click("Save preferred window")
        compose.runOnIdle {
            assertEquals(listOf("Carlton Gardens"), refreshRequests)
            assertEquals(listOf("08:00–10:00" to initial.copy(
                site = "Carlton Gardens", work = "Pruning", effort = "Light effort",
                clothing = "Heavy protective clothing"
            )), saved)
            snapshot = null
        }
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        click("Retry forecast")
        click("Return to Today")
        compose.runOnIdle {
            assertEquals(listOf("Carlton Gardens", "Carlton Gardens"), refreshRequests)
            assertEquals(1, saved.size)
            assertEquals(1, returns)
        }
    }

    @Test
    fun validatedEditsUpdateRecordsAndTotalsAndDeletionRequiresConfirmation() {
        var sessions by mutableStateOf(emptyList<WorkSession>())
        var route by mutableStateOf("records")
        var selectedId by mutableStateOf<Int?>(null)
        compose.setContent {
            HeatShieldTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Row {
                            TextButton(onClick = { route = "records" }) { Text("Records view") }
                            TextButton(onClick = { route = "trends" }) { Text("Trends view") }
                        }
                        Box(Modifier.weight(1f)) {
                            val selected = sessions.find { it.id == selectedId }
                            when (route) {
                                "records" -> RecordsScreen(sessions,
                                    onDetail = { selectedId = it; route = "detail" },
                                    onAdd = { selectedId = null; route = "edit" }, onSearch = {})
                                "detail" -> selected?.let { session ->
                                    SessionDetailScreen(session, onEdit = { route = "edit" }, onDelete = {
                                        sessions = sessions.filterNot { it.id == session.id }; route = "records"
                                    })
                                }
                                "edit" -> EditRecordScreen(selected, onSave = { entered ->
                                    val saved = entered.copy(id = selectedId ?: 1, ownerId = "worker-test")
                                    sessions = sessions.filterNot { it.id == saved.id } + saved
                                    selectedId = saved.id
                                    route = "records"
                                })
                                "trends" -> TrendsScreen(sessions)
                            }
                        }
                    }
                }
            }
        }
        click("Add record")
        replace("record_breaks", "15")
        click("Add record")
        compose.onNodeWithTag("record_outdoor").assert(hasError())
        compose.onNodeWithTag("record_breaks").assertTextContains("15")
        compose.runOnIdle { assertEquals(0, sessions.size) }
        replace("record_outdoor", "961")
        click("Add record")
        compose.onNodeWithTag("record_outdoor").assert(hasError())
        replace("record_outdoor", "0")
        click("Add record")
        compose.onNodeWithTag("record_outdoor").assert(hasError())
        compose.runOnIdle { assertTrue(sessions.isEmpty()) }
        replace("record_outdoor", "80")
        click("Add record")
        reach(hasText("1 sessions")).assertExists()
        compose.runOnIdle {
            assertEquals(80, sessions.single().outdoorMinutes)
            assertEquals(15, sessions.single().breakMinutes)
        }
        click("Trends view", scroll = false)
        reach(hasText("80", substring = false)).assertExists()
        reach(hasText("15", substring = false)).assertExists()

        click("Records view", scroll = false)
        click("80 min outdoors · 15 min breaks")
        click("Edit record")
        replace("record_outdoor", "85")
        replace("record_breaks", "481")
        click("Save changes")
        compose.onNodeWithTag("record_breaks").assert(hasError())
        compose.runOnIdle {
            assertEquals(80, sessions.single().outdoorMinutes)
            assertEquals(15, sessions.single().breakMinutes)
        }
        replace("record_breaks", "20")
        click("Save changes")
        compose.runOnIdle {
            assertEquals(1, sessions.size)
            assertEquals(1, sessions.single().id)
            assertEquals(85, sessions.single().outdoorMinutes)
            assertEquals(20, sessions.single().breakMinutes)
        }
        click("Trends view", scroll = false)
        reach(hasText("85", substring = false)).assertExists()
        reach(hasText("20", substring = false)).assertExists()
        click("Records view", scroll = false)
        click("85 min outdoors · 20 min breaks")
        click("Delete record")
        compose.onNodeWithText("Delete this record?").assertExists()
        compose.onNodeWithText("Keep record").performClick()
        compose.runOnIdle { assertEquals(1, sessions.size) }
        click("Delete record")
        compose.onNode(hasText("Delete record") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        reach(hasText("No work records yet")).assertExists()
        click("Trends view", scroll = false)
        reach(hasText("No sessions in this view")).assertExists()
        compose.runOnIdle { assertEquals(0, sessions.size) }
    }

    @Test
    fun searchCombinesFiltersResetsAndSortsRealRecords() {
        var openedId: Int? = null
        // The longest record must differ from the newest or a broken sort could pass.
        val searchRecords = records.map { if (it.id == 3) it.copy(outdoorMinutes = 125) else it }
        compose.setContent {
            HeatShieldTheme { SearchScreen(searchRecords) { openedId = it } }
        }
        compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction()).onFirst()
            .assert(hasText("95 min outdoors · 15 min breaks"))
        replace("search_query", " BIRRARUNG ")
        reach(hasText("2 results")).assertExists()
        click("Filters")
        choose("Follow-up", "Needs review")
        reach(hasText("1 result")).assertExists()
        choose("Work type", "Mowing")
        reach(hasText("No matching sessions")).assertExists()
        choose("Work type", "Planting beds")
        click("Any date")
        compose.onNodeWithText("Use date").performClick()
        reach(hasText("1 result")).assertExists()
        click("Reset")
        reach(hasText("3 results")).assertExists()
        replace("search_query", "missing worksite")
        reach(hasText("No matching sessions")).assertExists()
        click("Reset")
        click("Newest first")
        compose.onNodeWithText("Longest outdoors").performClick()
        val first = compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction()).onFirst()
        first.assert(hasText("125 min outdoors · 20 min breaks"))
        first.performClick()
        compose.runOnIdle { assertEquals(3, openedId) }
    }

    @Test
    fun trendsKeepWorkAndBreakTotalsSeparateAndWeeksBeginOnMonday() {
        compose.setContent { HeatShieldTheme { TrendsScreen(records) } }
        reach(hasText("195", substring = false)).assertExists()
        reach(hasText("45", substring = false)).assertExists()
        click("By week")
        click("Data values")
        reach(hasText("Week of 21 Sep 2026")).assertExists()
        compose.onAllNodesWithText("Outdoor work 100 min · Logged breaks 30 min")
            .onLast().performScrollTo().assertExists()
        reach(hasText("Week of 28 Sep 2026")).assertExists()
        choose("Work type", "Mowing")
        reach(hasText("40", substring = false)).assertExists()
        reach(hasText("10", substring = false)).assertExists()
        choose("Work type", "Pruning")
        reach(hasText("No sessions in this view")).assertExists()
        choose("Work type", "Planting beds")
        reach(hasText("155", substring = false)).assertExists()
        reach(hasText("35", substring = false)).assertExists()
    }

    private fun assertPasswordRendering(value: String, visible: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("auth_password")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Password field did not expose its rendered text layout", layouts.isNotEmpty())
        val expected = if (visible) value else "\u2022".repeat(value.length)
        assertTrue("Password rendering does not match the ${if (visible) "visible" else "masked"} state",
            layouts.any { it.layoutInput.text.text == expected })
    }

    private fun hasError() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private fun isPassword() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun reach(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitForIdle()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun click(text: String, scroll: Boolean = true) {
        val matcher = hasText(text) and hasClickAction()
        (if (scroll) reach(matcher) else compose.onNode(matcher)).performClick()
    }

    private fun replace(tag: String, value: String) = replace(compose.onNodeWithTag(tag).performScrollTo(), value)

    private fun replace(field: SemanticsNodeInteraction, value: String) {
        field.performTextReplacement(value)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun choose(label: String, value: String) {
        reach(hasText(label)).performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
    }

    private val records = listOf(
        WorkSession(1, "worker-test", "Planting beds", "Birrarung Marr", "28 Sep 2026", 95, 15, "Needs review"),
        WorkSession(2, "worker-test", "Mowing", "Birrarung Marr", "27 Sep 2026", 40, 10, "Reviewed"),
        WorkSession(3, "worker-test", "Planting beds", "Carlton Gardens", "21 Sep 2026", 60, 20, "Needs review")
    )
}
