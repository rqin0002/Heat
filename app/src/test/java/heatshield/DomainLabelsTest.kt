// Checks the resource boundary that screens and notifications share, while pure rule tests keep Android out.
// Existing copy assertions live here now that changing screen wording no longer changes domain identifiers.
package heatshield

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.domain.HeatLevel
import heatshield.domain.SourceLabel
import heatshield.domain.UvCategory
import heatshield.domain.WorkTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DomainLabelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().withAppLocale()

    @Test
    fun existingHeatUvAndSourceWordingComesFromResources() {
        assertEquals(listOf("Normal", "Warm", "Hot", "Very hot", "Extreme"),
            HeatLevel.entries.map { context.getString(heatshield.ui.heatLevelResource(it)) })
        assertEquals(listOf("Low", "Moderate", "High", "Very high", "Extreme"),
            UvCategory.entries.map { context.getString(heatshield.ui.uvCategoryResource(it)) })
        assertEquals(listOf("BOM", "WorkSafe Vic", "Safe Work Australia", "Workplace rule example",
            "Union starting point (VTHC)", "HeatShield rule", "Your setting"),
            SourceLabel.entries.map { context.getString(heatshield.ui.sourceLabelResource(it)) })
    }

    @Test
    fun savedJobKeysResolveToIndependentDisplayResources() {
        WorkTasks.all.forEach { task ->
            assertEquals(task.name, context.getString(requireNotNull(heatshield.ui.workTaskResource(task.name))))
        }
        listOf("Planting beds", "Mowing", "Pruning").forEach { oldName ->
            assertEquals(oldName, context.getString(requireNotNull(heatshield.ui.workTaskResource(oldName))))
        }
        assertNull(heatshield.ui.workTaskResource("Sweeping the depot"))
    }

    @Test
    fun windowWordingPreservesItsRoundedValuesAndPercentSign() {
        assertEquals("Up to 32° · 55% humidity · UV Moderate",
            context.getString(R.string.work_window_summary, 32, 55,
                context.getString(heatshield.ui.uvCategoryResource(UvCategory.MODERATE))))
    }

    @Test
    fun accountFailureResourcesKeepUsefulDistinctionsAndHideUnexpectedServiceText() {
        val cases = listOf(
            com.google.firebase.FirebaseNetworkException("raw network text") to R.string.auth_network_failed,
            com.google.firebase.FirebaseTooManyRequestsException("raw quota text") to R.string.auth_too_many_attempts,
            com.google.firebase.auth.FirebaseAuthException("ERROR_INVALID_EMAIL", "raw email") to R.string.auth_invalid_email,
            com.google.firebase.auth.FirebaseAuthException("ERROR_WEAK_PASSWORD", "raw weak") to R.string.auth_weak_password,
            com.google.firebase.auth.FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "raw duplicate") to R.string.auth_email_in_use,
            com.google.firebase.auth.FirebaseAuthException("ERROR_USER_DISABLED", "raw disabled") to R.string.auth_user_disabled,
            com.google.firebase.auth.FirebaseAuthException("ERROR_INVALID_API_KEY", "raw setup") to R.string.auth_unavailable,
            com.google.firebase.auth.FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "raw credential") to R.string.auth_details_failed,
            heatshield.data.AccountInputFailure(R.string.auth_password_missing) to R.string.auth_password_missing,
            IllegalStateException("unapproved server text") to R.string.auth_request_failed)
        cases.forEach { (failure, expected) ->
            assertEquals(context.getString(expected), context.getString(heatshield.data.accountFailureResource(failure)))
        }
    }
}
