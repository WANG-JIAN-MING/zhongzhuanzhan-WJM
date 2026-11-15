package app.quotadeck

import org.json.JSONObject

fun subscriptionTodayUsage(day: UsageDay, read: (String) -> String): Map<Long,Double> {
 val sums=mutableMapOf<Long,Double>()
 readTodayLogs(day,read) { item ->
  val quota=item.getDouble("quota")
  if(!quota.isFinite() || quota<0) throw WalletUsageFailure("订阅消费额度无效")
  run {
   val other=when(val raw=item.opt("other")) {
    is JSONObject -> raw
    is String -> if(raw.isBlank()) JSONObject() else JSONObject(raw)
    else -> JSONObject()
   }
   when(other.optString("billing_source")) {
    "wallet" -> Unit
    "subscription" -> {
     val id=other.optLong("subscription_id",0)
     if(id<=0) throw WalletUsageFailure("订阅消费明细缺少订阅 ID，无法分别统计")
     val consumed=if(other.has("subscription_consumed") && !other.isNull("subscription_consumed")) other.getDouble("subscription_consumed") else quota
     if(!consumed.isFinite() || consumed<0) throw WalletUsageFailure("订阅消费额度无效")
     val sum=(sums[id] ?: 0.0)+consumed
     if(!sum.isFinite()) throw WalletUsageFailure("订阅消费额度无效")
     sums[id]=sum
    }
    else -> if(quota!=0.0) throw WalletUsageFailure("消费明细缺少计费来源，无法分别统计订阅今日消耗")
   }
  }
 }
 return sums
}

fun subscriptionTodayAmount(subscription: SubscriptionQuota, unit: Double, day: UsageDay=usageDay()): String {
 val value=subscription.todayUsed?.takeIf { subscription.todayDate==day.date && subscription.todayError==null } ?: return "—"
 return "$ "+amount(value,unit)
}

fun subscriptionUsageError(error: Throwable): String = walletUsageError(error).removePrefix("今日钱包消耗：").let { "今日订阅消耗：$it" }
