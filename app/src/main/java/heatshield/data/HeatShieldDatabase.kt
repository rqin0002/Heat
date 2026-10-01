package heatshield.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import heatshield.domain.HeatRules
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

@Dao
abstract class HeatShieldDao {
    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId ORDER BY id DESC")
    abstract fun observeSessions(ownerId: String): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId AND id = :id")
    abstract suspend fun getSession(ownerId: String, id: Int): WorkSession?

    @Insert
    protected abstract suspend fun insertSession(session: WorkSession): Long

    suspend fun saveSession(session: WorkSession): Long {
        require(session.id == 0) { "New sessions must use an automatically assigned ID." }
        validateSession(session)
        return insertSession(session)
    }

    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId AND id = :id")
    abstract fun observeSession(ownerId: String, id: Int): Flow<WorkSession?>

    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId AND startedAt IS NOT NULL AND endedAt IS NULL ORDER BY id DESC LIMIT 1")
    abstract suspend fun getOpenShift(ownerId: String): WorkSession?

    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId AND startedAt IS NOT NULL AND endedAt IS NULL ORDER BY id DESC LIMIT 1")
    abstract fun observeOpenShift(ownerId: String): Flow<WorkSession?>

    /** The owner's shifts with times (tracked, added by hand or preview), the open one included; legacy typed rows have none. */
    @Query("SELECT * FROM work_sessions WHERE ownerId = :ownerId AND startedAt IS NOT NULL")
    abstract suspend fun getTimedSessions(ownerId: String): List<WorkSession>

    @Update
    protected abstract suspend fun updateRow(session: WorkSession): Int

    /** Whole-row update of an owned shift. 0 when the id belongs to another owner or is gone. */
    @Transaction
    open suspend fun updateSession(ownerId: String, session: WorkSession): Int {
        require(ownerId.isNotBlank() && ownerId == session.ownerId && session.id > 0) { "Record owner does not match." }
        validateSession(session)
        return if (getSession(ownerId, session.id) == null) 0 else updateRow(session)
    }

    /** ShiftRepository is the only production caller. Room has no partial unique index, so one open shift per owner lives here. */
    @Transaction
    open suspend fun startShift(shift: WorkSession): Long {
        require(shift.id == 0 && shift.startedAt != null && shift.endedAt == null && shift.source == ShiftSource.TRACKED) { "Not an open tracked shift." }
        validateSession(shift)
        if (getOpenShift(shift.ownerId) != null) throw ShiftFailure(ShiftError.SHIFT_OPEN)
        return insertSession(shift)
    }

    @Query("DELETE FROM work_sessions WHERE ownerId = :ownerId AND id = :id")
    abstract suspend fun deleteSession(ownerId: String, id: Int): Int

    @Query("SELECT * FROM shift_events WHERE shiftId = :shiftId ORDER BY startedAt, id")
    abstract fun observeEvents(shiftId: Int): Flow<List<ShiftEvent>>

    @Query("SELECT * FROM shift_events WHERE shiftId = :shiftId ORDER BY startedAt, id")
    abstract suspend fun getEvents(shiftId: Int): List<ShiftEvent>

    @Insert
    protected abstract suspend fun insertEvent(event: ShiftEvent): Long

    /** One open break per shift. The shift must belong to [ownerId]. */
    @Transaction
    open suspend fun addEvent(ownerId: String, event: ShiftEvent): Long {
        getSession(ownerId, event.shiftId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        validateEvent(event)
        if (event.isBreak && event.endedAt == null && getEvents(event.shiftId).any { it.isBreak && it.endedAt == null }) throw ShiftFailure(ShiftError.BREAK_OPEN)
        return insertEvent(event)
    }

    @Query("UPDATE shift_events SET endedAt = :endedAt WHERE id = :eventId AND shiftId = :shiftId AND endedAt IS NULL")
    protected abstract suspend fun closeEventRow(shiftId: Int, eventId: Int, endedAt: Long): Int

    @Transaction
    open suspend fun closeEvent(ownerId: String, shiftId: Int, eventId: Int, endedAt: Long): Int {
        getSession(ownerId, shiftId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val event = getEvents(shiftId).firstOrNull { it.id == eventId } ?: throw ShiftFailure(ShiftError.NO_BREAK)
        require(endedAt >= event.startedAt) { "A break cannot end before it starts." }
        return closeEventRow(shiftId, eventId, endedAt)
    }

    @Update
    protected abstract suspend fun updateEventRow(event: ShiftEvent): Int

    /**
     * Fix times moves or re-answers an event only within its existing owned shift, preserving its other details.
     * Example: owner A's shift ID paired with owner B's event ID throws NO_BREAK rather than moving B's event.
     */
    @Transaction
    open suspend fun updateEvent(ownerId: String, event: ShiftEvent): Int {
        getSession(ownerId, event.shiftId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        if (getEvents(event.shiftId).none { it.id == event.id }) throw ShiftFailure(ShiftError.NO_BREAK)
        validateEvent(event)
        return updateEventRow(event)
    }

    /** Fix times removes a break row the gardener took out; "Can't stop yet" answers are history and stay. */
    @Query("DELETE FROM shift_events WHERE shiftId = :shiftId AND id = :eventId AND type != 'cant_stop'")
    abstract suspend fun deleteEvent(shiftId: Int, eventId: Int): Int

    /** End shift drops what came after the shift's real end, a "Can't stop yet" answer included (ShiftRepository.endShift). */
    @Query("DELETE FROM shift_events WHERE shiftId = :shiftId AND id = :eventId")
    abstract suspend fun dropEvent(shiftId: Int, eventId: Int): Int

    @Query("SELECT * FROM user_preferences WHERE ownerId = :ownerId")
    abstract fun observePreferences(ownerId: String): Flow<UserPreferences?>

    @Query("SELECT * FROM user_preferences WHERE ownerId = :ownerId")
    abstract suspend fun getPreferences(ownerId: String): UserPreferences?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPreferences(preferences: UserPreferences)

    suspend fun savePreferences(preferences: UserPreferences) {
        require(preferences.ownerId.isNotBlank())
        require(WorkContext(site = preferences.site, effort = preferences.effort, clothing = preferences.clothing).isValid)
        require(preferences.task.isNotBlank() && preferences.task.length <= 100)
        require(preferences.shiftStartMinute in 0..1439 &&
            preferences.shiftEndMinute in (preferences.shiftStartMinute + 60)..1439)
        require(HeatRules.isValidTrigger(preferences.hotDayTrigger))
        require(preferences.smokoMinute in 0..1439 && preferences.lunchMinute in 0..1439)
        require(preferences.breakRule == "heatshield" || preferences.breakRule == "vthc")
        insertPreferences(preferences)
    }

    @Query("SELECT * FROM shift_plans WHERE ownerId = :ownerId")
    abstract fun observePlan(ownerId: String): Flow<ShiftPlan?>

    @Query("SELECT * FROM shift_plans WHERE ownerId = :ownerId")
    abstract suspend fun getPlan(ownerId: String): ShiftPlan?

    @Query("DELETE FROM shift_plans WHERE ownerId = :ownerId")
    abstract suspend fun deletePlan(ownerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPlan(plan: ShiftPlan)

    suspend fun savePlan(plan: ShiftPlan) {
        require(plan.ownerId.isNotBlank() && plan.context.isValid && plan.revision >= 0)
        require(plan.reminderAt == null || plan.reminderAt > 0)
        insertPlan(plan)
    }

    @Query("SELECT * FROM forecast_cache WHERE site = :site")
    abstract fun observeForecast(site: String): Flow<ForecastCache?>

    @Query("SELECT * FROM forecast_cache WHERE site = :site")
    abstract suspend fun getForecast(site: String): ForecastCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveForecast(cache: ForecastCache)

    @Query("DELETE FROM forecast_cache WHERE site = :site")
    abstract suspend fun deleteForecast(site: String): Int

    @Query("SELECT * FROM work_sites ORDER BY name")
    abstract fun observeSites(): Flow<List<WorkSite>>

    @Query("SELECT * FROM work_sites ORDER BY name")
    abstract suspend fun getSites(): List<WorkSite>

    @Query("SELECT * FROM work_sites WHERE name = :name LIMIT 1")
    abstract suspend fun getSite(name: String): WorkSite?

    @Upsert
    protected abstract suspend fun upsertSites(sites: List<WorkSite>)

    suspend fun saveSites(sites: List<WorkSite>) {
        require(sites.all { it.isValid } && sites.map { it.id }.distinct().size == sites.size &&
            sites.map { it.name }.distinct().size == sites.size)
        upsertSites(sites)
    }

    private fun validateEvent(event: ShiftEvent) {
        require(event.shiftId > 0 && event.type in ShiftEventType.all) { "Unknown shift event." }
        require(event.endedAt == null || event.endedAt >= event.startedAt) { "A break cannot end before it starts." }
        require(event.reason == null || event.reason in BreakReason.all || event.reason in AlertReason.all) { "Unknown break reason." }
        require(event.type != ShiftEventType.ALERT || event.reason in AlertReason.all) { "An alert names its reason." }
        require(!event.skipped || event.type == ShiftEventType.SMOKO || event.type == ShiftEventType.LUNCH) { "Only smoko or lunch can be skipped." }
        require(event.isBreak || event.endedAt == event.startedAt) { "An answer or alert has no duration." }
    }

    private fun validateSession(session: WorkSession) {
        require(session.ownerId.isNotBlank() && session.title.isNotBlank() && session.title.length <= 100)
        require(session.site.isNotBlank() && session.site.length <= 100)
        require(session.outdoorMinutes in 0..1440 && session.breakMinutes in 0..1440 && session.outdoorMinutes + session.breakMinutes <= 1440)
        require(session.status in ShiftStatus.all) { "Unknown follow-up status." }
        require(session.source in ShiftSource.all) { "Unknown shift source." }
        require(session.endedAt == null || session.startedAt != null && session.endedAt >= session.startedAt) { "A shift cannot end before it starts." }
        require(session.checkInNote == null || session.checkInNote.length <= 1000)
        require(session.note == null || session.note.length <= 1000)
        LocalDate.parse(session.date, DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT))
    }
}

/** Adds the three built-in parks; a row already holding a park's id or name (a catalogue refresh) is kept. */
fun seedDefaultSites(db: SupportSQLiteDatabase) {
    WorkSite.defaults.forEach { site ->
        db.execSQL("INSERT OR IGNORE INTO work_sites (id, name, latitude, longitude) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(site.id, site.name, site.latitude, site.longitude))
    }
}

// Exported to app/schemas. Never change an entity at an exported version: bump the version and add an AutoMigration.
@Database(entities = [WorkSession::class, UserPreferences::class, ShiftPlan::class, ForecastCache::class, WorkSite::class, ShiftEvent::class],
    version = 3, exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = HeatShieldDatabase.Migration1To2::class),
        AutoMigration(from = 2, to = 3, spec = HeatShieldDatabase.Migration2To3::class)
    ])
abstract class HeatShieldDatabase : RoomDatabase() {
    abstract fun dao(): HeatShieldDao

    /** v1 → v2. New columns arrive with their @ColumnInfo defaults; this runs afterwards, in the same transaction. */
    class Migration1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // v1 caches have no daily max or sunrise/sunset; drop them so Today refetches instead of guessing.
            db.execSQL("DELETE FROM forecast_cache")
            seedDefaultSites(db)
            // Keep setupDone = false: v1 never asked the owner to choose a usual task.
            // Task 10 confirms the visible task once, preserving the existing park, clothing and reminders.
            // Until then Task 5 uses the preserved effort, never the new task column default.
        }
    }

    /**
     * v2 → v3. Columns and shift_events arrive with their defaults. Legacy statuses are remapped here, once: a
     * "Needs review" row has something to raise only when its note is a "No shade here" / "Can't stop yet" answer in
     * M1's wording or A2's ("No cooler place available", "Cannot pause yet"). Everything else, including "Reviewed",
     * becomes "none". Old typed-minute rows keep their minutes and read as source = manual with no times.
     */
    class Migration2To3 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""UPDATE work_sessions SET status = 'to_raise' WHERE status = 'Needs review' AND checkInNote IS NOT NULL AND (
                TRIM(checkInNote) LIKE 'No shade here.%' OR TRIM(checkInNote) LIKE 'Can''t stop yet.%' OR
                TRIM(checkInNote) LIKE 'No cooler place available%' OR TRIM(checkInNote) LIKE 'Cannot pause yet%')""")
            db.execSQL("UPDATE work_sessions SET status = 'none' WHERE status IN ('Needs review', 'Reviewed')")
        }
    }

    companion object {
        const val DATABASE_NAME = "heatshield.db"
        @Volatile private var instance: HeatShieldDatabase? = null
        private val seedSitesOnCreate = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedDefaultSites(db)
        }

        fun get(context: Context): HeatShieldDatabase = instance ?: synchronized(this) {
            instance ?: builder(context, DATABASE_NAME).build().also { instance = it }
        }

        /** The one production configuration; tests open it on their own file instead of the singleton. */
        fun builder(context: Context, name: String): RoomDatabase.Builder<HeatShieldDatabase> =
            Room.databaseBuilder(context.applicationContext, HeatShieldDatabase::class.java, name)
                .addCallback(seedSitesOnCreate)
    }
}
