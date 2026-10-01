package heatshield.domain

/** How hard a job works the body. Labels match the effort strings already stored in Room. */
enum class Effort(val label: String) {
    LIGHT("Light effort"), MODERATE("Moderate effort"), HEAVY("Heavy effort");

    companion object {
        fun fromLabel(label: String): Effort = entries.firstOrNull { it.label == label } ?: MODERATE
    }
}

/**
 * A selectable job and the effort used by heat advice. [name] is an exact saved key, never display copy:
 * change its resource wording at the UI boundary, and retain old keys as aliases if identifiers ever change.
 * Example: a confirmed saved "Push mowing" must keep Heavy effort even after its visible wording changes.
 */
data class WorkTask(val name: String, val effort: Effort)

/** Parks jobs a gardener picks from. Each job implies its effort, so nobody has to rate their own work. */
object WorkTasks {
    const val DEFAULT_TASK_NAME = "Digging & planting"

    val all: List<WorkTask> = listOf(
        WorkTask("Push mowing", Effort.HEAVY),
        WorkTask("Ride-on mowing", Effort.MODERATE),
        WorkTask(DEFAULT_TASK_NAME, Effort.HEAVY),
        WorkTask("Pruning & hedging", Effort.MODERATE),
        WorkTask("Weeding & mulching", Effort.MODERATE),
        WorkTask("Irrigation & checks", Effort.LIGHT),
        WorkTask("Litter & paths", Effort.LIGHT)
    )

    /** Work types saved by the A2 screens ("Planting beds", "Mowing", "Pruning") before M1. */
    private val legacyEfforts: Map<String, Effort> =
        mapOf("Planting beds" to Effort.HEAVY, "Mowing" to Effort.HEAVY, "Pruning" to Effort.MODERATE)

    val default: WorkTask = byName(DEFAULT_TASK_NAME)!!

    fun byName(name: String): WorkTask? = all.firstOrNull { it.name == name }

    /**
     * Recovers effort from an exact saved task key, including older choices; unknown keys fall back to Moderate.
     * Keep every persisted key supported when changing the list: "Push mowing" means Heavy, so 31 °C becomes Hot
     * for a confirmed usual-day profile rather than silently reverting to Warm after a wording edit.
     */
    fun effortFor(taskName: String): Effort = byName(taskName)?.effort ?: legacyEfforts[taskName] ?: Effort.MODERATE
}
