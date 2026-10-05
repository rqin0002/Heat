// Which stop lines Today and the break check-in show under the union table, and in what order, checked without a
// screen. The union table's 36° stop (stop all work outdoors) and the Extreme line (stop heavy work only, personal
// adjustments count) say different things, so one must never hide the other.
package heatshield

import heatshield.data.Freshness
import heatshield.domain.BreakRule
import heatshield.domain.Coverage
import heatshield.domain.CrewRuleLine
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.PersonalFactors
import heatshield.domain.TodayHeat
import heatshield.ui.unionStopFirst
import heatshield.ui.unionStopShown
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The union stop line's two decisions: whether it shows (unionStopShown) and whether it goes above the Extreme line (unionStopFirst). */
class UnionStopLinesTest {
    private val date = LocalDate.of(2026, 1, 9)

    // Heat numbers for 7 am to 2 pm. Heavy work raises a Very hot 35° at 10 am to Extreme; 36° comes at 11 am.
    private val heavyWorkDay = listOf(30.0, 31.0, 32.0, 35.0, 36.0, 34.0, 33.0, 32.0)
    // The first hour at 36° (11 am) is already 38°, so Extreme starts in the same hour.
    private val firstUnionHourExtreme = listOf(30.0, 31.0, 32.0, 34.0, 38.0, 37.0, 36.0, 34.0)
    // 36° at 10 am; the first Extreme hour (38°) is 2 pm.
    private val laterExtremeDay = listOf(30.0, 31.0, 32.0, 36.0, 35.0, 35.0, 34.0, 38.0)
    // Heavy work makes 35° at 10 am Extreme, but no hour reaches 36°.
    private val noUnionHourDay = listOf(30.0, 31.0, 32.0, 35.0, 35.0, 34.0, 33.0, 32.0)

    @Test
    fun anEarlierExtremeHourForHeavyWorkStillShowsTheUnionStop() {
        assertTrue(unionStopShown(day(heavyWorkDay, heavyWork = true), BreakRule.UNION_VTHC))
    }

    @Test
    fun aFirstUnionHourThatIsAlreadyExtremeStillShowsTheUnionStop() {
        assertTrue(unionStopShown(day(firstUnionHourExtreme), BreakRule.UNION_VTHC))
    }

    @Test
    fun aLaterExtremeHourStillShowsTheUnionStop() {
        assertTrue(unionStopShown(day(laterExtremeDay), BreakRule.UNION_VTHC))
    }

    @Test
    fun noUnionStopWithoutAnHourAt36OrUnderTheHeatShieldRule() {
        assertFalse(unionStopShown(day(noUnionHourDay, heavyWork = true), BreakRule.UNION_VTHC))
        assertFalse(unionStopShown(day(laterExtremeDay), BreakRule.HEATSHIELD))
    }

    @Test
    fun theEarlierStopComesFirstAndTheUnionStopWinsATie() {
        assertFalse("Extreme for heavy work from 10 am, union from 11 am", unionStopFirst(day(heavyWorkDay, heavyWork = true)))
        assertTrue("Both from 11 am", unionStopFirst(day(firstUnionHourExtreme)))
        assertTrue("Union from 10 am, Extreme from 2 pm", unionStopFirst(day(laterExtremeDay)))
    }

    /**
     * One fixture day from [heats] (heat numbers for 7 am onwards, equal to air), read at 9 am: each hour's level as
     * TodayHeat carries it, base from the whole degrees and effective after a heavy-work bump when [heavyWork].
     * Example: 35.0 with heavy work is Very hot raised to Extreme.
     */
    private fun day(heats: List<Double>, heavyWork: Boolean = false): TodayHeat {
        val factors = PersonalFactors(heavyWork = heavyWork)
        val hours = heats.mapIndexed { index, heat ->
            val base = HeatRules.baseLevel(heat)
            HourHeat(time = date.atTime(7 + index, 0), airC = heat, humidityPct = 30.0, windMs = 2.0,
                feelsLikeC = heat - 1.5, heat = heat, base = base, effective = HeatRules.effectiveLevel(base, factors),
                overTrigger = HeatRules.wholeDegrees(heat) >= 35, uv = 6.5, uvForProtection = 6.5)
        }
        val peak = hours.maxByOrNull { it.heat }!!
        return TodayHeat(
            date = date, site = "Birrarung Marr", hours = hours,
            coverage = Coverage(complete = true, availableUntil = LocalTime.of(7 + heats.size, 0), missing = emptyList()),
            uvCoverage = Coverage(complete = true, availableUntil = LocalTime.MIDNIGHT, missing = emptyList()),
            now = hours.first { it.time.hour == 9 }, peak = peak, dayLevel = peak.effective, bumped = peak.effective != peak.base,
            crewRule = CrewRuleLine(35.0, null, 0, hours.maxOf { it.airC }), heavyJobsBefore = null, uvPeak = null,
            sunProtection = null, suggestedCadence = HeatRules.suggestedCadence(peak.effective), freshness = Freshness.FRESH,
            updatedAtMillis = null, refreshedBy = "app", error = null
        )
    }
}
