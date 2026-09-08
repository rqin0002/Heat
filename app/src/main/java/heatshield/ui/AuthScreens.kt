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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("worker@example.com") }
    var password by rememberSaveable { mutableStateOf("Shade before midday") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var resetPreview by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val emailError = if (submitted && !isDemoEmail(email)) "Enter a valid email address." else null
    val passwordError = if (submitted && password.isBlank()) "Enter a password." else null
    val submit: () -> Unit = {
        submitted = true
        if (isDemoEmail(email) && password.isNotBlank()) {
            focusManager.clearFocus()
            onLogin()
        }
    }

    PageColumn(maxWidth = 480.dp) {
        ScreenHeader("Log in")
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmailField(email, { email = it }, emailError)
            PasswordField(
                value = password,
                onValueChange = { password = it },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = !passwordVisible },
                errorMessage = passwordError,
                onDone = submit
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { resetPreview = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = UiShape.control
                ) {
                    Text("Forgot password?", textAlign = TextAlign.Center)
                }
            }
            PrimaryButton("Log in to demo", onClick = submit)
        }
        AuthAlternatives(accountAction = "Create account", onAccountAction = onSignUp, onExplore = onLogin)
    }
    if (resetPreview) {
        AlertDialog(
            onDismissRequest = { resetPreview = false },
            title = { Text("Password reset preview") },
            text = { Text("Password reset is not connected in this demo. No email will be sent.") },
            confirmButton = {
                TextButton(
                    onClick = { resetPreview = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Dismiss") }
            }
        )
    }
}

@Composable
fun SignUpScreen(onCreated: () -> Unit, onLogin: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("worker@example.com") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val passwordInvalid = password.length < 12
    val showPasswordError = password.isNotEmpty() && passwordInvalid
    val emailError = if (submitted && !isDemoEmail(email)) "Enter a valid email address." else null
    val submit: () -> Unit = {
        if (!passwordInvalid) {
            submitted = true
            if (isDemoEmail(email)) {
                focusManager.clearFocus()
                onCreated()
            }
        }
    }

    PageColumn(maxWidth = 480.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmailField(email, { email = it }, emailError)
            PasswordField(
                value = password,
                onValueChange = { password = it },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = !passwordVisible },
                errorMessage = if (showPasswordError) "Use at least 12 characters." else null,
                hint = if (password.isEmpty()) "Use at least 12 characters." else null,
                onDone = submit
            )
            PrimaryButton("Create demo account", enabled = !passwordInvalid, onClick = submit)
        }
        AuthAlternatives(accountAction = "Log in", onAccountAction = onLogin, onExplore = onCreated)
    }
}

@Composable
fun ProfileScreen(onSignOut: () -> Unit) {
    var site by rememberSaveable { mutableStateOf("Birrarung Marr") }
    var effort by rememberSaveable { mutableStateOf("Moderate effort") }
    var clothing by rememberSaveable { mutableStateOf("Standard workwear") }
    var reminders by rememberSaveable { mutableStateOf(true) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val siteError = if (submitted && site.isBlank()) "Enter your work site." else null

    PageColumn {
        FormSection("Work preferences") {
            OutlinedTextField(
                value = site,
                onValueChange = { site = it; saved = false },
                label = { Text("Work site") },
                singleLine = true,
                isError = siteError != null,
                supportingText = if (siteError != null) ({ FieldMessage(siteError, isError = true) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("profile_site").semantics {
                    if (siteError != null) error(siteError)
                },
                shape = UiShape.control
            )
            ChoiceField(
                label = "Typical work effort",
                value = effort,
                options = listOf("Light effort", "Moderate effort", "Heavy effort"),
                onChange = { effort = it; saved = false }
            )
            ChoiceField(
                label = "Protective clothing",
                value = clothing,
                options = listOf("Standard workwear", "Heavy protective clothing"),
                onChange = { clothing = it; saved = false }
            )
            Text("Follow workplace PPE requirements.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(
                        value = reminders,
                        role = Role.Switch,
                        onValueChange = { reminders = it; saved = false }
                    )
                    .semantics { contentDescription = "Break check-in reminders" },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Break check-in reminders",
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = reminders,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
        PrimaryButton("Save demo preferences") {
            submitted = true
            saved = site.isNotBlank()
            if (saved) focusManager.clearFocus()
        }
        if (saved) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(24.dp), tint = PrimaryAction)
                Text("Saved on this screen.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = UiShape.control
        ) { Text("Leave demo", textAlign = TextAlign.Center) }
    }
}

@Composable
private fun AuthAlternatives(accountAction: String, onAccountAction: () -> Unit, onExplore: () -> Unit) {
    Column {
        TextButton(
            onClick = onAccountAction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = UiShape.control
        ) { Text(accountAction, textAlign = TextAlign.Center) }
        TextButton(
            onClick = onExplore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = UiShape.control
        ) { Text("Explore demo", color = Muted, textAlign = TextAlign.Center) }
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

private fun isDemoEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    return at > 0 && at < trimmed.lastIndex && trimmed.substring(at + 1).contains('.') && !trimmed.contains(' ')
}
