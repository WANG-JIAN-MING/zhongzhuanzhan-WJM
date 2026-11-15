package app.quotadeck

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SubscriptionUsageTest {
 private val day=UsageDay("2026-11-15",100,200)
 private fun log(id: Int,source: String,sub: Long,quota: Int,consumed: Int?=null): JSONObject {
  val other=JSONObject().put("billing_source",source).put("subscription_id",sub)
  consumed?.let { other.put("subscription_consumed",it) }
  return JSONObject().put("id",id).put("type",2).put("created_at",150).put("quota",quota).put("other",other.toString())
 }
 private fun page(total: Int,vararg items: JSONObject)=JSONObject().put("success",true).put("data",JSONObject().put("total",total).put("items",JSONArray(items.toList()))).toString()
 @Test fun keepsSubscriptionsSeparateAcrossPagesAndExcludesWallet() {
  var calls=0
  val sums=subscriptionTodayUsage(day) {
   if(calls++==0) page(4,log(1,"subscription",10,100,30),log(2,"wallet",0,999))
   else page(4,log(3,"subscription",20,70),log(4,"subscription",10,80,20))
  }
  assertEquals(mapOf(10L to 50.0,20L to 70.0),sums)
 }
 @Test fun explicitZeroDoesNotFallBackToQuota() { assertEquals(0.0,subscriptionTodayUsage(day) { page(1,log(1,"subscription",10,100,0)) }[10L]!!,0.0) }
 @Test fun zeroQuotaStillPreservesExplicitSubscriptionConsumption() { assertEquals(20.0,subscriptionTodayUsage(day) { page(1,log(1,"subscription",10,0,20)) }[10L]!!,0.0) }
 @Test fun emptyDayHasNoConsumption() { assertTrue(subscriptionTodayUsage(day) { page(0) }.isEmpty()) }
 @Test(expected=WalletUsageFailure::class) fun missingSubscriptionIdDoesNotMergeByTitle() { subscriptionTodayUsage(day) { page(1,log(1,"subscription",0,100)) } }
 @Test(expected=WalletUsageFailure::class) fun unknownBillingSourceIsNotZeroSubscriptionUsage() { subscriptionTodayUsage(day) { page(1,log(1,"",0,100)) } }
 @Test(expected=WalletUsageFailure::class) fun negativeConsumptionRejected() { subscriptionTodayUsage(day) { page(1,log(1,"subscription",10,100,-1)) } }
}
