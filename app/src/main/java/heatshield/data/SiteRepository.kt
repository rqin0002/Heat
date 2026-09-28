package heatshield.data

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** An authenticated, read-only catalogue. Work records and account details never enter Firestore. */
class SiteRepository(context: Context) {
    private val app = firebaseAppOrNull(context)
    private val database = HeatShieldDatabase.get(context)

    suspend fun load(): Result<List<WorkSite>> = try {
        val configuredApp = checkNotNull(app) {
            "The shared work-site catalogue is not configured. Built-in sites are available locally."
        }
        check(FirebaseAuth.getInstance(configuredApp).currentUser != null) {
            "Sign in to a cloud account to refresh the shared work-site catalogue."
        }
        val snapshot = FirebaseFirestore.getInstance(configuredApp)
            .collection("worksites").limit(100).get(Source.SERVER).await()
        val sites = snapshot.documents.map { document ->
            val id = document.id
            val name = document.getString("name")?.trim().orEmpty()
            val latitude = (document.get("latitude") as? Number)?.toDouble()
            val longitude = (document.get("longitude") as? Number)?.toDouble()
            require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}")) && name.length in 1..100 &&
                latitude != null && latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude != null && longitude.isFinite() && longitude in -180.0..180.0) {
                "The shared catalogue contains an invalid work site. Built-in sites remain available."
            }
            WorkSite(id, name, latitude, longitude)
        }.sortedBy { it.name }
        require(sites.map { it.name }.distinct().size == sites.size) {
            "The shared catalogue has duplicate site names. Built-in sites remain available."
        }
        database.withTransaction {
            val dao = database.dao()
            val existing = (WorkSite.defaults + dao.getSites()).associateBy { it.id }
            val existingNames = existing.values.associateBy { it.name }
            sites.forEach { site ->
                val previous = existing[site.id]
                require(previous == null || previous.name == site.name) {
                    "A work-site identity changed. Existing records retain their saved site."
                }
                require(existingNames[site.name]?.id.let { it == null || it == site.id }) {
                    "The shared catalogue reuses an existing site name with a different identity."
                }
                if (previous != null && (previous.latitude != site.latitude || previous.longitude != site.longitude)) {
                    dao.deleteForecast(site.name)
                }
            }
            dao.saveSites(sites)
        }
        Result.success(sites)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(IllegalStateException(
            if (failure is IllegalArgumentException || failure is IllegalStateException) failure.message
            else "Cannot refresh the shared work-site catalogue. Built-in sites remain available.", failure
        ))
    }
}
