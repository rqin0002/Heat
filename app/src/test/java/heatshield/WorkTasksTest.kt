package heatshield

import heatshield.domain.Effort
import heatshield.domain.WorkTask
import heatshield.domain.WorkTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The usual-work list and the effort each job implies, including task names saved before M1. */
class WorkTasksTest {
    @Test
    fun savedConfirmedPushMowingKeepsHeavyAdviceWhenTheStoredEffortDisagrees() {
        val saved = heatshield.data.UserPreferences(ownerId = "returning-worker", task = "Push mowing",
            effort = "Moderate effort", setupDone = true, clothing = "Standard workwear", acclimatisingUntil = null)
        val profile = heatshield.domain.ContextReasoner.profileOf(saved, java.time.LocalDate.of(2026, 1, 15))
        assertEquals(Effort.HEAVY, heatshield.domain.ContextReasoner.usualEffort(saved))
        assertEquals(heatshield.domain.HeatLevel.HOT, heatshield.domain.HeatRules.effectiveLevel(
            heatshield.domain.HeatRules.baseLevel(31.0), profile.factors))
    }

    @Test
    fun catalogueListsParksJobsInOrderWithTheirEffort() {
        assertEquals(listOf(
            WorkTask("Push mowing", Effort.HEAVY),
            WorkTask("Ride-on mowing", Effort.MODERATE),
            WorkTask("Digging & planting", Effort.HEAVY),
            WorkTask("Pruning & hedging", Effort.MODERATE),
            WorkTask("Weeding & mulching", Effort.MODERATE),
            WorkTask("Irrigation & checks", Effort.LIGHT),
            WorkTask("Litter & paths", Effort.LIGHT)
        ), WorkTasks.all)
        assertEquals(WorkTasks.all.size, WorkTasks.all.map { it.name }.toSet().size)
    }

    @Test
    fun defaultIsDiggingAndPlantingFromTheCatalogue() {
        assertEquals("Digging & planting", WorkTasks.DEFAULT_TASK_NAME)
        assertEquals(WorkTask("Digging & planting", Effort.HEAVY), WorkTasks.default)
        assertSame(WorkTasks.byName(WorkTasks.DEFAULT_TASK_NAME), WorkTasks.default)
    }

    @Test
    fun byNameFindsCatalogueEntriesOnly() {
        assertEquals(WorkTask("Litter & paths", Effort.LIGHT), WorkTasks.byName("Litter & paths"))
        assertNull(WorkTasks.byName("Mowing"))
        assertNull(WorkTasks.byName("push mowing"))
        assertNull(WorkTasks.byName(""))
    }

    @Test
    fun effortForCoversCatalogueLegacyAndUnknownNames() {
        WorkTasks.all.forEach { assertEquals(it.name, it.effort, WorkTasks.effortFor(it.name)) }
        assertEquals(Effort.HEAVY, WorkTasks.effortFor("Planting beds"))
        assertEquals(Effort.HEAVY, WorkTasks.effortFor("Mowing"))
        assertEquals(Effort.MODERATE, WorkTasks.effortFor("Pruning"))
        assertEquals(Effort.MODERATE, WorkTasks.effortFor("Sweeping the depot"))
        assertEquals(Effort.MODERATE, WorkTasks.effortFor(""))
    }

    @Test
    fun effortLabelsMatchTheStoredStrings() {
        assertEquals(listOf("Light effort", "Moderate effort", "Heavy effort"), Effort.entries.map { it.label })
        Effort.entries.forEach { assertEquals(it, Effort.fromLabel(it.label)) }
        assertEquals(Effort.MODERATE, Effort.fromLabel("Extreme effort"))
        assertEquals(Effort.MODERATE, Effort.fromLabel(""))
    }
}
