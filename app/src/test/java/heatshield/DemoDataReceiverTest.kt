// Checks the debug demo's stored start across Melbourne clock changes and its Android sender boundary.
// A disposable Room database exercises the real start validation without creating a demo in the app's database.
package heatshield

import android.Manifest
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.data.HeatShieldDatabase
import heatshield.data.ShiftClock
import heatshield.data.ShiftRepository
import heatshield.data.UserPreferences
import heatshield.debug.DemoDataReceiver
import heatshield.debug.demoShiftInput
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DemoDataReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun springClockJumpStoresAStartFortyFourElapsedMinutesAgo() = runBlocking {
        // 03:10 AEDT follows the skipped 02:00 hour; 44 real minutes earlier is 01:26 AEST.
        assertStoredStart("2026-10-03T16:10:00Z", "2026-10-03T15:26:00Z")
    }

    @Test
    fun secondAutumnHourStoresAStartFortyFourElapsedMinutesAgo() = runBlocking {
        // This is the second 02:10 (AEST); its start belongs to the first 02:26 (AEDT).
        assertStoredStart("2026-04-04T16:10:00Z", "2026-04-04T15:26:00Z")
    }

    @Test
    fun demoReceiverRequiresTheShellsPrivilegedPermission() {
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, DemoDataReceiver::class.java), 0)
        assertTrue("The adb shell must still be able to address the receiver", receiver.exported)
        assertEquals(Manifest.permission.DUMP, receiver.permission)
    }

    /** Checks the actual saved epoch milliseconds and elapsed age through the repository's unchanged validation. */
    private suspend fun assertStoredStart(nowText: String, expectedStartText: String) {
        val now = Instant.parse(nowText)
        val databaseName = "demo-data-test-${UUID.randomUUID()}.db"
        val database = HeatShieldDatabase.builder(context, databaseName).build()
        try {
            val clock = object : ShiftClock {
                override fun wallMillis() = now.toEpochMilli()
                override fun elapsedMillis() = 10_000_000L
                override fun bootCount() = 1
            }
            val preferences = UserPreferences(ownerId = "demo-test", setupDone = true)
            database.dao().savePreferences(preferences)
            val id = ShiftRepository(database, clock).startShift(preferences.ownerId, demoShiftInput(preferences, now))
            val saved = requireNotNull(database.dao().getSession(preferences.ownerId, id))
            val start = Instant.ofEpochMilli(requireNotNull(saved.startedAt))
            assertEquals(Instant.parse(expectedStartText), start)
            assertEquals(44L, Duration.between(start, now).toMinutes())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
