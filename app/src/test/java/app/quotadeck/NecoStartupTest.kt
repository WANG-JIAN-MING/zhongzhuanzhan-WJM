package app.quotadeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NecoStartupTest {
    private val neco = Site(id = "neco", name = "Neco", url = "https://api.sbbbbbbbbb.xyz", key = "mock")
    private val other = Site(id = "other", name = "Other", url = "https://example.com", key = "mock")

    private class FakeStartupStore : NecoStartupStore {
        var date: String? = null
        var saves = 0
        var saveSucceeds = true
        var readFails = false
        var saveThrows = false
        override fun lastClaimDate(): String? {
            if (readFails) error("read failed")
            return date
        }
        override fun saveClaimDate(date: String): Boolean {
            saves++
            if (saveThrows) error("commit failed")
            if (saveSucceeds) this.date = date
            return saveSucceeds
        }
    }

    private val day = java.time.LocalDate.of(2026, 8, 12)

    @Test fun sameLocalDateIsSavedOnlyOnce() {
        val store = FakeStartupStore()
        val gate = NecoStartupGate(store) { day }
        assertFalse(gate.claim(false))
        assertEquals(0, store.saves)
        assertTrue(gate.claim(true))
        assertEquals(day.toString(), store.date)
        assertFalse(gate.claim(true))
        assertEquals(1, store.saves)
    }

    @Test fun recreatedGateSkipsPersistedDateLikeProcessRestart() {
        val store = FakeStartupStore()
        assertTrue(NecoStartupGate(store) { day }.claim(true))
        // New store instance reads only the previously persisted value, not gate memory.
        val reopened = FakeStartupStore().apply { date = store.date }
        assertFalse(NecoStartupGate(reopened) { day }.claim(true))
        assertEquals(0, reopened.saves)
    }

    @Test fun nextLocalDateAllowsOneNewBatchAndSameSite() {
        val store = FakeStartupStore()
        var date = day
        val gate = NecoStartupGate(store) { date }
        var calls = 0
        repeat(2) {
            assertTrue(gate.claim(true))
            gate.executeSite(neco) { _, _ -> calls++; NecoActionResult("ok") }
            assertFalse(gate.claim(true))
            date = date.plusDays(1)
        }
        assertEquals(8, calls)
        assertEquals(day.plusDays(1).toString(), store.date)
        assertEquals(2, store.saves)
    }

    @Test fun failedCommitPreventsRequestsAndSameGateRetry() {
        val store = FakeStartupStore().apply { saveSucceeds = false }
        val gate = NecoStartupGate(store) { day }
        var calls = 0
        if (gate.claim(true)) gate.executeSite(neco) { _, _ -> calls++; NecoActionResult("unexpected") }
        store.saveSucceeds = true
        assertFalse(gate.claim(true))
        assertEquals(0, calls)
        assertEquals(null, store.date)
        assertEquals(1, store.saves)
    }

    @Test fun storageExceptionsFailClosed() {
        val readFailure = FakeStartupStore().apply { readFails = true }
        assertFalse(NecoStartupGate(readFailure) { day }.claim(true))
        assertEquals(0, readFailure.saves)
        val writeFailure = FakeStartupStore().apply { saveThrows = true }
        assertFalse(NecoStartupGate(writeFailure) { day }.claim(true))
        assertEquals(null, writeFailure.date)
    }

    @Test fun claimIsPersistedBeforeFirstRequestAndFailureStillConsumesDay() {
        val store = FakeStartupStore()
        val gate = NecoStartupGate(store) { day }
        assertTrue(gate.claim(true))
        val result = gate.executeSite(neco) { _, _ ->
            assertEquals(day.toString(), store.date)
            error("mock timeout")
        }
        assertTrue(result.outcomeUnknown)
        assertFalse(NecoStartupGate(store) { day }.claim(true))
    }

    @Test fun emptyBatchStillConsumesGlobalDay() {
        val store = FakeStartupStore()
        assertTrue(NecoStartupGate(store) { day }.claim(true))
        assertFalse(NecoStartupGate(store) { day }.claim(true))
    }

    @Test fun onlyClaimsOnceAfterStorageReady() {
        val gate = NecoStartupGate()
        assertFalse(gate.claim(false))
        assertTrue(gate.claim(true))
        assertFalse(gate.claim(true))
    }

    @Test fun oneSiteRunsSigninThenAtMostThreeSerialDraws() {
        val gate = NecoStartupGate()
        assertTrue(gate.claim(true))
        val calls = mutableListOf<String>()
        val result = gate.executeSite(neco) { _, draw ->
            calls += if (draw) "draw" else "signin"
            NecoActionResult("ok")
        }
        assertEquals(listOf("signin", "draw", "draw", "draw"), calls)
        assertEquals(3, result.draws.size)
        assertEquals(null, result.failedStage)
    }

    @Test fun uncertainSigninDoesNotRetryOrDraw() {
        val calls = mutableListOf<String>()
        val result = NecoStartupGate().executeSite(neco) { _, draw ->
            calls += if (draw) "draw" else "signin"
            if (draw) NecoActionResult("unexpected") else error("mock timeout")
        }
        assertEquals(listOf("signin"), calls)
        assertEquals("signin", result.failedStage)
        assertTrue(result.outcomeUnknown)
    }

    @Test fun explicitDrawFailureStopsRemainingDraws() {
        val calls = mutableListOf<String>()
        val result = NecoStartupGate().executeSite(neco) { _, draw ->
            calls += if (draw) "draw" else "signin"
            if (draw) NecoActionResult("rejected", success = false) else NecoActionResult("ok")
        }
        assertEquals(listOf("signin", "draw"), calls)
        assertEquals("draw", result.failedStage)
        assertFalse(result.outcomeUnknown)
    }

    @Test fun uncertainSecondDrawDoesNotRetryOrContinue() {
        val calls = mutableListOf<String>()
        val result = NecoStartupGate().executeSite(neco) { _, draw ->
            calls += if (draw) "draw" else "signin"
            if (calls.size == 3) throw java.net.SocketTimeoutException("mock")
            NecoActionResult("ok")
        }
        assertEquals(listOf("signin", "draw", "draw"), calls)
        assertEquals(1, result.draws.size)
        assertTrue(result.outcomeUnknown)
    }

    @Test fun sameSiteCannotBeExecutedAgain() {
        val gate = NecoStartupGate()
        var calls = 0
        gate.executeSite(neco) { _, _ -> calls++; NecoActionResult("ok") }
        assertTrue(runCatching { gate.executeSite(neco) { _, _ -> calls++; NecoActionResult("unexpected") } }.isFailure)
        assertEquals(4, calls)
    }

    @Test fun filtersNonNecoSitesWithoutMakingCalls() {
        val gate = NecoStartupGate()
        assertTrue(gate.claim(true))
        val sites = listOf(neco, other).filter { isNecoOrigin(it.url) }
        val calls = mutableListOf<String>()
        sites.forEach { site -> gate.executeSite(site) { target, draw ->
            calls += "${target.id}:${if (draw) "draw" else "signin"}"
            NecoActionResult("ok")
        } }
        assertEquals("neco:signin", calls.first())
        assertEquals(4, calls.size)
        assertTrue(calls.none { it.startsWith("other:") })
    }
}
