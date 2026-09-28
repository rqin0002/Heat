package heatshield.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

    @Query("""UPDATE work_sessions SET title = :title, site = :site, date = :date,
        outdoorMinutes = :outdoorMinutes, breakMinutes = :breakMinutes, status = :status,
        checkInNote = :checkInNote WHERE ownerId = :ownerId AND id = :id""")
    protected abstract suspend fun updateOwnedSession(
        ownerId: String, id: Int, title: String, site: String, date: String,
        outdoorMinutes: Int, breakMinutes: Int, status: String, checkInNote: String?
    ): Int

    suspend fun updateSession(ownerId: String, session: WorkSession): Int {
        require(ownerId.isNotBlank() && ownerId == session.ownerId && session.id > 0) { "Record owner does not match." }
        validateSession(session)
        return updateOwnedSession(ownerId, session.id, session.title, session.site, session.date,
            session.outdoorMinutes, session.breakMinutes, session.status, session.checkInNote)
    }

    @Query("DELETE FROM work_sessions WHERE ownerId = :ownerId AND id = :id")
    abstract suspend fun deleteSession(ownerId: String, id: Int): Int

    @Query("SELECT * FROM user_preferences WHERE ownerId = :ownerId")
    abstract fun observePreferences(ownerId: String): Flow<UserPreferences?>

    @Query("SELECT * FROM user_preferences WHERE ownerId = :ownerId")
    abstract suspend fun getPreferences(ownerId: String): UserPreferences?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPreferences(preferences: UserPreferences)

    suspend fun savePreferences(preferences: UserPreferences) {
        require(preferences.ownerId.isNotBlank())
        require(WorkContext(site = preferences.site, effort = preferences.effort, clothing = preferences.clothing).isValid)
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

    private fun validateSession(session: WorkSession) {
        require(session.ownerId.isNotBlank() && session.title.isNotBlank() && session.title.length <= 100)
        require(session.site.isNotBlank() && session.site.length <= 100)
        require(session.outdoorMinutes in 0..1440 && session.breakMinutes in 0..1440 &&
            session.outdoorMinutes + session.breakMinutes <= 1440)
        require(session.status in listOf("Needs review", "Reviewed"))
        require(session.checkInNote == null || session.checkInNote.length <= 1000)
        LocalDate.parse(session.date, DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT))
    }
}

@Database(entities = [WorkSession::class, UserPreferences::class, ShiftPlan::class, ForecastCache::class, WorkSite::class],
    version = 1, exportSchema = false)
abstract class HeatShieldDatabase : RoomDatabase() {
    abstract fun dao(): HeatShieldDao

    companion object {
        @Volatile private var instance: HeatShieldDatabase? = null

        fun get(context: Context): HeatShieldDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HeatShieldDatabase::class.java,
                "heatshield.db").build().also { instance = it }
        }
    }
}
