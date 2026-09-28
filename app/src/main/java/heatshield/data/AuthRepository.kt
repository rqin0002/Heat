package heatshield.data

import android.content.Context
import android.util.Patterns
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class AuthUser(val id: String, val email: String)

/** Firebase owns persisted credentials. Local profiles are deliberately separate from authentication. */
class AuthRepository(context: Context) : AutoCloseable {
    private val auth = firebaseAppOrNull(context)?.let(FirebaseAuth::getInstance)
    val configured: Boolean get() = auth != null
    private val currentUser = MutableStateFlow(auth?.currentUser?.toAuthUser())
    val user: StateFlow<AuthUser?> = currentUser.asStateFlow()
    private val listener = FirebaseAuth.AuthStateListener { currentUser.value = it.currentUser?.toAuthUser() }

    init {
        auth?.addAuthStateListener(listener)
    }

    suspend fun signIn(email: String, password: String): Result<AuthUser> = request {
        val client = requireAuth()
        val address = validEmail(email)
        require(password.isNotBlank()) { "Enter a password." }
        val signedIn = client.signInWithEmailAndPassword(address, password).await().user
            ?: error("Sign-in did not return an account. Please try again.")
        signedIn.toAuthUser().also { currentUser.value = it }
    }

    suspend fun signUp(email: String, password: String): Result<AuthUser> = request {
        val client = requireAuth()
        val address = validEmail(email)
        require(password.length >= 12) { "Use a password with at least 12 characters." }
        val created = client.createUserWithEmailAndPassword(address, password).await().user
            ?: error("Account creation did not return an account. Please try again.")
        created.toAuthUser().also { currentUser.value = it }
    }

    suspend fun resetPassword(email: String): Result<Unit> = request {
        val client = requireAuth()
        client.sendPasswordResetEmail(validEmail(email)).await()
    }

    fun signOut() {
        auth?.signOut()
        currentUser.value = null
    }

    override fun close() {
        auth?.removeAuthStateListener(listener)
    }

    private fun requireAuth(): FirebaseAuth = checkNotNull(auth) {
        "Cloud accounts are not configured for this build. Use the local profile."
    }

    private fun validEmail(email: String): String = email.trim().also {
        require(it.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(it).matches()) {
            "Enter a valid email address."
        }
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val message = when (failure) {
            is FirebaseNetworkException -> "Cannot reach the account service. Check your connection and try again."
            is FirebaseTooManyRequestsException -> "Too many attempts. Wait a while before trying again."
            is FirebaseAuthException -> when (failure.errorCode) {
                "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
                "ERROR_WEAK_PASSWORD" -> "Choose a stronger password with at least 12 characters."
                "ERROR_EMAIL_ALREADY_IN_USE" -> "This email cannot be registered. Try signing in or resetting the password."
                "ERROR_USER_DISABLED" -> "This account is unavailable. Contact your project administrator."
                "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_API_KEY" ->
                    "Cloud accounts are not enabled for this build. Use the local profile."
                else -> "The account request failed. Check your details and try again."
            }
            else -> failure.message ?: "The account request failed. Please try again."
        }
        Result.failure(IllegalStateException(message, failure))
    }
}

internal fun firebaseAppOrNull(context: Context): FirebaseApp? =
    FirebaseApp.getApps(context.applicationContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        ?: FirebaseApp.initializeApp(context.applicationContext)

private fun FirebaseUser.toAuthUser() = AuthUser(uid, email.orEmpty())
