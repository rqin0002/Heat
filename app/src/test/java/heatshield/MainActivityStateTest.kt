// Checks that notification taps survive Android recreating the entry screen while a break request waits.
// Robolectric drives saved state without mounting Compose, so loading and busy deferrals are deterministic.
package heatshield

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import heatshield.background.DeepLinks
import heatshield.background.UnwellRequest
import heatshield.ui.HeatShieldViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityStateTest {
    @Test
    fun unwellWaitingForTheShiftToLoadSurvivesActivityRecreation() = deferredRequestSurvives(false, false)

    @Test
    fun unwellWaitingForAnotherWriteSurvivesActivityRecreation() = deferredRequestSurvives(true, true)

    @Test
    fun consumedRequestsAreNotReadAgainFromTheOriginalIntentOnRecreation() {
        val first = create()
        setPending(first.get(), "setStartRoute", String::class.java, null)
        setPending(first.get(), "setUnwell", UnwellRequest::class.java, null)
        val restored = recreate(first)
        try {
            assertNull(pending(restored.get(), "getStartRoute"))
            assertNull(pending(restored.get(), "getUnwell"))
        } finally { restored.destroy() }
    }

    @Test
    fun returningFromHistoryDoesNotReplayAnOldNotification() {
        val activity = create(intent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        try {
            assertNull(pending(activity.get(), "getStartRoute"))
            assertNull(pending(activity.get(), "getUnwell"))
        } finally { activity.destroy() }
    }

    /** Holds the real model's collectors until after the check, so no database timing can accept the request early. */
    private fun deferredRequestSurvives(loaded: Boolean, busy: Boolean) {
        Dispatchers.setMain(StandardTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        try {
            androidx.work.WorkManager.getInstance(app)
        } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val device = app.getSharedPreferences("heatshield_session", 0)
        device.edit().putBoolean("local", true).commit()
        val store = ViewModelStore()
        var activity: ActivityController<MainActivity>? = null
        try {
            val model = HeatShieldViewModel(app)
            store.put("pending-request", model)
            setPending(model, "setShiftLoaded", Boolean::class.javaPrimitiveType!!, loaded)
            setPending(model, "setBusy", Boolean::class.javaPrimitiveType!!, busy)
            activity = create()
            val request = pending(activity.get(), "getUnwell") as UnwellRequest
            assertFalse(model.startUnwellBreak(request))
            activity = recreate(activity)
            assertEquals(request, pending(activity.get(), "getUnwell"))
            assertEquals("first-aid", pending(activity.get(), "getStartRoute"))
        } finally {
            activity?.destroy()
            store.clear()
            device.edit().remove("local").commit()
            Dispatchers.resetMain()
        }
    }

    /** Sends the same owner and shift identity carried by a production Unwell notification. */
    private fun intent() = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(DeepLinks.ACTION_UNWELL).putExtra(DeepLinks.EXTRA_OWNER, "local").putExtra(DeepLinks.EXTRA_SHIFT, 7)

    /** Stops before mounting Compose, preserving the window in which its handling effects have not yet run. */
    private fun create(intent: Intent = intent()) = Robolectric.buildActivity(MainActivity::class.java, intent).create()

    /** Uses Android's saved bundle to make a new entry screen, without interpreting the launch as a second tap. */
    private fun recreate(first: ActivityController<MainActivity>): ActivityController<MainActivity> {
        val saved = Bundle()
        val intent = first.get().intent
        first.saveInstanceState(saved).destroy()
        return Robolectric.buildActivity(MainActivity::class.java, intent).create(saved)
    }

    /** Observes pending delivery without widening the Activity's production API for a lifecycle test. */
    private fun pending(activity: MainActivity, getter: String): Any? =
        MainActivity::class.java.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(activity)

    /** Reproduces a handling callback or a held model state without adding test-only setters to production. */
    private fun setPending(target: Any, setter: String, type: Class<*>, value: Any?) {
        target.javaClass.getDeclaredMethod(setter, type).apply { isAccessible = true }.invoke(target, value)
    }
}
