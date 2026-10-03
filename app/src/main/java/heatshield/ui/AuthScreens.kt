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
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.ui.res.stringResource
import heatshield.R
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit, onSignUp: () -> Unit,
    onReset: (String) -> Unit, configured: Boolean, busy: Boolean, message: UiMessage?
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val emailError = if (submitted && !isValidEmail(email)) stringResource(R.string.auth_error_email) else null
    val passwordError = if (submitted && password.isBlank()) stringResource(R.string.auth_error_password) else null
    val submit: () -> Unit = {
        submitted = true
        if (configured && !busy && isValidEmail(email) && password.isNotBlank()) {
            focusManager.clearFocus(); onLogin(email.trim(), password)
        }
    }
    PageColumn(maxWidth = 480.dp) {
        ScreenHeader(stringResource(R.string.auth_log_in), stringResource(R.string.auth_log_in_subtitle))
        if (!configured) Text(stringResource(R.string.auth_unavailable), color = Muted)
        FormSection(stringResource(R.string.auth_your_account), stringResource(R.string.auth_required_legend)) {
            EmailField(email, { email = it }, emailError, required = true)
            PasswordField(password, { password = it }, passwordVisible, { passwordVisible = !passwordVisible }, passwordError,
                required = true, onDone = submit)
            PrimaryButton(if (busy) stringResource(R.string.auth_please_wait) else stringResource(R.string.auth_log_in), enabled = configured && !busy, onClick = submit)
            SecondaryButton(stringResource(R.string.auth_forgot_password)) { focusManager.clearFocus(); resetOpen = true }
        }
        message?.let { shown ->
            val messageModifier = Modifier.testTag("auth_message").semantics {
                liveRegion = LiveRegionMode.Polite
                if (shown.isError) error(shown.text)
            }
            // An error is a neutral notice: Ink with an icon, never a warm colour (palette spec).
            if (shown.isError) NoticeText(shown.text, messageModifier, icon = Icons.Outlined.ErrorOutline)
            else Text(shown.text, messageModifier, color = Ink)
        }
        SecondaryButton(stringResource(R.string.auth_create_account), enabled = !busy, onClick = onSignUp)
    }
    if (resetOpen) AlertDialog(
        onDismissRequest = { resetOpen = false }, title = { Text(stringResource(R.string.auth_reset_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.auth_reset_body))
            EmailField(email, { email = it }, if (email.isNotBlank() && !isValidEmail(email)) stringResource(R.string.auth_error_email) else null)
            if (!configured) Text(stringResource(R.string.auth_unavailable))
        } },
        confirmButton = { PrimaryButton(stringResource(R.string.auth_send_reset), enabled = configured && !busy && isValidEmail(email)) {
            onReset(email.trim()); resetOpen = false
        } },
        dismissButton = { SecondaryButton(stringResource(R.string.auth_cancel)) { resetOpen = false } }
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
    val passwordRule = stringResource(R.string.auth_password_rule)
    val submit: () -> Unit = {
        submitted = true
        if (configured && !busy && !passwordInvalid && isValidEmail(email)) {
            focusManager.clearFocus(); onCreated(email.trim(), password)
        }
    }
    PageColumn(maxWidth = 480.dp) {
        ScreenHeader(stringResource(R.string.auth_create_account), stringResource(R.string.auth_create_account_subtitle))
        FormSection(stringResource(R.string.auth_your_account), stringResource(R.string.auth_required_legend)) {
            EmailField(
                email, { email = it },
                if (submitted && !isValidEmail(email)) stringResource(R.string.auth_error_email) else null,
                required = true
            )
            PasswordField(
                password, { password = it }, passwordVisible, { passwordVisible = !passwordVisible },
                if (password.isNotEmpty() && passwordInvalid) passwordRule else null,
                hint = if (password.isEmpty()) passwordRule else null, required = true, onDone = submit
            )
            PrimaryButton(
                stringResource(if (busy) R.string.auth_creating_account else R.string.auth_create_account),
                enabled = configured && !busy && !passwordInvalid, onClick = submit
            )
        }
        if (!configured) Text(stringResource(R.string.auth_unavailable), color = Muted)
        SecondaryButton(stringResource(R.string.auth_log_in), enabled = !busy, onClick = onLogin)
    }
}


@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, errorMessage: String?, required: Boolean = false) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(if (required) R.string.auth_email_required else R.string.auth_email)) },
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
    required: Boolean = false,
    onDone: () -> Unit
) {
    val supportingMessage = errorMessage ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(if (required) R.string.auth_password_required else R.string.auth_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        isError = errorMessage != null,
        supportingText = if (supportingMessage != null) ({
            FieldMessage(supportingMessage, isError = errorMessage != null)
        }) else null,
        trailingIcon = {
            IconButton(onClick = onVisibilityChange, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password)
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
