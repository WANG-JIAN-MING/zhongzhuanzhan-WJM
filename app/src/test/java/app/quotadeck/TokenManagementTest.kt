package app.quotadeck

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TokenManagementTest {
 private fun site(account: Boolean=true,key: String="management",cookie: String="")=Site(name="Test",url="https://example.com",key=key,account=account,userId="12",cookie=cookie)
 private fun token(id: Int=1)=JSONObject("""{"id":$id,"name":"Example","status":1,"remain_quota":500000,"used_quota":10,"unlimited_quota":false,"expired_time":-1,"key":"SHOULD_NOT_BE_RETAINED"}""")
 private fun page(total: Int=1,p: Int=1,size: Int=20,items: String=token().toString())="""{"success":true,"data":{"total":$total,"page":$p,"page_size":$size,"items":[$items]}}"""
 private inline fun fails(block:()->Unit) { try { block(); fail("Expected rejection") } catch(_: IllegalArgumentException) {} }
 @Test fun modelKeyIsNeverManagementIdentity() { assertFalse(TokenApi.eligible(site(false,"sk-model"))); assertFalse(TokenApi.eligible(site(true,"sk-model"))); fails { TokenApi.headers(site(true,"sk-model")) } }
 @Test fun queryConfigNotPromotedWithOpaqueKey() { assertFalse(TokenApi.eligible(site(false,"opaque"))); fails { TokenApi.headers(site(false,"opaque")) } }
 @Test fun cookieAndBearerAndUserAreReused() { val h=TokenApi.headers(site(key="Bearer management",cookie="session=example")); assertEquals("Bearer management",h["Authorization"]); assertEquals("session=example",h["Cookie"]); assertEquals("12",h["New-Api-User"]) }
 @Test fun cookieDoesNotForwardModelKey() { val h=TokenApi.headers(site(key="sk-model",cookie="session=example")); assertFalse(h.containsKey("Authorization")); assertTrue(h.containsKey("Cookie")) }
 @Test fun subscriptionIdentitySupported() { assertTrue(TokenApi.eligible(site(false).copy(subscription=true))) }
 @Test fun noHeaderInjection() { fails { TokenApi.headers(site(cookie="session=example\r\nInjected: yes")) } }
 @Test fun invalidUserRejected() { fails { TokenApi.headers(site().copy(userId="0")) } }
 @Test fun searchEncodesNameAndKeepsPagination() { assertEquals("/api/token/search?keyword=a+%26%E4%B8%AD&token=&p=3&size=20",TokenApi.listPath(3,search="a &中")); assertEquals("/api/token/?p=2&size=20",TokenApi.listPath(2)) }
 @Test fun pageMustBePositiveAndSizeBounded() { fails { TokenApi.listPath(0) }; fails { TokenApi.listPath(1,101) } }
 @Test fun cardParsesCurrentGroupAndModelLimitsWithoutKeepingSecrets() {
  val t=TokenApi.parseToken(token().put("group","vip").put("model_limits","model-a,model-b").put("model_limits_enabled",true))
  assertEquals("vip",t.group); assertEquals(listOf("model-a","model-b"),t.models); assertEquals(true,t.modelLimitsEnabled)
  assertFalse(t.toString().contains("SHOULD_NOT_BE_RETAINED"))
 }
 @Test fun missingCardOptionsAreUnknownRatherThanAssumedUnrestricted() {
  val t=TokenApi.parseToken(token())
  assertNull(t.group); assertNull(t.models); assertNull(t.modelLimitsEnabled)
 }
 @Test fun explicitCardDefaultsAreDifferentFromMissingOptions() {
  val t=TokenApi.parseToken(token().put("group", "").put("model_limits", "").put("model_limits_enabled",false))
  assertEquals("",t.group); assertTrue(t.models!!.isEmpty()); assertEquals(false,t.modelLimitsEnabled)
 }
 @Test fun parseCurrentNecoEnvelope() { val p=TokenApi.parsePage(page(),1); assertEquals(1,p.items.size); assertEquals(500000L,p.items[0].remaining); assertEquals(1L,p.total); assertFalse(p.hasNext) }
 @Test fun nextPageBoundaryIsAccurate() { assertFalse(TokenApi.parsePage(page(20),1).hasNext); assertTrue(TokenApi.parsePage(page(21),1).hasNext); assertFalse(TokenApi.parsePage(page(21,2),2).hasNext) }
 @Test fun deleteLastItemCanMoveLastPage() { val p=TokenApi.parsePage(page(20,2,items=""),2); assertEquals(1,p.lastPage) }
 @Test fun rejectsWrongServerPageAndSize() { fails { TokenApi.parsePage(page(p=2),1) }; fails { TokenApi.parsePage(page(size=10),1) } }
 @Test fun rejectsDuplicateIdsAndBrokenEmptyPage() { fails { TokenApi.parsePage(page(2,items=token().toString()+","+token().toString()),1) }; fails { TokenApi.parsePage(page(4,items=""),1) } }
 @Test fun quotaConvertsExactlyWithoutFloatingPointRounding() { assertEquals(500000L,TokenApi.quota("1",500000.0)); assertEquals(1L,TokenApi.quota("0.000002",500000.0)); assertEquals(0L,TokenApi.quota("0",500000.0)); fails { TokenApi.quota("0.000001",500000.0) }; fails { TokenApi.quota("-1",500000.0) }; fails { TokenApi.quota("999999999999999999999",500000.0) } }
 @Test fun neverExpiresUsesMinusOneSeconds() { assertEquals(-1L,TokenApi.expiry("",true,100)) }
 @Test fun dateParsingUsesSecondsAndFutureValidation() { val t=TokenApi.expiry("2035-01-02 12:30",false,1); assertTrue(t in 2000000000L..2200000000L); fails { TokenApi.expiry("2024-02-30 12:00",false,1) }; fails { TokenApi.expiry("2020-01-01 12:00",false,2000000000) } }
 @Test fun editPreservesAdvancedConstraintsButStripsSecret() { val j=token().put("allow_ips","1.2.3.4").put("model_limits_enabled",true).put("model_limits","model").put("auto_groups",org.json.JSONArray("[\"group\"]")).put("allow_user_quota_query",true); val b=JSONObject(TokenApi.body(TokenDraft("Edited",10,false,-1),j)); assertFalse(b.has("key")); assertFalse(b.has("used_quota")); assertEquals("1.2.3.4",b.getString("allow_ips")); assertEquals("model",b.getString("model_limits")); assertEquals("group",b.getJSONArray("auto_groups").getString(0)); assertTrue(b.getBoolean("allow_user_quota_query")); assertEquals(1,b.getInt("id")); assertEquals(10L,b.getLong("remain_quota")) }
 @Test fun createBodyUsesNecoDefaults() { val b=JSONObject(TokenApi.body(TokenDraft("New",0,true,-1))); assertFalse(b.has("id")); assertEquals("none",b.getString("auto_route_mode")); assertEquals(-1L,b.getLong("expired_time")); assertTrue(b.getBoolean("unlimited_quota")) }
 @Test fun nameLimitUsesUtf8ByteCount() { fails { TokenApi.body(TokenDraft("中".repeat(17),0,true,-1)) }; TokenApi.body(TokenDraft("中".repeat(16),0,true,-1)); fails { TokenApi.body(TokenDraft("  ",0,true,-1)) } }
 @Test fun keySupportsStringOrObjectAndPrefixesExactlyOnce() { assertEquals("sk-abc",TokenApi.key("""{"success":true,"data":{"key":"abc"}}""")); assertEquals("sk-abc",TokenApi.key("""{"success":true,"data":"sk-abc"}""")); fails { TokenApi.key("""{"success":true,"data":"ab***c"}""") } }
 @Test fun serverErrorsNeverExposeResponseSecrets() { try { TokenApi.checked("""{"success":false,"message":"SECRET_VALUE"}"""); fail() } catch(e:TokenApiError) { assertFalse(e.message!!.contains("SECRET_VALUE")) } }
 @Test fun verificationErrorIsRecognized() { try { TokenApi.checked("""{"success":false,"code":"TOKEN_KEY_EMAIL_VERIFICATION_REQUIRED"}"""); fail() } catch(e:TokenApiError) { assertTrue(e.verification) } }
 @Test fun unknownExceptionsAreSanitized() { assertFalse(TokenApi.safeError(Exception("SECRET_VALUE")).contains("SECRET_VALUE")) }
}
