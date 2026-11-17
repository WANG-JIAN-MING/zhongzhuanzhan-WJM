package app.quotadeck

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Neco pet requests never retry; startup sequencing is controlled by NecoStartupGate. */
data class NecoPetState(
    val signedIn: Boolean? = null,
    val message: String? = null,
    val inventory: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)

data class NecoReward(val text: String)

data class NecoActionResult(val message: String?, val reward: NecoReward? = null, val success: Boolean = true)

object NecoDrawGate {
    fun submit(confirmed: Boolean, action: () -> Unit) { if (confirmed) action() }
}

object NecoPetParser {
    fun signedIn(text: String): Boolean? {
        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: root
        for (key in listOf("today", "signed_in", "already_signed_in", "signin", "is_signed_in")) {
            if (!data.has(key) || data.isNull(key)) continue
            val value = data.opt(key)
            if (value is Boolean) return value
            if (value is String && (value.equals("true", true) || value.equals("false", true))) return value.toBoolean()
        }
        return null
    }

    fun reward(text: String): NecoReward? {
        val root = JSONObject(text)
        val raw = root.optJSONObject("raw") ?: root.optJSONObject("data") ?: root
        val item = raw.optJSONObject("reward") ?: if (raw.has("reward_type")) raw else null ?: return null
        val parts = mutableListOf<String>()
        val type = item.optString("reward_type")
        val title = item.optString("label").ifBlank { item.optString("item_name") }.ifBlank {
            when (type) { "promo_credit" -> "推广额度"; "display_credit" -> "展示额度"; "pet_coin" -> "宠物币"; else -> type }
        }
        if (title.isNotBlank()) parts += title
        val quantity = when {
            item.has("quantity") -> item.optDouble("quantity", 0.0)
            item.has("pet_coin") -> item.optDouble("pet_coin", 0.0)
            item.has("coin_amount") -> item.optDouble("coin_amount", 0.0)
            item.has("promo_credit_awarded") -> item.optDouble("promo_credit_awarded", 0.0)
            item.has("display_credit") -> item.optDouble("display_credit", 0.0)
            else -> raw.optDouble("promo_credit_awarded", 0.0)
        }
        if (quantity > 0) parts += formatNumber(quantity)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let(::NecoReward)
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    fun actionMessage(text: String): String? {
        val root = JSONObject(text)
        val raw = root.optJSONObject("raw") ?: root.optJSONObject("data") ?: root
        val parts = mutableListOf<String>()
        raw.optString("message").takeIf { it.isNotBlank() }?.let(parts::add)
        raw.optDouble("promo_credit_awarded", 0.0).takeIf { it > 0.0 }?.let { parts += "奖励额度 ${formatNumber(it)}" }
        raw.optString("reason").takeIf { it.isNotBlank() && it !in parts }?.let(parts::add)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return root.optString("message").takeIf { it.isNotBlank() }
    }
}

object NecoPetApi {
    fun self(s: Site): NecoPetState {
        val text = request(s, "GET", "/api/user/pet/self")
        val root = JSONObject(text)
        require(!root.has("success") || root.optBoolean("success")) { root.optString("message").ifBlank { "宠物状态读取失败，请核实站点响应" } }
        val data = root.optJSONObject("data") ?: root
        val profile = data.optJSONObject("profile")
        val inventory = data.optJSONArray("inventory")?.length()?.let { "背包 $it 项" }
        return NecoPetState(NecoPetParser.signedIn(text), profile?.optString("name"), inventory)
    }

    fun signin(s: Site): NecoActionResult {
        val text = request(s, "POST", "/api/user/pet/signin")
        return NecoActionResult(NecoPetParser.actionMessage(text), success = success(text))
    }

    fun draw(s: Site): NecoActionResult {
        val text = request(s, "POST", "/api/user/pet/draw")
        return NecoActionResult(NecoPetParser.actionMessage(text), NecoPetParser.reward(text), success(text))
    }

    private fun success(text: String): Boolean {
        val root = JSONObject(text)
        require(root.opt("success") is Boolean) { "操作响应缺少明确成功标记，结果未知" }
        return root.getBoolean("success")
    }

    private fun request(s: Site, method: String, path: String): String {
        val conn = URL(s.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            if (s.key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer " + s.key.trim().removePrefix("Bearer "))
            if (s.cookie.isNotBlank()) conn.setRequestProperty("Cookie", s.cookie)
            if (s.userId.isNotBlank()) conn.setRequestProperty("New-Api-User", s.userId)
            conn.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                val body = "{}".toByteArray(Charsets.UTF_8)
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size); conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            require(code in 200..299) { "接口返回 HTTP $code，请核实登录状态与站点地址" }
            return conn.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val output = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    require(output.length <= 1048576) { "接口响应过大" }
                }
                output.toString()
            }
        } finally { conn.disconnect() }
    }
}


data class NecoStartupSiteResult(
    val siteId: String,
    val signin: NecoActionResult?,
    val draws: List<NecoActionResult>,
    val failedStage: String? = null,
    val outcomeUnknown: Boolean = false
)

/** One invocation per ready startup; each mutating request is issued at most once. */
class NecoStartupGate {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val attemptedSites = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun claim(ready: Boolean): Boolean = ready && started.compareAndSet(false, true)

    fun executeSite(site: Site, action: (Site, draw: Boolean) -> NecoActionResult): NecoStartupSiteResult {
        require(isNecoOrigin(site.url))
        check(attemptedSites.add(site.id)) { "站点启动操作已执行，不会重复请求" }
        val signin = try {
            action(site, false)
        } catch (_: Exception) {
            return NecoStartupSiteResult(site.id, null, emptyList(), "signin", true)
        }
        if (!signin.success) return NecoStartupSiteResult(site.id, signin, emptyList(), "signin")
        val draws = mutableListOf<NecoActionResult>()
        repeat(3) {
            val draw = try {
                action(site, true)
            } catch (_: Exception) {
                return NecoStartupSiteResult(site.id, signin, draws, "draw", true)
            }
            draws += draw
            if (!draw.success) return NecoStartupSiteResult(site.id, signin, draws, "draw")
        }
        return NecoStartupSiteResult(site.id, signin, draws)
    }
}
