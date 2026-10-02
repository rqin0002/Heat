package heatshield

import heatshield.data.WorkSite
import heatshield.ui.ForecastRefreshCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForecastRefreshTest {
    @Test
    fun selectingBWhileAIsPendingStartsBAndDoesNotClearItsBusyState() = runTest {
        val a = WorkSite.defaults[0]
        val b = WorkSite.defaults[1]
        val gates = mapOf(a.name to CompletableDeferred<Unit>(), b.name to CompletableDeferred<Unit>())
        val calls = mutableListOf<String>()
        var busy = false
        val refresh = ForecastRefreshCoordinator(this, { site ->
            calls += site.name
            gates.getValue(site.name).await()
        }, { busy = it }, { throw AssertionError(it) })
        refresh.request(a)
        runCurrent()
        refresh.request(b)
        runCurrent()
        assertEquals(listOf(a.name, b.name), calls)
        assertTrue(busy)
        gates.getValue(b.name).complete(Unit)
        runCurrent()
        assertFalse(busy)
    }

    @Test
    fun repeatedSameParkCoalescesButCanRetryAfterFailure() = runTest {
        val a = WorkSite.defaults.first()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var failures = 0
        var busy = false
        val refresh = ForecastRefreshCoordinator(this, {
            calls++
            gate.await()
            throw java.io.IOException("storage failure")
        }, { busy = it }, { failures++ })
        refresh.request(a)
        runCurrent()
        refresh.request(a)
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, failures)
        assertFalse(busy)
        refresh.request(a)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(2, failures)
    }

    @Test
    fun ownerSwitchDiscardsAnOldNonCancellableFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        var failures = 0
        var busy = false
        val refresh = ForecastRefreshCoordinator(this, {
            withContext(NonCancellable) { gate.await(); throw java.io.IOException("old owner") }
        }, { busy = it }, { failures++ })
        refresh.request(WorkSite.defaults.first())
        runCurrent()
        refresh.cancel() // switchOwner invokes this before binding the new owner.
        assertFalse(busy)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, failures)
        assertFalse(busy)
    }
}
