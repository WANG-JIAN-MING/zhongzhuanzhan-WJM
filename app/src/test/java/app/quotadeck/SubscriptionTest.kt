package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class SubscriptionTest {
 private fun response(extra: String="",status: String="active")="""{"success":true,"data":{"all_subscriptions":[{"plan_title":"月卡 200刀/日",$extra"subscription":{"status":"$status","amount_total":100000000,"amount_used":199830000,"quota_advance_amount":100000000,"next_reset_time":1794412800,"quota_advance_reset_time":1794412800,"quota_reset_mode":"calendar"}}]}}"""
 @Test fun advancedDailyQuotaMatchesScreenshot() {
  val b=QuotaApi.parseSubscriptions(response()); val s=b.subscriptions.single()
  assertEquals(200.0,s.base/500000,0.0); assertEquals(400.0,s.total/500000,0.0); assertEquals(399.66,s.used/500000,0.00001); assertEquals(0.34,s.remaining/500000,0.00001)
  assertEquals(1794412800L,s.nextReset); assertEquals(200.0,s.advance/500000,0.0)
 }
 @Test fun effectiveTotalOverridesSum() { assertEquals(250000000.0,QuotaApi.parseSubscriptions(response("\"effective_amount_total\":250000000,")).subscriptions.single().total,0.0) }
 @Test fun inactiveSubscriptionExcluded() { assertTrue(QuotaApi.parseSubscriptions(response(status="expired")).subscriptions.isEmpty()) }
 @Test fun emptySubscriptionsIsValid() { assertTrue(QuotaApi.parseSubscriptions("""{"success":true,"data":{"subscriptions":[]}}""").subscriptions.isEmpty()) }
 @Test(expected=IllegalArgumentException::class) fun missingSubscriptionsRejected() { QuotaApi.parseSubscriptions("""{"success":true,"data":{}}""") }
 @Test(expected=IllegalStateException::class) fun unauthorizedRejected() { QuotaApi.parseSubscriptions("""{"success":false}""") }
}
