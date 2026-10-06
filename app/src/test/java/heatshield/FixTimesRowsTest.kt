// Fix times' break rows for a smoko or lunch that was not taken, checked without a screen.
// A night shift's 23:50 lunch runs past midnight, so ticking "I took this break" must keep its 30 minutes.
package heatshield

import heatshield.data.BreakEdit
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.ui.fixTimesEdit
import heatshield.ui.minuteOf
import heatshield.ui.toBreakRows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A not-taken scheduled break, once ticked in Fix times, is its full scheduled length on the shift's own timeline. */
class FixTimesRowsTest {
    private val day = LocalDate.of(2026, 10, 8)
    private fun ms(time: LocalDateTime) = ShiftRules.epochMillis(time)

    /** 8 October 22:00 to 9 October 06:00, with lunch set to 23:50 (minute 1430) in Profile. */
    private val night = WorkSession(id = 9, ownerId = "local", title = "Push mowing", site = "Royal Park", date = "8 Oct 2026",
        outdoorMinutes = 480, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ms(day.atTime(22, 0)),
        endedAt = ms(day.plusDays(1).atTime(6, 0)), plannedEndAt = ms(day.plusDays(1).atTime(6, 0)), source = ShiftSource.TRACKED)

    /** "Not yet" on the proposed lunch stores it skipped, starting and ending at 23:50. */
    private val skippedLunch = ShiftEvent(id = 41, shiftId = 9, type = ShiftEventType.LUNCH,
        startedAt = ms(day.atTime(23, 50)), endedAt = ms(day.atTime(23, 50)), skipped = true)

    /** Builds Fix times' rows as the screen does, ticks the lunch without touching its times, and returns what saving sends. */
    private fun tickedLunch(events: List<ShiftEvent>): BreakEdit {
        val rows = events.toBreakRows(night, smokoMinute = 570, lunchMinute = 1430)
            .map { if (it.kind == ScheduledKind.LUNCH) it.copy(taken = true) else it }
        val edit = fixTimesEdit(night, events, day, minuteOf(requireNotNull(night.startedAt)), minuteOf(requireNotNull(night.endedAt)), rows, null)
        val validation = edit.validation(night, events, Instant.ofEpochMilli(ms(day.plusDays(1).atTime(9, 0))))
        assertTrue("Fix times rejects the ticked lunch: $validation", validation?.ok == true)
        return edit.breaks.single { it.kind == ScheduledKind.LUNCH }
    }

    @Test
    fun aSkippedLunchTickedAsTakenKeepsItsThirtyMinutesPastMidnight() {
        val lunch = tickedLunch(listOf(skippedLunch))
        assertEquals(day.atTime(23, 50), lunch.start)
        assertEquals(day.plusDays(1).atTime(0, 20), lunch.end)
        assertEquals(30, lunch.times(skippedLunch).span.minutes)
    }

    @Test
    fun anUnansweredLunchTickedAsTakenGetsTheSameThirtyMinutes() {
        val lunch = tickedLunch(emptyList())
        assertEquals(day.atTime(23, 50), lunch.start)
        assertEquals(day.plusDays(1).atTime(0, 20), lunch.end)
        assertEquals(30, lunch.times(null).span.minutes)
    }
}
