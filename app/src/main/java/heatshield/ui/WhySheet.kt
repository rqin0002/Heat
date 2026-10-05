@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import heatshield.R
import heatshield.domain.SourceLabel

/** One "Why this?" row: the number as shown, the rule behind it, and exactly one source label. */
data class WhyRow(val key: String, val value: String, val rule: String, val source: SourceLabel)

/**
 * Every number on Today, with its rule and source. The row for what was tapped ([focusKey]) comes first, so a tap
 * on "UV High" opens on UV; the rest follow under "Other numbers on Today". Limits live once, in Profile › About & sources.
 */
@Composable
fun WhySheet(rows: List<WhyRow>, focusKey: String?, onDismiss: () -> Unit) {
    // The sheet is its own window; carry the caller's density in so its rows follow the same font scale as Today.
    val density = LocalDensity.current
    val focused = rows.firstOrNull { it.key == focusKey }
    val others = rows.filter { it.key != focused?.key }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HeatShieldTheme.colors.surface
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("why_sheet")
                    .padding(horizontal = UiSpace.section).padding(bottom = UiSpace.large),
                verticalArrangement = Arrangement.spacedBy(UiSpace.related)
            ) {
                Text(stringResource(R.string.why_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (focused != null) {
                    WhyRowItem(focused)
                    if (others.isNotEmpty()) Text(stringResource(R.string.why_more), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                }
                others.forEach { WhyRowItem(it) }
                Text(stringResource(R.string.why_footer), style = MaterialTheme.typography.bodyLarge, color = Muted)
            }
        }
    }
}

/** The value, its rule and its one source chip, read as one item by screen readers. */
@Composable
private fun WhyRowItem(row: WhyRow) {
    Column(
        Modifier.fillMaxWidth().testTag("why_row_${row.key}").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(UiSpace.small)
    ) {
        Text(row.value, style = MaterialTheme.typography.titleMedium)
        Text(row.rule, style = MaterialTheme.typography.bodyLarge, color = Muted)
        SourceChip(row.source)
    }
    HorizontalDivider(color = Line)
}
