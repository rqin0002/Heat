package heatshield

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The merged manifest as installed: the permissions M3 needs and the ones the spec forbids (§9, §10 M3). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManifestTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val info = context.packageManager.getPackageInfo(context.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
    private val permissions = info.requestedPermissions.orEmpty().toSet()

    @Test
    fun alertsNeedNotificationsBootAndUserGrantedExactAlarmsOnly() {
        assertTrue(permissions.toString(), permissions.containsAll(listOf(
            "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM")))
        // work-runtime contributes FOREGROUND_SERVICE; the app manifest removes it at merge time (tools:node="remove").
        assertTrue(permissions.toString(), permissions.none { it.startsWith("android.permission.FOREGROUND_SERVICE") })
        assertTrue(permissions.toString(), "android.permission.USE_EXACT_ALARM" !in permissions)
        assertTrue(permissions.toString(), "android.permission.SYSTEM_ALERT_WINDOW" !in permissions)
    }

    @Test
    fun receiversAreDeclaredAsM3Needs() {
        val receivers = info.receivers.orEmpty().associate { it.name to it.exported }
        // The A2 plan reminder's receiver is retired; these three carry every alarm, notification action and system broadcast.
        assertEquals(false, receivers["heatshield.background.AlertReceiver"])
        assertEquals(false, receivers["heatshield.background.ShiftActionReceiver"])
        assertEquals(true, receivers["heatshield.background.ReconcileReceiver"])   // system broadcasts need exported=true on API 31+
        assertTrue(receivers.keys.toString(), "heatshield.background.ReminderReceiver" !in receivers)
    }
}
