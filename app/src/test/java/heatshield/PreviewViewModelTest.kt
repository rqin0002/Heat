// Checks that practice shifts keep recoverable results, valid break times and consistent personal heat advice.
// Robolectric supplies Android's ViewModel and Room while the sensor clock stays pinned to explicit readings.
package heatshield

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import heatshield.data.AndroidShiftClock
import heatshield.data.HeatShieldDatabase
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftRepository
import heatshield.data.ShiftSource
import heatshield.domain.BreakTimes
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.PersonalFactors
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.ui.PreviewViewModel
import java.io.File
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val day = SensorReplaySimulator.PREVIEW_DATE
    private val rows = SensorReplaySimulator.parse(StringReader(File("src/main/assets/microclimate_replay.csv").readText()))
    private val profile = HeatProfile("Birrarung Marr", "Digging & planting", 420, 900, 35.0, PersonalFactors())

    @Before
    fun prepare() { Dispatchers.setMain(dispatcher) }

    @After
    fun close() { store.clear(); Dispatchers.resetMain() }

    @Test
    fun anUnacknowledgedCompletedShiftReturnsTheSameResultForRetry() {
        val preview = preview(listOf(readingAt(9, 52), readingAt(10, 30), readingAt(11, 0)))
        preview.startShift(false)
        preview.next(); dispatcher.scheduler.runCurrent()
        val first = requireNotNull(preview.endShift())
        preview.next(); dispatcher.scheduler.runCurrent()
        preview.startShift(true)
        assertNull(preview.shift.value)
        assertSame(first, preview.endShift())
        assertEquals(day.atTime(10, 30), first.end)
        preview.discardShift()
        assertNull(preview.endShift())
    }

    @Test
    fun endingDuringConfirmedLunchStoresOnlyItsMinutesInsideTheShift() = runBlocking {
        val preview = preview(listOf(readingAt(9, 52), readingAt(12, 0), readingAt(12, 5)))
        preview.startShift(false)
        preview.next(); dispatcher.scheduler.runCurrent()
        preview.confirmScheduled(ScheduledKind.LUNCH, true, 720)
        preview.next(); dispatcher.scheduler.runCurrent()
        val result = requireNotNull(preview.endShift())
        val application = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(application, HeatShieldDatabase::class.java).build()
        try {
            val repository = ShiftRepository(database, AndroidShiftClock(application))
            val id = repository.savePreviewShift("local", result)
            val saved = requireNotNull(database.dao().getSession("local", id))
            val lunch = database.dao().getEvents(id).single()
            assertEquals(ShiftRules.epochMillis(day.atTime(12, 5)), lunch.endedAt)
            assertEquals(5, saved.breakMinutes)
            assertEquals(ShiftSource.PREVIEW, saved.source)
            val times = BreakTimes(lunch.id, ScheduledKind.LUNCH, ShiftRules.localTime(lunch.startedAt),
                ShiftRules.localTime(requireNotNull(lunch.endedAt)))
            assertTrue(ShiftRules.validateTimes(result.start, result.end, listOf(times), ShiftRules.instant(day.atTime(13, 0))).ok)
        } finally { database.close() }
    }

    @Test
    fun endingBeforeAConfirmedFutureLunchDropsItInsteadOfSavingAnInvalidBreak() {
        val preview = preview(listOf(readingAt(9, 52), readingAt(11, 0)))
        preview.startShift(false)
        preview.confirmScheduled(ScheduledKind.LUNCH, true, 720)
        preview.next(); dispatcher.scheduler.runCurrent()
        assertTrue(requireNotNull(preview.endShift()).events.isEmpty())
    }

    @Test
    fun heavyWorkChangesDisplayedHeatAndTimingTogetherInBothDirectionsAndOnNextReading() {
        val observed = rows.first { it.observedAtLocal.startsWith("2026-01-09T10:52") }
        val preview = preview(listOf(readingAt(9, 52), observed, readingAt(10, 53).copy(
            temperature = observed.temperature, humidity = observed.humidity)),
            profile.copy(factors = PersonalFactors(heavyWork = true)))
        preview.startShift(true)
        preview.next(); dispatcher.scheduler.runCurrent()
        assertEquals(HeatLevel.EXTREME, preview.state.value!!.heat.current.effective)
        preview.setHeavyWork(false)
        assertEquals(HeatLevel.VERY_HOT, preview.state.value!!.heat.current.effective)
        assertEquals(HeatLevel.VERY_HOT, preview.state.value!!.heat.dayHours.single { it.time.hour == 10 }.effective)
        assertEquals(HeatLevel.VERY_HOT, preview.dayHours(false).single { it.time.hour == 10 }.effective)
        preview.setHeavyWork(true)
        assertEquals(HeatLevel.EXTREME, preview.state.value!!.heat.current.effective)
        preview.setHeavyWork(false)
        preview.next(); dispatcher.scheduler.runCurrent()
        assertEquals(HeatLevel.VERY_HOT, preview.state.value!!.heat.current.effective)
        preview.discardShift()
        assertEquals(HeatLevel.EXTREME, preview.state.value!!.heat.current.effective)
    }

    /** Starts the actual model without automatic advances; each Next selects one explicit sensor time. */
    private fun preview(readings: List<SensorReading>, activeProfile: HeatProfile = profile): PreviewViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preview = PreviewViewModel(application, SensorReplaySimulator({ readings },
            intervalMillis = Long.MAX_VALUE / 4, nowMillis = { Long.MAX_VALUE }))
        store.put("preview", preview)
        preview.start(activeProfile)
        dispatcher.scheduler.runCurrent()
        return preview
    }

    /** Makes the exact January minute needed for a boundary case, retaining valid sensor metadata. */
    private fun readingAt(hour: Int, minute: Int): SensorReading {
        val observed = day.atTime(hour, minute).atZone(MelbourneZone).toOffsetDateTime()
        return rows.first().copy(observedAtLocal = observed.toString(), observedAt = observed.toInstant().toString())
    }
}
