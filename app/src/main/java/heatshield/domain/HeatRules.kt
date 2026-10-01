package heatshield.domain

import kotlin.math.exp
import kotlin.math.roundToInt

enum class HeatLevel(val bars: Int) {
    NORMAL(1), WARM(2), HOT(3), VERY_HOT(4), EXTREME(5);

    fun atLeast(other: HeatLevel): Boolean = ordinal >= other.ordinal
}

data class PersonalFactors(val heavyWork: Boolean = false, val heavyClothing: Boolean = false, val acclimatising: Boolean = false) {
    val bumps: Int get() = listOf(heavyWork, heavyClothing, acclimatising).count { it }
}

enum class UvCategory { LOW, MODERATE, HIGH, VERY_HIGH, EXTREME }

data class Cadence(val workMinutes: Int, val breakMinutes: Int)

/**
 * The spec §5 heat model as pure functions: no clock, no Android, no I/O.
 * Temperatures are °C, humidity is %, wind is m/s. Callers pass validated (finite) forecast or sensor values.
 * Every threshold is judged on the whole number a screen shows (whole degrees, whole UV index), so a number on
 * screen never contradicts the rule printed beside it.
 */
object HeatRules {
    const val DEFAULT_TRIGGER_C = 35.0
    val TRIGGER_PRESETS: List<Double> = listOf(DEFAULT_TRIGGER_C, 38.0)
    const val TRIGGER_MIN_C = 25.0
    const val TRIGGER_MAX_C = 50.0
    const val MAX_BUMPS = 2
    const val UV_PROTECTION_THRESHOLD = 3.0

    /**
     * Days a new or returning worker counts as acclimatising, with an exclusive end: acclimatisingUntil is the start of
     * day +7, and a worker is acclimatising while acclimatisingUntil > the start of today, so day +7 is off.
     */
    const val ACCLIMATISING_DAYS = 7L

    /** BOM "feels like" in shade, without the solar term: AT = Ta + 0.33e − 0.70ws − 4.00 (e = vapour pressure, hPa). */
    fun apparentTemperature(airC: Double, humidityPct: Double, windMs: Double): Double {
        val vapourPressure = humidityPct / 100 * 6.105 * exp(17.27 * airC / (237.7 + airC))
        return airC + 0.33 * vapourPressure - 0.70 * windMs - 4.00
    }

    /**
     * HeatShield rule: H = max(air, feels like), so HeatShield never shows a heat number below the forecast air temperature.
     * It is not a measure of the heat load at a site.
     */
    fun heatNumber(airC: Double, humidityPct: Double, windMs: Double): Double =
        maxOf(airC, apparentTemperature(airC, humidityPct, windMs))

    /**
     * The whole number of degrees a screen shows, rounded half up and never truncated (31.5 → 32, 34.5 → 35, −2.5 → −2).
     * Formats.temperature prints this, and levels and the crew rule are judged on it, so "32°" is never labelled Warm
     * beside "Hot from 32°".
     */
    fun wholeDegrees(c: Double): Int {
        require(c.isFinite()) { "Temperature must be finite: $c" }
        return c.roundToInt()
    }

    /**
     * On the heat number as shown: Normal < 30 ≤ Warm < 32 ≤ Hot < 35 ≤ Very hot < 38 ≤ Extreme. On the raw number that is
     * Normal < 29.5 ≤ Warm < 31.5 ≤ Hot < 34.5 ≤ Very hot < 37.5 ≤ Extreme (raw 29.9 shows 30°, Warm).
     */
    fun baseLevel(heat: Double): HeatLevel {
        require(heat.isFinite()) { "Heat number must be finite: $heat" }
        val shown = wholeDegrees(heat)
        return when {
            shown < 30 -> HeatLevel.NORMAL
            shown < 32 -> HeatLevel.WARM
            shown < 35 -> HeatLevel.HOT
            shown < 38 -> HeatLevel.VERY_HOT
            else -> HeatLevel.EXTREME
        }
    }

    /** +1 level per personal factor, at most MAX_BUMPS, only from Warm up, never past Extreme. */
    fun effectiveLevel(base: HeatLevel, factors: PersonalFactors): HeatLevel {
        if (base == HeatLevel.NORMAL) return HeatLevel.NORMAL
        val bumped = base.ordinal + factors.bumps.coerceAtMost(MAX_BUMPS)
        return HeatLevel.entries[bumped.coerceAtMost(HeatLevel.EXTREME.ordinal)]
    }

    /**
     * BOM UV words on the UV index rounded to a whole number, as BOM and WHO report it: Low 0–2, Moderate 3–5,
     * High 6–7, Very high 8–10, Extreme 11+. Open-Meteo gives decimals, so 2.5 is UV 3 (Moderate), not Low.
     */
    fun uvCategory(uvIndex: Double): UvCategory {
        require(uvIndex.isFinite()) { "UV index must be finite: $uvIndex" }
        val index = uvIndex.roundToInt()
        return when {
            index < UV_PROTECTION_THRESHOLD -> UvCategory.LOW
            index <= 5 -> UvCategory.MODERATE
            index <= 7 -> UvCategory.HIGH
            index <= 10 -> UvCategory.VERY_HIGH
            else -> UvCategory.EXTREME
        }
    }

    /** Sun protection is needed from a whole-number UV index of 3 [BOM; SunSmart], so every Moderate-or-higher hour counts. */
    fun needsSunProtection(uvIndex: Double): Boolean {
        require(uvIndex.isFinite()) { "UV index must be finite: $uvIndex" }
        return uvIndex.roundToInt() >= UV_PROTECTION_THRESHOLD
    }

    /**
     * HeatShield rule; no regulator sets a break interval. Null at Normal (nothing to suggest) and at Extreme, where the
     * app shows the stop line ("Stop heavy work outdoors. Check your crew's hot-day rule.") instead of a work/rest split.
     */
    fun suggestedCadence(level: HeatLevel): Cadence? = when (level) {
        HeatLevel.NORMAL -> null
        HeatLevel.WARM -> Cadence(workMinutes = 60, breakMinutes = 10)
        HeatLevel.HOT -> Cadence(workMinutes = 45, breakMinutes = 15)
        HeatLevel.VERY_HOT -> Cadence(workMinutes = 30, breakMinutes = 15)
        HeatLevel.EXTREME -> null
    }

    /** Under the union-table option work stops from this heat number in whole degrees: VTHC's "no work from 36°" row. */
    const val UNION_STOP_C = 36

    /**
     * VTHC's rest-table rows, adapted by HeatShield: 15 min rest an hour from 30°, 30 from 32°, 45 from 34°, read on the
     * heat number in whole degrees with no personal bumps. Null below 30° (no timer) and from UNION_STOP_C (the walk
     * stops instead). The clock drains work at these rows rather than counting rest per hour, so the option carries
     * [HeatShield rule] and credits VTHC in its text; it is not the union's own rule (spec §5.10).
     */
    fun unionCadence(heatC: Double): Cadence? = when (wholeDegrees(heatC)) {
        30, 31 -> Cadence(workMinutes = 45, breakMinutes = 15)
        32, 33 -> Cadence(workMinutes = 30, breakMinutes = 30)
        34, 35 -> Cadence(workMinutes = 15, breakMinutes = 45)
        else -> null
    }

    /**
     * Every level is a HeatShield rule. Steps at 30/32/34 borrow VTHC's rest table; 35 and 38 borrow workplace rules that
     * use BOM shade air. HeatShield applies them to the heat number, so no outside body's label goes on a level; the Why
     * text names the borrowed numbers with their own conditions. Exhaustive, so a new level must be labelled on purpose.
     */
    fun levelSource(level: HeatLevel): SourceLabel = when (level) {
        HeatLevel.NORMAL, HeatLevel.WARM, HeatLevel.HOT, HeatLevel.VERY_HOT, HeatLevel.EXTREME -> SourceLabel.HEATSHIELD_RULE
    }

    /** A crew rule is whole degrees from 25 to 50, so it always shows exactly as set and compares cleanly with whole air degrees. */
    fun isValidTrigger(value: Double): Boolean =
        value.isFinite() && value % 1.0 == 0.0 && value in TRIGGER_MIN_C..TRIGGER_MAX_C
}
