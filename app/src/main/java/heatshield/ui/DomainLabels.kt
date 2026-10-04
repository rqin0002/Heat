// Turns stable heat categories and saved job keys into the words screens and notifications show.
// Keeping resources here lets copy change without changing the plain Kotlin rules or existing saved choices.
package heatshield.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import heatshield.R
import heatshield.domain.HeatLevel
import heatshield.domain.SourceLabel
import heatshield.domain.UvCategory

/** Selects the heat-level wording for screens and notifications; HOT always uses the same resource. */
@StringRes
fun heatLevelResource(level: HeatLevel): Int = when (level) {
    HeatLevel.NORMAL -> R.string.heat_level_normal
    HeatLevel.WARM -> R.string.heat_level_warm
    HeatLevel.HOT -> R.string.heat_level_hot
    HeatLevel.VERY_HOT -> R.string.heat_level_very_hot
    HeatLevel.EXTREME -> R.string.heat_level_extreme
}

/** Selects the UV wording independently of heat levels, so HIGH remains the UV category's label. */
@StringRes
fun uvCategoryResource(category: UvCategory): Int = when (category) {
    UvCategory.LOW -> R.string.uv_category_low
    UvCategory.MODERATE -> R.string.uv_category_moderate
    UvCategory.HIGH -> R.string.uv_category_high
    UvCategory.VERY_HIGH -> R.string.uv_category_very_high
    UvCategory.EXTREME -> R.string.uv_category_extreme
}

/** Selects the source chip's visible and spoken wording without altering its stable identifier. */
@StringRes
fun sourceLabelResource(label: SourceLabel): Int = when (label) {
    SourceLabel.BOM -> R.string.source_bom
    SourceLabel.WORKSAFE_VIC -> R.string.source_worksafe_vic
    SourceLabel.SAFE_WORK_AUSTRALIA -> R.string.source_safe_work_australia
    SourceLabel.WORKPLACE_RULE_EXAMPLE -> R.string.source_workplace_rule
    SourceLabel.UNION_STARTING_POINT -> R.string.source_union
    SourceLabel.HEATSHIELD_RULE -> R.string.source_heatshield_rule
    SourceLabel.YOUR_SETTING -> R.string.source_your_setting
}

/** Shows a heat-level word using the current screen's resources. */
val HeatLevel.word: String
    @Composable get() = stringResource(heatLevelResource(this))

/** Shows a UV-category word using the current screen's resources. */
val UvCategory.word: String
    @Composable get() = stringResource(uvCategoryResource(this))

/** Shows the same source wording in a chip and its spoken description. */
val SourceLabel.display: String
    @Composable get() = stringResource(sourceLabelResource(this))

/**
 * Finds display copy for a saved job key, including old choices; null means a worker's own title.
 * Example: stored "Push mowing" always selects its resource even if that resource's wording changes.
 */
@StringRes
fun workTaskResource(storedName: String): Int? = when (storedName) {
    "Push mowing" -> R.string.work_task_push_mowing
    "Ride-on mowing" -> R.string.work_task_ride_on_mowing
    "Digging & planting" -> R.string.work_task_digging_planting
    "Pruning & hedging" -> R.string.work_task_pruning_hedging
    "Weeding & mulching" -> R.string.work_task_weeding_mulching
    "Irrigation & checks" -> R.string.work_task_irrigation_checks
    "Litter & paths" -> R.string.work_task_litter_paths
    "Planting beds" -> R.string.work_task_legacy_planting
    "Mowing" -> R.string.work_task_legacy_mowing
    "Pruning" -> R.string.work_task_legacy_pruning
    else -> null
}

/** Displays a known job from resources and preserves a worker's own unknown title verbatim. */
@Composable
internal fun workTaskName(storedName: String): String = workTaskResource(storedName)?.let { stringResource(it) } ?: storedName

/** Displays a window's three maxima with resource wording; rounding belongs to the domain calculation. */
val heatshield.domain.WorkWindow.summary: String
    @Composable get() = stringResource(R.string.work_window_summary, maxAirC, maxHumidityPct, uvCategory.word)

/** Chooses a forecast failure's current wording; no raw stored error text is shown to the worker. */
@StringRes
fun forecastFailureResource(failure: heatshield.data.ForecastFailure): Int = when (failure) {
    heatshield.data.ForecastFailure.SERVICE -> R.string.forecast_service_failed
    heatshield.data.ForecastFailure.CONNECTION -> R.string.forecast_connection_failed
    heatshield.data.ForecastFailure.INCOMPLETE -> R.string.forecast_incomplete_failed
    heatshield.data.ForecastFailure.SAVED_UNREADABLE -> R.string.forecast_saved_unreadable
}

/** Shows a current or legacy forecast failure through resources; null keeps the error line absent. */
@Composable
internal fun forecastFailureText(stored: String?): String? =
    heatshield.data.ForecastFailure.fromStored(stored)?.let { stringResource(forecastFailureResource(it)) }
