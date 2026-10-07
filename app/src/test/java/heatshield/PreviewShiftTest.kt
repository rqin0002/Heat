package heatshield

import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftEventType
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.OpenShift
import heatshield.domain.PersonalFactors
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.ui.PreviewShift
import heatshield.ui.answered
import heatshield.ui.previewShiftHours
import heatshield.ui.previewShiftRow
import heatshield.ui.realDelayMillis
import java.io.File
import java.io.StringReader
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §10 M2: a preview shift started at 09:52 reaches Break due within about two real minutes, through the same rules as Today. */
class PreviewShiftTest {
    private val rows = SensorReplaySimulator.parse(StringReader(File("src/main/assets/microclimate_replay.csv").readText()))
    private val day = SensorReplaySimulator.PREVIEW_DATE
    private val profile = HeatProfile("Birrarung Marr", "Digging & planting", 420, 900, 35.0, PersonalFactors())

    @Test
    fun previewShiftFromNineFiftyTwoIsDueAtHalfPastTenOnTheSensorDay() {
        val start = day.atTime(SensorReplaySimulator.PREVIEW_START)
        val index = SensorReplaySimulator.startIndexFor(rows, day, SensorReplaySimulator.PREVIEW_START)
        val hours = ContextReasoner.previewHeat(rows, index, profile).dayHours
        // 9 am is Normal on the sensor day (its hottest reading is 25.0° at 09:52); the 10:52 reading, 35.6° air, makes 10 am Very hot.
        assertEquals(HeatLevel.NORMAL, hours.first { it.time.hour == 9 }.effective)
        assertEquals(HeatLevel.VERY_HOT, hours.first { it.time.hour == 10 }.effective)
        val due = ShiftRules.nextBreakDue(ShiftRules.workClock(ShiftRules.instant(start), emptyList(), ShiftRules.instant(start)), hours, BreakRule.HEATSHIELD, emptyList(), null,
            ShiftRules.instant(day.atTime(15, 0)))
        assertEquals(BreakDue.At(ShiftRules.instant(day.atTime(10, 30)), HeatLevel.VERY_HOT, heatshield.domain.Cadence(30, 15)), due)
        val virtualMinutes = Duration.between(ShiftRules.instant(start), requireNotNull(due.dueAt)).toMinutes()
        assertEquals(38L, virtualMinutes)
        assertTrue("${realDelayMillis(virtualMinutes)} ms real", realDelayMillis(virtualMinutes) <= 120_000L)
    }

    // The preview shift starts at 09:52 whatever the usual day, so it reads every hour of the sensor day: a usual day
    // starting at 10 am would leave 9 am unknown, and the walk would stop at once.
    @Test
    fun aPreviewShiftIsTimedFromItsFirstReadingWhateverTheUsualDay() {
        val start = day.atTime(9, 52)
        val index = SensorReplaySimulator.startIndexFor(rows, day, SensorReplaySimulator.PREVIEW_START)
        val tenToSix = profile.copy(shiftStartMinute = 600, shiftEndMinute = 1080)
        val shift = PreviewShift(start, heavyWork = false, events = emptyList())
        val row = previewShiftRow(shift, tenToSix)
        assertEquals(day.atTime(18, 0), ShiftRules.localTime(requireNotNull(row.plannedEndAt)))
        val evaluation = ShiftRules.evaluate(OpenShift(row, emptyList()), previewShiftHours(rows, index, tenToSix, false), BreakRule.HEATSHIELD, 570, 720, ShiftRules.instant(start))
        assertTrue("${evaluation.due}", evaluation.due is BreakDue.At)
        assertEquals(ShiftRules.instant(day.atTime(10, 30)), evaluation.due.dueAt)
        assertEquals(HeatLevel.NORMAL, evaluation.current?.effective)
        // The usual day's hours alone (from 10 am) leave 9:52 unknown.
        val usualOnly = ContextReasoner.previewHeat(rows, index, tenToSix).dayHours
        assertEquals(BreakDue.NoForecast, ShiftRules.evaluate(OpenShift(row, emptyList()), usualOnly, BreakRule.HEATSHIELD, 570, 720, ShiftRules.instant(start)).due)
        // A usual finish before 09:52 never plans the preview's end before its start: the usual length on instead.
        val early = profile.copy(shiftStartMinute = 360, shiftEndMinute = 540)
        assertEquals(day.atTime(12, 52), ShiftRules.localTime(requireNotNull(previewShiftRow(shift, early).plannedEndAt)))
    }

    @Test
    fun aPreviewShiftPastTheUsualFinishStillKnowsTheCurrentHour() {
        val index = SensorReplaySimulator.startIndexFor(rows, day, SensorReplaySimulator.PREVIEW_START)
        val shift = PreviewShift(day.atTime(9, 52), heavyWork = false, events = emptyList())
        val evaluation = ShiftRules.evaluate(OpenShift(previewShiftRow(shift, profile), emptyList()), previewShiftHours(rows, index, profile, false),
            BreakRule.HEATSHIELD, 570, 720, ShiftRules.instant(day.atTime(15, 30)))
        assertNotNull(evaluation.current)
        assertEquals(day.atTime(15, 0), evaluation.current?.time)
        // Playback starts the day again at 09:52 after its last reading; the shift's clock stays at the latest time it saw.
        val late = shift.copy(latest = day.atTime(23, 50))
        assertEquals(day.atTime(23, 50), late.nowAt(day.atTime(9, 53)))
        assertEquals(day.atTime(23, 55), late.nowAt(day.atTime(23, 55)))
    }

    @Test
    fun aSmokoIsAnsweredOnceOnThePreviewShift() {
        val shift = PreviewShift(day.atTime(9, 52), heavyWork = false, events = emptyList())
        val twice = shift.answered(ScheduledKind.SMOKO, true, 570).answered(ScheduledKind.SMOKO, true, 570)
        assertEquals(listOf(ShiftEventType.SMOKO to false), twice.events.map { it.type to it.skipped })
        // A later "Not yet" does not undo it either; lunch is its own answer.
        val answered = twice.answered(ScheduledKind.SMOKO, false, 570).answered(ScheduledKind.LUNCH, false, 720)
        assertEquals(listOf(ShiftEventType.SMOKO to false, ShiftEventType.LUNCH to true), answered.events.map { it.type to it.skipped })
    }

    @Test
    fun realDelayIsVirtualTimeOverFortyFive() {
        assertEquals(60_000L, realDelayMillis(45))
        assertEquals(SensorReplaySimulator.INTERVAL_MILLIS, realDelayMillis(15))   // one reading, 15 virtual minutes, 20 real seconds
        assertEquals(0L, realDelayMillis(0))
    }
}
