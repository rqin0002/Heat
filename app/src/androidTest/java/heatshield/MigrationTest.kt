package heatshield

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.HeatShieldDatabase
import heatshield.data.ShiftPlan
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.UserPreferences
import heatshield.data.WorkSession
import heatshield.domain.ContextReasoner
import heatshield.data.WorkSite
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades real version 1 and version 2 files the way every A2 and M1 install reaches M2, for several owners at once. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HeatShieldDatabase::class.java)
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "migration-test-${UUID.randomUUID()}.db"
    private val day = LocalDate.of(2026, 1, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val v1Preferences = listOf(
        UserPreferences("local", "Carlton Gardens", "Light effort", "Heavy protective clothing", reminders = true),
        UserPreferences("uid-abc", "Royal Park", "Heavy effort", "Standard workwear", reminders = false),
        UserPreferences("uid-moderate", "Birrarung Marr", "Moderate effort", "Standard workwear", reminders = true)
    )
    private val v1Sessions = listOf(
        WorkSession(1, "local", "Pruning roses", "Carlton Gardens", "8 Jan 2026", 120, 15, "Reviewed"),
        WorkSession(2, "local", "Mowing", "Carlton Gardens", "9 Jan 2026", 90, 5, "Needs review",
            "Cannot pause yet. Discuss a lighter task or a cooler location."),
        WorkSession(3, "uid-abc", "Planting beds", "Royal Park", "8 Jan 2026", 200, 30, "Reviewed",
            "Break measured for 600 seconds; 10 whole minutes added. Follow workplace guidance."),
        WorkSession(4, "uid-abc", "Litter run", "Royal Park", "9 Jan 2026", 45, 0, "Needs review")
    )
    private val v1Plan = ShiftPlan(ownerId = "uid-abc", work = "Mowing", effort = "Heavy effort",
        clothing = "Standard workwear", dayMillis = day, startHour = 8, startMinute = 30, site = "Royal Park",
        window = "09:00–11:00", reminderAt = day + 9 * 3_600_000L, revision = 4)
    // A catalogue refresh moved Royal Park before the upgrade; seeding must never overwrite it.
    private val refreshedRoyalPark = WorkSite("royal-park", "Royal Park", -37.7905, 144.9515)

    @After
    fun deleteFile() { context.deleteDatabase(databaseName) }

    @Test
    fun version1UpgradeKeepsAllOwnersEffortPendingTaskConfirmationAndRemapsStatus() = runBlocking<Unit> {
        helper.createDatabase(databaseName, 1).use { seedVersion1(it) }

        val migrated = helper.runMigrationsAndValidate(databaseName, 3, true)
        val expectedSites = listOf(WorkSite.defaults[0], WorkSite.defaults[1], refreshedRoyalPark)
        migrated.query("SELECT COUNT(*) FROM forecast_cache").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT id, name, latitude, longitude FROM work_sites ORDER BY name").use { cursor ->
            val sites = buildList {
                while (cursor.moveToNext()) add(WorkSite(cursor.getString(0), cursor.getString(1),
                    cursor.getDouble(2), cursor.getDouble(3)))
            }
            assertEquals(expectedSites, sites)
        }
        migrated.query("SELECT COUNT(*) FROM shift_events").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        migrated.close()

        // The production builder must open the migrated file as-is: no second migration, no reseeding.
        val database = HeatShieldDatabase.builder(context, databaseName).build()
        try {
            val dao = database.dao()
            v1Preferences.forEach { v1 ->
                val upgraded = requireNotNull(dao.getPreferences(v1.ownerId))
                assertEquals(v1, upgraded)
                assertEquals(v1.effort, upgraded.effort)
                assertEquals(v1.effort, ContextReasoner.usualEffort(upgraded).label)
                assertEquals(v1.effort == "Heavy effort", ContextReasoner.profileOf(upgraded, LocalDate.of(2026, 1, 9)).factors.heavyWork)
                assertEquals("Digging & planting", upgraded.task)
                assertEquals(420, upgraded.shiftStartMinute)
                assertEquals(900, upgraded.shiftEndMinute)
                assertEquals(35.0, upgraded.hotDayTrigger, 0.0)
                assertNull(upgraded.acclimatisingUntil)
                assertFalse(upgraded.setupDone)
                assertEquals(570, upgraded.smokoMinute); assertEquals(720, upgraded.lunchMinute); assertEquals("heatshield", upgraded.breakRule)
            }
            // Legacy "Needs review" rows carry nothing to raise unless an answer note says so; "Reviewed" never did.
            val expected = v1Sessions.map { s -> s.copy(status = if (s.id == 2) ShiftStatus.TO_RAISE else ShiftStatus.NONE, source = ShiftSource.MANUAL) }
            v1Preferences.forEach { v1 ->
                assertEquals(expected.filter { it.ownerId == v1.ownerId }.sortedByDescending { it.id }, dao.observeSessions(v1.ownerId).first())
            }
            assertEquals(v1Plan, dao.getPlan("uid-abc"))
            assertEquals(900, dao.getPlan("uid-abc")?.endMinute)
            assertNull(dao.getPlan("local"))
            assertNull(dao.getForecast("Royal Park"))
            assertEquals(expectedSites, dao.getSites())
            // Upgraded rows already satisfy the v2 validation, so the owner's next save goes through.
            val edited = requireNotNull(dao.getPreferences("local")).copy(task = "Push mowing", smokoMinute = 600)
            dao.savePreferences(edited)
            assertEquals(edited, dao.getPreferences("local"))
        } finally {
            database.close()
        }
    }

    @Test
    fun version2UpgradeRemapsEveryLegacyStatusFormForTwoOwnersAndAddsShiftEvents() = runBlocking<Unit> {
        // Every stored form an M1 phone can hold: both A2 answers, both M1 answers, a timed break, a hand flag, nothing.
        val rows = listOf(
            WorkSession(1, "local", "Mowing", "Birrarung Marr", "1 Oct 2026", 60, 5, "Needs review", "No shade here. Raise it with your team leader."),
            WorkSession(2, "local", "Mowing", "Birrarung Marr", "2 Oct 2026", 60, 5, "Needs review", "Can't stop yet. Raise it with your team leader."),
            WorkSession(3, "local", "Mowing", "Birrarung Marr", "3 Oct 2026", 60, 5, "Needs review", "  No cooler place available. Discuss a lighter task or a cooler location."),
            WorkSession(4, "uid-x", "Pruning", "Royal Park", "1 Oct 2026", 60, 5, "Needs review", "Cannot pause yet. Discuss a lighter task or a cooler location."),
            WorkSession(5, "uid-x", "Pruning", "Royal Park", "2 Oct 2026", 60, 5, "Needs review", "Break timed: 12 min"),
            WorkSession(6, "uid-x", "Pruning", "Royal Park", "3 Oct 2026", 60, 5, "Needs review", null),
            WorkSession(7, "uid-x", "Pruning", "Royal Park", "4 Oct 2026", 60, 5, "Reviewed", "No shade here. Raise it with your team leader.")
        )
        helper.createDatabase(databaseName, 2).use { db ->
            listOf("local", "uid-x").forEach { owner ->
                db.execSQL("""INSERT INTO user_preferences (ownerId, site, effort, clothing, reminders, task, shiftStartMinute, shiftEndMinute,
                    hotDayTrigger, acclimatisingUntil, setupDone) VALUES (?, 'Birrarung Marr', 'Light effort', 'Standard workwear', 0,
                    'Irrigation & checks', 420, 900, 38.0, NULL, 1)""", arrayOf<Any?>(owner))
            }
            rows.forEach { s ->
                db.execSQL("""INSERT INTO work_sessions (id, ownerId, title, site, date, outdoorMinutes, breakMinutes, status, checkInNote)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    arrayOf<Any?>(s.id, s.ownerId, s.title, s.site, s.date, s.outdoorMinutes, s.breakMinutes, s.status, s.checkInNote))
            }
        }
        val migrated = helper.runMigrationsAndValidate(databaseName, 3, true)
        migrated.query("SELECT id, status, source, startedAt FROM work_sessions ORDER BY id").use { cursor ->
            val statuses = buildMap { while (cursor.moveToNext()) { put(cursor.getInt(0), cursor.getString(1)); assertEquals("manual", cursor.getString(2)); assertTrue(cursor.isNull(3)) } }
            assertEquals(mapOf(1 to "to_raise", 2 to "to_raise", 3 to "to_raise", 4 to "to_raise", 5 to "none", 6 to "none", 7 to "none"), statuses)
        }
        migrated.query("PRAGMA foreign_key_list(shift_events)").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("work_sessions", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
        }
        migrated.close()

        val database = HeatShieldDatabase.builder(context, databaseName).build()
        try {
            val dao = database.dao()
            assertEquals(listOf(3, 2, 1), dao.observeSessions("local").first().map { it.id })
            assertEquals(ShiftStatus.TO_RAISE, dao.getSession("local", 1)?.status)
            assertEquals(ShiftStatus.NONE, dao.getSession("uid-x", 7)?.status)
            assertNull(dao.getOpenShift("local"))
            assertEquals(38.0, requireNotNull(dao.getPreferences("uid-x")).hotDayTrigger, 0.0)
            assertTrue(dao.getEvents(1).isEmpty())
        } finally { database.close() }
    }

    private fun seedVersion1(db: SupportSQLiteDatabase) {
        v1Preferences.forEach { p ->
            db.execSQL("INSERT INTO user_preferences (ownerId, site, effort, clothing, reminders) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(p.ownerId, p.site, p.effort, p.clothing, if (p.reminders) 1 else 0))
        }
        v1Sessions.forEach { s ->
            db.execSQL("""INSERT INTO work_sessions (id, ownerId, title, site, date, outdoorMinutes, breakMinutes,
                status, checkInNote) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(s.id, s.ownerId, s.title, s.site, s.date, s.outdoorMinutes, s.breakMinutes, s.status,
                    s.checkInNote))
        }
        db.execSQL("""INSERT INTO shift_plans (ownerId, work, effort, clothing, dayMillis, startHour, startMinute,
            site, `window`, reminderAt, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(v1Plan.ownerId, v1Plan.work, v1Plan.effort, v1Plan.clothing, v1Plan.dayMillis,
                v1Plan.startHour, v1Plan.startMinute, v1Plan.site, v1Plan.window, v1Plan.reminderAt, v1Plan.revision))
        db.execSQL("""INSERT INTO forecast_cache (site, fetchedAt, hoursJson, lastAttempt, outcome, error)
            VALUES (?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("Royal Park", 1_000L,
                """[{"time":"2026-01-09T09:00","temperature":31.0,"humidity":30.0,"uv":8.0}]""",
                1_000L, "success", null))
        db.execSQL("INSERT INTO work_sites (id, name, latitude, longitude) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(refreshedRoyalPark.id, refreshedRoyalPark.name, refreshedRoyalPark.latitude,
                refreshedRoyalPark.longitude))
    }
}
