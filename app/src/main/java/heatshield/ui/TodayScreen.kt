@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import heatshield.R
import heatshield.background.AlertStatus
import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.domain.BreakRule
import heatshield.domain.BreakSpan
import heatshield.domain.Cadence
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.NextStep
import heatshield.domain.ShiftRules
import heatshield.domain.SourceLabel
import heatshield.domain.TodayHeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val LevelWordStyle = TextStyle(fontFamily = AtkinsonNext, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
private val NumeralStyle = TextStyle(fontFamily = AtkinsonMono, fontSize = 88.sp, lineHeight = 1.em, fontWeight = FontWeight.SemiBold)
/** A Plan window as `compare()` labels it and Room stores it: "07:00–09:00". */
private val SavedWindowLabel = Regex("""(\d{2}):00–(\d{2}):00""")

/**
 * Today, wired to the ViewModel; everything it shows comes from [TodayContent]. The shift panel's actions go to the
 * ViewModel, except Start shift, the break sheet and End shift's summary, which open their screens.
 */
@Composable
fun TodayScreen(
    model: HeatShieldViewModel, onOpenPreview: () -> Unit, onOpenPlan: () -> Unit, onOpenStartShift: () -> Unit,
    onOpenBreak: () -> Unit, onOpenSummary: (Int) -> Unit, onOpenFirstAid: () -> Unit
) {
    val site = model.context.site
    LaunchedEffect(site) { model.previewForecast(site) }
    val heat = model.todayHeat
    // A saved window shows only for its own day and park. Read the label, not reminderAt, which a finished break clears.
    val savedWindow = model.plan?.takeIf { it.context.date == heat.date && it.site == heat.site }?.window
    TodayContent(
        heat = heat, sites = model.sites.map { it.name }, refreshing = model.refreshing, nowMillis = model.now,
        onSelectSite = model::selectSite, onRefresh = { model.refreshForecast() },
        onOpenPreview = onOpenPreview, onOpenPlan = onOpenPlan,
        onOpenFirstAid = onOpenFirstAid,
        shift = model.shiftState, breakRule = model.breakRule,
        alertStatus = model.alertStatus, showTimerAgain = model.showTimerAgain, onShowTimerAgain = model::showTimerAgain,
        onShiftAction = { action ->
            when (action) {
                ShiftAction.OpenStart -> onOpenStartShift()
                ShiftAction.StartBreak -> model.startBreak()
                ShiftAction.EndBreak -> model.endBreak()
                ShiftAction.OpenBreakSheet -> onOpenBreak()
                ShiftAction.EndShift -> model.endShift(onOpenSummary)
                is ShiftAction.Scheduled -> model.confirmScheduled(action.kind, action.taken)
                is ShiftAction.HeavyWork -> model.setHeavyWork(action.on)
                ShiftAction.OpenFirstAid -> onOpenFirstAid()
            }
        },
        // The same next step the break sheet opens on, with the open shift's standing answer.
        nextStep = ContextReasoner.nextStep(heat, model.openAnswer, model.breakRule), savedWindow = savedWindow
    )
}

/**
 * Stateless Today: hero, ribbon and sourced lines for [heat], or a forecast card when there is no heat to show.
 * [nextStep] is `ContextReasoner.nextStep` for today. An open answer gets its own line in every state; the stop line
 * and the cadence come from [heat] itself, so they stay on screen under an open answer.
 * [savedWindow] is today's stored Plan label at this park ("07:00–09:00"), or null.
 * [shift] drives the [ShiftPanel] under the hero (or under the forecast card), which owns Today's one filled action;
 * its actions go to [onShiftAction]. While a shift runs, Today follows the shift's park, so the eyebrow names it and
 * offers no park switcher. [breakRule] is the gardener's break timing, for the cadence line and the union stop line.
 */
@Composable
fun TodayContent(
    heat: TodayHeat, sites: List<String>, refreshing: Boolean, nowMillis: Long,
    onSelectSite: (String) -> Unit, onRefresh: () -> Unit, onOpenPreview: () -> Unit,
    onOpenPlan: () -> Unit, onOpenFirstAid: () -> Unit,
    shift: ShiftUiState = ShiftUiState.Off, onShiftAction: (ShiftAction) -> Unit = {}, breakRule: BreakRule = BreakRule.HEATSHIELD,
    alertStatus: AlertStatus? = null, showTimerAgain: Boolean = false, onShowTimerAgain: () -> Unit = {},
    nextStep: NextStep = NextStep.None, savedWindow: String? = null, modifier: Modifier = Modifier
) {
    val use24Hour = rememberUse24Hour()
    val nowLocal = Instant.ofEpochMilli(nowMillis).atZone(MelbourneZone).toLocalDateTime()
    val lines = todayLines(heat, nowMillis, use24Hour, breakRule)
    val whyLabel = stringResource(R.string.today_why_open)
    // The Why row for what was tapped; the sheet opens with it first. Null while the sheet is closed.
    var whyFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val peak = heat.peak
    // On shift the heat is the shift's park: the eyebrow names it and changes nothing (the shift chose the park).
    val parkSwitcher: @Composable () -> Unit = if (shift is ShiftUiState.Off) {
        { ParkSwitcher(heat.date, heat.site, sites, onSelectSite) }
    } else {
        { Text(stringResource(R.string.today_eyebrow, Formats.dayDate(heat.date), heat.site), Modifier.testTag("today_eyebrow"),
            style = MaterialTheme.typography.labelLarge) }
    }
    // The union table, adapted by HeatShield, stops all work outdoors from a heat number of 36° (unionStopShown). The
    // Extreme line stops heavy work only, so neither hides the other. Read from the heat itself, as the Extreme line is,
    // so it stays on screen under an open answer, and timed like it, so a cooler morning is not told to stop yet.
    val unionLine = if (unionStopShown(heat, breakRule)) unionStopLine(heat, use24Hour) else null
    val unionFirst = unionLine != null && unionStopFirst(heat)
    // A1: an answer the gardener gave stays the next step in every state, even with no forecast, until a timed break
    // closes it. It opens the break sheet. StopHeavyWork and BreakSuggested already have their own lines (stop, cadence).
    val openAnswerLine: (@Composable () -> Unit)? = (nextStep as? NextStep.OpenAnswer)?.let { open ->
        {
            HeatLine(stringResource(R.string.today_next_answer, answerLine(open.kind, breakDueAgainAt(shift), use24Hour)), "today_line_next",
                stringResource(R.string.screen_title_break), icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                onClick = { onShiftAction(ShiftAction.OpenBreakSheet) })
        }
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        PageColumn(maxWidth = 840.dp) {
            alertStatus?.let { AlertsBanner(it) }
            if (peak == null) {
                val tag = when (heat.freshness) {
                    Freshness.MISSING -> "today_missing"
                    Freshness.STALE -> "today_stale"
                    else -> "today_no_hours"
                }
                ForecastStatusCard(statusMessage(heat, refreshing, nowMillis, alertStatus?.alertsOn == true), forecastFailureText(heat.error), refreshing,
                    tag = tag, onRefresh = onRefresh, eyebrow = parkSwitcher)
                // The panel owns Today's filled action. With no forecast there is no Why sheet, so its status opens nothing.
                ShiftPanel(shift, onShiftAction, breakRule = breakRule)
                if (showTimerAgain && shift !is ShiftUiState.Off) TextButton(onClick = onShowTimerAgain, modifier = Modifier.heightIn(min = 48.dp).testTag("today_show_timer")) { Text(stringResource(R.string.today_show_timer)) }
            } else {
                // A forecast over 6 h old is flagged above the numbers it qualifies, not below them.
                if (heat.freshness == Freshness.STALE) ForecastStatusCard(statusMessage(heat, refreshing, nowMillis, alertStatus?.alertsOn == true),
                    forecastFailureText(heat.error), refreshing, tag = "today_stale", onRefresh = onRefresh)
                val peakTime = Formats.time(peak.time.toLocalTime(), use24Hour)
                val nowDegrees = heat.now?.let { Formats.temperature(it.heat) }
                val numeral = Formats.temperature(peak.heat)
                val air = Formats.temperature(peak.airC)
                // A3: with shift hours missing, the peak is only the highest hour the forecast has.
                val whole = heat.coverage.complete
                val caption = if (nowDegrees == null) {
                    stringResource(if (whole) R.string.today_caption_peak else R.string.today_caption_partial, peakTime)
                } else {
                    stringResource(if (whole) R.string.today_caption_peak_now else R.string.today_caption_partial_now,
                        peakTime, nowDegrees)
                }
                HeatHero(
                    level = heat.dayLevel, numeral = numeral,
                    levelQualifier = if (heat.bumped) stringResource(R.string.today_level_qualifier) else null,
                    caption = caption, eyebrow = parkSwitcher, onClick = { whyFocus = "level" },
                    // Compare the digits shown, so the label never calls a number "feels like" when it reads the same as air.
                    numeralLabel = if (numeral != air) stringResource(R.string.today_numeral_feels, air)
                        else stringResource(R.string.today_numeral_air)
                )
                // With both stop lines, the one that starts earlier comes first (unionStopFirst).
                val unionStop: @Composable () -> Unit = {
                    unionLine?.let { HeatLine(it, "today_line_stop", whyLabel, onClick = { whyFocus = "next_break" }) }
                }
                if (unionFirst) unionStop()
                lines.extreme?.let { HeatLine(it, "today_extreme", whyLabel, emphasis = true) { whyFocus = "extreme" } }
                if (!unionFirst) unionStop()
                ShiftPanel(shift, onShiftAction, onStatusClick = { whyFocus = "next_break" }, breakRule = breakRule)
                if (showTimerAgain && shift !is ShiftUiState.Off) TextButton(onClick = onShowTimerAgain, modifier = Modifier.heightIn(min = 48.dp).testTag("today_show_timer")) { Text(stringResource(R.string.today_show_timer)) }
                HeatRibbon(heat.hours, nowLocal, heat.crewRule.triggerC, heat.sunProtection, missing = heat.coverage.missing,
                    breaks = ribbonBreaks(shift))
                // Silent keys tie a line to its mark on the ribbon: the hatch on hours at the rule, the bar for sun protection.
                // Typed locals, not takeIf on a lambda, so each lambda keeps its @Composable type.
                val crewKey: (@Composable () -> Unit)? = if (heat.crewRule.firstHour != null && heat.crewRule.hoursInShift > 0) {
                    { RibbonKeyHatch(level = heat.hours.firstOrNull { it.overTrigger }?.effective ?: HeatLevel.VERY_HOT) }
                } else null
                val uvKey: (@Composable () -> Unit)? = if (heat.sunProtection != null) { { RibbonKeyUv() } } else null
                Column(Modifier.fillMaxWidth()) {
                    HeatLine(lines.crew, "today_line_crew", whyLabel, leading = crewKey) { whyFocus = "crew_rule" }
                    HeatLine(lines.heavy, "today_line_heavy", whyLabel) { whyFocus = "heavy_jobs" }
                    savedWindowText(savedWindow, use24Hour)?.let {
                        HeatLine(it, "today_line_plan", stringResource(R.string.today_plan),
                            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight, onClick = onOpenPlan)
                    }
                    lines.uv?.let { HeatLine(it, "today_line_uv", whyLabel, leading = uvKey) { whyFocus = "uv" } }
                    lines.cadence?.let { HeatLine(it, "today_line_cadence", whyLabel) { whyFocus = "cadence" } }
                    // Spec §10: the next step comes before the forecast stamp, which closes the lines.
                    openAnswerLine?.invoke()
                    lines.updated?.let { HeatLine(it, "today_line_updated", whyLabel, muted = true) { whyFocus = "forecast_age" } }
                    if (heat.freshness == Freshness.AGING) forecastFailureText(heat.error)?.let { NoticeText(it) }
                }
            }
            // With no hero there are no lines, so the open answer follows the forecast card.
            if (peak == null) openAnswerLine?.invoke()
            if (heat.dayLevel == HeatLevel.NORMAL && shift is ShiftUiState.Off) HeatLine(
                stringResource(if (peak == null) R.string.today_preview_plain else R.string.today_preview_cool),
                "today_preview_row", stringResource(R.string.today_preview_open),
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight, onClick = onOpenPreview
            )
            Column(Modifier.fillMaxWidth()) {
                // Spec §5.6: someone unwell opens first aid whatever the level, so the link shows in every state.
                TextButton(
                    onClick = onOpenFirstAid, modifier = Modifier.heightIn(min = 48.dp).testTag("today_unwell"),
                    colors = ButtonDefaults.textButtonColors(contentColor = HeatShieldTheme.colors.emergency)
                ) { Text(stringResource(R.string.today_unwell)) }
                TextButton(onClick = onOpenPlan, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_plan)) }
            }
        }
    }
    val focus = whyFocus
    if (focus != null && peak != null) {
        // The next-break row explains the panel's status on shift, or the union table's stop line off shift.
        val nextBreak = (shift as? ShiftUiState.On)?.let { shiftStatusText(it.evaluation, use24Hour) } ?: unionLine
        WhySheet(whyRows(heat, lines, use24Hour, nextBreak, breakRule), focusKey = focus, onDismiss = { whyFocus = null })
    }
}

/**
 * The ribbon's Action notches: confirmed breaks, and the break running now up to the shift's own now, the same time
 * the break card's clock counts from (Global Constraint 2), never the wall clock.
 */
private fun ribbonBreaks(shift: ShiftUiState): List<BreakSpan> = when (shift) {
    ShiftUiState.Off -> emptyList()
    is ShiftUiState.On -> shift.evaluation.breaks
    is ShiftUiState.OnBreak -> shift.evaluation.breaks +
        ShiftRules.instant(shift.breakEvent.startedAt).let { start -> BreakSpan(start, maxOf(start, shift.evaluation.now)) }
}

/**
 * When a "Can't stop yet" answer brings Break due back, for the open-answer line: the evaluation's own snoozed due time
 * (ShiftEvaluation.breakDueAgainAt), null when a stop comes first or off shift, so no break time shows past the stop line.
 * Null on a break too: the break answers the snooze, so the line never names a time that has been overtaken.
 * Example: "Can't stop yet" at 8:00 (back at 8:10), then a break from 8:11: the line says only "You said: Can't stop yet".
 */
private fun breakDueAgainAt(shift: ShiftUiState): Instant? = when (shift) {
    is ShiftUiState.On -> shift.evaluation.breakDueAgainAt
    is ShiftUiState.OnBreak, ShiftUiState.Off -> null
}

/**
 * The level hero: eyebrow, bars and level word, one auto-sized numeral and a caption, on the level's own fill.
 * Reused by Preview. [onClick] makes the body (not the eyebrow) a "Why this?" button. [numeralLabel] names the
 * quantity the numeral shows (feels like or air), so it is never read against an air-temperature rule by mistake.
 */
@Composable
fun HeatHero(
    level: HeatLevel, numeral: String, levelQualifier: String?, caption: String,
    eyebrow: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    numeralLabel: String? = null
) {
    val dark = HeatShieldTheme.colors.isDark
    val background by animateColorAsState(HeatPalette.heroBackground(level, dark), tween(400), label = "heroBackground")
    val content by animateColorAsState(HeatPalette.heroContent(level, dark), tween(400), label = "heroContent")
    val whyLabel = stringResource(R.string.today_why_open)
    Card(
        modifier.fillMaxWidth().testTag("heat_hero"), shape = UiShape.card,
        colors = CardDefaults.cardColors(containerColor = background, contentColor = content)
    ) {
        Column(Modifier.padding(UiSpace.section), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            eyebrow()
            Column(
                Modifier.fillMaxWidth().then(
                    if (onClick == null) Modifier
                    else Modifier.clickable(onClickLabel = whyLabel, role = Role.Button, onClick = onClick)
                ),
                verticalArrangement = Arrangement.spacedBy(UiSpace.small)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiSpace.compact)) {
                    LevelBars(level, content)
                    Column(Modifier.weight(1f)) {
                        Text(level.word, Modifier.fillMaxWidth().testTag("hero_level").semantics { heading() }, style = LevelWordStyle, color = content)
                        if (levelQualifier != null) Text(levelQualifier, Modifier.testTag("hero_qualifier"),
                            style = MaterialTheme.typography.labelLarge, color = content)
                    }
                }
                BasicText(
                    numeral, Modifier.fillMaxWidth().testTag("hero_numeral"), style = NumeralStyle.copy(color = content),
                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 56.sp, maxFontSize = 88.sp, stepSize = 4.sp)
                )
                if (numeralLabel != null) Text(numeralLabel, Modifier.fillMaxWidth().testTag("hero_numeral_label"),
                    style = MaterialTheme.typography.labelLarge, color = content)
                Text(caption, Modifier.fillMaxWidth().testTag("hero_caption"), style = MaterialTheme.typography.bodyLarge, color = content)
            }
        }
    }
}

/** "Fri 9 Jan · Royal Park ▾": the date and the park, opening an exposed dropdown of every park. */
@Composable
private fun ParkSwitcher(date: LocalDate, site: String, sites: List<String>, onSelectSite: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Row(
            Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).focusable()
                .heightIn(min = 48.dp).testTag("park_switcher").semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The text wraps at large font instead of measuring the dropdown arrow down to nothing.
            Text(stringResource(R.string.today_eyebrow, Formats.dayDate(date), site), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.today_change_park))
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sites.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    if (option != site) onSelectSite(option)
                })
            }
        }
    }
}

/**
 * One Today line. By default it opens the Why sheet and carries an info mark; rows that open another screen pass a
 * chevron as [icon]. [emphasis] is the Extreme instruction: section style, announced when it appears. [leading] is a
 * silent ribbon key (Task 7) drawn before the text; it adds nothing to what the line says.
 */
@Composable
private fun HeatLine(
    text: String, tag: String, clickLabel: String, muted: Boolean = false, emphasis: Boolean = false,
    icon: ImageVector = Icons.Outlined.Info, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .then(if (emphasis) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .padding(vertical = UiSpace.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiSpace.small)
    ) {
        leading?.invoke()
        Text(
            text, Modifier.weight(1f),
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (muted) Muted else Color.Unspecified
        )
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Muted)
    }
}

/** The forecast's state and Retry. Retry is tonal: the shift panel owns Today's one filled action. */
@Composable
private fun ForecastStatusCard(
    message: String, error: String?, refreshing: Boolean, tag: String,
    onRefresh: () -> Unit, eyebrow: (@Composable () -> Unit)? = null
) {
    SurfaceCard(Modifier.testTag(tag)) {
        eyebrow?.invoke()
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
        if (error != null) NoticeText(error)
        TonalButton(stringResource(if (refreshing) R.string.today_retrying else R.string.today_retry), enabled = !refreshing, onClick = onRefresh)
    }
}

private data class TodayLines(
    val extreme: String?, val crew: String, val heavy: String, val uv: String?, val cadence: String?, val updated: String?
)

@Composable
private fun todayLines(heat: TodayHeat, nowMillis: Long, use24Hour: Boolean, breakRule: BreakRule): TodayLines {
    val nowLocal = Instant.ofEpochMilli(nowMillis).atZone(MelbourneZone).toLocalDateTime()
    val firstShiftHour = heat.hours.firstOrNull()?.time?.toLocalTime()
    val incomplete = !heat.coverage.complete
    val crewRule = heat.crewRule
    val rule = Formats.temperature(crewRule.triggerC)
    val firstOver = crewRule.firstHour
    val crew = when {
        firstOver != null && crewRule.hoursInShift > 0 -> {
            val known = pluralStringResource(R.plurals.today_crew_passes, crewRule.hoursInShift,
                rule, Formats.time(firstOver, use24Hour), crewRule.hoursInShift)
            if (incomplete) stringResource(R.string.today_crew_passes_partial, known) else known
        }
        incomplete -> stringResource(R.string.today_crew_under_partial, rule)
        else -> stringResource(R.string.today_crew_under, rule)
    }
    val cutoff = heat.heavyJobsBefore
    // The cut-off hour itself, dated: a night shift's hours cross midnight, so today's date may not be the hour's.
    val cutoffAt = cutoff?.let { time -> heat.hours.firstOrNull { it.time.toLocalTime() == time }?.time ?: heat.date.atTime(time) }
    val heavy = when {
        cutoff == null && incomplete -> stringResource(R.string.today_heavy_none_partial)
        cutoff == null -> stringResource(R.string.today_heavy_none)
        incomplete -> stringResource(R.string.today_heavy_found_partial, Formats.time(cutoff, use24Hour))
        cutoff == firstShiftHour -> stringResource(R.string.today_heavy_from_start)
        cutoffAt != null && !nowLocal.isBefore(cutoffAt) -> stringResource(R.string.today_heavy_past, Formats.time(cutoff, use24Hour))
        else -> stringResource(R.string.today_heavy_before, Formats.time(cutoff, use24Hour))
    }
    val uvPeak = heat.uvPeak
    val window = heat.sunProtection
    val uv = when {
        uvPeak == null -> null
        !heat.uvCoverage.complete -> stringResource(R.string.today_uv_partial, uvPeak.word)
        window == null -> stringResource(R.string.today_uv_category, uvPeak.word)
        else -> stringResource(R.string.today_uv_window, uvPeak.word,
            Formats.time(window.from, use24Hour), Formats.time(window.until, use24Hour))
    }
    // A8: no work/break split at Extreme. The stop line sits under the hero; this line is for a crew that keeps working.
    // Otherwise the rhythm of the break table in use, as the break sheet and the panel's walk read it (dayCadence): the
    // union table's row has no level gate of its own, since it has no row below 30°.
    val cadence = if (heat.dayLevel == HeatLevel.EXTREME) stringResource(R.string.today_cadence_extreme)
        else ContextReasoner.dayCadence(heat, breakRule)?.takeIf {
            (breakRule == BreakRule.UNION_VTHC || heat.dayLevel.atLeast(HeatLevel.WARM)) &&
                (heat.freshness == Freshness.FRESH || heat.freshness == Freshness.AGING)
        }?.let { cadenceText(heat, it) }
    return TodayLines(
        extreme = extremeLine(heat, use24Hour), crew = crew, heavy = heavy, uv = uv, cadence = cadence,
        updated = updatedText(heat, nowMillis)
    )
}

/**
 * Spec §5.10 and §12: at Extreme, stop heavy work outdoors. Timed from the first Extreme shift hour, so a cooler
 * morning is not told to stop yet; no time when the shift starts Extreme or the current hour already is. Null when no
 * shift hour is Extreme. Today's `today_extreme` line and the break check-in's `NextStep.StopHeavyWork` line share it.
 */
@Composable
internal fun extremeLine(heat: TodayHeat, use24Hour: Boolean): String? {
    val extremeFrom = heat.hours.firstOrNull { ShiftRules.stopNow(it, BreakRule.HEATSHIELD) }?.time ?: return null
    return if (extremeFrom == heat.hours.first().time || ShiftRules.stopNow(heat.now, BreakRule.HEATSHIELD))
        stringResource(R.string.today_extreme_now)
    else stringResource(R.string.today_extreme_from, Formats.time(extremeFrom.toLocalTime(), use24Hour))
}

/**
 * Whether the union table's stop line shows: under [breakRule] [BreakRule.UNION_VTHC], whenever a shift hour reaches a
 * heat number of 36°, whatever the Extreme line says. The two lines say different things: the Extreme line (personal
 * bumps count) stops heavy work only, the union line stops all work outdoors, so one never stands in for the other.
 * Today and the break check-in both ask this.
 * Example: heavy work, 35° at 10:00 (Extreme for you) and 36° at 11:00 → true: "From 10:00: stop heavy work…" and
 * "From 11:00: stop work outdoors…" both show. Under the HeatShield rule → false.
 */
internal fun unionStopShown(heat: TodayHeat, breakRule: BreakRule): Boolean =
    breakRule == BreakRule.UNION_VTHC && heat.hours.any { ShiftRules.stopNow(it, BreakRule.UNION_VTHC) }

/**
 * Where the union stop line goes when it shows: first (true) when its first 36° hour comes at or before the first
 * Extreme hour, or no hour is Extreme; after the Extreme line (false) when that starts earlier. Earlier first, and on
 * a tie the union line first, since it stops all work. Today and the break check-in order the two lines the same way.
 * Example: 36° at 10:00 and Extreme (38°) at 14:00 → true; heavy work with Extreme (35°) at 10:00 and 36° at 11:00 →
 * false; a first 36° hour that is already 38° (both from 11:00) → true.
 */
internal fun unionStopFirst(heat: TodayHeat): Boolean {
    val unionFrom = heat.hours.firstOrNull { ShiftRules.stopNow(it, BreakRule.UNION_VTHC) }?.time ?: return false
    val extremeFrom = heat.hours.firstOrNull { ShiftRules.stopNow(it, BreakRule.HEATSHIELD) }?.time ?: return true
    return !unionFrom.isAfter(extremeFrom)
}

/**
 * The union table's stop line (a heat number of 36° or more, adapted by HeatShield), as Today, the break check-in and
 * the panel time it. Mirrors [extremeLine]: from the first shift hour at 36° or more, so a cooler morning is not told to
 * stop yet; no time when the shift starts there or the current hour already is. Null when no shift hour reaches 36°.
 * Callers show it when [unionStopShown].
 */
@Composable
internal fun unionStopLine(heat: TodayHeat, use24Hour: Boolean): String? {
    val stopFrom = heat.hours.firstOrNull { ShiftRules.stopNow(it, BreakRule.UNION_VTHC) } ?: return null
    return if (stopFrom.time == heat.hours.first().time || ShiftRules.stopNow(heat.now, BreakRule.UNION_VTHC)) stringResource(R.string.shift_union_stop_line)
    else stringResource(R.string.shift_union_stop_from_line, Formats.time(stopFrom.time.toLocalTime(), use24Hour))
}

/**
 * A Warm, Hot or Very hot rhythm as Today's cadence line and the check-in both say it. Once air reaches the crew rule
 * work may stop, so the rhythm is then only for work that goes ahead.
 */
@Composable
internal fun cadenceText(heat: TodayHeat, cadence: Cadence): String =
    stringResource(if (heat.crewRule.hoursInShift > 0) R.string.today_cadence_if_working else R.string.today_cadence,
        cadence.workMinutes, cadence.breakMinutes)

/** Today's saved Plan window, stored as "07:00–09:00", in the phone's time format; null if there is none. */
@Composable
private fun savedWindowText(label: String?, use24Hour: Boolean): String? {
    val match = label?.let { SavedWindowLabel.matchEntire(it) } ?: return null
    val from = match.groupValues[1].toInt()
    val until = match.groupValues[2].toInt()
    if (from !in 0..23 || until !in 0..23) return null
    return stringResource(R.string.today_plan_saved,
        Formats.time(LocalTime.of(from, 0), use24Hour), Formats.time(LocalTime.of(until, 0), use24Hour))
}

@Composable
private fun updatedText(heat: TodayHeat, nowMillis: Long): String? {
    val updatedAt = heat.updatedAtMillis ?: return null
    // The debug demo fixture's invented day is never presented as Open-Meteo's forecast.
    if (heat.refreshedBy == ForecastSnapshot.REFRESHED_BY_DEMO) return stringResource(R.string.today_updated_demo)
    val minutes = ((nowMillis - updatedAt) / 60_000L).toInt()
    if (minutes < 0) return null
    val age = when {
        minutes < 1 -> stringResource(R.string.today_updated_now)
        minutes < 60 -> pluralStringResource(R.plurals.today_updated_minutes, minutes, minutes)
        else -> pluralStringResource(R.plurals.today_updated_hours, minutes / 60, minutes / 60)
    }
    return if (heat.refreshedBy == ForecastSnapshot.REFRESHED_BY_BACKGROUND) stringResource(R.string.today_updated_background, age) else age
}

@Composable
private fun statusMessage(heat: TodayHeat, refreshing: Boolean, nowMillis: Long, alertsOn: Boolean): String = when (heat.freshness) {
    Freshness.MISSING -> stringResource(if (refreshing) R.string.today_loading else R.string.today_missing)
    Freshness.STALE -> staleMessage(heat, nowMillis, alertsOn)
    else -> stringResource(R.string.today_no_shift_hours)
}

/**
 * How old the forecast is. With break-due alerts on, it also says they pause (spec §5.13): a stale forecast times no break
 * and nudges no start, so Today says why the phone has gone quiet.
 */
@Composable
private fun staleMessage(heat: TodayHeat, nowMillis: Long, alertsOn: Boolean): String {
    val age = heat.updatedAtMillis?.let { nowMillis - it }
    val message = if (age == null || age <= ForecastSnapshot.STALE_AFTER_MILLIS) stringResource(R.string.today_stale_not_today) else {
        val hours = (age / 3_600_000L).toInt()
        if (hours >= 48) pluralStringResource(R.plurals.today_stale_days, hours / 24, hours / 24)
        else pluralStringResource(R.plurals.today_stale_hours, hours, hours)
    }
    return if (alertsOn) message + " " + stringResource(R.string.today_alerts_paused) else message
}

/**
 * The Why rows behind every number on Today, each with exactly one source. The air temperature sits in the feels-like
 * row: its BOM chip covers the shade formula and its rule names the Open-Meteo forecast, so no forecast number is
 * filed under a regulator's name. Adjustments appear only when they changed the level. A7: the level and every action
 * HeatShield composes (adjustments, the stop line, heavy jobs, cadence) carry [SourceLabel.HEATSHIELD_RULE], with what
 * they borrow named in the rule text. The crew row is a workplace rule example for a preset and the gardener's own
 * setting for any other number. [nextBreak] is the next-break line (the shift status, or the union table's stop line);
 * its row follows the cadence row and is the app timer's own, under [breakRule]'s source.
 */
@Composable
private fun whyRows(heat: TodayHeat, lines: TodayLines, use24Hour: Boolean, nextBreak: String?, breakRule: BreakRule): List<WhyRow> {
    val peak = heat.peak ?: return emptyList()
    val peakTime = Formats.time(peak.time.toLocalTime(), use24Hour)
    val rule = Formats.temperature(heat.crewRule.triggerC)
    val crewRow = if (heat.crewRule.triggerC in HeatRules.TRIGGER_PRESETS)
        WhyRow("crew_rule", lines.crew, stringResource(R.string.why_crew_rule, rule), SourceLabel.WORKPLACE_RULE_EXAMPLE)
    else WhyRow("crew_rule", lines.crew, stringResource(R.string.why_crew_rule_custom, rule), SourceLabel.YOUR_SETTING)
    return listOfNotNull(
        // The hero says the raised level when personal factors raise it, so the level row opens on that same word.
        WhyRow("level", if (heat.bumped) stringResource(R.string.why_level_value_bumped, peak.effective.word,
                peak.base.word, Formats.temperature(peak.heat))
            else stringResource(R.string.why_level_value, peak.base.word, Formats.temperature(peak.heat)),
            stringResource(R.string.why_level_rule), HeatRules.levelSource(peak.base)),
        WhyRow("heat_number", stringResource(R.string.why_heat_value, Formats.temperature(peak.heat), peakTime),
            stringResource(R.string.why_heat_rule), SourceLabel.HEATSHIELD_RULE),
        heat.now?.let { now ->
            WhyRow("now", stringResource(R.string.why_now_value, Formats.temperature(now.heat),
                Formats.time(now.time.toLocalTime(), use24Hour)), stringResource(R.string.why_now_rule), SourceLabel.HEATSHIELD_RULE)
        },
        WhyRow("feels_like", stringResource(R.string.why_feels_value, Formats.temperature(peak.feelsLikeC),
            Formats.temperature(peak.airC), peakTime), stringResource(R.string.why_feels_rule), SourceLabel.BOM),
        if (!heat.bumped) null else WhyRow("adjustments", stringResource(R.string.why_adjust_bumped, peak.effective.word),
            stringResource(R.string.why_adjust_rule), SourceLabel.HEATSHIELD_RULE),
        lines.extreme?.let { WhyRow("extreme", it, stringResource(R.string.why_extreme_rule), SourceLabel.HEATSHIELD_RULE) },
        crewRow,
        WhyRow("heavy_jobs", lines.heavy, stringResource(R.string.why_heavy_rule), SourceLabel.HEATSHIELD_RULE),
        lines.uv?.let { WhyRow("uv", it, stringResource(R.string.why_uv_rule), SourceLabel.BOM) },
        lines.uv?.let {
            WhyRow("sun_all_year", stringResource(R.string.why_sun_all_year_value),
                stringResource(R.string.why_sun_all_year_rule), SourceLabel.WORKSAFE_VIC)
        },
        // The cadence row explains the table the line came from: HeatShield's, or its adaptation of the union table.
        WhyRow("cadence", lines.cadence ?: stringResource(when {
                heat.freshness != Freshness.FRESH && heat.freshness != Freshness.AGING -> R.string.why_cadence_unavailable
                breakRule == BreakRule.HEATSHIELD -> R.string.why_cadence_none
                HeatRules.wholeDegrees(peak.heat) >= HeatRules.UNION_STOP_C -> R.string.shift_union_stop_line
                else -> R.string.why_cadence_union_none
            }),
            stringResource(if (breakRule == BreakRule.UNION_VTHC) R.string.why_cadence_union_rule else R.string.why_cadence_rule), breakRule.source),
        nextBreak?.let {
            WhyRow("next_break", it, stringResource(if (breakRule == BreakRule.UNION_VTHC) R.string.why_next_break_union
                else R.string.why_next_break_rule), breakRule.source)
        },
        WhyRow("union_rest", stringResource(R.string.why_union_rest_value),
            stringResource(R.string.why_union_rest_rule), SourceLabel.UNION_STARTING_POINT),
        lines.updated?.let { WhyRow("forecast_age", it, stringResource(R.string.why_age_rule), SourceLabel.HEATSHIELD_RULE) }
    )
}
