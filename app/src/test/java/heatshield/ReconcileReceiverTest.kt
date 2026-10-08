package heatshield

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.background.AlertAction
import heatshield.background.AlertDispatch
import heatshield.background.AlertReceiver
import heatshield.background.AlertScheduler
import heatshield.background.ReconcileReceiver
import heatshield.background.ShiftActionReceiver
import heatshield.domain.AlarmKind
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The three receivers accept exactly their own intents and hand them to the one dispatcher; nothing else reaches it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReconcileReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val received = Collections.synchronizedList(mutableListOf<String>())
    private var latch = CountDownLatch(1)
    private val previous = AlertDispatch.handler

    @Before
    fun record() {
        AlertDispatch.handler = { _, intent -> received += intent.action.orEmpty() + ":" + intent.dataString.orEmpty(); latch.countDown() }
    }

    @After
    fun restore() { AlertDispatch.handler = previous }

    private fun deliver(receiver: android.content.BroadcastReceiver, intent: Intent, expectDelivery: Boolean) {
        latch = CountDownLatch(1)
        receiver.onReceive(context, intent)
        assertEquals(expectDelivery, latch.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun systemBroadcastsReachTheDispatcherAndOthersDoNot() {
        ReconcileReceiver.ACTIONS.forEach { action -> deliver(ReconcileReceiver(), Intent(action), expectDelivery = true) }
        deliver(ReconcileReceiver(), Intent(Intent.ACTION_BATTERY_LOW), expectDelivery = false)
        deliver(ReconcileReceiver(), Intent(AlertScheduler.ACTION_FIRE), expectDelivery = false)
        assertEquals(ReconcileReceiver.ACTIONS.map { "$it:" }.toSet(), received.toSet())
        assertEquals(5, ReconcileReceiver.ACTIONS.size)
    }

    @Test
    fun alarmAndActionIntentsReachTheDispatcherWithTheirData() {
        deliver(AlertReceiver(), AlertScheduler.fireIntent(context, "local", AlarmKind.BREAK_DUE, 7), expectDelivery = true)
        deliver(AlertReceiver(), Intent("heatshield.SOMETHING_ELSE"), expectDelivery = false)
        deliver(ShiftActionReceiver(), ShiftActionReceiver.intent(context, "local", AlertAction.SNOOZE, 7), expectDelivery = true)
        deliver(ShiftActionReceiver(), Intent(Intent.ACTION_BOOT_COMPLETED), expectDelivery = false)
        assertEquals(listOf("heatshield.ALERT_FIRE:heatshield://alert/local/BREAK_DUE/7", "heatshield.SHIFT_ACTION:heatshield://action/local/SNOOZE/7"), received)
    }

    @Test
    fun aMissingHandlerIsNotACrash() {
        AlertDispatch.handler = null
        AlertReceiver().onReceive(context, AlertScheduler.fireIntent(context, "local", AlarmKind.AUTO_END, 7))
        ReconcileReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(received.isEmpty())
    }
}
