@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier.widthIn(max = maxWidth).fillMaxWidth().testTag("page_content")
                .verticalScroll(scrollState).imePadding()
                .padding(horizontal = UiSpace.section).padding(top = UiSpace.related, bottom = UiSpace.large),
            verticalArrangement = Arrangement.spacedBy(UiSpace.section), content = content
        )
    }
}

@Composable
fun FormSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.related)) {
        SectionHeading(title, subtitle)
        content()
    }
}

@Composable
fun Disclosure(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide details" else "Show details"
                ) { expanded = !expanded }
                .padding(vertical = UiSpace.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiSpace.small)
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = PrimaryAction)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = PrimaryAction)
        }
        if (expanded) Column(
            Modifier.fillMaxWidth().padding(top = UiSpace.small, bottom = UiSpace.small),
            verticalArrangement = Arrangement.spacedBy(UiSpace.related), content = content
        )
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, eyebrow: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        if (eyebrow != null) Text(eyebrow, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.tiny)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = UiShape.card,
        colors = CardDefaults.cardColors(containerColor = tint),
        border = if (tint == Color.White) BorderStroke(1.dp, Line) else null
    ) {
        Column(
            Modifier.padding(UiSpace.section),
            verticalArrangement = Arrangement.spacedBy(UiSpace.related),
            content = content
        )
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled, shape = UiShape.control,
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.compact)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled, shape = UiShape.control,
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.compact)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun ChoiceField(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            singleLine = false, textStyle = MaterialTheme.typography.bodyLarge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = UiShape.control
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onChange(option); expanded = false })
            }
        }
    }
}
