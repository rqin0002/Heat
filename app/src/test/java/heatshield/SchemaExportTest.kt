package heatshield

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exported v1 schema is the baseline that every later AutoMigration and migration test starts from. */
class SchemaExportTest {
    private val moduleDir = File(System.getProperty("user.dir") ?: ".").let { dir -> File(dir, "app").takeIf { it.isDirectory } ?: dir }
    private val schemaDir = File(moduleDir, "schemas/heatshield.data.HeatShieldDatabase")

    @Test
    fun versionOneSchemaIsExportedWithTheFiveExistingTables() {
        val file = File(schemaDir, "1.json")
        assertTrue("Missing ${file.path}: build with the Room plugin and exportSchema = true", file.isFile)
        val database = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("database")
        assertEquals(1, database.get("version").asInt)
        // Room stored this hash in every installed v1 database; a different value means an entity changed at version 1.
        assertEquals("c2c50856f05dd642b13f85275d7b3a82", database.get("identityHash").asString)
        val entities = database.getAsJsonArray("entities")
        assertEquals(5, entities.size())
        assertEquals(setOf("work_sessions", "user_preferences", "shift_plans", "forecast_cache", "work_sites"),
            entities.map { (it as JsonObject).get("tableName").asString }.toSet())
    }

    @Test
    fun versionThreeSchemaAddsShiftColumnsAndTheEventsTable() {
        val file = File(schemaDir, "3.json")
        assertTrue("Missing ${file.path}: build :app:kspDebugKotlin after bumping the version", file.isFile)
        val database = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("database")
        assertEquals(3, database.get("version").asInt)
        val entities = database.getAsJsonArray("entities").map { it.asJsonObject }
        assertEquals(setOf("work_sessions", "user_preferences", "shift_plans", "forecast_cache", "work_sites", "shift_events"),
            entities.map { it.get("tableName").asString }.toSet())
        val sessions = entities.first { it.get("tableName").asString == "work_sessions" }
        val columns = sessions.getAsJsonArray("fields").map { it.asJsonObject.get("columnName").asString }.toSet()
        assertTrue(columns.containsAll(listOf("startedAt", "endedAt", "plannedEndAt", "source", "heavyWork", "heavyClothing", "peakHeat",
            "peakLevel", "normalMinutes", "warmMinutes", "hotMinutes", "veryHotMinutes", "extremeMinutes", "longestStretchMinutes",
            "autoEnded", "startedElapsed", "bootCount", "note")))
        val events = entities.first { it.get("tableName").asString == "shift_events" }
        val foreignKey = events.getAsJsonArray("foreignKeys").single().asJsonObject
        assertEquals("work_sessions", foreignKey.get("table").asString)
        assertEquals("CASCADE", foreignKey.get("onDelete").asString)
        // v1 and v2 stay frozen: AutoMigration diffs them against v3.
        assertEquals("c2c50856f05dd642b13f85275d7b3a82", JsonParser.parseString(File(schemaDir, "1.json").readText()).asJsonObject.getAsJsonObject("database").get("identityHash").asString)
        assertEquals("f2fff8a0ecd63799aa5c3d8046e0d82c", JsonParser.parseString(File(schemaDir, "2.json").readText()).asJsonObject.getAsJsonObject("database").get("identityHash").asString)
    }
}
