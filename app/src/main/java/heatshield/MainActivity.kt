// Opens HeatShield from the launcher, first-aid shortcut or a notification.
// Pending requests stay here until the screens accept them, including when Android recreates the activity.
package heatshield

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import heatshield.background.DeepLinks
import heatshield.background.UnwellRequest
import heatshield.ui.HeatShieldApp
import heatshield.ui.HeatShieldTheme

class MainActivity : ComponentActivity() {
    private var startRoute by mutableStateOf<String?>(null)
    private var unwell by mutableStateOf<UnwellRequest?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(appLocaleOverride())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.routeFor(intent.action)?.let { startRoute = it }
        DeepLinks.unwellRequest(intent)?.let { unwell = it }
    }

    /**
     * Restores only requests the screens have not handled, or reads a fresh notification tap.
     * For example, an Unwell tap waiting for its shift survives rotation; a consumed tap is not read again.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reopened from Recents, Android hands back the intent that first started the task. On API 26–30 Back finishes this
        // root activity, so a task cold-started by Unwell would open first aid and start a second unwell break on every
        // return from Recents. A deep link acts once: never from history. (ACTION_UNWELL is exported like the launcher; any
        // app could send it, but it only starts a break on the owner's open shift, which the accepted risk allows.)
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState != null) {
            startRoute = savedInstanceState.getString(PENDING_ROUTE)
            val owner = savedInstanceState.getString(PENDING_UNWELL_OWNER)
            val shift = savedInstanceState.getInt(PENDING_UNWELL_SHIFT)
            unwell = if (owner != null && shift > 0) UnwellRequest(owner, shift) else null
        } else if (!fromHistory) {
            startRoute = DeepLinks.routeFor(intent?.action)
            unwell = DeepLinks.unwellRequest(intent)
        }
        enableEdgeToEdge()
        setContent {
            HeatShieldTheme {
                HeatShieldApp(startRoute = startRoute, onStartRouteHandled = { startRoute = null },
                    unwell = unwell, onUnwellHandled = { unwell = null })
            }
        }
    }

    /**
     * Keeps unhandled notification work through recreation; the existing handling callbacks clear these fields.
     * Example: first aid may already be open while shift 7's Unwell request still waits for a database write.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PENDING_ROUTE, startRoute)
        outState.putString(PENDING_UNWELL_OWNER, unwell?.ownerId)
        outState.putInt(PENDING_UNWELL_SHIFT, unwell?.shiftId ?: 0)
    }

    private companion object {
        const val PENDING_ROUTE = "heatshield.pending.route"
        const val PENDING_UNWELL_OWNER = "heatshield.pending.unwell.owner"
        const val PENDING_UNWELL_SHIFT = "heatshield.pending.unwell.shift"
    }
}
