package heatshield

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.ui.FirstAid
import heatshield.ui.FirstAidScreen
import heatshield.ui.HeatShieldTheme
import heatshield.ui.rememberDialer
import org.hamcrest.CoreMatchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline urgent guidance and dial intents. Calls are intercepted and never placed. */
@RunWith(AndroidJUnit4::class)
class FirstAidTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val intents = IntentsRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<*>? = null

    @Before
    fun stubDialer() {
        intending(hasAction(Intent.ACTION_DIAL)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun closeScenario() { scenario?.close() }

    @Test
    fun emergencyAndAdviceActionsOnlyOpenTheirDialNumbers() {
        showCard()
        compose.onNodeWithTag("first_aid_call_000").assertIsDisplayed().performClick()
        intended(allOf(hasAction(Intent.ACTION_DIAL), hasData(Uri.parse("tel:000"))))
        compose.onNodeWithTag("first_aid_nurse").performScrollTo().performClick()
        intended(allOf(hasAction(Intent.ACTION_DIAL), hasData(Uri.parse("tel:1300606024"))))
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse("Opening the dialer needs no call permission", "android.permission.CALL_PHONE" in permissions)
    }

    @Test
    fun missingDialerShowsTheNumberForTheRequestedService() {
        showCard(dialAvailable = false)
        compose.onNodeWithTag("first_aid_call_000").performClick()
        compose.onNodeWithText(context.getString(R.string.first_aid_no_phone_app)).assertIsDisplayed()
        compose.onNodeWithTag("first_aid_nurse").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.first_aid_no_advice_phone_app))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.first_aid_no_phone_app)).assertDoesNotExist()
    }

    @Test
    fun breathingAndResuscitationBranchPrecedesDetailedCooling() {
        showCard()
        val sections = listOf("first_aid_call_000", "first_aid_call_now_signs", "first_aid_unresponsive",
            "first_aid_cooling", "first_aid_early_signs", "first_aid_step_1", "first_aid_step_5")
        val positions = sections.map { it to compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        positions.zipWithNext().forEach { (above, below) ->
            assertTrue("${above.first} precedes ${below.first}", above.second < below.second)
        }
        context.resources.getStringArray(R.array.first_aid_unresponsive_steps).forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.first_aid_still_sweating)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun urgentActionAndLastStepRemainReachableAtDoubleFont() {
        showCard(fontScale = 2f)
        compose.onNodeWithTag("first_aid_call_000").assertIsDisplayed()
        compose.onNodeWithTag("first_aid_step_5").performScrollTo().assertIsDisplayed()
        val text = context.resources.getStringArray(R.array.first_aid_steps).last()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("The guidance must wrap at large font", layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("first_aid_nurse").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun publishedShortcutOpensFirstAidAndBackStaysInTheApp() {
        val shortcut = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
            .single { it.id == "first_aid" }
        assertTrue(shortcut.isEnabled)
        val intent = requireNotNull(shortcut.intent)
        assertEquals(FirstAid.ACTION, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        val launched = ActivityScenario.launch<MainActivity>(intent)
        scenario = launched
        val title = context.getString(R.string.first_aid_title)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("first_aid_call_000").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.first_aid_back)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        assertEquals(Lifecycle.State.RESUMED, launched.state)
    }

    private fun showCard(fontScale: Float = 1f, dialAvailable: Boolean = true) {
        val host = ActivityScenario.launch(ComponentActivity::class.java)
        scenario = host
        host.onActivity { activity ->
            activity.setContent {
                HeatShieldTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        val dial = rememberDialer()
                        Box(Modifier.width(360.dp)) {
                            FirstAidScreen(onDial = if (dialAvailable) dial else { _ -> false })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
