package heatshield

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.HeatShieldDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the exported v1 schema reaches the test APK and matches the database users already have on their phones. */
@RunWith(AndroidJUnit4::class)
class SchemaAssetTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "schema-asset-test-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HeatShieldDatabase::class.java)

    @After
    fun delete() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun versionOneSchemaAssetBuildsADatabaseRoomAccepts() = runBlocking {
        helper.createDatabase(databaseName, 1).use { db ->
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue("Tables in v1: $tables", tables.containsAll(listOf("work_sessions", "user_preferences",
                "shift_plans", "forecast_cache", "work_sites")))
            db.execSQL("""INSERT INTO user_preferences (ownerId, site, effort, clothing, reminders)
                VALUES ('local', 'Royal Park', 'Moderate effort', 'Standard workwear', 0)""")
        }
        val database = Room.databaseBuilder(context, HeatShieldDatabase::class.java, databaseName).build()
        try {
            assertEquals("Royal Park", database.dao().getPreferences("local")?.site)
        } finally {
            database.close()
        }
    }
}
