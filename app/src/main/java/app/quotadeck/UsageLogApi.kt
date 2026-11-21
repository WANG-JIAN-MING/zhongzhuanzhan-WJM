package app.quotadeck

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class UsageLogPage(val entries: List<UsageLogEntry>, val hasMore: Boolean)

internal data class UsageLogEntry(
    val createdAt: Long,
    val tokenName: String,
    val modelName: String,
    val quota: Long?,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val useTime: Double?,
    val type: Int,
)

internal object UsageLogApi {
    private const val PAGE_SIZE = 20
    private const val CONSUMPTION = 2
    private const val MAX_RESPONSE_CHARS = 1_048_576

    fun fetch(site: Site, page: Int): UsageLogPage =
        fetch(site, page) { it.openConnection() as HttpURLConnection }

    internal fun fetch(site: Site, page: Int, open: (URL) -> HttpURLConnection): UsageLogPage {
        require(page > 0) { "消费明细页码无效" }
        val account = site.account || site.subscription
        // The token endpoint exposes only a single, upstream-capped list of recent logs.
        if (!account && page > 1) return UsageLogPage(emptyList(), false)
        val path = if (account) "/api/log/self?type=$CONSUMPTION&p=$page&page_size=$PAGE_SIZE" else "/api/log/token"
        val text = try {
            read(site, account, path, open)
        } catch (failure: LogFailure) {
            throw failure
        } catch (_: Exception) {
            throw LogFailure("无法连接站点读取消费明细")
        }
        return try {
            val root = JSONObject(text)
            if (!root.optBoolean("success")) throw LogFailure("站点拒绝查询消费明细，请检查凭据和权限")
            if (account) {
                val data = root.getJSONObject("data")
                val total = data.integer("total")?.takeIf { it >= 0 } ?: invalidResponse()
                val size = if (data.has("page_size")) data.integer("page_size") else PAGE_SIZE.toLong()
                if (size == null || size !in 1L..100L) invalidResponse()
                if (data.has("page") && data.integer("page") != page.toLong()) invalidResponse()
                UsageLogPage(data.logEntries("items"), page.toLong() * size < total)
            } else {
                UsageLogPage(root.logEntries("data"), false)
            }
        } catch (failure: LogFailure) {
            throw failure
        } catch (_: Exception) {
            invalidResponse()
        }
    }

    private fun read(site: Site, account: Boolean, path: String, open: (URL) -> HttpURLConnection): String {
        val base = try {
            URI(site.url)
        } catch (_: Exception) {
            throw LogFailure("请输入有效的 HTTPS 站点地址")
        }
        if (base.scheme != "https" || base.host.isNullOrBlank() || base.userInfo != null ||
            base.rawQuery != null || base.rawFragment != null
        ) throw LogFailure("请输入有效的 HTTPS 站点地址")

        val configuredKey = site.key.trim()
        val bearer = if (configuredKey.startsWith("Bearer ", ignoreCase = true)) configuredKey.substring(7).trim() else configuredKey
        val key = bearer.takeUnless { account && it.startsWith("sk-", ignoreCase = true) }.orEmpty()
        val cookie = if (account) site.cookie else ""
        val userId = if (account) site.userId else ""
        if (key.isBlank() && cookie.isBlank()) throw LogFailure("请配置有效凭据以查询消费明细")
        if (listOf(site.key, cookie, userId).any { header -> header.any { it == '\r' || it == '\n' || it == '\u0000' } }) {
            throw LogFailure("请配置有效凭据以查询消费明细")
        }

        val connection = open(URL(site.url.trimEnd('/') + path))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            if (userId.isNotBlank()) connection.setRequestProperty("New-Api-User", userId)
            when (val code = connection.responseCode) {
                in 200..299 -> Unit
                in 300..399 -> throw LogFailure("接口发生重定向，请填写最终站点地址")
                401, 403 -> throw LogFailure("无法访问消费明细，请检查凭据和权限")
                429 -> throw LogFailure("请求过于频繁，请稍后重试")
                else -> throw LogFailure("无法读取消费明细（HTTP $code）")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_RESPONSE_CHARS) throw LogFailure("消费明细响应过大")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun entries(items: JSONArray): List<UsageLogEntry> = (0 until items.length()).mapNotNull { index ->
        val item = items.getJSONObject(index)
        val type = item.integer("type") ?: invalidResponse()
        if (type != CONSUMPTION.toLong()) return@mapNotNull null
        UsageLogEntry(
            createdAt = item.integer("created_at")?.takeIf { it >= 0 } ?: invalidResponse(),
            tokenName = (item.opt("token_name") as? String).orEmpty(),
            modelName = (item.opt("model_name") as? String).orEmpty(),
            quota = item.integer("quota"),
            promptTokens = item.integer("prompt_tokens"),
            completionTokens = item.integer("completion_tokens"),
            useTime = item.optDouble("use_time").takeIf { it.isFinite() && it >= 0 },
            type = CONSUMPTION,
        )
    }

    private fun JSONObject.integer(name: String): Long? = opt(name)?.toString()?.toLongOrNull()

    private fun invalidResponse(): Nothing = throw LogFailure("站点返回的消费明细格式无效")

    private fun JSONObject.logEntries(name: String): List<UsageLogEntry> =
        if (opt(name) === JSONObject.NULL) emptyList() else entries(getJSONArray(name))
}

private class LogFailure(val detail: String) : IllegalStateException(detail)

internal fun usageLogError(failure: Throwable): String =
    (failure as? LogFailure)?.detail ?: "无法读取消费明细，请稍后重试"
