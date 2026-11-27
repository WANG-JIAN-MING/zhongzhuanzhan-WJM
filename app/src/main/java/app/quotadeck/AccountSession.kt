package app.quotadeck

import org.json.JSONObject
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Only this origin's modern cookie-backed protocol has been verified. Manual bearer-only
// accounts, API keys and legacy New API sessions keep their existing request behavior.
internal fun isCookieSessionOrigin(url: String): Boolean = runCatching {
 val uri=URI(url)
 uri.scheme=="https" && uri.host.equals("wkapi.pro",true) && uri.userInfo==null &&
  (uri.port==-1 || uri.port==443) && uri.path.orEmpty() in listOf("","/") && uri.query==null && uri.fragment==null
}.getOrDefault(false)
internal fun supportsSessionRenewal(site: Site): Boolean =
 (site.account || site.subscription) && site.cookie.isNotBlank() && isCookieSessionOrigin(site.url)

internal class SessionRenewalException(message: String): IllegalStateException(message)
private const val REFRESH_COOKIE = "new_api_refresh"
private fun hasRefreshCookie(site: Site): Boolean = site.cookie.split(';').any {
 val pair=it.trim().split('=',limit=2)
 pair.size==2 && pair[0]==REFRESH_COOKIE && pair[1].isNotBlank()
}

// A readable wallet only proves the short-lived bearer is valid. Verify the cookie
// before handing a modern WebView login to the independently persisted app session.
internal fun prepareSessionBinding(site: Site, refresh: (Site)->SessionResponse = ::requestSessionRenewal): Site =
 if((site.account || site.subscription) && isCookieSessionOrigin(site.url)) renewAccountSession(site,refresh) else site

private fun renewalFailure(response: SessionResponse): SessionRenewalException {
 val code=runCatching { JSONObject(response.body).optString("code") }.getOrDefault("")
 val detail=when {
  code=="AUTH_ORIGIN_FORBIDDEN" -> "站点拒绝续期请求的来源，请更新应用后重新绑定"
  code=="AUTH_SESSION_REVOKED" -> "站点已撤销登录会话，请重新网页登录绑定"
  code=="AUTH_SESSION_MISMATCH" -> "登录会话不匹配，请重新网页登录绑定"
  code=="AUTH_REFRESH_RACE" -> "登录会话正在轮换，请稍后重试"
  response.status==401 -> "刷新凭据已失效，请重新网页登录绑定"
  response.status==403 -> "站点拒绝续期请求，请重新网页登录绑定"
  response.status==429 -> "续期请求过于频繁，请稍后重试"
  response.status in 500..599 -> "站点续期服务暂时异常，请稍后重试"
  response.status in 300..399 -> "续期接口发生重定向，请检查站点地址"
  else -> "站点返回的续期数据不兼容，请重新网页登录绑定"
 }
 return SessionRenewalException("登录会话续期失败：$detail")
}
internal fun renewAccountSession(site: Site, refresh: (Site)->SessionResponse): Site {
 if(!hasRefreshCookie(site)) throw SessionRenewalException("登录会话续期失败：未获取到刷新 Cookie，请重新网页登录绑定")
 val response=try { refresh(site) } catch(_: java.net.SocketTimeoutException) {
  throw SessionRenewalException("登录会话续期失败：连接超时，请稍后重试")
 } catch(_: java.io.IOException) {
  throw SessionRenewalException("登录会话续期失败：网络连接异常，请稍后重试")
 } catch(_: Exception) {
  throw SessionRenewalException("登录会话续期失败：请求未能完成，请重新网页登录绑定")
 }
 if(response.status !in 200..299) throw renewalFailure(response)
 return try {
  parseSessionRenewal(site,response).also { require(hasRefreshCookie(it)) }
 } catch(_: Exception) { throw renewalFailure(response) }
}

// Detach each new WK login from any session already owned by a saved site. Expiring
// browser cookies does not call logout or revoke the account's saved native session.
internal fun webLoginSessionResetCookies(origin: String): List<Pair<String,String>> =
 if(!isCookieSessionOrigin(origin)) emptyList() else listOf(
  origin.trimEnd('/')+"/api/user/auth/refresh" to "$REFRESH_COOKIE=; Path=/api/user/auth; Max-Age=0; HttpOnly; Secure; SameSite=Strict",
  origin.trimEnd('/')+"/" to "new_api_has_session=; Path=/; Max-Age=0; Secure; SameSite=Strict",
  origin.trimEnd('/')+"/" to "new_api_session_hint=; Path=/; Max-Age=0; Secure; SameSite=Strict"
 )

internal class QuotaHttpException(val status: Int, message: String): IllegalStateException(message)
internal data class SessionResponse(val status: Int, val body: String, val cookies: List<String> = emptyList())

// One instance per quota fetch, shared by the primary and optional daily/wallet reads.
// Retry the failed GET once, never replay mutations or refresh recursively.
internal class AccountSession(
 private val original: Site,
 private val transport: (Site,String)->String,
 private val refresh: (Site)->SessionResponse,
 private val onCredentials: (Site)->Unit
) {
 private var current=original
 private var attempted=false
 fun read(target: Site, path: String): String {
  fun credentials()=target.copy(key=current.key,cookie=current.cookie,userId=current.userId)
  try { return transport(credentials(),path) } catch(error: QuotaHttpException) {
   if(error.status!=401 || attempted || !supportsSessionRenewal(original)) throw error
   attempted=true
   val renewed=renewAccountSession(current,refresh)
   current=renewed
   // Publish rotation even when the retried GET or a later optional read fails.
   onCredentials(renewed)
   return transport(credentials(),path)
  }
 }
}

internal fun parseSessionRenewal(site: Site, response: SessionResponse): Site {
 require(response.status in 200..299)
 val root=JSONObject(response.body)
 require(root.optBoolean("success"))
 val data=root.getJSONObject("data")
 val token=data.getString("access_token")
 require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
 require(data.getString("token_type").equals("Bearer",true))
 val expires=data.getDouble("access_expires_at")
 require(expires.isFinite() && expires>System.currentTimeMillis()/1000.0)
 val user=data.optJSONObject("user")
 val id=if(user?.has("id")==true) user.getLong("id") else null
 if(id!=null) require(id>0 && (site.userId.isBlank() || site.userId.trim()==id.toString()))
 // SID is optional bootstrap metadata; refresh is authorized by the cookie.
 return site.copy(key=token,userId=id?.toString() ?: site.userId,cookie=mergeSessionCookies(site.cookie,response.cookies,site.url))
}

// CookieManager and HTTP supply Cookie headers / Set-Cookie headers respectively.
// Keep unrelated cookies; replace rotations by name and honor explicit expiration.
internal fun mergeCookieHeaders(vararg headers: String): String {
 val cookies=linkedMapOf<String,String>()
 headers.forEach { header -> header.split(';').forEach { part ->
  val pair=part.trim().split('=',limit=2)
  if(pair.size==2 && pair[0].isNotBlank()) cookies[pair[0]]=pair[1]
 } }
 return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
internal fun mergeSessionCookies(previous: String, headers: List<String>, origin: String): String {
 val cookies=linkedMapOf<String,String>()
 mergeCookieHeaders(previous).split(';').forEach { part ->
  val pair=part.trim().split('=',limit=2); if(pair.size==2) cookies[pair[0]]=pair[1]
 }
 val host=URI(origin).host
 headers.forEach { header -> HttpCookie.parse(header).forEach { cookie ->
  val domain=cookie.domain?.trimStart('.')
  // Ignore cookies for other hosts; never permit response-header injection.
  if(domain==null || host.equals(domain,true) || host.endsWith(".$domain",true)) {
   require(cookie.name.none { it.isISOControl() } && cookie.value.none { it.isISOControl() })
   if(cookie.maxAge==0L || cookie.hasExpired()) cookies.remove(cookie.name) else cookies[cookie.name]=cookie.value
  }
 } }
 return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal fun sessionRenewalHeaders(site: Site): Map<String,String> {
 require(supportsSessionRenewal(site))
 val origin=URI(site.url).let { "${it.scheme}://${it.rawAuthority}" }
 return mapOf("Cookie" to site.cookie,"Accept" to "application/json","Cache-Control" to "no-store",
  "Origin" to origin,"Referer" to "$origin/")
}
internal fun requestSessionRenewal(site: Site): SessionResponse {
 val headers=sessionRenewalHeaders(site)
 val conn=URL(site.url.trimEnd('/')+"/api/user/auth/refresh").openConnection() as HttpURLConnection
 try {
  conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=15000
  conn.instanceFollowRedirects=false
  // Bootstrap has no known SID. Like the website, omit X-Auth-Session and bearer.
  headers.forEach { (name,value) -> conn.setRequestProperty(name,value) }
  val status=conn.responseCode
  val stream=if(status in 200..299) conn.inputStream else conn.errorStream
  val body=stream?.bufferedReader()?.use { reader ->
   val out=StringBuilder(); val buffer=CharArray(4096)
   while(true) { val n=reader.read(buffer); if(n<0) break; out.append(buffer,0,n); require(out.length<=1048576) }
   out.toString()
  }.orEmpty()
  val cookies=conn.headerFields.entries.filter { it.key.equals("Set-Cookie",true) }.flatMap { it.value }
  return SessionResponse(status,body,cookies)
 } finally { conn.disconnect() }
}

// Compare the full snapshot so an in-flight request cannot overwrite an edit, deletion
// or restore. The caller writes this list to Vault before publishing it in AppState.
internal fun renewedSites(sites: List<Site>, expected: Site, renewed: Site): List<Site>? {
 if(sites.none { it==expected }) return null
 require(expected.id==renewed.id && expected.url==renewed.url)
 return sites.map { if(it==expected) it.copy(key=renewed.key,cookie=renewed.cookie,userId=renewed.userId) else it }
}
