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
import heatshield.data.WeatherRepository
import heatshield.data.WorkSite
import java.io.IOException
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

/** Best-effort refresh, independent of private account data. Android may defer periodic work. */
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
        val result = WeatherRepository(applicationContext).refresh(site)
        if (result.isSuccess) return Result.success()
        val error = result.exceptionOrNull()
        val transient = error is IOException || error is HttpException &&
            (error.code() == 408 || error.code() == 429 || error.code() in 500..599)
        return if (transient && runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val UNIQUE_WORK = "heatshield_forecast"

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
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
