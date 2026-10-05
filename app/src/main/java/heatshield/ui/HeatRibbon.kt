package heatshield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heatshield.R
import heatshield.domain.BreakSpan
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.ShiftRules
import heatshield.domain.SunProtection
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** Above this font scale the ribbon reflows to a vertical hour list (spec §7). */
private const val LARGE_FONT_SCALE = 1.3f
private val CellGap = 1.dp
private val TopBand = 8.dp          // holds the 3 dp UV line and the 6 dp needle dot above the cells
private val UvLine = 3.dp
private val DotRadius = 3.dp
private val HatchSpacing = 6.dp
private val HatchStroke = 1.5.dp
private val KeyWidth = 16.dp        // both Today keys, so the crew-rule and UV lines start their text at the same x
private val BreakNotch = 4.dp       // Ink notch along the bottom of the cells for each break on the shift
private val HourLabel = TextStyle(fontFamily = AtkinsonMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
private val HourNumber = TextStyle(fontFamily = AtkinsonMono, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp)

/**
 * One ribbon column: a forecast hour, or a shift hour the forecast does not cover ([hour] is null). [start] is the
 * hour's start; for a forecast hour it equals [HourHeat.time].
 */
private class RibbonSlot(val start: LocalDateTime, val hour: HourHeat?)

/**
 * The signature hour ribbon. Each forecast hour is a cell in its effective-level colour, hatched where air
 * reaches the crew rule ([HourHeat.overTrigger], whole air degrees against the rule). Each shift hour in [missing]
 * (TodayHeat.coverage.missing, dated) is an empty Line-coloured cell spoken "No forecast", in time order among the others.
 * A UV line runs over the sun-protection hours that have a forecast, and an Ink needle marks [nowTime].
 * Each of the shift's [breaks] (confirmed breaks and the one running now) is an Ink notch along the bottom of the
 * cells it spans; the hour semantics do not change, and at large font the hour list names each break under its hours.
 * One invisible semantics node per hour carries the spoken summary. Above 1.3× font it becomes an hour list.
 * With no forecast hours it draws nothing, whatever [missing] holds: Today shows its forecast card instead.
 */
@Composable
fun HeatRibbon(
    hours: List<HourHeat>, nowTime: LocalDateTime?, triggerC: Double, sunProtection: SunProtection?,
    modifier: Modifier = Modifier, height: Dp = 56.dp, missing: List<LocalDateTime> = emptyList(),
    breaks: List<BreakSpan> = emptyList()
) {
    if (hours.isEmpty()) return
    val use24Hour = rememberUse24Hour()
    val slots = slotsOf(hours, missing)
    val slotStarts = slots.map { it.start }
    val descriptions = slots.map { hourDescription(it, triggerC, use24Hour) }
    val nowLabel = stringResource(R.string.ribbon_now)
    val nowIndex = nowTime?.let { now ->
        slots.indexOfFirst { !now.isBefore(it.start) && now.isBefore(it.start.plusHours(1)) }
    } ?: -1
    if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) {
        HeatHourList(slots, descriptions, nowIndex, nowLabel, triggerC, use24Hour, modifier, breaks)
        return
    }
    val colors = HeatShieldTheme.colors
    // A missing hour is an empty cell in the Line colour: no level fill, no hatch and no UV line, because there is no data.
    val fills = slots.map { slot -> slot.hour?.let { HeatPalette.fill(it.effective, colors.isDark) } ?: colors.line }
    val hatch = hatchColor(colors)
    val needle = colors.ink
    val halo = colors.paper
    val uvColor = colors.secondarySeries
    val breakColor = colors.ink
    val uvHours = slots.map { sunProtection != null && it.hour != null && covers(sunProtection, it.start.toLocalTime()) }
    val nowFraction = if (nowTime == null || nowIndex < 0) 0f else Duration.between(slots[nowIndex].start, nowTime).seconds / 3600f
    Column(modifier.fillMaxWidth().semantics { testTag = "heat_ribbon" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(TopBand + height)) {
            Canvas(Modifier.matchParentSize()) {
                val gap = CellGap.toPx()
                val cell = (size.width - gap * (slots.size - 1)) / slots.size
                val top = TopBand.toPx()
                val bottom = size.height
                slots.forEachIndexed { index, slot ->
                    val left = index * (cell + gap)
                    drawRect(fills[index], Offset(left, top), Size(cell, bottom - top))
                    if (slot.hour?.overTrigger == true) drawHatch(hatch, left, top, left + cell, bottom)
                    if (uvHours[index]) {
                        val joinsNext = uvHours.getOrElse(index + 1) { false }
                        drawRect(uvColor, Offset(left, 0f), Size(cell + if (joinsNext) gap else 0f, UvLine.toPx()))
                    }
                }
                // Breaks are information rather than actions, so their notches use Ink.
                breaks.forEach { span ->
                    val from = ribbonBreakXAt(slotStarts, span.start, cell, gap)
                    val to = ribbonBreakXAt(slotStarts, span.end, cell, gap)
                    if (to > from) drawRect(breakColor, Offset(from, bottom - BreakNotch.toPx()), Size(to - from, BreakNotch.toPx()))
                }
                if (nowIndex >= 0) {
                    val x = nowIndex * (cell + gap) + cell * nowFraction
                    val dot = Offset(x, DotRadius.toPx())
                    drawLine(halo, dot, Offset(x, bottom), 4.dp.toPx())
                    drawLine(needle, dot, Offset(x, bottom), 2.dp.toPx())
                    drawCircle(halo, DotRadius.toPx() + 1.dp.toPx(), dot)
                    drawCircle(needle, DotRadius.toPx(), dot)
                }
            }
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                slots.forEachIndexed { index, slot ->
                    Box(Modifier.weight(1f).fillMaxHeight().semantics {
                        testTag = hourTag(if (slot.hour != null) "ribbon_hour_" else "ribbon_missing_", slot)
                        contentDescription = descriptions[index]
                        if (index == nowIndex) stateDescription = nowLabel
                    })
                }
            }
        }
        HourLabels(slots.map { Formats.time(it.start.toLocalTime(), use24Hour) }, colors.muted)
    }
}

/**
 * Places a saved break endpoint on the ribbon in pixels, clipped to its shown hours.
 * [slotStarts] are sorted Melbourne hour labels; [cell] and [gap] are pixel widths.
 * Each cell covers the real interval to the next local hour, so the repeated autumn hour keeps both occurrences.
 * Example: on 5 April 2026, second-occurrence 02:10–02:25 fills 70/120 to 85/120 of the 02:00 cell.
 */
internal fun ribbonBreakXAt(slotStarts: List<LocalDateTime>, t: Instant, cell: Float, gap: Float): Float {
    val index = slotStarts.indexOfLast { !t.isBefore(ShiftRules.instant(it)) }
    if (index < 0) return 0f
    val start = ShiftRules.instant(slotStarts[index])
    val end = ShiftRules.instant(slotStarts[index].plusHours(1))
    val seconds = Duration.between(start, end).seconds
    // A skipped spring hour has no real duration; its endpoint belongs at the cell's trailing edge.
    val fraction = if (seconds <= 0) 1f else (Duration.between(start, t).seconds / seconds.toFloat()).coerceIn(0f, 1f)
    return index * (cell + gap) + cell * fraction
}

/**
 * Forecast hours and missing shift hours, in time order. Missing hours carry their own dates, so a night shift's gap
 * after midnight sits after the evening's hours; a missing time that a forecast hour already fills is dropped.
 */
private fun slotsOf(hours: List<HourHeat>, missing: List<LocalDateTime>): List<RibbonSlot> {
    val filled = hours.map { it.time }.toSet()
    val gaps = missing.distinct().filter { it !in filled }.map { RibbonSlot(it, null) }
    return (hours.map { RibbonSlot(it.time, it) } + gaps).sortedBy { it.start }
}

/**
 * Key for the crew-rule hatch: a 16 dp square drawn like a hatched ribbon cell, with [level]'s fill and the same
 * hatch colour, spacing and stroke. Today puts it before the crew-rule line. Decorative, so it adds no semantics;
 * the line's text already says what the hatch means.
 */
@Composable
fun RibbonKeyHatch(modifier: Modifier = Modifier, level: HeatLevel = HeatLevel.VERY_HOT) {
    val colors = HeatShieldTheme.colors
    val fill = HeatPalette.fill(level, colors.isDark)
    val hatch = hatchColor(colors)
    Canvas(modifier.size(KeyWidth).clearAndSetSemantics { }) {
        drawRect(fill)
        drawHatch(hatch, 0f, 0f, size.width, size.height)
    }
}

/**
 * Key for the UV line: a 16 dp bar in Secondary Series at the UV line's 3 dp thickness. Today puts it before the
 * UV line. Decorative, so it adds no semantics.
 */
@Composable
fun RibbonKeyUv(modifier: Modifier = Modifier) {
    val color = HeatShieldTheme.colors.secondarySeries
    Box(modifier.size(width = KeyWidth, height = UvLine).background(color).clearAndSetSemantics { })
}

/** Labels every 2 hours under the cells. A label that would collide with the previous one is skipped. */
@Composable
private fun HourLabels(labels: List<String>, color: Color) {
    Layout(
        content = {
            labels.forEachIndexed { index, label ->
                if (index % 2 == 0) Text(label, style = HourLabel, color = color, maxLines = 1, softWrap = false)
            }
        },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = CellGap.toPx()
        val cell = (width - gap * (labels.size - 1)) / labels.size
        val spacing = 8.dp.roundToPx()
        var lastEnd = Int.MIN_VALUE
        val placed = measurables.mapIndexedNotNull { slot, measurable ->
            val placeable = measurable.measure(Constraints())
            val x = (slot * 2 * (cell + gap)).toInt().coerceAtMost(width - placeable.width).coerceAtLeast(0)
            if (x < lastEnd + spacing) null else { lastEnd = x + placeable.width; placeable to x }
        }
        layout(width, placed.maxOfOrNull { it.first.height } ?: 0) {
            placed.forEach { (placeable, x) -> placeable.place(x, 0) }
        }
    }
}

/**
 * The large-font reflow: one row per hour, with no drawing, so text wraps instead of clipping. A missing hour shows
 * a Line-coloured swatch, its time and "No forecast", with no number or level. Each break that overlaps an hour is
 * named under that hour's row ("Break 10:02 am – 10:17 am"), as its own text, since the row speaks only its summary.
 */
@Composable
private fun HeatHourList(
    slots: List<RibbonSlot>, descriptions: List<String>, nowIndex: Int, nowLabel: String,
    triggerC: Double, use24Hour: Boolean, modifier: Modifier = Modifier, breaks: List<BreakSpan> = emptyList()
) {
    val colors = HeatShieldTheme.colors
    val overRule = stringResource(R.string.ribbon_over_rule, Formats.temperature(triggerC))
    val noForecast = stringResource(R.string.ribbon_no_forecast)
    Column(modifier.fillMaxWidth().semantics { testTag = "heat_hour_list" }) {
        slots.forEachIndexed { index, slot ->
            val hour = slot.hour
            val hourBreaks = breaks.filter { it.start.isBefore(ShiftRules.instant(slot.start.plusHours(1))) && it.end.isAfter(ShiftRules.instant(slot.start)) }
            if (index > 0) HorizontalDivider(color = colors.line)
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    testTag = hourTag(if (hour != null) "hour_row_" else "missing_row_", slot)
                    contentDescription = descriptions[index]
                    if (index == nowIndex) stateDescription = nowLabel
                }.padding(top = 12.dp, bottom = if (hourBreaks.isEmpty()) 12.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val swatch = if (hour != null) HeatPalette.fill(hour.effective, colors.isDark) else colors.line
                Box(Modifier.padding(top = 4.dp).size(width = 8.dp, height = 32.dp)
                    .background(swatch, RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Formats.time(slot.start.toLocalTime(), use24Hour), Modifier.weight(1f), style = HourLabel, color = colors.muted)
                        if (hour != null) Text(Formats.temperature(hour.heat), style = HourNumber, color = colors.ink)
                    }
                    if (hour == null) {
                        Text(noForecast, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LevelBars(hour.effective, colors.ink)
                            Text(hour.effective.word, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold, color = colors.ink)
                        }
                        if (hour.overTrigger) Text(overRule, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    }
                }
            }
            // Outside the row's cleared semantics, so each break is read and found as text. Indented to the row's text.
            if (hourBreaks.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hourBreaks.forEach { span ->
                    Text(stringResource(R.string.ribbon_break, Formats.time(span.start, use24Hour), Formats.time(span.end, use24Hour)),
                        style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                }
            }
        }
    }
}

/**
 * "7 am to 8 am, Hot, 33 degrees, over your 35 degree rule", or "12 pm to 1 pm, No forecast" for a shift hour the
 * forecast does not cover. The number is H in whole degrees, the same number [Formats.temperature] prints and the
 * level is judged on. The clause follows [HourHeat.overTrigger].
 */
@Composable
private fun hourDescription(slot: RibbonSlot, triggerC: Double, use24Hour: Boolean): String {
    val start = slot.start.toLocalTime()
    val from = Formats.time(start, use24Hour)
    val to = Formats.time(start.plusHours(1), use24Hour)
    val hour = slot.hour ?: return stringResource(R.string.ribbon_hour_missing, from, to)
    val degrees = HeatRules.wholeDegrees(hour.heat)
    val heat = pluralStringResource(R.plurals.ribbon_degrees, degrees, degrees)
    return if (hour.overTrigger) stringResource(R.string.ribbon_hour_over_rule, from, to, hour.effective.word, heat, HeatRules.wholeDegrees(triggerC))
    else stringResource(R.string.ribbon_hour, from, to, hour.effective.word, heat)
}

/**
 * Ink at 35 % on light fills. Dark-theme fills are the lifted level colours, where light Ink would vanish,
 * so the hatch takes the dark background tone there.
 */
private fun hatchColor(colors: HeatShieldColors): Color =
    (if (colors.isDark) colors.paper else colors.ink).copy(alpha = 0.35f)

/** 45° hatch lines clipped to the box. The ribbon cells and [RibbonKeyHatch] both draw through this. */
private fun DrawScope.drawHatch(color: Color, left: Float, top: Float, right: Float, bottom: Float) {
    clipRect(left, top, right, bottom) {
        val rise = bottom - top
        var x = left - rise
        while (x < right) {
            drawLine(color, Offset(x, bottom), Offset(x + rise, top), HatchStroke.toPx())
            x += HatchSpacing.toPx()
        }
    }
}

/** Hour starts in [from, until); an `until` at or before `from` (midnight wrap) runs to the end of the day. */
private fun covers(sun: SunProtection, time: LocalTime): Boolean =
    !time.isBefore(sun.from) && (time.isBefore(sun.until) || !sun.until.isAfter(sun.from))

private fun hourTag(prefix: String, slot: RibbonSlot): String = prefix + slot.start.hour.toString().padStart(2, '0')
