package heatshield

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.ForecastCache
import heatshield.data.HeatShieldDatabase
import heatshield.data.ShiftError
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftFailure
import heatshield.data.ShiftPlan
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.UserPreferences
import heatshield.data.WorkSession
import heatshield.data.WorkSite
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Room database without launching an activity or cloud service. */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private lateinit var context: Context
    private lateinit var database: HeatShieldDatabase
    private val databaseName = "persistence-test-${UUID.randomUUID()}.db"

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = openDatabase()
    }

    @After
    fun close() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test
    fun recordsRemainOwnedDuringReadUpdateAndDelete() = runBlocking {
        val dao = database.dao()
        val firstId = dao.saveSession(record("worker-a", 75)).toInt()
        val secondId = dao.saveSession(record("worker-b", 40)).toInt()
        assertNotEquals(firstId, secondId)
        assertEquals(listOf(firstId), dao.observeSessions("worker-a").first().map { it.id })
        assertEquals(listOf(secondId), dao.observeSessions("worker-b").first().map { it.id })
        assertNull(dao.getSession("worker-b", firstId))

        // A guessed global ID cannot be used to change another owner's record.
        val forged = record("worker-b", 900).copy(id = firstId)
        assertEquals(0, dao.updateSession("worker-b", forged))
        assertEquals(0, dao.deleteSession("worker-b", firstId))
        assertEquals(75, requireNotNull(dao.getSession("worker-a", firstId)).outdoorMinutes)

        val edited = requireNotNull(dao.getSession("worker-a", firstId)).copy(
            outdoorMinutes = 85,
            status = ShiftStatus.TO_RAISE,
            checkInNote = "Cannot pause yet. Follow-up remains open."
        )
        assertEquals(1, dao.updateSession("worker-a", edited))
        assertEquals(edited, dao.observeSessions("worker-a").first().single())
        assertEquals(10, requireNotNull(dao.getSession("worker-a", firstId)).breakMinutes)
        assertEquals(40, requireNotNull(dao.getSession("worker-b", secondId)).outdoorMinutes)

        assertEquals(1, dao.deleteSession("worker-a", firstId))
        assertTrue(dao.observeSessions("worker-a").first().isEmpty())
        assertEquals(secondId, dao.observeSessions("worker-b").first().single().id)
    }

    @Test
    fun recordsPreferencesAndPlanSurviveReopenWithSeparateOwners() = runBlocking {
        val firstId = database.dao().saveSession(record("worker-a", 75)).toInt()
        val secondId = database.dao().saveSession(record("worker-b", 40)).toInt()
        val preferences = UserPreferences(
            ownerId = "worker-a", site = "Carlton Gardens", effort = "Light effort",
            clothing = "Heavy protective clothing", reminders = true
        )
        val otherPreferences = UserPreferences(ownerId = "worker-b", reminders = false)
        val day = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val plan = ShiftPlan(
            ownerId = "worker-a", work = "Mowing", effort = "Light effort",
            clothing = "Heavy protective clothing", dayMillis = day,
            startHour = 8, startMinute = 30, site = "Carlton Gardens",
            window = "09:00–11:00", reminderAt = day + 10 * 60 * 60 * 1000L, revision = 2
        )
        val otherPlan = plan.copy(ownerId = "worker-b", site = "Birrarung Marr", revision = 1)
        database.dao().savePreferences(preferences)
        database.dao().savePreferences(otherPreferences)
        database.dao().savePlan(plan)
        database.dao().savePlan(otherPlan)

        database.close()
        database = openDatabase()
        val reopened = database.dao()
        assertEquals(firstId, reopened.observeSessions("worker-a").first().single().id)
        assertEquals(secondId, reopened.observeSessions("worker-b").first().single().id)
        assertEquals(preferences, reopened.getPreferences("worker-a"))
        assertEquals(otherPreferences, reopened.getPreferences("worker-b"))
        assertEquals(plan, reopened.getPlan("worker-a"))
        assertEquals(otherPlan, reopened.getPlan("worker-b"))
        assertNull(reopened.getPreferences("unknown-owner"))
        assertNull(reopened.getPlan("unknown-owner"))
        assertTrue(reopened.observeSessions("unknown-owner").first().isEmpty())
    }

    @Test
    fun invalidRecordMutationsLeaveSavedRecordsUnchanged() = runBlocking {
        val dao = database.dao()
        val original = record("worker-a", 75)
        val id = dao.saveSession(original).toInt()
        val saved = original.copy(id = id)
        val invalidRecords = listOf(
            original.copy(ownerId = ""),
            original.copy(title = " "),
            original.copy(date = "31 Sep 2026"),
            original.copy(outdoorMinutes = -1),
            original.copy(breakMinutes = -1),
            original.copy(outdoorMinutes = 1431, breakMinutes = 10),
            original.copy(status = "Unknown"),
            original.copy(checkInNote = "x".repeat(1001))
        )
        invalidRecords.forEach { invalid ->
            expectValidationFailure { dao.saveSession(invalid) }
            expectValidationFailure { dao.updateSession("worker-a", invalid.copy(id = id)) }
            assertEquals(listOf(saved), dao.observeSessions("worker-a").first())
        }
        // An insert cannot overwrite an existing global ID, even for its real owner.
        expectValidationFailure { dao.saveSession(saved.copy(outdoorMinutes = 900)) }
        expectValidationFailure { dao.updateSession("worker-b", saved.copy(outdoorMinutes = 900)) }
        assertEquals(saved, dao.getSession("worker-a", id))
        assertTrue(dao.observeSessions("").first().isEmpty())
        assertTrue(dao.observeSessions("worker-b").first().isEmpty())

        // The full-day boundary is valid; rejecting bad durations must not reject this value.
        val fullDay = saved.copy(outdoorMinutes = 1430, breakMinutes = 10)
        assertEquals(1, dao.updateSession("worker-a", fullDay))
        assertEquals(fullDay, dao.getSession("worker-a", id))
    }

    @Test
    fun preferenceAndPlanReplacementValidationAndDeletionAreOwnerScoped() = runBlocking {
        val dao = database.dao()
        val preferences = UserPreferences(ownerId = "worker-a")
        val otherPreferences = preferences.copy(ownerId = "worker-b")
        val plan = ShiftPlan(ownerId = "worker-a", revision = 1)
        val otherPlan = plan.copy(ownerId = "worker-b")
        dao.savePreferences(preferences)
        dao.savePreferences(otherPreferences)
        dao.savePlan(plan)
        dao.savePlan(otherPlan)

        val replacementPreferences = preferences.copy(site = "Royal Park", reminders = true,
            task = "Irrigation & checks", shiftStartMinute = 480, shiftEndMinute = 840,
            hotDayTrigger = 38.0, acclimatisingUntil = 1_800_000_000_000L, setupDone = true)
        val replacementPlan = plan.copy(site = "Royal Park", startHour = 9, revision = 2, endMinute = 13 * 60)
        dao.savePreferences(replacementPreferences)
        dao.savePlan(replacementPlan)
        assertEquals(replacementPreferences, dao.observePreferences("worker-a").first())
        assertEquals(replacementPlan, dao.observePlan("worker-a").first())

        expectValidationFailure { dao.savePreferences(replacementPreferences.copy(ownerId = "")) }
        expectValidationFailure { dao.savePreferences(replacementPreferences.copy(effort = "Unknown")) }
        expectValidationFailure { dao.savePreferences(replacementPreferences.copy(clothing = "Unknown")) }
        listOf(replacementPreferences.copy(task = " "), replacementPreferences.copy(task = "x".repeat(101)),
            replacementPreferences.copy(shiftStartMinute = -1), replacementPreferences.copy(shiftEndMinute = 479),
            replacementPreferences.copy(shiftEndMinute = 1440), replacementPreferences.copy(hotDayTrigger = 36.5),
            replacementPreferences.copy(hotDayTrigger = Double.NaN)).forEach { invalid ->
            expectValidationFailure { dao.savePreferences(invalid) }
        }
        expectValidationFailure { dao.savePlan(replacementPlan.copy(startHour = 15)) }
        expectValidationFailure { dao.savePlan(replacementPlan.copy(endMinute = 1439, startHour = 23)) }
        expectValidationFailure { dao.savePlan(replacementPlan.copy(revision = -1)) }
        expectValidationFailure { dao.savePlan(replacementPlan.copy(reminderAt = 0)) }
        assertEquals(replacementPreferences, dao.getPreferences("worker-a"))
        assertEquals(replacementPlan, dao.getPlan("worker-a"))
        assertEquals(otherPreferences, dao.getPreferences("worker-b"))
        assertEquals(otherPlan, dao.getPlan("worker-b"))
        assertNull(dao.getPreferences(""))

        assertEquals(0, dao.deletePlan("unknown-owner"))
        assertEquals(1, dao.deletePlan("worker-a"))
        assertEquals(0, dao.deletePlan("worker-a"))
        assertNull(dao.observePlan("worker-a").first())
        assertEquals(otherPlan, dao.observePlan("worker-b").first())
        assertEquals(replacementPreferences, dao.getPreferences("worker-a"))
    }

    @Test
    fun forecastCacheSurvivesReopenAndChangesOnlyTheSelectedSite() = runBlocking {
        val sites = WorkSite.defaults.take(2)
        val first = ForecastCache(
            site = sites[0].name, fetchedAt = 1000,
            hoursJson = """[{"time":"2026-09-28T09:00","temperature":26.0,"humidity":45.0,"uv":3.0}]""",
            lastAttempt = 1000, outcome = "success"
        )
        val second = first.copy(site = sites[1].name, fetchedAt = 2000, lastAttempt = 2000)
        database.dao().saveSites(sites)
        database.dao().saveForecast(first)
        database.dao().saveForecast(second)
        database.close()
        database = openDatabase()

        val dao = database.dao()
        assertEquals(first, dao.observeForecast(first.site).first())
        assertEquals(second, dao.getForecast(second.site))
        assertNull(dao.getForecast("Unknown site"))
        val replacement = first.copy(fetchedAt = 3000, lastAttempt = 3000, hoursJson = "[]")
        dao.saveForecast(replacement)
        assertEquals(replacement, dao.getForecast(first.site))
        assertEquals(second, dao.getForecast(second.site))
        assertEquals(0, dao.deleteForecast("Unknown site"))
        assertEquals(1, dao.deleteForecast(first.site))
        assertEquals(0, dao.deleteForecast(first.site))
        assertNull(dao.observeForecast(first.site).first())
        assertEquals(second, dao.getForecast(second.site))
        assertEquals(WorkSite.defaults.sortedBy { it.name }, dao.getSites())
    }

    @Test
    fun siteCatalogueRejectsInvalidBatchesWithoutPartiallyUpdatingSavedSites() = runBlocking {
        val dao = database.dao()
        val sites = WorkSite.defaults
        dao.saveSites(sites)
        val changed = sites[0].copy(latitude = -37.82)
        val invalidBatches = listOf(
            listOf(changed, sites[1].copy(latitude = Double.NaN)),
            listOf(changed, sites[1].copy(longitude = 181.0)),
            listOf(changed, sites[1].copy(id = changed.id)),
            listOf(changed, sites[1].copy(name = changed.name))
        )
        invalidBatches.forEach { batch ->
            expectValidationFailure { dao.saveSites(batch) }
            assertEquals(sites.sortedBy { it.name }, dao.getSites())
        }

        // Refreshing one site updates its coordinates and retains the other catalogue entries.
        dao.saveSites(listOf(changed))
        val expected = (listOf(changed) + sites.drop(1)).sortedBy { it.name }
        assertEquals(expected, dao.observeSites().first())
        assertEquals(changed, dao.getSite(changed.name))
        assertNull(dao.getSite("Unknown site"))
        database.close()
        database = openDatabase()
        assertEquals(expected, database.dao().getSites())
    }

    @Test
    fun deletingAShiftCascadesToItsEventsAndOpenShiftIsOnePerOwner() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()
        val open = WorkSession(ownerId = "worker-a", title = "Push mowing", site = "Birrarung Marr", date = "28 Sep 2026",
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = now - 3_600_000L, plannedEndAt = now + 3_600_000L,
            source = ShiftSource.TRACKED, startedElapsed = 1_000L, bootCount = 1)
        val id = dao.startShift(open).toInt()
        expectShiftFailure(ShiftError.SHIFT_OPEN) { dao.startShift(open) }
        assertEquals(id.toLong(), dao.startShift(open.copy(ownerId = "worker-b")) - 1)   // another owner is not blocked
        val breakId = dao.addEvent("worker-a", ShiftEvent(shiftId = id, type = ShiftEventType.BREAK, startedAt = now - 1_800_000L)).toInt()
        expectShiftFailure(ShiftError.BREAK_OPEN) { dao.addEvent("worker-a", ShiftEvent(shiftId = id, type = ShiftEventType.BREAK, startedAt = now - 900_000L)) }
        expectShiftFailure(ShiftError.NO_SHIFT) { dao.addEvent("worker-b", ShiftEvent(shiftId = id, type = ShiftEventType.BREAK, startedAt = now)) }
        assertEquals(1, dao.closeEvent("worker-a", id, breakId, now - 900_000L))
        assertEquals(1, dao.getEvents(id).size)
        assertEquals(id, dao.getOpenShift("worker-a")?.id)
        assertEquals(1, dao.deleteSession("worker-a", id))
        assertTrue(dao.getEvents(id).isEmpty())
        assertNull(dao.getOpenShift("worker-a"))
    }

    // The DAO's guards stand behind ShiftRepository, the one writer: each rejects what the app never writes.
    @Test
    fun shiftEventSessionAndPreferenceGuardsRejectWhatTheAppNeverWrites() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()
        val open = WorkSession(ownerId = "worker-a", title = "Push mowing", site = "Birrarung Marr", date = "28 Sep 2026",
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = now - 3_600_000L, plannedEndAt = now + 3_600_000L,
            source = ShiftSource.TRACKED, startedElapsed = 1_000L, bootCount = 1)
        val id = dao.startShift(open).toInt()
        val answer = ShiftEvent(shiftId = id, type = ShiftEventType.CANT_STOP, startedAt = now - 600_000L, endedAt = now - 600_000L)
        val answerId = dao.addEvent("worker-a", answer).toInt()
        // "Can't stop yet" answers are history: Fix times never deletes them.
        assertEquals(0, dao.deleteEvent(id, answerId))
        // Events: a known type and reason, a skip only on smoko or lunch, an answer with no duration.
        val breakRow = ShiftEvent(shiftId = id, type = ShiftEventType.BREAK, startedAt = now - 300_000L, endedAt = now - 60_000L)
        expectValidationFailure { dao.addEvent("worker-a", breakRow.copy(type = "nap")) }
        expectValidationFailure { dao.addEvent("worker-a", breakRow.copy(reason = "tired")) }
        expectValidationFailure { dao.addEvent("worker-a", breakRow.copy(skipped = true)) }
        expectValidationFailure { dao.addEvent("worker-a", answer.copy(endedAt = answer.startedAt + 60_000L)) }
        // Sessions: a known source, and no end before the start.
        expectValidationFailure { dao.saveSession(record("worker-a", 30).copy(source = "x")) }
        expectValidationFailure { dao.saveSession(record("worker-a", 30).copy(startedAt = now, endedAt = now - 1)) }
        // Preferences: smoko, lunch and the break rule in range.
        val preferences = UserPreferences(ownerId = "worker-a")
        expectValidationFailure { dao.savePreferences(preferences.copy(smokoMinute = 1440)) }
        expectValidationFailure { dao.savePreferences(preferences.copy(breakRule = "osha")) }
        // Closing an event that does not exist, or updating one under another owner.
        expectShiftFailure(ShiftError.NO_BREAK) { dao.closeEvent("worker-a", id, answerId + 99, now) }
        val breakId = dao.addEvent("worker-a", breakRow).toInt()
        expectShiftFailure(ShiftError.NO_SHIFT) { dao.updateEvent("worker-b", breakRow.copy(id = breakId, endedAt = now)) }
        assertEquals(listOf(answerId, breakId), dao.getEvents(id).map { it.id })
        assertEquals(now - 60_000L, dao.getEvents(id).single { it.id == breakId }.endedAt)
        assertNull(dao.getPreferences("worker-a"))
        assertTrue(dao.observeSessions("worker-a").first().all { it.id == id })
    }

    @Test
    fun anOwnedShiftIdCannotBePairedWithAnotherOwnersEventIdToMoveOrOverwriteIt() = runBlocking {
        val dao = database.dao()
        val firstId = dao.saveSession(record("worker-a", 75)).toInt()
        val secondId = dao.saveSession(record("worker-b", 40)).toInt()
        val first = ShiftEvent(shiftId = firstId, type = ShiftEventType.BREAK, startedAt = 1_000L, endedAt = 2_000L)
        val second = ShiftEvent(shiftId = secondId, type = ShiftEventType.BREAK, startedAt = 3_000L, endedAt = 4_000L)
        val firstEvent = first.copy(id = dao.addEvent("worker-a", first).toInt())
        val secondEvent = second.copy(id = dao.addEvent("worker-b", second).toInt())
        expectShiftFailure(ShiftError.NO_BREAK) { dao.updateEvent("worker-a", secondEvent.copy(shiftId = firstId, endedAt = 5_000L)) }
        assertEquals(listOf(firstEvent), dao.getEvents(firstId))
        assertEquals(listOf(secondEvent), dao.getEvents(secondId))
        assertEquals(record("worker-a", 75).copy(id = firstId), dao.getSession("worker-a", firstId))
        assertEquals(record("worker-b", 40).copy(id = secondId), dao.getSession("worker-b", secondId))
        assertEquals(1, dao.updateEvent("worker-b", secondEvent.copy(endedAt = 5_000L)))
        assertEquals(listOf(secondEvent.copy(endedAt = 5_000L)), dao.getEvents(secondId))
    }

    // Astra M2 #8: End shift alone drops what came after a shift's real end, an answer included; only on that shift.
    @Test
    fun endShiftCanDropAnAnswerThatFixTimesKeeps() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()
        val id = dao.startShift(WorkSession(ownerId = "worker-a", title = "Push mowing", site = "Birrarung Marr", date = "28 Sep 2026",
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = now - 3_600_000L, plannedEndAt = now + 3_600_000L,
            source = ShiftSource.TRACKED, startedElapsed = 1_000L, bootCount = 1)).toInt()
        val answerId = dao.addEvent("worker-a", ShiftEvent(shiftId = id, type = ShiftEventType.CANT_STOP, startedAt = now - 600_000L, endedAt = now - 600_000L)).toInt()
        assertEquals(0, dao.deleteEvent(id, answerId))
        assertEquals(0, dao.dropEvent(id + 1, answerId))
        assertEquals(1, dao.dropEvent(id, answerId))
        assertTrue(dao.getEvents(id).isEmpty())
    }

    private suspend fun expectShiftFailure(expected: ShiftError, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $expected, got $error", error is ShiftFailure && error.error == expected)
    }

    private suspend fun expectValidationFailure(action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected validation to reject the mutation, but got $error",
            error is IllegalArgumentException || error is DateTimeParseException)
    }

    private fun openDatabase(): HeatShieldDatabase =
        HeatShieldDatabase.builder(context, databaseName).build()

    private fun record(owner: String, outdoor: Int) = WorkSession(
        ownerId = owner, title = "Planting beds", site = "Birrarung Marr", date = "28 Sep 2026",
        outdoorMinutes = outdoor, breakMinutes = 10, status = ShiftStatus.NONE
    )
}
