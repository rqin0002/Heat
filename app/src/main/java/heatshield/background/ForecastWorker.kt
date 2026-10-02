package heatshield.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import heatshield.data.HeatShieldDatabase
import heatshield.data.ForecastSnapshot
import heatshield.data.WeatherRepository
import heatshield.data.WorkSite
import java.io.IOException
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

/**
 * Best-effort refresh, independent of private account data: the input names a park, never an account. AlertCoordinator
 * points it at the active owner's work park for today ([retarget]), so a plan for a new day is followed with the app
 * closed. Android may defer periodic work.
 */
class ForecastWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("site_id") ?: return Result.failure()
        val name = inputData.getString("site_name") ?: return Result.failure()
        val latitude = inputData.getDouble("latitude", Double.NaN)
        val longitude = inputData.getDouble("longitude", Double.NaN)
        if (id.isBlank() || name.isBlank() || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return Result.failure()
        val suppliedSite = WorkSite(id, name, latitude, longitude)
        if (!suppliedSite.isValid) return Result.failure()
        val site = HeatShieldDatabase.get(applicationContext).dao().getSite(name) ?: suppliedSite
        if (site.id != id) return Result.failure()
        val result = WeatherRepository(applicationContext).refresh(site, ForecastSnapshot.REFRESHED_BY_BACKGROUND)
        if (result.isSuccess) {
            // A fresher forecast can move the next break (or let a stale one time breaks again): the alarms and the
            // lock-screen timer follow it without the app being opened. reconcile() never throws.
            AlertCoordinator(applicationContext).reconcile()
            return Result.success()
        }
        val error = result.exceptionOrNull()
        val transient = error is IOException || error is HttpException &&
            (error.code() == 408 || error.code() == 429 || error.code() in 500..599)
        return if (transient && runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val UNIQUE_WORK = "heatshield_forecast"
        /** Where [enqueue] notes the park this work refreshes, so [retarget] can tell a new park from the same one. */
        private const val PREFS = "heatshield_forecast_work"
        private const val KEY_TARGET = "target"

        @Synchronized
        fun enqueue(context: Context, site: WorkSite) {
            val input = Data.Builder().putString("site_id", site.id).putString("site_name", site.name)
                .putDouble("latitude", site.latitude).putDouble("longitude", site.longitude).build()
            val request = PeriodicWorkRequestBuilder<ForecastWorker>(30, TimeUnit.MINUTES)
                .setInputData(input)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            target(context).edit().putString(KEY_TARGET, targetOf(site)).apply()
        }

        /**
         * Points the periodic refresh at [site] unless it already refreshes that park; true when it moved. Every reconcile
         * asks (each write, alarm and refresh), and each [enqueue] replaces the work's input as a new WorkManager
         * generation, so the same park is left alone.
         * Example: the work refreshes Birrarung Marr and today's plan names Royal Park. retarget(Royal Park) enqueues and
         * returns true; the next retarget(Royal Park) returns false and changes nothing.
         */
        @Synchronized
        fun retarget(context: Context, site: WorkSite): Boolean {
            if (target(context).getString(KEY_TARGET, null) == targetOf(site)) return false
            enqueue(context, site)
            return true
        }

        @Synchronized
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
            target(context).edit().remove(KEY_TARGET).apply()
        }

        private fun target(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The park as the worker checks it, id and name; it re-reads the coordinates from Room when it runs. */
        private fun targetOf(site: WorkSite) = "${site.id}|${site.name}"
    }
}
