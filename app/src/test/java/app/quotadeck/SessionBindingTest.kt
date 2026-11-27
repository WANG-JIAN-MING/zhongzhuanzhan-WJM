package app.quotadeck

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SessionBindingTest {
 private val site=Site(id="wk",name="WK",url="https://wkapi.pro",key="still-valid",account=true,userId="7",cookie="new_api_refresh=old; theme=dark")
 private fun success(user: Int=7)=SessionResponse(200,"""{"success":true,"data":{"access_token":"renewed","token_type":"Bearer","access_expires_at":4102444800,"user":{"id":$user}}}""",listOf("new_api_refresh=rotated; Path=/api/user/auth; HttpOnly; SameSite=Strict"))
 private fun failure(block: ()->Unit): String {
  try { block(); fail("expected renewal failure") } catch(e: SessionRenewalException) { return e.message.orEmpty() }
  return ""
 }

 @Test fun bindingVerifiesRefreshEvenWithAnUnexpiredBearer() {
  var calls=0
  val renewed=prepareSessionBinding(site) { calls++; assertEquals("still-valid",it.key); success() }
  assertEquals(1,calls)
  assertEquals("renewed",renewed.key)
  assertEquals("new_api_refresh=rotated; theme=dark",renewed.cookie)
  assertEquals("7",renewed.userId)
 }
 @Test fun bindingWithoutRefreshCookieIsRejectedBeforeAnyRequest() {
  for(cookie in listOf("","theme=dark","new_api_refresh=")) {
   val message=failure { prepareSessionBinding(site.copy(cookie=cookie)) { error("must not request") } }
   assertTrue(message.contains("未获取到刷新 Cookie"))
  }
 }
 @Test fun aRejectedRefreshCannotBindWithTheOldBearer() {
  var calls=0
  val message=failure { prepareSessionBinding(site) { calls++; SessionResponse(401,"""{"success":false,"code":"AUTH_UNAUTHORIZED","message":"secret"}""") } }
  assertEquals(1,calls); assertTrue(message.contains("刷新凭据已失效")); assertFalse(message.contains("secret"))
  assertEquals("still-valid",site.key)
 }
 @Test fun bindingRejectsARefreshForAnotherUser() {
  assertTrue(failure { prepareSessionBinding(site) { success(8) } }.contains("数据不兼容"))
 }
 @Test fun bindingRejectsAnExplicitlyDeletedRefreshCookie() {
  assertTrue(failure { prepareSessionBinding(site) { success().copy(cookies=listOf("new_api_refresh=; Path=/api/user/auth; Max-Age=0")) } }.contains("数据不兼容"))
 }
 @Test fun networkFailuresAreSanitizedWithoutDroppingCredentials() {
  assertTrue(failure { prepareSessionBinding(site) { throw SocketTimeoutException("cookie=private") } }.contains("连接超时"))
  val message=failure { prepareSessionBinding(site) { throw IOException("Bearer private-token") } }
  assertTrue(message.contains("网络连接异常")); assertFalse(message.contains("private"))
  assertEquals("new_api_refresh=old; theme=dark",site.cookie)
 }
 @Test fun serverFailureCategoriesDoNotExposeRawBodies() {
  val cases=listOf(
   SessionResponse(401,"""{"code":"AUTH_SESSION_REVOKED","message":"private"}""") to "撤销",
   SessionResponse(409,"""{"code":"AUTH_SESSION_MISMATCH"}""") to "不匹配",
   SessionResponse(409,"""{"code":"AUTH_REFRESH_RACE"}""") to "正在轮换",
   SessionResponse(403,"""{"code":"AUTH_ORIGIN_FORBIDDEN"}""") to "来源",
   SessionResponse(429,"private") to "频繁",
   SessionResponse(503,"private") to "服务暂时异常",
   SessionResponse(302,"private") to "重定向",
   SessionResponse(200,"private") to "数据不兼容"
  )
  cases.forEach { (response,expected) ->
   val message=failure { prepareSessionBinding(site) { response } }
   assertTrue(message,message.contains(expected)); assertFalse(message.contains("private"))
  }
 }
 @Test fun legacyAndOtherSiteBindingsDoNotStartRefresh() {
  listOf(site.copy(url="https://other.example"),site.copy(account=false)).forEach { other ->
   assertSame(other,prepareSessionBinding(other) { error("must not refresh") })
  }
 }
 @Test fun subscriptionBindingAlsoRequiresAWorkingRefresh() {
  assertEquals("renewed",prepareSessionBinding(site.copy(account=false,subscription=true)) { success() }.key)
 }
 @Test fun refreshHeadersAreSameOriginAndDoNotCarryTheShortLivedBearer() {
  val headers=sessionRenewalHeaders(site)
  assertEquals("https://wkapi.pro",headers["Origin"])
  assertEquals("https://wkapi.pro/",headers["Referer"])
  assertEquals(site.cookie,headers["Cookie"])
  assertFalse(headers.containsKey("Authorization")); assertFalse(headers.containsKey("X-Auth-Session"))
 }
 @Test fun onlyVerifiedWkLoginCookiesAreResetAndOtherSitesAreUntouched() {
  val resets=webLoginSessionResetCookies(site.url)
  assertEquals(3,resets.size)
  assertTrue(resets.all { (url,value) -> isLoginOrigin(url,site.url) && value.contains("Max-Age=0") })
  assertTrue(resets.first().second.startsWith("new_api_refresh="))
  assertTrue(resets.first().second.contains("Path=/api/user/auth"))
  assertTrue(resets.any { it.second.startsWith("new_api_has_session=") })
  assertTrue(resets.any { it.second.startsWith("new_api_session_hint=") })
  assertTrue(webLoginSessionResetCookies("https://other.example").isEmpty())
  assertTrue(webLoginSessionResetCookies("https://wkapi.pro.evil.example").isEmpty())
 }
}
