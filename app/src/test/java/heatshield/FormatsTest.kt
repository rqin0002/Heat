package heatshield

import heatshield.domain.HeatRules
import heatshield.ui.Formats
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Display formats from spec §8: phone-setting times, one date style, whole-degree temperatures. */
class FormatsTest {
    @Test
    fun twelveHourTimesDropZeroMinutesAndUseLowerCaseMarkers() {
        assertEquals("7 am", Formats.time(LocalTime.of(7, 0), use24Hour = false))
        assertEquals("7:30 am", Formats.time(LocalTime.of(7, 30), use24Hour = false))
        assertEquals("1:05 pm", Formats.time(LocalTime.of(13, 5), use24Hour = false))
        assertEquals("11 pm", Formats.time(LocalTime.of(23, 0), use24Hour = false))
    }

    @Test
    fun twelveHourNoonAndMidnight() {
        assertEquals("12 pm", Formats.time(LocalTime.NOON, use24Hour = false))
        assertEquals("12 am", Formats.time(LocalTime.MIDNIGHT, use24Hour = false))
        assertEquals("12:30 am", Formats.time(LocalTime.of(0, 30), use24Hour = false))
    }

    @Test
    fun twentyFourHourTimesArePadded() {
        assertEquals("07:00", Formats.time(LocalTime.of(7, 0), use24Hour = true))
        assertEquals("13:05", Formats.time(LocalTime.of(13, 5), use24Hour = true))
        assertEquals("12:00", Formats.time(LocalTime.NOON, use24Hour = true))
        assertEquals("00:00", Formats.time(LocalTime.MIDNIGHT, use24Hour = true))
    }

    @Test
    fun datesUseOneDisplayStyle() {
        assertEquals("Thu 8 Jan", Formats.dayDate(LocalDate.of(2026, 1, 8)))
        assertEquals("Sun 4 Oct", Formats.dayDate(LocalDate.of(2026, 10, 4)))
        assertEquals("Thu 8 Jan 2026", Formats.fullDate(LocalDate.of(2026, 1, 8)))
        assertEquals("Fri 9 Jan 2026", Formats.fullDate(LocalDate.of(2026, 1, 9)))
    }

    @Test
    fun durationsReadInHoursAndMinutes() {
        assertEquals("0 min", Formats.duration(0)); assertEquals("45 min", Formats.duration(45))
        assertEquals("1 h", Formats.duration(60)); assertEquals("7 h 23 min", Formats.duration(443))
    }

    @Test
    fun breakClockShowsMinutesAndSecondsThenHours() {
        assertEquals("00:00", Formats.clock(0)); assertEquals("04:12", Formats.clock(252))
        assertEquals("1:04:12", Formats.clock(3852)); assertEquals("00:00", Formats.clock(-5))
    }

    /** HeatRules.wholeDegrees, printed: ties go up (towards positive infinity), and nothing is truncated. */
    @Test
    fun temperaturesRoundHalfUpToWholeDegrees() {
        assertEquals("34°", Formats.temperature(33.5))
        assertEquals("33°", Formats.temperature(33.4))
        assertEquals("35°", Formats.temperature(35.0))
        assertEquals("32°", Formats.temperature(31.5))   // the Warm/Hot boundary: raw 31.5 is Hot, so it must print 32°
        assertEquals("35°", Formats.temperature(34.5))   // the Hot/Very hot boundary
        assertEquals("32°", Formats.temperature(31.99))  // truncating would print 31°
        assertEquals("0°", Formats.temperature(-0.4))
        assertEquals("-2°", Formats.temperature(-2.5))
        assertEquals("-3°", Formats.temperature(-2.6))
    }

    /**
     * The printed number is the one levels are judged on (spec §5.4), so "32°" never sits beside a Warm label.
     * Read back, the printed number gives the same level as the raw heat number, at every tenth from 28.5 to 38.4.
     */
    @Test
    fun printedDegreesAlwaysGiveTheSameLevelAsTheRawHeatNumber() {
        for (tenths in 285..384) {
            val c = tenths / 10.0
            val shown = Formats.temperature(c)
            assertEquals("$c prints $shown", HeatRules.baseLevel(c), HeatRules.baseLevel(shown.removeSuffix("°").toDouble()))
        }
    }
}
