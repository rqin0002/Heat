package heatshield

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses only local Auth/Firestore emulators for demo-heatshield, never the configured default app.
 * Run with -e firebaseEmulators true. Each test owns and cleans up its emulator-only fixtures.
 * Official connection guidance: https://firebase.google.com/docs/emulator-suite/connect_auth
 * and https://firebase.google.com/docs/emulator-suite/connect_firestore
 */
@RunWith(AndroidJUnit4::class)
class FirebaseIntegrationTest {
    private lateinit var app: FirebaseApp
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private val email = "heatshield-${UUID.randomUUID()}@example.test"
    private val password = "Local emulator passphrase 2026"
    private val siteId = "integration-${UUID.randomUUID()}"
    private var accountCreated = false
    private var siteSeedAttempted = false

    @Before
    fun connectOnlyToLocalEmulators() {
        assumeTrue(
            "Start the demo-heatshield emulators and pass -e firebaseEmulators true.",
            InstrumentationRegistry.getArguments().getString("firebaseEmulators") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = FirebaseOptions.Builder()
            .setProjectId("demo-heatshield")
            .setApplicationId("1:1234567890:android:0123456789abcdef012345")
            .setApiKey("AIza" + "0".repeat(35))
            .build()
        app = FirebaseApp.initializeApp(context, options, "heatshield-emulator-${UUID.randomUUID()}")
        assertEquals("demo-heatshield", app.options.projectId)
        auth = FirebaseAuth.getInstance(app).also { it.useEmulator("10.0.2.2", 9099) }
        firestore = FirebaseFirestore.getInstance(app).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            it.useEmulator("10.0.2.2", 8080)
        }
    }

    @After
    fun closeNamedApp() = runBlocking {
        try {
            if (accountCreated) withTimeout(15_000) {
                val user = auth.currentUser ?: auth.signInWithEmailAndPassword(email, password).await().user
                requireNotNull(user).delete().await()
            }
        } finally {
            if (::auth.isInitialized) auth.signOut()
            try {
                if (siteSeedAttempted) emulatorFixtureRequest("DELETE")
            } finally {
                try {
                    if (::firestore.isInitialized) withTimeout(10_000) { firestore.terminate().await() }
                } finally {
                    if (::app.isInitialized) app.delete()
                }
            }
        }
    }

    @Test
    fun accountLifecycleRejectsIncorrectCredentialsAndDuplicateRegistration() = runBlocking {
        withTimeout(60_000) {
            assertNull(auth.currentUser)
            val accountId = createAccount()
            assertEquals(email, auth.currentUser?.email)
            assertTrue(accountId.isNotBlank())
            auth.signOut()
            assertNull(auth.currentUser)
            assertAuthRejected { auth.signInWithEmailAndPassword(email, "An incorrect passphrase").await() }
            assertNull(auth.currentUser)
            assertAuthRejected { auth.createUserWithEmailAndPassword(email, password).await() }
            assertNull(auth.currentUser)
            val signedIn = requireNotNull(auth.signInWithEmailAndPassword(email, password).await().user)
            assertEquals(accountId, signedIn.uid)
            assertNotNull(signedIn.getIdToken(true).await().token)

            signedIn.delete().await()
            accountCreated = false
            assertNull(auth.currentUser)
            assertAuthRejected { auth.signInWithEmailAndPassword(email, password).await() }
            assertNull(auth.currentUser)
        }
    }

    @Test
    fun catalogueReadsRequireSignInAndAllClientWritesRemainDenied() = runBlocking {
        withTimeout(60_000) {
            siteSeedAttempted = true
            emulatorFixtureRequest("POST")
            val worksites = firestore.collection("worksites")
            val reference = worksites.document(siteId)
            assertPermissionDenied { worksites.get(Source.SERVER).await() }
            assertPermissionDenied { reference.get(Source.SERVER).await() }
            assertPermissionDenied { reference.set(mapOf("name" to "Unauthenticated write")).await() }

            createAccount()
            val site = reference.get(Source.SERVER).await()
            assertTrue(site.exists())
            assertEquals("Birrarung Marr", site.getString("name"))
            assertEquals(-37.8185931, requireNotNull(site.getDouble("latitude")), 0.000001)
            assertEquals(144.9716404, requireNotNull(site.getDouble("longitude")), 0.000001)
            assertFalse(worksites.whereEqualTo("name", "Birrarung Marr").get(Source.SERVER).await().isEmpty)

            assertPermissionDenied {
                worksites.document("client-write-${UUID.randomUUID()}").set(
                    mapOf("name" to "Unapproved site", "latitude" to -37.8, "longitude" to 144.9)
                ).await()
            }
            assertPermissionDenied { site.reference.update("name", "Changed by client").await() }
            assertPermissionDenied { site.reference.delete().await() }
            assertPermissionDenied { firestore.collection("private-records").get(Source.SERVER).await() }
            assertPermissionDenied { firestore.collection("private-records").document(siteId).set(mapOf("value" to 1)).await() }
            assertPermissionDenied { reference.collection("private").document("entry").get(Source.SERVER).await() }
            assertPermissionDenied { reference.collection("private").document("entry").set(mapOf("value" to 1)).await() }
            assertEquals("Birrarung Marr", site.reference.get(Source.SERVER).await().getString("name"))
            auth.signOut()
            assertNull(auth.currentUser)
            assertPermissionDenied { reference.get(Source.SERVER).await() }
            assertPermissionDenied { worksites.get(Source.SERVER).await() }
        }
    }

    private suspend fun createAccount(): String {
        val user = requireNotNull(auth.createUserWithEmailAndPassword(email, password).await().user)
        accountCreated = true
        return user.uid
    }

    private suspend fun assertAuthRejected(request: suspend () -> Unit) {
        try {
            request()
            fail("Invalid credentials or a duplicate account must be rejected.")
        } catch (expected: FirebaseAuthException) {
            assertTrue(expected.errorCode.isNotBlank())
        }
    }

    /** Admin fixture calls are fixed to the local demo emulator; client assertions use the SDK. */
    private fun emulatorFixtureRequest(method: String) {
        val base = "http://10.0.2.2:8080/v1/projects/demo-heatshield/databases/(default)/documents/worksites"
        val suffix = if (method == "POST") "?documentId=$siteId" else "/$siteId"
        val connection = URL(base + suffix).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            // The emulator's fixed admin token is not a production credential.
            connection.setRequestProperty("Authorization", "Bearer owner")
            if (method == "POST") {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val body = """{"fields":{"name":{"stringValue":"Birrarung Marr"},"latitude":{"doubleValue":-37.8185931},"longitude":{"doubleValue":144.9716404}}}"""
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            assertTrue("Local Firestore fixture $method failed with HTTP $code",
                code in 200..299 || (method == "DELETE" && code == 404))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun assertPermissionDenied(request: suspend () -> Unit) {
        try {
            request()
            fail("The Firestore rules should reject this request.")
        } catch (denied: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, denied.code)
        }
    }
}
