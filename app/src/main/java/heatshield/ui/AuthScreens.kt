package heatshield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit, onSignUp: () -> Unit, onLocal: () -> Unit,
    onReset: (String) -> Unit, configured: Boolean, busy: Boolean, message: String?
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val emailError = if (submitted && !isValidEmail(email)) "Enter a valid email address." else null
    val passwordError = if (submitted && password.isBlank()) "Enter a password." else null
    val submit: () -> Unit = {
        submitted = true
        if (configured && !busy && isValidEmail(email) && password.isNotBlank()) {
            focusManager.clearFocus(); onLogin(email.trim(), password)
        }
    }
    PageColumn(maxWidth = 480.dp) {
        ScreenHeader("Log in", "Plan your outdoor work and keep a record of your check-ins.")
        if (!configured) Text("Cloud accounts are not configured on this build. You can keep records on this device.", color = Muted)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmailField(email, { email = it }, emailError)
            PasswordField(password, { password = it }, passwordVisible, { passwordVisible = !passwordVisible }, passwordError, onDone = submit)
            PrimaryButton(if (busy) "Please wait…" else "Log in", enabled = configured && !busy, onClick = submit)
            SecondaryButton("Forgot password?") { focusManager.clearFocus(); resetOpen = true }
        }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Attention) }
        SecondaryButton("Create account", enabled = !busy, onClick = onSignUp)
        SecondaryButton("Continue on this device", enabled = !busy, onClick = onLocal)
        Text("Device mode has no cloud identity. Its records stay separate from signed-in accounts.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (resetOpen) AlertDialog(
        onDismissRequest = { resetOpen = false }, title = { Text("Reset password") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter your account email. Requesting a reset sends an email if the account is eligible.")
            EmailField(email, { email = it }, if (email.isNotBlank() && !isValidEmail(email)) "Enter a valid email address." else null)
            if (!configured) Text("Cloud accounts are not configured on this build.")
        } },
        confirmButton = { PrimaryButton("Send reset email", enabled = configured && !busy && isValidEmail(email)) {
            onReset(email.trim()); resetOpen = false
        } },
        dismissButton = { SecondaryButton("Cancel") { resetOpen = false } }
    )
}

@Composable
fun SignUpScreen(
    onCreated: (String, String) -> Unit, onLogin: () -> Unit, configured: Boolean, busy: Boolean
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val passwordInvalid = password.length < 12
    val submit: () -> Unit = {
        submitted = true
        if (configured && !busy && !passwordInvalid && isValidEmail(email)) {
            focusManager.clearFocus(); onCreated(email.trim(), password)
        }
    }
    PageColumn(maxWidth = 480.dp) {
        ScreenHeader("Create account")
        FormSection("Your account", "All fields required") {
            EmailField(email, { email = it }, if (submitted && !isValidEmail(email)) "Enter a valid email address." else null)
            PasswordField(password, { password = it }, passwordVisible, { passwordVisible = !passwordVisible },
                if (password.isNotEmpty() && passwordInvalid) "Use at least 12 characters." else null,
                hint = if (password.isEmpty()) "Use at least 12 characters." else null, onDone = submit)
            PrimaryButton(if (busy) "Creating account…" else "Create account", enabled = configured && !busy && !passwordInvalid, onClick = submit)
        }
        if (!configured) Text("Cloud accounts are not configured on this build.", color = Muted)
        SecondaryButton("Log in", enabled = !busy, onClick = onLogin)
    }
}

@Composable
fun ProfileScreen(model: HeatShieldViewModel, onSaved: () -> Unit, onSignOut: () -> Unit) {
    val prefs = model.preferences
    var site by rememberSaveable(prefs.ownerId, prefs.site) { mutableStateOf(prefs.site) }
    var effort by rememberSaveable(prefs.ownerId, prefs.effort) { mutableStateOf(prefs.effort) }
    var clothing by rememberSaveable(prefs.ownerId, prefs.clothing) { mutableStateOf(prefs.clothing) }
    var reminders by rememberSaveable(prefs.ownerId, prefs.reminders) { mutableStateOf(prefs.reminders) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var notificationAllowed by androidx.compose.runtime.remember {
        mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { allowed -> notificationAllowed = allowed }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PageColumn {
        if (model.needsOnboarding) ScreenHeader("Set up your work context", "Choose your usual site and work details before planning a shift.")
        Text(if (model.ownerId == "local") "Device mode · records stored on this device" else model.email ?: "Signed in")
        FormSection("Work preferences", "All fields required") {
            ChoiceField("Work site", site, model.sites.map { it.name }, { site = it; saved = false })
            ChoiceField("Typical work effort", effort, listOf("Light effort", "Moderate effort", "Heavy effort"), { effort = it; saved = false })
            ChoiceField("Protective clothing", clothing, listOf("Standard workwear", "Heavy protective clothing"), { clothing = it; saved = false })
            Text("Follow workplace PPE requirements.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = reminders, role = Role.Switch,
            onValueChange = { reminders = it; saved = false }).semantics { contentDescription = "Break check-in reminders" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Break check-in reminders", Modifier.weight(1f).clearAndSetSemantics { })
            Switch(reminders, null, Modifier.clearAndSetSemantics { })
        }
        if (reminders && !notificationAllowed) {
            Text("Notifications are off. Your plan and check-in remain available inside the app.", color = Attention)
            SecondaryButton("Allow notifications") {
                if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        PrimaryButton("Save preferences", enabled = !model.busy) {
            val firstSetup = model.needsOnboarding
            model.savePreferences(site, effort, clothing, reminders) { saved = true; if (firstSetup) onSaved() }
        }
        if (saved) Text("Preferences saved on this device.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Disclosure("How HeatShield uses context") {
            Text("Historical environmental readings are replayed every 20 seconds to explore changing conditions. They are not current sensor measurements. Forecasts come from Open-Meteo for the selected site.")
            Text("Effort, clothing, shift timing and your response affect the explanation. Check-ins are prompts for a work discussion, not medical assessments or permission to resume work.")
            Text("Reminders are approximate and Android may delay them. Private records and preferences stay in this device's Room database, separately for each account.")
        }
        Disclosure("Worksite catalogue") {
            Text(model.catalogueMessage)
            if (model.ownerId != "local") SecondaryButton("Refresh shared sites", onClick = model::loadSites)
        }
        SecondaryButton(if (model.ownerId == "local") "Leave device mode" else "Sign out", enabled = !model.busy, onClick = onSignOut)
    }
}

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, errorMessage: String?) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Email") },
        singleLine = true,
        isError = errorMessage != null,
        supportingText = if (errorMessage != null) ({ FieldMessage(errorMessage, isError = true) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
        modifier = Modifier.fillMaxWidth().testTag("auth_email").semantics {
            if (errorMessage != null) error(errorMessage)
        },
        shape = UiShape.control
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibilityChange: () -> Unit,
    errorMessage: String?,
    hint: String? = null,
    onDone: () -> Unit
) {
    val supportingMessage = errorMessage ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        isError = errorMessage != null,
        supportingText = if (supportingMessage != null) ({
            FieldMessage(
                supportingMessage,
                isError = errorMessage != null
            )
        }) else null,
        trailingIcon = {
            IconButton(onClick = onVisibilityChange, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("auth_password").semantics {
            if (errorMessage != null) error(errorMessage)
        },
        shape = UiShape.control
    )
}

@Composable
private fun FieldMessage(message: String, isError: Boolean) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (isError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    )
}

private fun isValidEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    return at > 0 && at < trimmed.lastIndex && trimmed.substring(at + 1).contains('.') && !trimmed.contains(' ')
}
