// Checks where saved breaks appear on the heat ribbon without needing a screen.
// Real offsets keep both occurrences of Melbourne's autumn hour distinct in these regression cases.
package heatshield

import heatshield.domain.ShiftRules
import heatshield.ui.ribbonBreakXAt
import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps break positions tied to elapsed time, including the hour that Melbourne repeats in autumn. */
class HeatRibbonCoordinatesTest {
    private val autumn = LocalDate.of(2026, 4, 5)
    private val repeatedSlots = listOf(autumn.atTime(2, 0), autumn.atTime(3, 0))

    /** A second-occurrence 02:10–02:25 break must occupy 15 of the repeated cell's 120 pixels. */
    @Test
    fun secondAutumnOccurrenceHasANonzeroVisibleBreakInterval() {
        val from = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T02:10:00+10:00").toInstant(), 120f, 1f)
        val to = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T02:25:00+10:00").toInstant(), 120f, 1f)

        assertTrue("The confirmed break must have a visible interval", to > from)
        assertEquals(70f, from, 0.001f)
        assertEquals(85f, to, 0.001f)
    }

    /** Crossing the clock change from first 02:50 to second 02:10 retains 20 elapsed minutes. */
    @Test
    fun breakAcrossTheClockChangeKeepsItsElapsedWidth() {
        val from = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T02:50:00+11:00").toInstant(), 120f, 1f)
        val to = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T02:10:00+10:00").toInstant(), 120f, 1f)

        assertEquals(50f, from, 0.001f)
        assertEquals(20f, to - from, 0.001f)
    }

    /** Crossing into 03:00 preserves the gap and the next cell's ordinary 60-minute scale. */
    @Test
    fun breakAcrossCellsKeepsTheGapAndEachHoursDuration() {
        val from = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T02:50:00+10:00").toInstant(), 120f, 1f)
        val to = ribbonBreakXAt(repeatedSlots, OffsetDateTime.parse("2026-04-05T03:10:00+10:00").toInstant(), 120f, 1f)

        assertEquals(110f, from, 0.001f)
        assertEquals(141f, to, 0.001f)
    }

    /** An ordinary 10:02–10:17 break still spans 15 pixels in a 60-pixel cell. */
    @Test
    fun ordinaryHourBreakKeepsItsMinuteScale() {
        val start = LocalDate.of(2026, 1, 9).atTime(10, 0)
        val slots = listOf(start, start.plusHours(1))
        val from = ribbonBreakXAt(slots, ShiftRules.instant(start.plusMinutes(2)), 60f, 1f)
        val to = ribbonBreakXAt(slots, ShiftRules.instant(start.plusMinutes(17)), 60f, 1f)

        assertEquals(2f, from, 0.001f)
        assertEquals(17f, to, 0.001f)
    }

    /** Break endpoints outside the shown hours remain clipped to the ribbon's two edges. */
    @Test
    fun breakEndpointsOutsideTheRibbonAreClipped() {
        val start = LocalDate.of(2026, 1, 9).atTime(10, 0)
        val slots = listOf(start, start.plusHours(1))

        assertEquals(0f, ribbonBreakXAt(slots, ShiftRules.instant(start.minusMinutes(10)), 60f, 1f), 0.001f)
        assertEquals(121f, ribbonBreakXAt(slots, ShiftRules.instant(start.plusHours(3)), 60f, 1f), 0.001f)
    }
}
