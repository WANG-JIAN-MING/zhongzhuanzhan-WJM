package app.quotadeck

import org.json.JSONObject

// Never infer wallet spending from the combined statistics endpoint.
fun walletTodayUsage(day: UsageDay, read: (String) -> String): Double {
 var sum=0.0
 readTodayLogs(day,read) { item ->
  sum+=walletLogQuota(item)
  require(sum.isFinite()) { "钱包消费统计数值无效" }
 }
 return sum
}

fun readTodayLogs(day: UsageDay, read: (String) -> String, consume: (JSONObject) -> Unit) {
 var page=1
 var count=0
 var total: Int?=null
 val seen=HashSet<Long>()
 while(page<=100) {
  val root=JSONObject(read("/api/log/self?type=2&start_timestamp=${day.start}&end_timestamp=${day.end}&p=$page&page_size=100"))
  if(!root.optBoolean("success")) {
   val message=root.optString("message")
   val detail=when {
    message.contains("登录") || message.contains("unauthorized",true) || message.contains("access token",true) -> "日志接口拒绝登录状态，请重新网页登录绑定"
    message.contains("权限") || message.contains("permission",true) -> "日志接口拒绝访问，请检查消费明细权限"
    else -> "日志接口返回 success=false，未提供可用消费明细"
   }
   throw WalletUsageFailure(detail)
  }
  val data=root.getJSONObject("data")
  val expected=data.getInt("total")
  require(expected>=0 && (total==null || total==expected)) { "消费明细发生变化，请重新刷新" }
  total=expected
  val items=data.getJSONArray("items")
  require(items.length()>0 || count==expected) { "消费明细不完整" }
  for(i in 0 until items.length()) {
   val item=items.getJSONObject(i)
   require(item.getInt("type")==2) { "消费明细类型不匹配" }
   val time=item.getLong("created_at")
   require(time in day.start..day.end) { "消费明细时间不匹配" }
   require(seen.add(item.getLong("id"))) { "消费明细重复，请重新刷新" }
   consume(item)
  }
  count+=items.length()
  require(count<=expected) { "消费明细数量不匹配" }
  if(count==expected) return
  page++
 }
 throw IllegalArgumentException("消费明细过多，无法完整统计钱包消费")
}

fun walletLogQuota(item: JSONObject): Double {
 val quota=item.getDouble("quota")
 require(quota.isFinite() && quota>=0) { "消费额度无效" }
 if(quota==0.0) return 0.0
 val raw=item.opt("other")
 val other=when(raw) {
  is JSONObject -> raw
  is String -> JSONObject(raw)
  else -> throw IllegalArgumentException("站点未提供计费来源，无法单独统计钱包消费")
 }
 if(other.has("wallet_quota_deducted") && !other.isNull("wallet_quota_deducted")) {
  val deducted=other.getDouble("wallet_quota_deducted")
  require(deducted.isFinite() && deducted>=0 && deducted<=quota) { "钱包扣款数值无效" }
  return deducted
 }
 return when(other.optString("billing_source")) {
  "wallet" -> quota
  "subscription" -> 0.0
  else -> throw IllegalArgumentException("站点未提供计费来源，无法单独统计钱包消费")
 }
}
