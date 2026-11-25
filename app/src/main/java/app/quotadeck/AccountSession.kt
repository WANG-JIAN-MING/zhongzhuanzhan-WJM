package app.quotadeck

import org.json.JSONObject
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Only this origin's modern cookie-backed protocol has been verified. Manual bearer-only
// accounts, API keys and legacy New API sessions keep their existing request behavior.
internal fun supportsSessionRenewal(site: Site): Boolean =
 (site.account || site.subscription) && site.cookie.isNotBlank() && runCatching {
  val uri=URI(site.url)
  uri.scheme=="https" && uri.host.equals("wkapi.pro",true) && uri.userInfo==null &&
   (uri.port==-1 || uri.port==443) && uri.path.orEmpty() in listOf("","/") && uri.query==null && uri.fragment==null
 }.getOrDefault(false)

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
   val renewed=try { parseSessionRenewal(current,refresh(current)) } catch(_: Exception) {
    // Do not surface a server body, bearer, cookie or transport exception containing secrets.
    throw IllegalStateException("登录会话续期失败，请重新网页登录绑定")
   }
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

internal fun requestSessionRenewal(site: Site): SessionResponse {
 require(supportsSessionRenewal(site))
 val conn=URL(site.url.trimEnd('/')+"/api/user/auth/refresh").openConnection() as HttpURLConnection
 try {
  conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=15000
  conn.instanceFollowRedirects=false
  // Bootstrap has no known SID. Like the website, omit X-Auth-Session and bearer.
  conn.setRequestProperty("Cookie",site.cookie)
  conn.setRequestProperty("Accept","application/json")
  conn.setRequestProperty("Cache-Control","no-store")
  val status=conn.responseCode
  if(status !in 200..299) return SessionResponse(status,"")
  val body=conn.inputStream.bufferedReader().use { reader ->
   val out=StringBuilder(); val buffer=CharArray(4096)
   while(true) { val n=reader.read(buffer); if(n<0) break; out.append(buffer,0,n); require(out.length<=1048576) }
   out.toString()
  }
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
