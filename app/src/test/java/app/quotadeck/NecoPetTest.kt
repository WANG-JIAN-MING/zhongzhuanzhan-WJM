package app.quotadeck

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class NecoPetTest {
    @Test fun petEndpointsUseExpectedMethodsPathsAndEmptyBody() {
        val requests = mutableListOf<Pair<String, String>>()
        val server = ServerSocket(0)
        val worker = thread {
            repeat(3) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    val line = input.readLine().orEmpty()
                    val headers = mutableListOf<String>()
                    while (true) { val header = input.readLine().orEmpty(); if (header.isEmpty()) break; headers += header }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = CharArray(length)
                    if (length > 0) input.read(body)
                    synchronized(requests) { requests += line to String(body) }
                    val response = "{\"success\":true,\"data\":{\"profile\":{},\"inventory\":[],\"today\":false}}"
                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
                }
            }
            server.close()
        }
        val site = Site(name = "mock", url = "http://127.0.0.1:${server.localPort}", key = "")
        NecoPetApi.self(site)
        NecoPetApi.signin(site)
        NecoPetApi.draw(site)
        worker.join(3000)
        assertEquals(listOf("GET /api/user/pet/self HTTP/1.1" to "", "POST /api/user/pet/signin HTTP/1.1" to "{}", "POST /api/user/pet/draw HTTP/1.1" to "{}"), requests)
    }

    @Test fun parsesSigninStateAndRewardVariants() {
        assertEquals(false, NecoPetParser.signedIn("""{"data":{"today":false}}"""))
        assertEquals(true, NecoPetParser.signedIn("""{"data":{"already_signed_in":true}}"""))
        assertNull(NecoPetParser.signedIn("""{"data":{"profile":{},"inventory":[]}}"""))
        assertEquals("稀有道具 · 2", NecoPetParser.reward("""{"raw":{"reward":{"reward_type":"item","item_name":"稀有道具","quantity":2}}}""")?.text)
        assertEquals("宠物币 · 10", NecoPetParser.reward("""{"reward":{"reward_type":"pet_coin","pet_coin":10}}""")?.text)
        assertNull(NecoPetParser.reward("""{"success":true}"""))
    }

    @Test fun drawConfirmationGateSubmitsOnlyAfterConfirmation() {
        var submitted = 0
        NecoDrawGate.submit(false) { submitted++ }
        assertEquals(0, submitted)
        NecoDrawGate.submit(true) { submitted++ }
        assertEquals(1, submitted)
    }
}
