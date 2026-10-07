package heatshield

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.background.ShiftNotifications
import heatshield.data.SensorReplaySimulator
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatProfile
import heatshield.domain.PersonalFactors
import heatshield.ui.HeatShieldTheme
import heatshield.ui.PreviewContent
import heatshield.ui.PreviewScreen
import heatshield.ui.PreviewState
import heatshield.ui.PreviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The labelled hot-day preview: banner, sensor chip, a hero that climbs from Normal to Extreme and says what its
 * number is, the two Extreme lines, crew line, Next reading, 200 % font on a 360 dp phone, and the real
 * PreviewViewModel playing the bundled file alone, both on its own and with Next, labelled as Birrarung Marr
 * whatever the gardener's park. M2: the preview shift reaches Break due on the virtual clock and is saved when ended,
 * and cannot start while a real shift is open. M3: it posts a Preview-labelled break-due notification on a channel the
 * screen creates itself, and Exit cancels it.
 */
@RunWith(AndroidJUnit4::class)
class PreviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows = runBlocking { SensorReplaySimulator.fromAssets(context).rows() }
    private val profile = HeatProfile("Birrarung Marr", "Digging & planting", 420, 900, 35.0, PersonalFactors())

    private fun stateAt(localPrefix: String): PreviewState {
        val index = rows.indexOfFirst { it.observedAtLocal.startsWith(localPrefix) }
        val heat = ContextReasoner.previewHeat(rows, index, profile)
        return PreviewState(heat, index, rows.size, heat.current.time)
    }

    /** The real view model over the bundled file only, with [intervalMillis] between readings instead of 20 s. */
    private fun bundledPreview(intervalMillis: Long) = PreviewViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        SensorReplaySimulator({ SensorReplaySimulator.fromAssets(context).rows() }, intervalMillis = intervalMillis)
    )

    @Test
    fun bannerChipAndCrewLineDescribeTheSensorDay() {
        var state by mutableStateOf(stateAt("2026-01-09T10:22"))
        compose.setEnglishContent { HeatShieldTheme { PreviewContent(state, unavailable = false, onNext = {}, onExit = {}) } }
        compose.onNodeWithText("PREVIEW · recorded Fri 9 Jan 2026 · not today").assertIsDisplayed()
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("Reading at 10:22", substring = true)
            .assertTextContains("28.3°", substring = true).assertTextContains("47% humidity", substring = true)
        compose.onNodeWithText("Birrarung Marr street sensor").assertIsDisplayed()
        compose.onNodeWithText("readings so far are under your 35° rule", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = stateAt("2026-01-09T11:07") }
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("37.0°", substring = true)
        compose.onNodeWithText("sensor passed 35° at 10:52", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The sensor has no wind data, so still air is assumed.", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anExtremeReadingAfterUsualHoursKeepsTheCrewRuleScopeExplicit() {
        val early = profile.copy(shiftStartMinute = 360, shiftEndMinute = 540)
        val index = rows.indexOfFirst { it.observedAtLocal.startsWith("2026-01-09T11:52") }
        val heat = ContextReasoner.previewHeat(rows, index, early)
        var state by mutableStateOf(PreviewState(heat, index, rows.size, heat.current.time))
        var activeProfile by mutableStateOf(early)
        compose.setEnglishContent { HeatShieldTheme { PreviewContent(state, false, onNext = {}, onExit = {}, profile = activeProfile) } }
        compose.onNodeWithTag("hero_level", useUnmergedTree = true).assertTextEquals("Extreme")
        val use24Hour = android.text.format.DateFormat.is24HourFormat(context)
        compose.onNodeWithText("hours covering your usual day", substring = true).performScrollTo()
            .assertTextContains(heatshield.ui.Formats.time(java.time.LocalTime.of(6, 0), use24Hour), substring = true)
            .assertTextContains(heatshield.ui.Formats.time(java.time.LocalTime.of(9, 0), use24Hour), substring = true)
            .assertTextContains("under your 35° rule", substring = true)
        compose.runOnIdle {
            activeProfile = profile.copy(shiftStartMinute = 570, shiftEndMinute = 630)
            val partial = ContextReasoner.previewHeat(rows, index, activeProfile)
            state = PreviewState(partial, index, rows.size, partial.current.time)
        }
        // A 09:30–10:30 usual day includes the whole 09:00 and 10:00 hours, including the 10:52 crossing.
        compose.onNodeWithText("hours covering your usual day", substring = true).performScrollTo()
            .assertTextContains(heatshield.ui.Formats.time(java.time.LocalTime.of(9, 0), use24Hour), substring = true)
            .assertTextContains(heatshield.ui.Formats.time(java.time.LocalTime.of(11, 0), use24Hour), substring = true)
            .assertTextContains("sensor passed 35° at 10:52", substring = true)
    }

    @Test
    fun heroClimbsFromNormalAtTheStartToExtremeAtThePeak() {
        // Whole degrees, rounded half up. 09:52 is 25.0° air at 57.8 % humidity: feels like 27.02, so the heat number
        // shows 27° (Normal, under 30) and the air shows 25°, and the label says which is which. 14:53 is the day's peak:
        // 42.9° air at 7.4 % feels like 41.0, so the heat number is the air itself, 43° (Extreme, 38 or more), and the
        // label just says "sensor air". This profile has no personal factors, so there is no "for you".
        var state by mutableStateOf(stateAt("2026-01-09T09:52"))
        compose.setEnglishContent { HeatShieldTheme { PreviewContent(state, unavailable = false, onNext = {}, onExit = {}) } }
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("25.0°", substring = true)
            .assertTextContains("58% humidity", substring = true)
        compose.onNodeWithTag("hero_level", useUnmergedTree = true).assertTextEquals("Normal")
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).assertTextEquals("27°")
        compose.onNodeWithTag("hero_numeral_label", useUnmergedTree = true)
            .assertTextEquals("feels like in still air · sensor air 25°")
        compose.onNodeWithTag("hero_qualifier", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("preview_extreme").assertDoesNotExist()
        compose.onNodeWithTag("preview_extreme_keep_working").assertDoesNotExist()
        compose.runOnIdle { state = stateAt("2026-01-09T14:53") }
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("42.9°", substring = true)
        compose.onNodeWithTag("hero_level", useUnmergedTree = true).assertTextEquals("Extreme")
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).assertTextEquals("43°")
        compose.onNodeWithTag("hero_numeral_label", useUnmergedTree = true).assertTextEquals("sensor air")
        // Spec §5.10, as on Today: at Extreme the screen says to stop heavy work, directly under the hero, then what
        // to do if the crew keeps working. It gives no work/rest cadence.
        compose.onNodeWithTag("preview_extreme").performScrollTo()
            .assertTextEquals("Stop heavy work outdoors. Check your crew's hot-day rule.")
        compose.onNodeWithTag("preview_extreme_keep_working").performScrollTo()
            .assertTextEquals("If your crew keeps working, take breaks in the shade and follow your crew's rule.")
    }

    @Test
    fun nextReadingAndExitCallBack() {
        var nexts = 0
        var exited = false
        compose.setEnglishContent {
            HeatShieldTheme { PreviewContent(stateAt("2026-01-09T10:22"), false, onNext = { nexts++ }, onExit = { exited = true }) }
        }
        compose.onNodeWithText("Next reading").performScrollTo().performClick()
        compose.onNodeWithText("Exit preview").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, nexts)
            assertTrue(exited)
        }
    }

    // Review Focus 4: 200 % font on a 360 dp phone.
    @Test
    fun previewAtDoubleFontWrapsAndScrolls() {
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    Box(Modifier.width(360.dp)) {
                        PreviewContent(stateAt("2026-01-09T10:22"), unavailable = false, onNext = {}, onExit = {})
                    }
                }
            }
        }
        // The banner's own Text, found inside the hatched box rather than by its wording.
        val banner = compose.onNode(
            hasAnyAncestor(hasTestTag("preview_banner")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true
        ).assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        banner.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("The Preview banner is cut off at 200 % font", layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("preview_sensor_chip").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next reading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exit preview").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun previewPlaysTheBundledDayWithoutAnyNetworkComponent() {
        // No HeatShieldViewModel, forecast repository or Firebase: only the bundled file and the reasoner.
        // No reading arrives on its own during this test, so only the Next tap can move the chip.
        // This gardener works at Royal Park. The day is still captioned as the Birrarung Marr sensor and never named
        // as Royal Park, so a January Birrarung Marr reading can't pass as another park's weather.
        val preview = bundledPreview(intervalMillis = Long.MAX_VALUE / 4)
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile.copy(site = "Royal Park"), onExit = {}, preview = preview) } }
        compose.waitUntil(timeoutMillis = 5_000) { preview.state.value != null }
        val first = preview.state.value!!
        assertEquals(231, first.index)
        assertEquals(288, first.total)
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("25.0°", substring = true)
        compose.onNodeWithText("Birrarung Marr street sensor").assertIsDisplayed()
        compose.onNodeWithText("Royal Park", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Next reading").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            preview.state.value?.heat?.reading?.observedAtLocal?.startsWith("2026-01-09T10:07") == true
        }
        assertEquals(232, preview.state.value!!.index)
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("26.4°", substring = true)
    }

    @Test
    fun unreadableSensorFileOffersExitWithoutInventingAReading() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preview = PreviewViewModel(application, SensorReplaySimulator({ throw java.io.IOException("Missing sensor fixture") }))
        var exits = 0
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile, onExit = { exits++ }, preview = preview) } }
        compose.waitUntil(timeoutMillis = 5_000) { preview.unavailable.value }
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
        compose.onNodeWithText("Next reading").assertDoesNotExist()
        compose.onNodeWithText("The sensor day could not be read. Exit and try again.").assertIsDisplayed()
        compose.onNodeWithText("Exit preview").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun chipAdvancesOnItsOwn() {
        // The same view model and flow, never tapping Next. SensorReplaySimulatorTest and PreviewPlaybackTest pin the
        // real 20 s spacing; 2 s keeps this end-to-end check short and still leaves time to read each chip.
        val preview = bundledPreview(intervalMillis = 2_000)
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile, onExit = {}, preview = preview) } }
        compose.waitUntil(timeoutMillis = 5_000) { preview.state.value?.index == 231 }
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("25.0°", substring = true)
        compose.waitUntil(timeoutMillis = 5_000) { preview.state.value?.index == 232 }
        compose.onNodeWithTag("preview_sensor_chip").assertTextContains("Reading at 10:07", substring = true)
            .assertTextContains("26.4°", substring = true)
    }

    @Test
    fun previewShiftReachesBreakDueOnItsOwnAndSavesWhenEnded() {
        val preview = bundledPreview(intervalMillis = 2_000)
        val saved = mutableListOf<heatshield.data.PreviewShiftResult>()
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile, onExit = {}, preview = preview,
            onSavePreviewShift = { result, savedSuccessfully -> saved += result; savedSuccessfully() }) } }
        compose.waitUntil(timeoutMillis = 5_000) { preview.state.value != null }
        compose.onNodeWithTag("shift_start").performScrollTo().assertTextEquals("Start a preview shift").performClick()
        compose.waitUntil(5_000) { preview.shift.value != null }
        // The virtual clock runs at 45x (a minute every 1.3 real seconds), so the tap lands at 09:52 on a quick device and
        // a minute or a reading later on a slow one. The start is always the virtual minute on the labelled day.
        val started = preview.shift.value!!.start
        assertEquals(SensorReplaySimulator.PREVIEW_DATE, started.toLocalDate())
        assertFalse(started.isBefore(SensorReplaySimulator.PREVIEW_DATE.atTime(9, 52)))
        assertEquals(started.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), started)
        compose.onNodeWithText("Preview shift · 45× speed").assertExists()
        // 09:52 start, Very hot from 10:00 on the hour-max profile: due 10:30, 38 virtual minutes, about three readings at this interval.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodes(hasTestTag("shift_status") and hasText("Break due", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("shift_break_start").performScrollTo().performClick()
        compose.waitUntil(5_000) { preview.shift.value?.events?.size == 1 }
        compose.onNodeWithTag("break_card").assertExists()
        // The preview clock moves once a real second (45 virtual seconds), and End drops a break that starts at the end
        // itself, as a real shift does. A person's taps are seconds apart; this test's can land inside one tick, which
        // would save the shift with no break. So end only once the shift's own clock has passed the break's start.
        val breakStart = preview.shift.value!!.events.single().startedAt
        compose.waitUntil(5_000) {
            preview.shift.value?.latest?.let { heatshield.domain.ShiftRules.epochMillis(it) > breakStart } == true
        }
        compose.onNodeWithTag("shift_end").performScrollTo().performClick()
        compose.onNodeWithTag("shift_end_confirm").assertTextEquals("End preview shift").performClick()
        compose.waitUntil(5_000) { saved.isNotEmpty() }
        val result = saved.single()
        assertEquals(started, result.start)
        assertTrue(result.end.isAfter(result.start))
        assertEquals(1, result.events.size); assertNotNull(result.events.single().endedAt)
        assertTrue(preview.shift.value == null)
        compose.onNodeWithTag("shift_start").assertExists()
    }

    @Test
    fun previewShiftPostsALabelledBreakDueNotificationAndExitCancelsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", ShiftNotifications(context).notificationsAllowed())
        // Self-contained: this rule hosts a bare ComponentActivity, so no HeatShieldViewModel/AlertCoordinator creates the
        // channels, and a fresh install has none; Android drops a post to a missing channel. Idempotent on a shared AVD.
        ShiftNotifications(context).ensureChannels()
        manager.cancel(ShiftNotifications.ID_PREVIEW)
        val preview = bundledPreview(intervalMillis = 2_000)
        var exits = 0
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile, onExit = { exits++ }, preview = preview) } }
        compose.waitUntil(5_000) { preview.state.value?.index == 231 }
        compose.onNodeWithTag("shift_start").performScrollTo().performClick()
        compose.waitUntil(30_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_PREVIEW } }
        val posted = manager.activeNotifications.single { it.id == ShiftNotifications.ID_PREVIEW }.notification
        val title = posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.startsWith("Preview · Break due · "))
        assertTrue(posted.actions.isNullOrEmpty())
        assertEquals(ShiftNotifications.CHANNEL_BREAK_DUE, posted.channelId)
        compose.onNode(hasText("Exit preview") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == ShiftNotifications.ID_PREVIEW } }
        assertEquals(1, exits)
    }

    @Test
    fun anAlreadyDuePreviewAlertUpdatesToTheCurrentStopInstructionWithoutAnotherBuzz() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", ShiftNotifications(context).notificationsAllowed())
        manager.cancel(ShiftNotifications.ID_PREVIEW)
        var state by mutableStateOf(stateAt("2026-01-09T10:37"))
        val shift = heatshield.ui.PreviewShift(SensorReplaySimulator.PREVIEW_DATE.atTime(9, 52), false, emptyList())
        compose.setEnglishContent {
            HeatShieldTheme {
                PreviewContent(state, false, onNext = {}, onExit = {}, shift = shift, profile = profile,
                    dayHours = { heatshield.ui.previewShiftHours(rows, state.index, profile, it) })
            }
        }
        compose.waitUntil(5_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_PREVIEW } }
        val first = manager.activeNotifications.single { it.id == ShiftNotifications.ID_PREVIEW }.notification
        assertTrue(first.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().startsWith("Preview · Break due"))
        compose.runOnIdle { state = stateAt("2026-01-09T11:07") }
        compose.waitUntil(5_000) {
            manager.activeNotifications.firstOrNull { it.id == ShiftNotifications.ID_PREVIEW }?.notification?.extras
                ?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.startsWith("Preview · Stop heavy work outdoors") == true
        }
        val updated = manager.activeNotifications.single { it.id == ShiftNotifications.ID_PREVIEW }.notification
        assertTrue(updated.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun anAlreadyDuePreviewAlertIsRemovedWhenTheCurrentHourBecomesNormal() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", ShiftNotifications(context).notificationsAllowed())
        manager.cancel(ShiftNotifications.ID_PREVIEW)
        var state by mutableStateOf(stateAt("2026-01-09T10:37"))
        val shift = heatshield.ui.PreviewShift(SensorReplaySimulator.PREVIEW_DATE.atTime(9, 52), false, emptyList())
        compose.setEnglishContent {
            HeatShieldTheme {
                PreviewContent(state, false, onNext = {}, onExit = {}, shift = shift, profile = profile,
                    dayHours = { heatshield.ui.previewShiftHours(rows, state.index, profile, it) })
            }
        }
        compose.waitUntil(5_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_PREVIEW } }
        compose.runOnIdle { state = stateAt("2026-01-09T21:38") }
        compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == ShiftNotifications.ID_PREVIEW } }
    }

    @Test
    fun endingWithoutSaveAcknowledgementKeepsTheCompletedResultAvailableToRetry() {
        val preview = bundledPreview(intervalMillis = Long.MAX_VALUE / 4)
        val attempts = mutableListOf<heatshield.data.PreviewShiftResult>()
        var saving by mutableStateOf(false)
        var acknowledge: (() -> Unit)? = null
        compose.setEnglishContent {
            HeatShieldTheme { PreviewScreen(profile, onExit = {}, preview = preview, saving = saving,
                onSavePreviewShift = { result, savedSuccessfully ->
                    attempts += result
                    acknowledge = savedSuccessfully
                    saving = true
                }) }
        }
        compose.waitUntil(5_000) { preview.state.value != null }
        compose.onNodeWithTag("shift_start").performScrollTo().performClick()
        compose.onNodeWithTag("shift_end").performScrollTo().performClick()
        compose.onNodeWithTag("shift_end_confirm").performClick()
        compose.waitUntil(5_000) { attempts.size == 1 }
        compose.onNodeWithTag("preview_save_retry").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Exit preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, attempts.size)
            saving = false
        }
        compose.onNodeWithTag("preview_save_retry").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, attempts.size)
            org.junit.Assert.assertSame(attempts[0], attempts[1])
        }
        compose.onNodeWithTag("shift_start").assertDoesNotExist()
        compose.runOnIdle { acknowledge!!.invoke(); saving = false }
        compose.onNodeWithTag("preview_save_retry").assertDoesNotExist()
        compose.onNodeWithTag("shift_start").assertExists()
    }

    /**
     * M3 fix wave: with Heavy work on (the set-up default work, Digging & planting, is heavy), the bundled day's 10 am hour
     * (36°) is Extreme for you, so the walk from a 9:52 start meets the stop line at 10:00 before the work budget fills. The
     * panel shows the stop and no "Preview · Break due" posts, as the live app never buzzes at a stop. The README's demo
     * turns Heavy work off on the panel first.
     */
    @Test
    fun aHeavyWorkPreviewShiftShowsTheStopLineAndPostsNoBreakAlert() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", ShiftNotifications(context).notificationsAllowed())
        ShiftNotifications(context).ensureChannels()
        manager.cancel(ShiftNotifications.ID_PREVIEW)
        val heavy = profile.copy(factors = PersonalFactors(heavyWork = true))
        val preview = bundledPreview(intervalMillis = 2_000)
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(heavy, onExit = {}, preview = preview) } }
        compose.waitUntil(5_000) { preview.state.value?.index == 231 }
        compose.onNodeWithTag("shift_start").performScrollTo().performClick()
        compose.waitUntil(5_000) { preview.shift.value != null }
        assertTrue(preview.shift.value!!.heavyWork)
        // Past 10:30, when an unbumped shift's Very hot break falls due (previewShiftReachesBreakDueOnItsOwnAndSavesWhenEnded).
        compose.waitUntil(40_000) { preview.state.value?.virtualTime?.let { !it.toLocalTime().isBefore(java.time.LocalTime.of(10, 40)) } == true }
        compose.onNode(hasTestTag("shift_status") and hasText("stop heavy work outdoors", substring = true)).assertExists()
        compose.onNode(hasTestTag("shift_status") and hasText("Break due", substring = true)).assertDoesNotExist()
        assertTrue(manager.activeNotifications.none { it.id == ShiftNotifications.ID_PREVIEW })
    }

    @Test
    fun previewCreatesTheBreakDueChannelItPostsTo() {
        // Preview posts through its own presenter, so it must not depend on the app's AlertCoordinator having run first:
        // without the channel Android drops the post while ShiftNotifications.post still reports success, and the screen
        // would remember a due time whose notification never appeared. Deleting first makes the check independent of
        // test order; re-creating the same id restores the channel's settings.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.deleteNotificationChannel(ShiftNotifications.CHANNEL_BREAK_DUE)
        assertNull(manager.getNotificationChannel(ShiftNotifications.CHANNEL_BREAK_DUE))
        compose.setEnglishContent { HeatShieldTheme { PreviewContent(null, unavailable = false, onNext = {}, onExit = {}) } }
        compose.runOnIdle { assertNotNull(manager.getNotificationChannel(ShiftNotifications.CHANNEL_BREAK_DUE)) }
    }

    @Test
    fun previewShiftIsBlockedWhileARealShiftIsOpen() {
        val preview = bundledPreview(intervalMillis = Long.MAX_VALUE / 4)
        compose.setEnglishContent { HeatShieldTheme { PreviewScreen(profile, onExit = {}, preview = preview, realShiftOpen = true) } }
        compose.waitUntil(timeoutMillis = 5_000) { preview.state.value != null }
        compose.onNodeWithTag("shift_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("shift_start_note").assertTextEquals("End your real shift before starting a preview shift.")
    }
}
