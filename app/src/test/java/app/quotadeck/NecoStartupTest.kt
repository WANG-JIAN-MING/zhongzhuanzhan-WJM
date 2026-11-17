package app.quotadeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NecoStartupTest {
    private val neco = Site(id = "neco", name = "Neco", url = "https://api.sbbbbbbbbb.xyz", key = "mock")
    private val other = Site(id = "other", name = "Other", url = "https://example.com", key = "mock")

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
