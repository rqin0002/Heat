// Identifies a failed forecast without storing screen wording in Room.
// Old cache messages are recognised here so an upgrade keeps their useful failure distinctions.
package heatshield.data

/** Stable values stored in the existing forecast error column; the UI supplies the displayed words. */
enum class ForecastFailure(val key: String) {
    SERVICE("forecast_service"), CONNECTION("forecast_connection"), INCOMPLETE("forecast_incomplete"),
    SAVED_UNREADABLE("forecast_saved_unreadable");

    companion object {
        /**
         * Reads a current key or an old cache message; null means no failure and unknown text means incomplete data.
         * Example: the old connection sentence becomes CONNECTION, whose resource is chosen only when displayed.
         */
        fun fromStored(value: String?): ForecastFailure? = when (value) {
            null -> null
            SERVICE.key, "The forecast service didn't answer. Retry in a few minutes." -> SERVICE
            CONNECTION.key, "Forecast could not be reached. Check your connection and retry." -> CONNECTION
            INCOMPLETE.key, "The forecast came back incomplete. Retry." -> INCOMPLETE
            SAVED_UNREADABLE.key, "The saved forecast couldn't be read. Retry." -> SAVED_UNREADABLE
            else -> INCOMPLETE
        }
    }
}
