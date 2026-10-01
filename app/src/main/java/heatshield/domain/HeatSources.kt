package heatshield.domain

/**
 * The only labels a number in HeatShield may carry (spec §5). Each number shows exactly one.
 * Five name an outside body; HEATSHIELD_RULE marks a number HeatShield decided (every level and composite action);
 * YOUR_SETTING marks a value the user typed in (a custom crew rule).
 */
enum class SourceLabel {
    BOM, WORKSAFE_VIC, SAFE_WORK_AUSTRALIA, WORKPLACE_RULE_EXAMPLE, UNION_STARTING_POINT,
    HEATSHIELD_RULE, YOUR_SETTING
}

/** A source shown in About & sources. [label] is null when the publisher is none of the five labelled bodies: no chip. */
data class Citation(val key: String, val label: SourceLabel?, val title: String, val url: String)

/**
 * Every source behind the heat model, first aid and data credits, keyed and ordered as in spec §5.
 * BOM, WorkSafe Vic, Safe Work Australia, the workplace rules and VTHC carry their own label. The rest
 * (SMA, health services, SunSmart, data providers) are none of those bodies and are not HeatShield rules,
 * so their label is null: the title alone names who published them. No citation carries HEATSHIELD_RULE or YOUR_SETTING.
 */
object HeatSources {
    val all: List<Citation> = listOf(
        Citation("bom-at", SourceLabel.BOM, "BOM, Thermal comfort observations",
            "http://www.bom.gov.au/info/thermal_stress/"),
        Citation("bom-uv", SourceLabel.BOM, "BOM, About the UV Index",
            "https://www.bom.gov.au/resources/learn-and-explore/uv-knowledge-centre/about-the-uv-index"),
        Citation("wsv-heat", SourceLabel.WORKSAFE_VIC, "WorkSafe Victoria, Prevention and protection from heat illness (reviewed 12 Jan 2026)",
            "https://www.worksafe.vic.gov.au/prevention-and-protection-heat-illness"),
        Citation("wsv-gn-2012", SourceLabel.WORKSAFE_VIC, "WorkSafe Victoria, Guidance note: Working in heat (2012)",
            "https://content-v2.api.worksafe.vic.gov.au/sites/default/files/2018-06/ISBN-Working-in-heat-2012-07.pdf"),
        Citation("wsv-sun", SourceLabel.WORKSAFE_VIC, "WorkSafe Victoria, Sun protection for outdoor workers",
            "https://www.worksafe.vic.gov.au/sun-protection-outdoor-workers"),
        Citation("swa-2021", SourceLabel.SAFE_WORK_AUSTRALIA, "Safe Work Australia, Managing the risks of working in heat (2021)",
            "https://www.safeworkaustralia.gov.au/doc/guide-managing-risks-working-heat"),
        Citation("awu", SourceLabel.WORKPLACE_RULE_EXAMPLE, "AWU, Model hot-weather clause",
            "https://www.awu.net.au/wp-content/uploads/2019/10/heat-model-ea.pdf"),
        Citation("glen-eira", SourceLabel.WORKPLACE_RULE_EXAMPLE, "Glen Eira City Council, Policy 4.23 (rev. Dec 2013)",
            "https://www.gleneira.vic.gov.au/media/11522/working-in-extreme-weather-policy.pdf"),
        Citation("banyule", SourceLabel.WORKPLACE_RULE_EXAMPLE, "Banyule City Council, CD5074 (2018)",
            "https://www.banyule.vic.gov.au/files/assets/public/_operating-images-amp-docs/documents/heat-and-uv-protection-guidelines.pdf"),
        Citation("vthc", SourceLabel.UNION_STARTING_POINT, "VTHC OHS Reps, Heat",
            "https://www.ohsrep.org.au/heat"),
        Citation("sma", null, "Sports Medicine Australia, Extreme Heat Policy (2021)",
            "https://sma.org.au/sma-unveils-new-extreme-heat-policy/"),
        Citation("bhc", null, "Better Health Channel, Heat-related illness",
            "https://www.betterhealth.vic.gov.au/health/healthyliving/heat-stress-and-heat-related-illness"),
        Citation("st-john", null, "St John Ambulance Australia, Heat-induced illness (2022)",
            "https://stjohn.org.au/fact-sheet/heat-induced-illness/"),
        Citation("av", null, "Ambulance Victoria, Heat health",
            "https://www.ambulance.vic.gov.au/heat-health/"),
        Citation("healthdirect", null, "Healthdirect, Hot weather risks",
            "https://www.healthdirect.gov.au/hot-weather-risks-and-staying-cool"),
        Citation("sunsmart", null, "SunSmart, Workplaces",
            "https://www.sunsmart.com.au/advice-for/workplaces"),
        Citation("com-sensor", null, "City of Melbourne, Microclimate sensors (CC BY 4.0)",
            "https://data.melbourne.vic.gov.au/explore/dataset/microclimate-sensors-data/information/"),
        Citation("open-meteo", null, "Open-Meteo, Forecast and air quality data (CC BY 4.0)",
            "https://open-meteo.com/")
    )

    private val byKeys: Map<String, Citation> = all.associateBy { it.key }

    fun byKey(key: String): Citation = requireNotNull(byKeys[key]) { "Unknown source key: $key" }
}
