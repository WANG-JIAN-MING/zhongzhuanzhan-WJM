package app.quotadeck

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UsageLogApiTest {
    private val site = Site(name = "Example", url = "https://example.com/base/", key = "sk-model")
    private val consume = """{"created_at":1730000000,"type":2,"token_name":"Mobile","model_name":"example-model","quota":9007199254740993,"prompt_tokens":0,"completion_tokens":4,"use_time":0.25,"other":"ignored-field","key":"ignored-key"}"""

    @Test fun accountUsesWalletAuthenticationAndServerPagination() {
        val account = site.copy(account = true, key = "Bearer management", cookie = "session=test", userId = "42")
        val response = accountPage(page = 2, total = 41, items = consume)
        val (result, connection) = fetch(account, 2, response)
        assertEquals("https://example.com/base/api/log/self?type=2&p=2&page_size=20", connection.url.toString())
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("Bearer management", connection.getRequestProperty("Authorization"))
        assertEquals("session=test", connection.getRequestProperty("Cookie"))
        assertEquals("42", connection.getRequestProperty("New-Api-User"))
        assertTrue(connection.disconnected)
        assertTrue(result.hasMore)
        assertEquals(UsageLogEntry(1730000000L, "Mobile", "example-model", 9007199254740993L, 0L, 4L, 0.25, 2), result.entries.single())
        assertFalse(fetch(account, 2, accountPage(page = 2, total = 40)).first.hasMore)
    }

    @Test fun subscriptionSupportsCookieOnlyAccountAuthentication() {
        val subscription = site.copy(subscription = true, key = "", cookie = "session=test", userId = "42")
        val (result, connection) = fetch(subscription, 1, accountPage(page = 1, total = 0))
        assertEquals("/base/api/log/self", connection.url.path)
        assertEquals("session=test", connection.getRequestProperty("Cookie"))
        assertEquals("42", connection.getRequestProperty("New-Api-User"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals(UsageLogPage(emptyList(), false), result)
    }

    @Test fun accountNeverSendsModelKeyAndAcceptsNilLogList() {
        val account = site.copy(account = true, key = "Bearer sk-model", cookie = "session=test", userId = "42")
        val (result, connection) = fetch(account, 1, """{"success":true,"data":{"total":0,"items":null}}""")
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("session=test", connection.getRequestProperty("Cookie"))
        assertEquals(UsageLogPage(emptyList(), false), result)
        assertThrows(IllegalStateException::class.java) {
            UsageLogApi.fetch(account.copy(cookie = ""), 1) { throw AssertionError("Unexpected request") }
        }
        assertEquals(UsageLogPage(emptyList(), false), fetch(site, 1, """{"success":true,"data":null}""").first)
    }

    @Test fun tokenRouteSendsOnlyModelKeyAndLocallyFiltersConsumption() {
        val token = site.copy(cookie = "session=unused", userId = "42")
        val (result, connection) = fetch(token, 1, """{"success":true,"data":[{"type":5},$consume]}""")
        assertEquals("/base/api/log/token", connection.url.file)
        assertEquals("Bearer sk-model", connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertNull(connection.getRequestProperty("New-Api-User"))
        assertEquals("example-model", result.entries.single().modelName)
        assertFalse(result.hasMore)
        assertEquals(UsageLogPage(emptyList(), false), UsageLogApi.fetch(token, 2) { error("Unexpected request") })
    }

    @Test fun missingNumbersAndExplicitZerosStayDistinct() {
        val missing = """{"type":2,"created_at":1730000001,"quota":null,"token_name":null}"""
        val zero = """{"type":2,"created_at":1730000000,"quota":0,"prompt_tokens":0,"completion_tokens":0,"use_time":0}"""
        val (result) = fetch(site, 1, """{"success":true,"data":[$missing,$zero]}""")
        val (first, second) = result.entries
        assertEquals(listOf(1730000001L, 1730000000L), result.entries.map { it.createdAt })
        assertEquals("", first.tokenName)
        assertNull(first.quota)
        assertNull(first.promptTokens)
        assertNull(first.completionTokens)
        assertNull(first.useTime)
        assertEquals(0L, second.quota)
        assertEquals(0L, second.promptTokens)
        assertEquals(0L, second.completionTokens)
        assertEquals(0.0, second.useTime)
    }

    @Test fun redirectAndHttpErrorsNeverReadBodiesOrOpenAnotherConnection() {
        for (status in listOf(302, 401, 403, 429, 500)) {
            var opens = 0
            lateinit var connection: FakeConnection
            val failure = assertThrows(IllegalStateException::class.java) {
                UsageLogApi.fetch(site, 1) { url ->
                    opens++
                    FakeConnection(url, "private-payload", status).also { connection = it }
                }
            }
            assertEquals(1, opens)
            assertFalse(connection.instanceFollowRedirects)
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
            assertSafe(failure)
        }
    }

    @Test fun applicationJsonAndTransportErrorsAreSanitized() {
        for (body in listOf("""{"success":false,"message":"private-payload"}""", "private-payload", """{"success":true,"data":[{"type":2,"created_at":"private-payload"}]}""")) {
            assertSafe(assertThrows(IllegalStateException::class.java) { fetch(site, 1, body) })
        }
        assertSafe(assertThrows(IllegalStateException::class.java) {
            UsageLogApi.fetch(site, 1) { throw IOException("private-payload sk-model") }
        })
    }

    @Test fun invalidCredentialsOrAddressNeverOpenConnection() {
        for (invalid in listOf(site.copy(key = "", cookie = "session=unused"), site.copy(key = "sk-model\r\nInjected: yes"), site.copy(url = "http://example.com"), site.copy(url = "https://user:sk-model@example.com"))) {
            assertSafe(assertThrows(IllegalStateException::class.java) {
                UsageLogApi.fetch(invalid, 1) { error("Unexpected request") }
            })
        }
    }

    private fun assertSafe(failure: Throwable) {
        assertFalse(failure.toString().contains("private-payload"))
        assertFalse(failure.toString().contains("sk-model"))
        assertFalse(usageLogError(failure).contains("private-payload"))
        assertFalse(usageLogError(failure).contains("sk-model"))
        assertFalse(failure.toString().contains("Unexpected request"))
        assertNull(failure.cause)
    }

    private fun accountPage(page: Int, total: Int, items: String = "") =
        """{"success":true,"data":{"page":$page,"page_size":20,"total":$total,"items":[$items]}}"""

    private fun fetch(site: Site, page: Int, text: String): Pair<UsageLogPage, FakeConnection> {
        lateinit var connection: FakeConnection
        val result = UsageLogApi.fetch(site, page) { url -> FakeConnection(url, text).also { connection = it } }
        return result to connection
    }

    private class FakeConnection(url: URL, private val body: String, private val status: Int = 200) : HttpURLConnection(url) {
        var disconnected = false
        var bodyRead = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream {
            bodyRead = true
            return body.byteInputStream()
        }
        override fun getErrorStream(): InputStream = inputStream
    }
}
