@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import heatshield.R
import heatshield.domain.HeatLevel
import heatshield.domain.SourceLabel

/** Test tag on every [SourceChip], so tests can count chips per row. */
const val SOURCE_CHIP_TAG = "source_chip"

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
    // Read here: the semantics lambda below is not composable.
    val state = stringResource(if (expanded) R.string.disclosure_expanded else R.string.disclosure_collapsed)
    val action = stringResource(if (expanded) R.string.disclosure_hide else R.string.disclosure_show)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = state }
                .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
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

/**
 * A card on the page. Every card keeps a Line hairline, as TurnAgain's do: a white card on the light grey page, or a
 * tinted one such as the Action container (#EAF3FF on #F5F5F7), would otherwise all but vanish. Text is Ink on any
 * tint, so on the Action container only the card's buttons are blue and blue still means something you can do.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    tint: Color = HeatShieldTheme.colors.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(), shape = UiShape.card,
        colors = CardDefaults.cardColors(containerColor = tint, contentColor = HeatShieldTheme.colors.ink),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(
            Modifier.padding(UiSpace.section),
            verticalArrangement = Arrangement.spacedBy(UiSpace.related),
            content = content
        )
    }
}

/** The one filled action on a screen: Action fill, full width, at least 56 dp tall. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled, shape = UiShape.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = HeatShieldTheme.colors.action, contentColor = HeatShieldTheme.colors.onAction
        ),
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.compact)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * A secondary action. Spec §7 makes secondaries tonal and sized to content, so this is the same button as
 * [TonalButton] (ActionContainer fill, OnActionContainer text, at least 48 dp tall, no fillMaxWidth). The name stays so
 * screens that are not rebuilt in M1 compile unchanged and lose their full-width slabs.
 */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TonalButton(text, modifier, enabled, onClick)
}

/** A tonal secondary action sized to its content, with a 48 dp touch height. */
@Composable
fun TonalButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled, shape = UiShape.control, colors = tonalColors(),
        contentPadding = PaddingValues(horizontal = UiSpace.related, vertical = UiSpace.small)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun tonalColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = HeatShieldTheme.colors.actionContainer, contentColor = HeatShieldTheme.colors.onActionContainer
)

/**
 * A neutral notice, such as an old forecast or a phone setting that holds alerts back. Warm colours only mean heat, so
 * the text is the Attention token (Ink) and the leading icon carries the meaning (palette spec). The icon is decorative:
 * the text says it all. [modifier] goes on the text, so a test tag or live region still finds the words.
 */
@Composable
fun NoticeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    icon: ImageVector = Icons.Outlined.Info
) {
    // Centre the 20 dp icon on the first line at any font scale.
    val lineHeight = with(LocalDensity.current) { if (style.lineHeight.isSp) style.lineHeight.toDp() else 24.dp }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        Icon(icon, contentDescription = null, Modifier.padding(top = ((lineHeight - 20.dp) / 2).coerceAtLeast(0.dp)).size(20.dp),
            tint = Attention)
        Text(text, modifier.weight(1f), style = style, color = Attention)
    }
}

/**
 * Five rising signal bars with the first [HeatLevel.bars] filled. Decorative: the level word always
 * sits beside it, so it adds no semantics of its own.
 */
@Composable
fun LevelBars(level: HeatLevel, color: Color, modifier: Modifier = Modifier, barWidth: Dp = 5.dp, maxHeight: Dp = 20.dp) {
    val count = HeatLevel.entries.size
    val gap = barWidth * 0.6f
    Canvas(modifier.size(width = barWidth * count + gap * (count - 1), height = maxHeight)) {
        val width = barWidth.toPx()
        val step = width + gap.toPx()
        repeat(count) { index ->
            val height = size.height * (index + 1) / count
            drawRoundRect(
                color = if (index < level.bars) color else color.copy(alpha = 0.25f),
                topLeft = Offset(index * step, size.height - height),
                size = Size(width, height), cornerRadius = CornerRadius(width / 4)
            )
        }
    }
}

/**
 * The one source label carried by a number, e.g. "BOM". Screen readers hear "Source: BOM".
 * A citation with no label (`Citation.label == null`) shows no chip: callers write `citation.label?.let { SourceChip(it) }`.
 */
@Composable
fun SourceChip(label: SourceLabel, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.source_chip_description, label.display)
    Surface(
        modifier.testTag(SOURCE_CHIP_TAG).clearAndSetSemantics { contentDescription = description },
        shape = UiShape.small, color = HeatShieldTheme.colors.paper, border = BorderStroke(1.dp, Line)
    ) {
        Text(
            label.display, Modifier.padding(horizontal = UiSpace.small, vertical = UiSpace.tiny),
            style = MaterialTheme.typography.labelMedium, color = Muted
        )
    }
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
