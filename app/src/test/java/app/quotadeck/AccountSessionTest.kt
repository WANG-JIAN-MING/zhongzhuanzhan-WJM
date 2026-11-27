package app.quotadeck

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountSessionTest {
 private val site=Site(id="a",name="WK",url="https://wkapi.pro",key="expired",account=true,userId="7",cookie="new_api_refresh=old; theme=dark")
 private fun response(id: Long=7, token: String="new-token", headers: List<String> = listOf("new_api_refresh=rotated; Path=/api/user/auth; Secure; HttpOnly")): SessionResponse = SessionResponse(200,
  JSONObject().put("success",true).put("data",JSONObject().put("access_token",token).put("token_type","Bearer")
   .put("access_expires_at",System.currentTimeMillis()/1000+3600).put("user",JSONObject().put("id",id))
   .put("session",JSONObject().put("sid","session-a"))).toString(),headers)
 private fun unauthorized(): Nothing = throw QuotaHttpException(401,"未授权")
 private fun fails(block: ()->Unit): Throwable {
  try { block() } catch(t: Throwable) { return t }
  throw AssertionError("Expected failure")
 }
 @Test fun renewsOnceAndUsesRotationForRemainingReads() {
  var refreshes=0; val reads=mutableListOf<Site>(); val saved=mutableListOf<Site>()
  val session=AccountSession(site,{ target,_ -> reads+=target; if(target.key=="expired") unauthorized(); "ok" },{
   assertEquals(site.cookie,it.cookie); refreshes++; response()
  },saved::add)
  assertEquals("ok",session.read(site,"/api/user/self"))
  assertEquals("ok",session.read(site,"/api/log/self"))
  assertEquals(1,refreshes); assertEquals(3,reads.size); assertEquals(1,saved.size)
  assertEquals("new-token",saved.single().key)
  assertEquals("new_api_refresh=rotated; theme=dark",saved.single().cookie)
  assertEquals(saved.single().cookie,reads.last().cookie)
 }
 @Test fun successfulReadNeverRefreshes() {
  val session=AccountSession(site,{ _,_->"ok" },{ fail("unexpected refresh"); response() },{ fail("unexpected rotation") })
  assertEquals("ok",session.read(site,"/api/user/self"))
 }
 @Test fun refreshOnlyAfterHttp401() {
  for(error in listOf(QuotaHttpException(403,"forbidden"),QuotaHttpException(429,"rate"),IllegalStateException("未授权"))) {
   var calls=0
   val session=AccountSession(site,{ _,_->throw error },{ calls++; response() },{})
   assertSame(error,fails { session.read(site,"/api/user/self") }); assertEquals(0,calls)
  }
 }
 @Test fun failedRetryCannotRefreshAgainAndStillPublishesRotation() {
  var refreshes=0; var published: Site?=null; var reads=0
  val session=AccountSession(site,{ _,_->reads++; unauthorized() },{ refreshes++; response() },{ published=it })
  fails { session.read(site,"/api/user/self") }
  fails { session.read(site,"/api/log/self") }
  assertEquals(1,refreshes); assertEquals(3,reads); assertEquals("new-token",published!!.key)
 }
 @Test fun failedRefreshCannotLoopOrExposeServerCredentials() {
  var refreshes=0
  val session=AccountSession(site,{ _,_->unauthorized() },{ refreshes++; SessionResponse(401,"secret-token-cookie") },{ fail("must not publish") })
  val error=fails { session.read(site,"/api/user/self") }
  assertEquals("登录会话续期失败：刷新凭据已失效，请重新网页登录绑定",error.message)
  assertNull(error.cause)
  fails { session.read(site,"/api/log/self") }; assertEquals(1,refreshes)
 }
 @Test fun manualKeysLegacySitesAndUnsafeOriginsAreUnchanged() {
  val excluded=listOf(site.copy(cookie=""),site.copy(account=false),site.copy(url="https://other.example"),
   site.copy(url="http://wkapi.pro"),site.copy(url="https://wkapi.pro:444"),site.copy(url="https://wkapi.pro.evil.example"),
   site.copy(url="https://user@wkapi.pro"),site.copy(url="https://wkapi.pro/prefix"))
  excluded.forEach { target ->
   assertFalse(supportsSessionRenewal(target)); var calls=0
   val session=AccountSession(target,{ actual,_ -> assertEquals(target,actual); unauthorized() },{ calls++; response() },{})
   fails { session.read(target,"/api/user/self") }; assertEquals(0,calls)
  }
  assertTrue(supportsSessionRenewal(site.copy(account=false,subscription=true)))
 }
 @Test fun refreshMaySupplyMissingUserIdButCannotChangeAccount() {
  assertEquals("7",parseSessionRenewal(site.copy(userId=""),response()).userId)
  fails { parseSessionRenewal(site,response(id=8)) }
 }
 @Test fun optionalBootstrapMetadataIsNotRequired() {
  val reply=response(); val root=JSONObject(reply.body)
  root.getJSONObject("data").remove("session"); root.getJSONObject("data").remove("user")
  val renewed=parseSessionRenewal(site,reply.copy(body=root.toString()))
  assertEquals("new-token",renewed.key); assertEquals(site.userId,renewed.userId)
 }
 @Test fun invalidBundleDoesNotPublishRotatedCookies() {
  val invalid=listOf(SessionResponse(200,"{}"),SessionResponse(302,""),response(token="bad\r\ntoken"),
   response().let { it.copy(body=it.body.replace("Bearer","Basic")) },
   response().let { val root=JSONObject(it.body); root.getJSONObject("data").put("access_expires_at",1); it.copy(body=root.toString()) })
  invalid.forEach { reply ->
   var published=false
   val session=AccountSession(site,{ _,_->unauthorized() },{reply},{published=true})
   fails { session.read(site,"/api/user/self") }; assertFalse(published)
  }
 }
 @Test fun mergesCookieRotationDeletionAndExpiration() {
  val merged=mergeSessionCookies("new_api_refresh=old; theme=dark; delete=x; expired=x",listOf(
   "new_api_refresh=new; Path=/api/user/auth; HttpOnly; Secure", "delete=; Max-Age=0", "expired=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
   "foreign=bad; Domain=evil.example", "extra=value; Domain=.wkapi.pro"),site.url)
  assertEquals("new_api_refresh=new; theme=dark; extra=value",merged)
  assertEquals("theme=dark; new_api_refresh=path-specific",mergeCookieHeaders("theme=dark; new_api_refresh=root","new_api_refresh=path-specific"))
 }
 @Test fun renewalPreservesSubscriptionModeOnPrimaryAndWalletReads() {
  val subscription=site.copy(subscription=true,account=false)
  val targets=mutableListOf<Site>()
  val session=AccountSession(subscription,{ target,_ -> targets+=target; if(target.key=="expired") unauthorized(); "ok" },{ response() },{})
  session.read(subscription,"/api/subscription/self")
  session.read(subscription.copy(subscription=false,account=true),"/api/user/self")
  assertTrue(targets[1].subscription); assertFalse(targets.last().subscription); assertTrue(targets.last().account)
  assertEquals("new-token",targets.last().key)
 }
 @Test fun persistedSnapshotSurvivesColdRestartAndDoesNotCrossAccounts() {
  val other=site.copy(id="b",key="other",cookie="new_api_refresh=other")
  val renewed=parseSessionRenewal(site,response())
  val stored=renewedSites(listOf(site,other),site,renewed)!!
  assertEquals(other,stored[1]); assertEquals(renewed,stored[0])
  // A fresh request receives only the persisted snapshot, with no shared in-memory bearer.
  val restarted=AccountSession(stored[0],{ target,_ -> assertEquals("new-token",target.key); assertEquals(renewed.cookie,target.cookie); "ok" },{ fail("unexpected refresh"); response() },{})
  assertEquals("ok",restarted.read(stored[0],"/api/user/self"))
 }
 @Test fun staleRefreshCannotResurrectDeletedOrEditedSite() {
  val renewed=parseSessionRenewal(site,response())
  assertNull(renewedSites(emptyList(),site,renewed))
  assertNull(renewedSites(listOf(site.copy(key="edited")),site,renewed))
  assertNull(renewedSites(listOf(site.copy(name="renamed")),site,renewed))
 }
}
