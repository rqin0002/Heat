package heatshield.ui

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import heatshield.background.ForecastWorker
import heatshield.background.ReminderScheduler
import heatshield.data.*
import heatshield.domain.ContextEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screen state and user actions; storage, network and reasoning live outside Compose. */
class HeatShieldViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HeatShieldDatabase.get(application)
    private val dao = database.dao()
    private val weather = WeatherRepository(application)
    private val auth = AuthRepository(application)
    private val reminder = ReminderScheduler(application)
    private val device = application.getSharedPreferences("heatshield_session", 0)
    private var ownerJob: Job? = null
    private var forecastJob: Job? = null
    private var replayJob: Job? = null
    private var ownerGeneration = 0
    private var replayRows: List<SensorReading> = emptyList()

    val firebaseConfigured get() = auth.configured
    var ownerId by mutableStateOf<String?>(null); private set
    var email by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var sessions by mutableStateOf<List<WorkSession>>(emptyList()); private set
    var preferences by mutableStateOf(UserPreferences(ownerId = "local")); private set
    var needsOnboarding by mutableStateOf(false); private set
    var plan by mutableStateOf<ShiftPlan?>(null); private set
    var forecast by mutableStateOf<ForecastSnapshot?>(null); private set
    var refreshing by mutableStateOf(false); private set
    var sites by mutableStateOf(WorkSite.defaults); private set
    var catalogueMessage by mutableStateOf("Bundled worksite catalogue"); private set
    var reading by mutableStateOf<SensorReading?>(null); private set
    var replayIndex by mutableStateOf(0); private set
    var replayEnabled by mutableStateOf(true); private set
    var emittedAt by mutableStateOf(0L); private set
    var now by mutableStateOf(System.currentTimeMillis()); private set
    var elapsedNow by mutableStateOf(SystemClock.elapsedRealtime()); private set
    // Monotonic time survives process recreation on the same boot, and ignores wall-clock edits.
    var breakStartedAt by mutableStateOf(0L); private set
    var breakSessionId by mutableStateOf(0); private set
    val replayCount get() = replayRows.size
    val breakElapsedSeconds get() = ((elapsedNow - breakStartedAt) / 1000).coerceAtLeast(0)
    val context get() = plan?.context?.takeIf { it.date == LocalDate.now(MELBOURNE) }
        ?: WorkContext(site = preferences.site, effort = preferences.effort, clothing = preferences.clothing)
    val plannedContext get() = plan?.context ?: context
    val activeSession get() = sessions.firstOrNull {
        it.date == LocalDate.now(MELBOURNE).format(RECORD_DATE) && it.site == context.site
    }
    val decision get() = ContextEngine.evaluate(reading, context, activeSession?.outdoorMinutes ?: 0,
        activeSession?.checkInNote?.takeIf { activeSession?.status == "Needs review" }.orEmpty(), now)

    init {
        switchOwner(auth.user.value?.id ?: if (device.getBoolean("local", false)) "local" else null)
        viewModelScope.launch {
            auth.user.collect { user ->
                email = user?.email
                val next = user?.id ?: if (device.getBoolean("local", false)) "local" else null
                if (next != ownerId) switchOwner(next)
            }
        }
        viewModelScope.launch {
            try {
                replayRows = SensorReplay(application).load()
                jumpReplayToShift()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "Sensor replay unavailable: ${error.message}"
            }
        }
        viewModelScope.launch {
            dao.observeSites().collect { cached ->
                sites = (cached + WorkSite.defaults).distinctBy { it.id }
                if (ownerId != null) resolveSite(context.site)?.let { ForecastWorker.enqueue(getApplication(), it) }
            }
        }
    }

    private fun switchOwner(next: String?) {
        ownerGeneration++
        ownerJob?.cancel(); forecastJob?.cancel()
        ownerId?.let(reminder::cancelForOwner)
        ownerId = next
        sessions = emptyList(); plan = null; forecast = null; message = null; busy = false
        catalogueMessage = "Bundled and cached worksite catalogue"
        preferences = UserPreferences(ownerId = next ?: "local")
        needsOnboarding = false; loading = next != null
        reminder.activateOwner(next)
        ForecastWorker.cancel(getApplication())
        breakStartedAt = if (next == null) 0 else device.getLong("break_start_$next", 0)
        breakSessionId = if (next == null) 0 else device.getInt("break_session_$next", 0)
        if (next != null && (device.getInt("break_boot_$next", -1) != bootCount() || breakStartedAt > SystemClock.elapsedRealtime())) clearBreak()
        if (next == null) { setVisible(false); return }
        ownerJob = viewModelScope.launch {
            combine(dao.observeSessions(next), dao.observePreferences(next), dao.observePlan(next)) { records, prefs, savedPlan ->
                Triple(records, prefs, savedPlan)
            }.collect { (records, prefs, savedPlan) ->
                sessions = records
                preferences = prefs ?: UserPreferences(ownerId = next)
                needsOnboarding = prefs == null
                plan = savedPlan
                loading = false
                val siteName = context.site
                observeForecast(siteName)
                if (preferences.reminders && savedPlan != null) reminder.schedule(savedPlan)
                else reminder.cancelForOwner(next)
            }
        }
        loadSites()
    }

    private var observedSite: String? = null
    private fun observeForecast(siteName: String) {
        if (forecastJob?.isActive == true && observedSite == siteName) return
        observedSite = siteName
        forecastJob?.cancel(); forecast = null
        forecastJob = viewModelScope.launch { weather.observe(siteName).collect { forecast = it } }
        resolveSite(siteName)?.let { ForecastWorker.enqueue(getApplication(), it) }
        refreshForecast(siteName)
    }

    fun enterLocal() {
        if (busy) return
        auth.signOut()
        device.edit().putBoolean("local", true).apply()
        switchOwner("local")
    }

    fun authenticate(email: String, password: String, create: Boolean) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            val result = if (create) auth.signUp(email, password) else auth.signIn(email, password)
            result.fold(onSuccess = {
                device.edit().putBoolean("local", false).apply()
                this@HeatShieldViewModel.email = it.email
                if (ownerId != it.id) switchOwner(it.id)
            }, onFailure = { message = it.message ?: "Authentication failed. Try again." })
            busy = false
        }
    }

    fun resetPassword(email: String) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            auth.resetPassword(email).fold(
                onSuccess = { message = "If the account is eligible, password reset instructions will arrive by email." },
                onFailure = { message = it.message ?: "Could not request a password reset." })
            busy = false
        }
    }

    fun signOut() {
        if (busy) return
        clearBreak()
        device.edit().putBoolean("local", false).apply()
        auth.signOut()
        email = null
        switchOwner(null)
    }

    fun dismissMessage() { message = null }

    fun savePreferences(site: String, effort: String, clothing: String, reminders: Boolean, done: () -> Unit = {}) = ownedAction(done) { owner ->
        require(resolveSite(site) != null) { "Select a known worksite." }
        val invalidatePlan = plan?.let { it.site != site || it.effort != effort || it.clothing != clothing } == true
        database.withTransaction {
            dao.savePreferences(UserPreferences(owner, site, effort, clothing, reminders))
            // Site or work-context changes invalidate the previous accepted comparison.
            if (invalidatePlan) dao.deletePlan(owner)
        }
        if (invalidatePlan) reminder.cancelForOwner(owner)
    }

    fun saveRecord(session: WorkSession, done: () -> Unit) = ownedAction(done) { owner ->
        require(session.outdoorMinutes in 1..960 && session.breakMinutes in 0..480 &&
            session.outdoorMinutes + session.breakMinutes <= 1440) { "Check the logged minutes." }
        if (session.id == 0) dao.saveSession(session.copy(ownerId = owner))
        else require(dao.updateSession(owner, session.copy(ownerId = owner)) == 1) { "This record no longer exists." }
    }

    fun deleteRecord(id: Int, done: () -> Unit) = ownedAction(done) { owner ->
        val activeDeleted = activeSession?.id == id
        require(dao.deleteSession(owner, id) == 1) { "This record no longer exists." }
        if (ownerId == owner && breakSessionId == id) clearBreak()
        if (activeDeleted) cancelPlanReminder(owner)
    }

    fun savePlan(window: String, selected: WorkContext, done: () -> Unit) = ownedAction(done) { owner ->
        val selectedWindow = ContextEngine.compare(selected, forecast, System.currentTimeMillis()).find { it.label == window }
        require(selectedWindow != null) { "Conditions changed. Refresh and compare the windows again." }
        val date = Instant.ofEpochMilli(selected.dayMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val reminderAt = date.atTime(selectedWindow.startHour, 0).atZone(MELBOURNE).toInstant().toEpochMilli()
        val next = ShiftPlan(owner, selected.work, selected.effort, selected.clothing,
            selected.dayMillis, selected.startHour, selected.startMinute, selected.site,
            window, reminderAt, maxOf(System.currentTimeMillis(), (plan?.revision ?: 0L) + 1))
        dao.savePlan(next)
    }

    fun cancelPlan() = ownedAction({}) { owner -> dao.deletePlan(owner); reminder.cancelForOwner(owner) }

    fun startBreak() {
        val active = activeSession ?: return
        if (breakStartedAt != 0L) return
        breakStartedAt = SystemClock.elapsedRealtime(); elapsedNow = breakStartedAt; breakSessionId = active.id
        ownerId?.let { owner -> device.edit().putLong("break_start_$owner", breakStartedAt)
            .putInt("break_session_$owner", active.id).putInt("break_boot_$owner", bootCount()).apply() }
    }

    fun recordResponse(completed: Boolean, response: String, done: () -> Unit) = ownedAction(done) { owner ->
        val sessionId = if (completed) breakSessionId else activeSession?.id
        require(sessionId != null && sessionId > 0) { "This session no longer exists. Add today's work record." }
        require(!completed || breakStartedAt > 0) { "Start a break before recording its duration." }
        val seconds = if (completed) ((SystemClock.elapsedRealtime() - breakStartedAt) / 1000).coerceAtLeast(0) else 0
        val addedMinutes = (seconds / 60).toInt()
        val note = if (completed) "Break measured for $seconds seconds; $addedMinutes whole minutes added. Follow workplace guidance."
            else "$response. Discuss a lighter task or a cooler location."
        database.withTransaction {
            val active = requireNotNull(dao.getSession(owner, sessionId)) { "This record no longer exists." }
            require(active.breakMinutes + addedMinutes <= 480 && active.outdoorMinutes + active.breakMinutes + addedMinutes <= 1440) {
                "This timer exceeds the daily record limit. Edit the logged minutes instead."
            }
            require(dao.updateSession(owner, active.copy(status = if (completed) "Reviewed" else "Needs review",
                breakMinutes = active.breakMinutes + addedMinutes, checkInNote = note)) == 1)
        }
        if (completed) { if (ownerId == owner) clearBreak(); cancelPlanReminder(owner) }
    }

    private suspend fun cancelPlanReminder(owner: String) {
        dao.getPlan(owner)?.let { dao.savePlan(it.copy(reminderAt = null, revision = maxOf(System.currentTimeMillis(), it.revision + 1))) }
        reminder.cancelForOwner(owner)
    }

    fun clearBreak() {
        ownerId?.let { device.edit().remove("break_start_$it").remove("break_session_$it").remove("break_boot_$it").apply() }
        breakStartedAt = 0; breakSessionId = 0
    }

    fun refreshForecast(siteName: String = context.site) {
        val site = resolveSite(siteName) ?: return
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try { weather.refresh(site) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "Could not store the forecast. Check available device storage and retry."
            } finally { refreshing = false }
        }
    }

    fun previewForecast(siteName: String) {
        if (observedSite == siteName && forecastJob?.isActive == true) { refreshForecast(siteName); return }
        observedSite = siteName
        forecastJob?.cancel(); forecast = null
        forecastJob = viewModelScope.launch { weather.observe(siteName).collect { forecast = it } }
        refreshForecast(siteName)
    }

    fun loadSites() {
        val owner = ownerId
        if (owner == null || owner == "local") return
        viewModelScope.launch {
            val result = SiteRepository(getApplication()).load()
            if (owner != ownerId) return@launch
            result.fold(onSuccess = {
                catalogueMessage = "Shared worksite catalogue loaded"
            }, onFailure = { catalogueMessage = "Using bundled sites. ${it.message.orEmpty()}" })
        }
    }

    private fun resolveSite(name: String) = sites.find { it.name == name }

    fun setVisible(value: Boolean) {
        replayJob?.cancel()
        if (!value || ownerId == null) return
        viewModelScope.launch { reminder.restoreForActiveOwner() }
        replayJob = viewModelScope.launch {
            var lastReplay = System.currentTimeMillis()
            while (true) {
                now = System.currentTimeMillis()
                elapsedNow = SystemClock.elapsedRealtime()
                if (replayEnabled && now - lastReplay >= 20_000) {
                    emitReading(replayIndex + 1); lastReplay = now
                }
                delay(1_000)
            }
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun toggleReplay() { replayEnabled = !replayEnabled }
    fun advanceReplay() = emitReading(replayIndex + 1)
    fun jumpReplayToShift() {
        val index = replayRows.indexOfFirst {
            java.time.OffsetDateTime.parse(it.observedAtLocal).hour >= context.startHour
        }.coerceAtLeast(0)
        emitReading(index)
    }
    private fun emitReading(index: Int) {
        if (replayRows.isEmpty()) return
        replayIndex = index.mod(replayRows.size)
        emittedAt = System.currentTimeMillis()
        reading = replayRows[replayIndex].copy(emittedAt = emittedAt)
    }

    private fun ownedAction(done: () -> Unit, block: suspend (String) -> Unit) {
        val owner = ownerId ?: return
        if (busy) return
        val generation = ownerGeneration
        busy = true; message = null
        viewModelScope.launch {
            try {
                block(owner)
                if (generation == ownerGeneration) done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == ownerGeneration) message = error.message ?: "Could not save. Please try again."
            } finally { if (generation == ownerGeneration) busy = false }
        }
    }

    override fun onCleared() { auth.close(); super.onCleared() }

    companion object {
        val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
        val RECORD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
    }
}
