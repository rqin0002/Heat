package heatshield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(onLogin: () -> Unit, onSignUp: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("worker@example.com") }
    var password by rememberSaveable { mutableStateOf("Shade before midday") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var resetPreview by rememberSaveable { mutableStateOf(false) }
    val emailInvalid = submitted && !isDemoEmail(email)
    val passwordInvalid = submitted && password.isBlank()

    PageColumn {
        AuthIntro(
            title = "Welcome to HeatShield",
            description = "Plan your outdoor work and keep your shift records together."
        )
        SurfaceCard {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                isError = emailInvalid,
                supportingText = if (emailInvalid) ({ Text("Enter an email address, for example worker@example.com.") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = !passwordVisible },
                error = if (passwordInvalid) "Enter a sample password to continue." else null
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { resetPreview = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Forgot password?")
                }
            }
            PrimaryButton("Log in to demo") {
                submitted = true
                if (isDemoEmail(email) && password.isNotBlank()) onLogin()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSignUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("New to HeatShield? Sign up")
            }
            OutlinedButton(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Explore demo")
            }
        }
    }
    if (resetPreview) {
        AlertDialog(
            onDismissRequest = { resetPreview = false },
            title = { Text("Password reset preview") },
            text = { Text("This is the preview of reset password.") },
            confirmButton = {
                TextButton(onClick = { resetPreview = false }) { Text("Dismiss") }
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
    val passwordInvalid = password.length < 12
    val showPasswordError = password.isNotEmpty() && passwordInvalid
    val emailInvalid = submitted && !isDemoEmail(email)

    PageColumn {
        AuthIntro(
            title = "Start with your next shift",
            description = "Create your account, then set the work context for your shift."
        )
        SurfaceCard {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                isError = emailInvalid,
                supportingText = if (emailInvalid) ({ Text("Enter an email address, for example worker@example.com.") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                visible = passwordVisible,
                onVisibilityChange = { passwordVisible = !passwordVisible },
                error = if (showPasswordError) "Too short. Use at least 12 characters, such as a memorable phrase." else null,
                hint = if (password.isEmpty()) "Use at least 12 characters." else if (!passwordInvalid) "Length requirement met. You can show your password to check it." else null
            )
            PrimaryButton("Create demo account", enabled = !passwordInvalid) {
                submitted = true
                if (isDemoEmail(email)) onCreated()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Already have an account? Log in")
            }
            OutlinedButton(
                onClick = onCreated,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Explore demo")
            }
        }
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

    PageColumn {
        ScreenHeader(
            title = "Your work context",
            subtitle = "Defaults for your next outdoor shift."
        )
        SurfaceCard {
            SectionHeading("Work setting")
            OutlinedTextField(
                value = site,
                onValueChange = { site = it; saved = false },
                label = { Text("Work site") },
                leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                singleLine = true,
                isError = submitted && site.isBlank(),
                supportingText = if (submitted && site.isBlank()) ({ Text("Enter your work site.") }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
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
            Text(
                "Adjust these for each shift. Follow your workplace PPE requirements.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        SurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Break check-in reminders",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = reminders,
                    onCheckedChange = { reminders = it; saved = false },
                    modifier = Modifier.semantics { contentDescription = "Break check-in reminders" }
                )
            }
            Text(
                "Approximate timing. Check-ins remain available if notifications are off.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        PrimaryButton("Save preferences (demo)") {
            submitted = true
            saved = site.isNotBlank()
        }
        if (saved) {
            SurfaceCard(tint = Mint) {
                IconLine(
                    Icons.Outlined.CheckCircle,
                    "Demo preferences saved",
                    "These selections stay within this prototype screen."
                )
            }
        }
        SurfaceCard {
            SectionHeading("Data and availability")
            IconLine(
                Icons.Outlined.CloudOff,
                "Know when data is unavailable",
                "The planned app will retain local records and show the age of weather data."
            )
            HorizontalDivider(color = Line)
            Text(
                "This prototype uses illustrative records and conditions. It does not retrieve live weather or schedule notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Leave demo / log out")
        }
    }
}

@Composable
private fun AuthIntro(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Shield, size = 40.dp)
            Text("HEATSHIELD", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp), color = Pine)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibilityChange: () -> Unit,
    error: String?,
    hint: String? = null
) {
    val supportingMessage = error ?: hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        isError = error != null,
        supportingText = if (supportingMessage != null) ({ Text(supportingMessage) }) else null,
        trailingIcon = {
            IconButton(onClick = onVisibilityChange, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
}

private fun isDemoEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    return at > 0 && at < trimmed.lastIndex && trimmed.substring(at + 1).contains('.') && !trimmed.contains(' ')
}
