package app.quotadeck

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WalletUsageTest {
 private fun log(id: Int, source: String, quota: Int=100)=JSONObject().put("id",id).put("type",2).put("created_at",150).put("quota",quota).put("other",JSONObject().put("billing_source",source).toString())
 private fun page(total: Int,vararg items: JSONObject)=JSONObject().put("success",true).put("data",JSONObject().put("total",total).put("items",JSONArray(items.toList()))).toString()
 private val day=UsageDay("test",100,200)
 @Test fun excludesSubscriptionsFromMixedPages() {
  var calls=0
  val result=walletTodayUsage(day) { path ->
   calls++
   assertTrue(path.startsWith("/api/log/self?type=2&"))
   assertTrue(path.contains("start_timestamp=100&end_timestamp=200"))
   if(calls==1) { assertTrue(path.contains("&p=1&")); page(3,log(1,"wallet",25),log(2,"subscription",900)) }
   else { assertTrue(path.contains("&p=2&")); page(3,log(3,"wallet",75)) }
  }
  assertEquals(100.0,result,0.0)
  assertEquals(2,calls)
 }
 @Test fun subscriptionOnlyIsZeroWalletUsage() { assertEquals(0.0,walletTodayUsage(day) { page(1,log(1,"subscription")) },0.0) }
 @Test fun explicitWalletDeductionTakesPriority() {
  val item=log(1,"subscription").put("other",JSONObject().put("billing_source","subscription").put("wallet_quota_deducted",20))
  assertEquals(20.0,walletLogQuota(item),0.0)
 }
 @Test fun emptyDayIsZero() { assertEquals(0.0,walletTodayUsage(day) { page(0) },0.0) }
 @Test(expected=IllegalArgumentException::class) fun unknownSourceDoesNotBecomeWallet() { walletTodayUsage(day) { page(1,log(1,"")) } }
 @Test(expected=IllegalArgumentException::class) fun incompletePagesDoNotReturnPartialTotal() { var n=0; walletTodayUsage(day) { if(n++==0) page(2,log(1,"wallet")) else page(2) } }
 @Test(expected=IllegalArgumentException::class) fun repeatedPagesRejected() { walletTodayUsage(day) { page(2,log(1,"wallet")) } }
 @Test(expected=IllegalArgumentException::class) fun changedTotalRejected() { var n=0; walletTodayUsage(day) { if(n++==0) page(2,log(1,"wallet")) else page(3,log(2,"wallet")) } }
 @Test(expected=IllegalArgumentException::class) fun outOfDayRejected() { walletTodayUsage(day) { page(1,log(1,"wallet").put("created_at",99)) } }
}
