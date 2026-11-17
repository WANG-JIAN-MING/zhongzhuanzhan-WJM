package app.quotadeck

import org.junit.Assert.*
import org.junit.Test

class SubscriptionWalletTest {
 private val day=UsageDay("2026-11-15",100,200)
 private val site=Site(name="Claude服务",url="https://example.invalid",key="test-key",userId="7",cookie="session=test",subscription=true,unit=100.0)
 private val subscription="""{"success":true,"data":{"subscriptions":[{"plan_title":"Pro月度套餐","subscription":{"id":10,"status":"active","amount_total":10000,"amount_used":8200}}]}}"""
 private val wallet="""{"success":true,"data":{"id":7,"quota":20000,"used_quota":900}}"""
 private val logs="""{"success":true,"data":{"total":2,"items":[{"id":1,"type":2,"created_at":150,"quota":342,"other":{"billing_source":"wallet"}},{"id":2,"type":2,"created_at":150,"quota":999,"other":{"billing_source":"subscription","subscription_id":10,"subscription_consumed":215}}]}}"""
 private fun fetch(user: String=wallet,walletLog: String=logs,subLog: String=logs): Balance = QuotaApi.fetch(site,day) { target,path ->
  when {
   path=="/api/subscription/self" -> subscription
   path=="/api/user/self" -> user
   path.startsWith("/api/log/self?") -> if(target.subscription) subLog else walletLog
   else -> error("Unexpected request $path")
  }
 }
 @Test fun realEndpointsKeepWalletAndSubscriptionAmountsSeparate() {
  val calls=mutableListOf<Pair<Site,String>>()
  val balance=QuotaApi.fetch(site,day) { target,path ->
   calls+=target to path
   when(path) {
    "/api/subscription/self" -> subscription
    "/api/user/self" -> wallet
    else -> { assertTrue(path.startsWith("/api/log/self?type=2&")); logs }
   }
  }
  assertEquals(1800.0,balance.remaining,0.0)
  assertEquals(20000.0,balance.walletRemaining!!,0.0)
  assertEquals(342.0,balance.walletTodayUsed!!,0.0)
  assertEquals(215.0,balance.subscriptions.single().todayUsed!!,0.0)
  assertNull(balance.todayUsed)
  assertEquals(listOf(false,true,true,false),calls.map { it.first.account })
  assertEquals(listOf(true,false,false,true),calls.map { it.first.subscription })
  calls.forEach { (target,_) ->
   assertEquals(site.key,target.key); assertEquals(site.userId,target.userId)
   assertEquals(site.cookie,target.cookie); assertEquals(site.url,target.url)
  }
  assertEquals("$200.00",subscriptionWalletAmount(QueryState(balance=balance),100.0))
  assertEquals("$3.42",subscriptionWalletTodayAmount(QueryState(balance=balance),100.0,day))
 }
 @Test fun walletEndpointFailureDoesNotLoseSubscriptionOrWalletLogs() {
  val balance=QuotaApi.fetch(site,day) { target,path ->
   when(path) { "/api/subscription/self" -> subscription; "/api/user/self" -> throw IllegalStateException("未授权"); else -> logs }
  }
  assertEquals(1800.0,balance.remaining,0.0)
  assertNull(balance.walletRemaining); assertTrue(balance.walletError!!.contains("不可用"))
  assertEquals(342.0,balance.walletTodayUsed!!,0.0)
  assertEquals(215.0,balance.subscriptions.single().todayUsed!!,0.0)
  assertEquals("—",subscriptionWalletAmount(QueryState(balance=balance),100.0))
 }
 @Test fun wrongAccountResponseIsIsolatedAndNeverAcceptedAsWallet() {
  val balance=fetch(user=wallet.replace("\"id\":7","\"id\":8"))
  assertNull(balance.walletRemaining); assertNotNull(balance.walletError)
  assertEquals(1800.0,balance.remaining,0.0)
 }
 @Test fun walletLogFailureDoesNotLoseSubscriptionDailyOrWalletBalance() {
  val balance=fetch(walletLog="""{"success":false}""")
  assertEquals(20000.0,balance.walletRemaining!!,0.0)
  assertNull(balance.walletTodayUsed); assertNotNull(balance.walletTodayError)
  assertEquals(215.0,balance.subscriptions.single().todayUsed!!,0.0)
 }
 @Test fun subscriptionLogFailureDoesNotLoseWalletDaily() {
  val balance=fetch(subLog="""{"success":false}""")
  assertEquals(342.0,balance.walletTodayUsed!!,0.0)
  assertNull(balance.subscriptions.single().todayUsed)
  assertNotNull(balance.subscriptions.single().todayError)
 }
 @Test fun emptySubscriptionStillQueriesWalletAndWalletLogs() {
  val balance=QuotaApi.fetch(site,day) { _,path ->
   when(path) { "/api/subscription/self" -> """{"success":true,"data":{"subscriptions":[]}}"""; "/api/user/self" -> wallet; else -> logs }
  }
  assertTrue(balance.subscriptions.isEmpty())
  assertEquals(20000.0,balance.walletRemaining!!,0.0)
  assertEquals(342.0,balance.walletTodayUsed!!,0.0)
 }
 @Test fun failedRefreshAndOldDateDoNotShowStaleAmounts() {
  val balance=fetch()
  val failed=QueryState(balance=balance,error="failed")
  assertEquals("—",subscriptionWalletAmount(failed,100.0))
  assertEquals("—",subscriptionWalletTodayAmount(failed,100.0,day))
  assertEquals("—",subscriptionWalletTodayAmount(QueryState(balance=balance),100.0,day.copy(date="next")))
  assertEquals("—",subscriptionWalletAmount(QueryState(balance=balance.copy(walletRemaining=null)),100.0))
 }
 @Test fun sameOriginDifferentAccountsUseTheirOwnCredentials() {
  listOf("7","8").forEach { id ->
   val accountSite=site.copy(userId=id,key="key-$id",cookie="session=$id")
   val balance=QuotaApi.fetch(accountSite,day) { target,path ->
    assertEquals(id,target.userId); assertEquals("key-$id",target.key); assertEquals("session=$id",target.cookie)
    when(path) { "/api/subscription/self" -> subscription; "/api/user/self" -> wallet.replace("\"id\":7","\"id\":$id").replace("20000",if(id=="7") "20000" else "30000"); else -> logs }
   }
   assertEquals(if(id=="7") 20000.0 else 30000.0,balance.walletRemaining!!,0.0)
  }
 }
 @Test fun accountAndTokenRequestBehaviorRemainsUnchanged() {
  val account=QuotaApi.fetch(site.copy(account=true,subscription=false),day) { _,path -> if(path=="/api/user/self") wallet else logs }
  assertEquals(20000.0,account.remaining,0.0); assertEquals(342.0,account.todayUsed!!,0.0)
  var calls=0
  val token=QuotaApi.fetch(site.copy(account=false,subscription=false),day) { _,path ->
   calls++; assertEquals("/api/usage/token/",path)
   """{"code":true,"data":{"total_available":100,"total_used":20}}"""
  }
  assertEquals(1,calls); assertEquals(100.0,token.remaining,0.0)
 }
}
