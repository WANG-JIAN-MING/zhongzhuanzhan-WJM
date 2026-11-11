package app.quotadeck
import org.junit.Assert.*
import org.junit.Test

class QuotaApiTest {
 private val token=Site(name="Test",url="https://example.com",key="fake-test-key")
 @Test fun tokenUsesCodeAndTotalFields() {
  val b=QuotaApi.parse(token,"""{"code":true,"data":{"total_available":250000,"total_used":750000,"unlimited_quota":false,"expires_at":1800000000}}""")
  assertEquals(250000.0,b.remaining,0.0); assertEquals(750000.0,b.used,0.0); assertEquals(1800000000L,b.expires); assertFalse(b.unlimited)
 }
 @Test fun accountUsesSuccessAndQuota() {
  val b=QuotaApi.parse(token.copy(account=true),"""{"success":true,"data":{"quota":500000,"used_quota":125000}}""")
  assertEquals(500000.0,b.remaining,0.0); assertEquals(125000.0,b.used,0.0)
 }
 @Test fun unlimitedIsNotZeroBalance() {
  val b=QuotaApi.parse(token,"""{"code":true,"data":{"total_available":0,"total_used":10,"unlimited_quota":true,"expires_at":0}}""")
  assertTrue(b.unlimited); assertEquals(0,b.expires)
 }
 @Test(expected=IllegalStateException::class) fun http200FailureRejected() { QuotaApi.parse(token,"""{"success":false,"message":"invalid key"}""") }
 @Test(expected=IllegalArgumentException::class) fun missingQuotaIsNotAssumedZero() { QuotaApi.parse(token,"""{"code":true,"data":{"total_used":1}}""") }
 @Test(expected=IllegalStateException::class) fun wrongSuccessFlagRejected() { QuotaApi.parse(token,"""{"success":true,"data":{"total_available":1,"total_used":1}}""") }
}
