package app.quotadeck

import org.junit.Assert.*
import org.junit.Test

class TokenKeyCacheTest {
 private val site=Site(id="one",name="Test",url="https://example.invalid",key="management",account=true,userId="1",cookie="session=one")
 @Test fun repeatLookupKeepsKeyOnlyForMatchingTokenAndIdentity() {
  val cache=TokenKeyCache(site)
  assertNull(cache.get(site,7))
  cache.put(site,7,"sk-test-only")
  repeat(3) { assertEquals("sk-test-only",cache.get(site,7)) }
  assertNull(cache.get(site,8))
  listOf(site.copy(id="two"),site.copy(url="https://other.invalid"),site.copy(key="other"),site.copy(userId="2"),site.copy(cookie="session=two"),site.copy(account=false,subscription=true)).forEach {
   assertNull(cache.get(it,7))
   cache.put(it,7,"sk-wrong-identity")
   assertEquals("sk-test-only",cache.get(site,7))
  }
 }
 @Test fun deleteInvalidatesOnlyTargetToken() {
  val cache=TokenKeyCache(site)
  cache.put(site,7,"sk-seven"); cache.put(site,8,"sk-eight")
  cache.remove(7)
  assertNull(cache.get(site,7)); assertEquals("sk-eight",cache.get(site,8))
 }
 @Test fun pageExitOrIdentitySwitchClosesCacheAndRejectsLateResults() {
  val cache=TokenKeyCache(site)
  cache.put(site,7,"sk-seven"); cache.close()
  assertNull(cache.get(site,7))
  cache.put(site,7,"sk-late-result")
  assertNull(cache.get(site,7))
  assertNull(TokenKeyCache(site).get(site,7))
 }
}
