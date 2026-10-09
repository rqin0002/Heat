package heatshield

import android.content.ContextWrapper
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Compose tests host screens in the test runner's activity, which MainActivity's [AppLocale] pin does not reach.
 * This gives them the app's English resources on any device language, so plurals ("1 shift") and number formats
 * ("28.3°") read as they do in the app. Dialogs and pop-ups provide their own LocalContext, so LocalResources,
 * which stringResource and pluralStringResource read, is provided as well.
 */
@Composable
fun EnglishResources(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val english = remember(base) {
        val appResources = base.withAppLocale().resources
        // A wrapper, not the configuration context itself, so the hosting activity stays reachable from LocalContext.
        object : ContextWrapper(base) {
            override fun getResources(): Resources = appResources
        }
    }
    CompositionLocalProvider(
        LocalContext provides english,
        LocalResources provides english.resources,
        LocalConfiguration provides english.resources.configuration,
        content = content
    )
}

/** [ComposeContentTestRule.setContent], with the content rendered in [EnglishResources]. */
fun ComposeContentTestRule.setEnglishContent(content: @Composable () -> Unit) = setContent { EnglishResources(content) }
