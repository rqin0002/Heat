@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, eyebrow: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (eyebrow != null) Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = Pine)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = Pine, container: Color = Mint, size: Dp = 40.dp) {
    Surface(color = container, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * .55f), tint = tint) }
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = tint),
        border = BorderStroke(1.dp, if (tint == Color.White) Line else tint)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Tag(text: String, caution: Boolean = false) {
    Surface(
        color = if (caution) AmberPale else Mint,
        contentColor = if (caution) Amber else Pine,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun IconLine(icon: ImageVector, title: String, subtitle: String? = null, iconTint: Color = Pine) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
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
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
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

@Composable
fun DemoNote(text: String = "Illustrative demo data · not a live safety assessment") {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, Modifier.size(15.dp), tint = Muted)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
