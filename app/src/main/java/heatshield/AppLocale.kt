package heatshield

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The locale of every resource HeatShield reads. The copy exists only in Australian English (`res/values`), but
 * Android picks a `<plurals>` item, and formats `%d` and `%.1f`, in the locale it reads resources in. Left to the
 * phone, Indonesian, Chinese, Japanese, Korean or Thai would turn "1 shift" into "1 shifts", and a decimal-comma
 * language would turn "28.3°" into "28,3°".
 */
val AppLocale: Locale = Locale.forLanguageTag("en-AU")

/**
 * A configuration delta that sets only [AppLocale], and with it a left-to-right layout. Font scale, dark theme and
 * screen size still follow the phone, because `Configuration()` starts with every other field undefined.
 */
fun appLocaleOverride(): Configuration = Configuration().apply { setLocale(AppLocale) }

/** This context with its resources read in [AppLocale], for text built outside an activity, such as the view model's. */
fun Context.withAppLocale(): Context = createConfigurationContext(appLocaleOverride())
