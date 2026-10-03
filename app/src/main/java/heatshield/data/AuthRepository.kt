// Lets the account screens sign in, create accounts and reset passwords through Firebase.
// Failures are translated into resource wording here; the ViewModel separately chooses whose saved shifts to show.
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
import heatshield.R
import heatshield.withAppLocale

data class AuthUser(val id: String, val email: String)

/** Firebase owns persisted credentials. Local profiles are deliberately separate from authentication. */
class AuthRepository(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val auth = firebaseAppOrNull(context)?.let(FirebaseAuth::getInstance)
    val configured: Boolean get() = auth != null
    private val currentUser = MutableStateFlow(auth?.currentUser?.toAuthUser())
    val user: StateFlow<AuthUser?> = currentUser.asStateFlow()
    private val listener = FirebaseAuth.AuthStateListener { currentUser.value = it.currentUser?.toAuthUser() }

    init {
        auth?.addAuthStateListener(listener)
    }

    /** Signs in with a trimmed email and unchanged password; a nonblank existing 10-character password is allowed. */
    suspend fun signIn(email: String, password: String): Result<AuthUser> = request {
        val client = requireAuth()
        val address = validEmail(email)
        if (password.isBlank()) throw AccountInputFailure(R.string.auth_password_missing)
        val signedIn = client.signInWithEmailAndPassword(address, password).await().user
            ?: throw AccountInputFailure(R.string.auth_sign_in_missing_account)
        signedIn.toAuthUser().also { currentUser.value = it }
    }

    /** Creates an account after local validation; an 11-character password fails before Firebase is called. */
    suspend fun signUp(email: String, password: String): Result<AuthUser> = request {
        val client = requireAuth()
        val address = validEmail(email)
        if (password.length < 12) throw AccountInputFailure(R.string.auth_password_length)
        val created = client.createUserWithEmailAndPassword(address, password).await().user
            ?: throw AccountInputFailure(R.string.auth_sign_up_missing_account)
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

    /** Requires configured accounts, using the same unavailable wording as Login and Profile. */
    private fun requireAuth(): FirebaseAuth = auth ?: throw AccountInputFailure(R.string.auth_unavailable)

    /** Trims an email and checks its shape and 254-character limit; a valid address surrounded by spaces is accepted. */
    private fun validEmail(email: String): String = email.trim().also {
        if (it.length > 254 || !Patterns.EMAIL_ADDRESS.matcher(it).matches()) throw AccountInputFailure(R.string.auth_invalid_email)
    }

    /** Returns an account result with resource-based failures, while cancellation still stops the calling coroutine. */
    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val message = appContext.withAppLocale().getString(accountFailureResource(failure))
        Result.failure(IllegalStateException(message, failure))
    }
}

/** A local account validation failure with resource wording, so raw SDK text is never mistaken for approved copy. */
internal class AccountInputFailure(@androidx.annotation.StringRes val messageResource: Int) : IllegalArgumentException()

/**
 * Keeps useful account failure distinctions while selecting only resource wording.
 * Example: a network failure asks for a connection check; duplicate registration still suggests sign-in or reset.
 */
@androidx.annotation.StringRes
internal fun accountFailureResource(failure: Exception): Int = when (failure) {
    is AccountInputFailure -> failure.messageResource
    is FirebaseNetworkException -> R.string.auth_network_failed
    is FirebaseTooManyRequestsException -> R.string.auth_too_many_attempts
    is FirebaseAuthException -> when (failure.errorCode) {
        "ERROR_INVALID_EMAIL" -> R.string.auth_invalid_email
        "ERROR_WEAK_PASSWORD" -> R.string.auth_weak_password
        "ERROR_EMAIL_ALREADY_IN_USE" -> R.string.auth_email_in_use
        "ERROR_USER_DISABLED" -> R.string.auth_user_disabled
        "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_API_KEY" -> R.string.auth_unavailable
        else -> R.string.auth_details_failed
    }
    else -> R.string.auth_request_failed
}

internal fun firebaseAppOrNull(context: Context): FirebaseApp? =
    FirebaseApp.getApps(context.applicationContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        ?: FirebaseApp.initializeApp(context.applicationContext)

private fun FirebaseUser.toAuthUser() = AuthUser(uid, email.orEmpty())
