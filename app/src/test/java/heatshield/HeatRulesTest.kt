package heatshield

import heatshield.domain.Cadence
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HeatSources
import heatshield.domain.PersonalFactors
import heatshield.domain.SourceLabel
import heatshield.domain.UvCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §5 heat model: formula checks, every boundary on the whole number shown, personal bumps, UV, cadence and source labels. */
class HeatRulesTest {
    private val allFactorSets = listOf(false, true).flatMap { work ->
        listOf(false, true).flatMap { clothing ->
            listOf(false, true).map { acclimatising -> PersonalFactors(work, clothing, acclimatising) }
        }
    }

    // Feels like in shade [BOM] and the heat number [HeatShield rule].
    @Test
    fun apparentTemperatureMatchesTheSpecChecks() {
        assertEquals(36.3, HeatRules.apparentTemperature(35.0, 40.0, 3.0), 0.1)
        assertEquals(32.8, HeatRules.apparentTemperature(40.0, 10.0, 8.0), 0.1)
    }

    @Test
    fun heatNumberIsNeverBelowAirTemperature() {
        assertEquals(40.0, HeatRules.heatNumber(40.0, 10.0, 8.0), 0.0)   // dry wind: air decides
        assertEquals(36.3, HeatRules.heatNumber(35.0, 40.0, 3.0), 0.1)   // humid: feels like decides
        assertEquals(33.0, HeatRules.heatNumber(30.0, 50.0, 0.0), 0.1)   // still, humid 30° counts as Hot
        assertEquals(HeatLevel.HOT, HeatRules.baseLevel(HeatRules.heatNumber(30.0, 50.0, 0.0)))
    }

    // The whole number a screen shows, rounded half up exactly as Formats.temperature prints it.
    @Test
    fun wholeDegreesRoundHalfUp() {
        assertEquals(32, HeatRules.wholeDegrees(31.5))
        assertEquals(35, HeatRules.wholeDegrees(34.5))
        assertEquals(34, HeatRules.wholeDegrees(33.5))
        assertEquals(33, HeatRules.wholeDegrees(33.4))
        assertEquals(30, HeatRules.wholeDegrees(29.5))
        assertEquals(30, HeatRules.wholeDegrees(29.9))
        assertEquals(35, HeatRules.wholeDegrees(35.0))
        assertEquals(0, HeatRules.wholeDegrees(-0.4))
        assertEquals(-2, HeatRules.wholeDegrees(-2.5))
        assertEquals(-3, HeatRules.wholeDegrees(-2.6))
        assertThrows(IllegalArgumentException::class.java) { HeatRules.wholeDegrees(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { HeatRules.wholeDegrees(Double.POSITIVE_INFINITY) }
    }

    // Levels from the heat number as shown, in whole degrees: raw boundaries sit at 29.5, 31.5, 34.5 and 37.5,
    // so 29.9 (shown 30°) is already Warm and 34.9 (shown 35°) already Very hot.
    @Test
    fun everyLevelBoundary() {
        val expected = linkedMapOf(
            29.4 to HeatLevel.NORMAL, 29.5 to HeatLevel.WARM, 29.9 to HeatLevel.WARM, 30.0 to HeatLevel.WARM,
            31.4 to HeatLevel.WARM, 31.5 to HeatLevel.HOT, 31.9 to HeatLevel.HOT, 32.0 to HeatLevel.HOT,
            34.4 to HeatLevel.HOT, 34.5 to HeatLevel.VERY_HOT, 34.9 to HeatLevel.VERY_HOT, 35.0 to HeatLevel.VERY_HOT,
            37.4 to HeatLevel.VERY_HOT, 37.5 to HeatLevel.EXTREME, 37.9 to HeatLevel.EXTREME, 38.0 to HeatLevel.EXTREME
        )
        expected.forEach { (heat, level) -> assertEquals("heat $heat", level, HeatRules.baseLevel(heat)) }
        assertEquals(HeatLevel.NORMAL, HeatRules.baseLevel(-5.0))
        assertEquals(HeatLevel.EXTREME, HeatRules.baseLevel(48.0))
        assertThrows(IllegalArgumentException::class.java) { HeatRules.baseLevel(Double.NaN) }
    }

    // A shown number never sits next to the wrong word: "32°" is Hot and "35°" is Very hot, whatever the decimals.
    @Test
    fun levelAlwaysMatchesTheWholeNumberShown() {
        val levelByShownDegrees = mapOf(
            29 to HeatLevel.NORMAL, 30 to HeatLevel.WARM, 31 to HeatLevel.WARM, 32 to HeatLevel.HOT, 33 to HeatLevel.HOT,
            34 to HeatLevel.HOT, 35 to HeatLevel.VERY_HOT, 36 to HeatLevel.VERY_HOT, 37 to HeatLevel.VERY_HOT, 38 to HeatLevel.EXTREME
        )
        (285..384).map { it / 10.0 }.forEach { heat ->
            val shown = HeatRules.wholeDegrees(heat)
            assertEquals("heat $heat shown as $shown°", levelByShownDegrees.getValue(shown), HeatRules.baseLevel(heat))
        }
    }

    @Test
    fun levelsCarryBarsAndStableOrder() {
        assertEquals(listOf(1, 2, 3, 4, 5), HeatLevel.entries.map { it.bars })
        assertEquals(listOf("NORMAL", "WARM", "HOT", "VERY_HOT", "EXTREME"), HeatLevel.entries.map { it.name })
        assertTrue(HeatLevel.HOT.atLeast(HeatLevel.HOT))
        assertTrue(HeatLevel.EXTREME.atLeast(HeatLevel.WARM))
        assertFalse(HeatLevel.WARM.atLeast(HeatLevel.HOT))
    }

    // Personal modifiers: +1 each, at most +2, only from Warm up, never past Extreme.
    @Test
    fun personalFactorsBumpOneLevelEachCappedAtTwoAndAtExtreme() {
        val heavy = PersonalFactors(heavyWork = true)
        val two = PersonalFactors(heavyWork = true, heavyClothing = true)
        val all = PersonalFactors(heavyWork = true, heavyClothing = true, acclimatising = true)
        assertEquals(listOf(0, 1, 2, 3), listOf(PersonalFactors(), heavy, two, all).map { it.bumps })
        assertEquals(HeatLevel.HOT, HeatRules.effectiveLevel(HeatLevel.HOT, PersonalFactors()))
        assertEquals(HeatLevel.HOT, HeatRules.effectiveLevel(HeatLevel.WARM, heavy))
        assertEquals(HeatLevel.HOT, HeatRules.effectiveLevel(HeatLevel.WARM, PersonalFactors(heavyClothing = true)))
        assertEquals(HeatLevel.HOT, HeatRules.effectiveLevel(HeatLevel.WARM, PersonalFactors(acclimatising = true)))
        assertEquals(HeatLevel.VERY_HOT, HeatRules.effectiveLevel(HeatLevel.WARM, two))
        assertEquals(HeatLevel.VERY_HOT, HeatRules.effectiveLevel(HeatLevel.WARM, all))      // capped at +2
        assertEquals(HeatLevel.EXTREME, HeatRules.effectiveLevel(HeatLevel.HOT, all))
        assertEquals(HeatLevel.EXTREME, HeatRules.effectiveLevel(HeatLevel.VERY_HOT, two))   // capped at Extreme
        assertEquals(HeatLevel.EXTREME, HeatRules.effectiveLevel(HeatLevel.EXTREME, heavy))
    }

    @Test
    fun coolDaysStayQuietWhateverTheFactors() {
        allFactorSets.forEach { factors ->
            assertEquals(factors.toString(), HeatLevel.NORMAL, HeatRules.effectiveLevel(HeatLevel.NORMAL, factors))
        }
        assertEquals(HeatLevel.NORMAL, HeatRules.effectiveLevel(HeatRules.baseLevel(29.4), PersonalFactors(heavyWork = true)))
    }

    @Test
    fun moreHeatOrMoreFactorsNeverLowerTheLevel() {
        HeatLevel.entries.forEach { base ->
            allFactorSets.forEach { factors ->
                val effective = HeatRules.effectiveLevel(base, factors)
                assertTrue("$base $factors", effective.atLeast(base))
                assertTrue("$base $factors", effective.ordinal - base.ordinal <= HeatRules.MAX_BUMPS)
                HeatLevel.entries.filter { it.atLeast(base) }.forEach { hotter ->
                    assertTrue("$hotter vs $base $factors", HeatRules.effectiveLevel(hotter, factors).atLeast(effective))
                }
            }
        }
    }

    // UV [BOM], kept out of the heat level. BOM and WHO report the UV index as a whole number, so 2.5 is UV 3.
    @Test
    fun uvCategoryBoundaries() {
        val expected = linkedMapOf(
            0.0 to UvCategory.LOW, 2.4 to UvCategory.LOW, 2.5 to UvCategory.MODERATE, 5.4 to UvCategory.MODERATE,
            5.5 to UvCategory.HIGH, 7.4 to UvCategory.HIGH, 7.5 to UvCategory.VERY_HIGH, 10.4 to UvCategory.VERY_HIGH,
            10.5 to UvCategory.EXTREME, 14.0 to UvCategory.EXTREME
        )
        expected.forEach { (uv, category) -> assertEquals("uv $uv", category, HeatRules.uvCategory(uv)) }
        assertEquals(listOf("LOW", "MODERATE", "HIGH", "VERY_HIGH", "EXTREME"), UvCategory.entries.map { it.name })
        assertEquals(UvCategory.MODERATE, HeatRules.uvCategory(HeatRules.UV_PROTECTION_THRESHOLD))
        assertThrows(IllegalArgumentException::class.java) { HeatRules.uvCategory(Double.NaN) }
    }

    // Sun protection from a whole-number UV index of 3 [BOM; SunSmart], always agreeing with the UV word.
    @Test
    fun sunProtectionStartsAtAWholeUvIndexOfThree() {
        assertFalse(HeatRules.needsSunProtection(0.0))
        assertFalse(HeatRules.needsSunProtection(2.4))
        assertTrue(HeatRules.needsSunProtection(2.5))
        assertTrue(HeatRules.needsSunProtection(HeatRules.UV_PROTECTION_THRESHOLD))
        assertTrue(HeatRules.needsSunProtection(11.55))
        (0..140).map { it / 10.0 }.forEach { uv ->
            assertEquals("uv $uv", HeatRules.uvCategory(uv) != UvCategory.LOW, HeatRules.needsSunProtection(uv))
        }
        assertThrows(IllegalArgumentException::class.java) { HeatRules.needsSunProtection(Double.NaN) }
    }

    // Break cadence [HeatShield rule]. No work/rest split at Normal, and none at Extreme either:
    // there the stop line ("Stop heavy work outdoors. Check your crew's hot-day rule.") replaces it.
    @Test
    fun suggestedCadenceTable() {
        assertNull(HeatRules.suggestedCadence(HeatLevel.NORMAL))
        assertEquals(Cadence(60, 10), HeatRules.suggestedCadence(HeatLevel.WARM))
        assertEquals(Cadence(45, 15), HeatRules.suggestedCadence(HeatLevel.HOT))
        assertEquals(Cadence(30, 15), HeatRules.suggestedCadence(HeatLevel.VERY_HOT))
        assertNull(HeatRules.suggestedCadence(HeatLevel.EXTREME))
        assertNull(HeatRules.unionCadence(36.0))
        assertNull(HeatRules.unionCadence(29.0))
    }

    // Every level is a HeatShield rule. The steps borrow VTHC and workplace-rule numbers, but HeatShield applies them
    // to the heat number with its own rounding and personal adjustments, so no outside body's label goes on a level.
    @Test
    fun levelSourceIsTheHeatShieldRuleForEveryLevel() {
        HeatLevel.entries.forEach { level ->
            assertEquals(level.name, SourceLabel.HEATSHIELD_RULE, HeatRules.levelSource(level))
        }
    }

    // Crew hot-day rule input: whole degrees from 25 to 50, so the rule always shows exactly as set.
    @Test
    fun triggerValidationAndPresets() {
        assertFalse(HeatRules.isValidTrigger(24.0))
        assertFalse(HeatRules.isValidTrigger(24.9))
        assertTrue(HeatRules.isValidTrigger(25.0))
        assertTrue(HeatRules.isValidTrigger(36.0))
        assertFalse(HeatRules.isValidTrigger(36.5))
        assertTrue(HeatRules.isValidTrigger(50.0))
        assertFalse(HeatRules.isValidTrigger(50.1))
        assertFalse(HeatRules.isValidTrigger(51.0))
        assertFalse(HeatRules.isValidTrigger(Double.NaN))
        assertFalse(HeatRules.isValidTrigger(Double.POSITIVE_INFINITY))
        assertEquals(35.0, HeatRules.DEFAULT_TRIGGER_C, 0.0)
        assertEquals(listOf(35.0, 38.0), HeatRules.TRIGGER_PRESETS)
        assertTrue(HeatRules.TRIGGER_PRESETS.all(HeatRules::isValidTrigger))
        assertEquals(7L, HeatRules.ACCLIMATISING_DAYS)
    }

    // Source labels and citations: five outside bodies, HeatShield's own rule, and the user's own setting (a custom crew rule).
    @Test
    fun sourceLabelsKeepTheSevenStableIdentifiers() {
        assertEquals(listOf("BOM", "WORKSAFE_VIC", "SAFE_WORK_AUSTRALIA", "WORKPLACE_RULE_EXAMPLE",
            "UNION_STARTING_POINT", "HEATSHIELD_RULE", "YOUR_SETTING"), SourceLabel.entries.map { it.name })
    }

    @Test
    fun everySpecCitationKeyResolvesWithAWebAddress() {
        val specKeys = listOf("bom-at", "bom-uv", "wsv-heat", "wsv-gn-2012", "wsv-sun", "swa-2021", "awu", "glen-eira",
            "banyule", "vthc", "sma", "bhc", "st-john", "av", "healthdirect", "sunsmart", "com-sensor", "open-meteo")
        assertEquals(specKeys, HeatSources.all.map { it.key })
        specKeys.forEach { key ->
            val citation = HeatSources.byKey(key)
            assertEquals(key, citation.key)
            assertTrue(key, citation.url.matches(Regex("""https?://\S+""")))
            assertTrue(key, citation.title.isNotBlank())
        }
        assertThrows(IllegalArgumentException::class.java) { HeatSources.byKey("osha-heat-index") }
    }

    @Test
    fun citationsCarryTheLabelOfTheBodyBehindThem() {
        listOf("bom-at", "bom-uv").forEach { assertEquals(it, SourceLabel.BOM, HeatSources.byKey(it).label) }
        listOf("wsv-heat", "wsv-gn-2012", "wsv-sun").forEach { assertEquals(it, SourceLabel.WORKSAFE_VIC, HeatSources.byKey(it).label) }
        assertEquals(SourceLabel.SAFE_WORK_AUSTRALIA, HeatSources.byKey("swa-2021").label)
        listOf("awu", "glen-eira", "banyule").forEach {
            assertEquals(it, SourceLabel.WORKPLACE_RULE_EXAMPLE, HeatSources.byKey(it).label)
        }
        assertEquals(SourceLabel.UNION_STARTING_POINT, HeatSources.byKey("vthc").label)
        // None of the five labelled bodies and not a HeatShield rule: no chip, so nothing reads as HeatShield's own advice or data.
        listOf("sma", "bhc", "st-john", "av", "healthdirect", "sunsmart", "com-sensor", "open-meteo").forEach {
            assertNull(it, HeatSources.byKey(it).label)
        }
        // [HeatShield rule] and [Your setting] say who decided a number on screen, never who published a source.
        HeatSources.all.forEach {
            assertTrue(it.key, it.label != SourceLabel.HEATSHIELD_RULE && it.label != SourceLabel.YOUR_SETTING)
        }
    }


}
