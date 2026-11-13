package app.quotadeck

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// No credentials or raw server messages are included in exceptions.
class TokenApiError(message: String, val verification: Boolean = false): IllegalStateException(message)
data class ManagedToken(val id: Long,val name: String,val status: Int,val remaining: Long,val used: Long,val unlimited: Boolean,val expires: Long,val group: String?=null,val models: List<String>?=null,val modelLimitsEnabled: Boolean?=null)
data class TokenPage(val items: List<ManagedToken>,val total: Long,val page: Int,val size: Int) {
 val hasNext: Boolean get()=page.toLong()*size<total
 val lastPage: Int get()=((total.coerceAtLeast(1)-1)/size+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
data class TokenDraft(val name: String,val quota: Long,val unlimited: Boolean,val expires: Long,val group: String?=null,val models: List<String>?=null)
data class TokenGroup(val value: String,val label: String)

object TokenApi {
 const val SIZE=20
 val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
 fun eligible(s: Site)=(s.account || s.subscription) && (s.cookie.isNotBlank() || (s.key.isNotBlank() && !s.key.trim().removePrefix("Bearer ").startsWith("sk-")))
 fun headers(s: Site): Map<String,String> {
  require(eligible(s)) { "请先配置账户或订阅的管理身份，模型 sk 密钥不能用于管理" }
  val h=mutableMapOf("Accept" to "application/json")
  val bearer=s.key.trim().removePrefix("Bearer ")
  if(bearer.isNotBlank() && !bearer.startsWith("sk-")) h["Authorization"]="Bearer $bearer"
  if(s.cookie.isNotBlank()) h["Cookie"]=s.cookie
  if(s.userId.isNotBlank()) { require((s.userId.toLongOrNull() ?: 0)>0) { "用户 ID 无效" }; h["New-Api-User"]=s.userId.trim() }
  require(h.values.none { it.contains('\r') || it.contains('\n') }) { "管理身份格式无效，请重新登录" }
  return h
 }
 fun listPath(page: Int,size: Int=SIZE,search: String=""): String {
  require(page>0 && size in 1..100)
  return if(search.isBlank()) "/api/token/?p=$page&size=$size" else "/api/token/search?keyword=${URLEncoder.encode(search.trim(),"UTF-8")}&token=&p=$page&size=$size"
 }
 fun checked(text: String): JSONObject {
  val j=JSONObject(text)
  if(!j.optBoolean("success")) throw error(j)
  return j
 }
 private fun error(j: JSONObject): TokenApiError {
  val verification=j.optString("code").startsWith("TOKEN_KEY_EMAIL_VERIFICATION_")
  return TokenApiError(if(verification) "查看密钥需要邮箱验证码" else "站点拒绝操作，请核实登录状态、用户 ID、权限及输入；刷新后再操作",verification)
 }
 fun parseToken(j: JSONObject): ManagedToken {
  val t=ManagedToken(j.getLong("id"),j.getString("name"),j.getInt("status"),j.getLong("remain_quota"),j.getLong("used_quota"),j.getBoolean("unlimited_quota"),j.getLong("expired_time"),j.takeIf { it.has("group") && !it.isNull("group") }?.getString("group"),j.takeIf { it.has("model_limits") && !it.isNull("model_limits") }?.let { modelSelection(it) },j.takeIf { it.has("model_limits_enabled") && !it.isNull("model_limits_enabled") }?.getBoolean("model_limits_enabled"))
  require(t.id>0 && t.used>=0 && (t.expires==-1L || t.expires>=0)) { "令牌字段无效" }
  return t
 }
 fun parsePage(text: String,requested: Int,size: Int=SIZE): TokenPage {
  val d=checked(text).getJSONObject("data")
  val total=d.getLong("total"); val page=d.getInt("page"); val actualSize=d.getInt("page_size"); val a=d.getJSONArray("items")
  require(total>=0 && page==requested && actualSize==size && a.length()<=size) { "站点分页协议不兼容，请在网页查看令牌" }
  val items=(0 until a.length()).map { parseToken(a.getJSONObject(it)) }
  require(items.map { it.id }.distinct().size==items.size && items.size<=total) { "令牌分页数据无效" }
  require(items.isNotEmpty() || total==0L || (page.toLong()-1)*size>=total) { "站点返回不完整分页" }
  return TokenPage(items,total,page,actualSize)
 }
 fun quota(value: String,unit: Double): Long {
  require(unit.isFinite() && unit>0) { "额度换算无效" }
  val n=runCatching { BigDecimal(value.trim()).multiply(BigDecimal.valueOf(unit)).setScale(0,RoundingMode.UNNECESSARY).longValueExact() }.getOrNull()
  require(n!=null && n>=0) { "请输入非负 USD 金额，精确到整数 quota 且不超出范围" }; return n
 }
 fun expiry(value: String,never: Boolean,now: Long=System.currentTimeMillis()/1000): Long {
  if(never) return -1
  val t=runCatching { LocalDateTime.parse(value.trim(),dateFormat).atZone(ZoneId.systemDefault()).toEpochSecond() }.getOrNull()
  require(t!=null && t>now) { "有效期格式应为 yyyy-MM-dd HH:mm，且晚于当前时间" }; return t
 }
 fun body(d: TokenDraft,detail: JSONObject?=null): String {
  require(d.name.isNotBlank() && d.name.toByteArray(Charsets.UTF_8).size<=50 && d.quota>=0 && (d.expires==-1L || d.expires>0)) { "名称需为 1–50 字节，额度或有效期无效" }
  val j=if(detail!=null) JSONObject(detail.toString()) else JSONObject().put("model_limits_enabled",false).put("model_limits","").put("allow_ips","").put("group","").put("auto_route_mode","none").put("auto_groups",org.json.JSONArray()).put("cross_group_retry",false)
  listOf("key","key_display","key_masked","created_time","accessed_time","used_quota","user_id").forEach { j.remove(it) }
  if(detail!=null) require(j.getLong("id")>0)
  applyOptions(j,d.group,d.models)
  return j.put("name",d.name.trim()).put("remain_quota",d.quota).put("unlimited_quota",d.unlimited).put("expired_time",d.expires).toString()
 }
 // Card option changes preserve exact quota, expiry, status and all unrelated configuration.
 fun optionsBody(detail: JSONObject,group: String?,models: List<String>?): String {
  require(detail.getLong("id")>0)
  val j=JSONObject(detail.toString())
  listOf("key","key_display","key_masked","created_time","accessed_time","used_quota","user_id").forEach { j.remove(it) }
  applyOptions(j,group,models)
  return j.toString()
 }
 private fun applyOptions(j: JSONObject,group: String?,models: List<String>?) {
  // null means untouched: never replace old restrictions when option discovery fails.
  models?.let { models ->
   require(models.all { it.isNotBlank() && !it.contains(',') }) { "模型名称无效" }
   val values=models.distinct()
   j.put("model_limits",values.joinToString(",")).put("model_limits_enabled",values.isNotEmpty())
  }
  group?.let { group ->
   j.put("group",group)
   if(group=="auto") {
    j.put("auto_groups",org.json.JSONArray()).put("cross_group_retry",true).remove("auto_route_mode")
   } else {
    j.put("auto_groups",org.json.JSONArray()).put("cross_group_retry",false).remove("auto_route_mode")
   }
  }
 }
 // Current Neco frontend loads these independently; models are account-wide, not filtered by group.
 fun parseModels(text: String): List<String> {
  val a=checked(text).getJSONArray("data")
  return (0 until a.length()).map { a.getString(it).also { v-> require(v.isNotBlank() && !v.contains(',')) { "模型列表协议无效" } } }.distinct()
 }
 fun parseGroups(text: String): List<TokenGroup> {
  val d=checked(text).getJSONObject("data")
  return d.keys().asSequence().map { k ->
   require(k.isNotBlank()) { "分组协议无效" }
   val g=d.getJSONObject(k)
   TokenGroup(k,g.optString("desc").ifBlank { g.optString("raw_desc").ifBlank { k } })
  }.toList()
 }
 fun models(s: Site)=parseModels(request(s,"/api/user/models"))
 fun groups(s: Site)=parseGroups(request(s,"/api/user/self/groups"))
 fun modelSelection(detail: JSONObject?): List<String> = detail?.optString("model_limits")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
 fun key(text: String): String {
  val d=checked(text).get("data"); val k=if(d is JSONObject) d.getString("key") else d as? String ?: ""
  require(k.isNotBlank() && k.none { it.isWhitespace() || it=='*' || it=='…' }) { "站点未返回完整密钥" }
  return if(k.startsWith("sk-")) k else "sk-$k"
 }
 fun request(s: Site,path: String,method: String="GET",body: String?=null): String {
  val uri=URI(s.url); require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "请输入有效 HTTPS 根地址" }
  val h=headers(s); val c=URL(s.url.trimEnd('/')+path).openConnection() as HttpURLConnection
  try {
   c.requestMethod=method; c.connectTimeout=15000; c.readTimeout=15000; c.instanceFollowRedirects=false
   h.forEach { (k,v)->c.setRequestProperty(k,v) }
   if(body!=null) { val bytes=body.toByteArray(Charsets.UTF_8); c.doOutput=true; c.setRequestProperty("Content-Type","application/json; charset=utf-8"); c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) } }
   val code=c.responseCode
   val stream=if(code in 200..299) c.inputStream else c.errorStream
   val text=stream?.bufferedReader()?.use { r-> val out=StringBuilder(); val b=CharArray(4096); while(true) { val n=r.read(b); if(n<0) break; out.append(b,0,n); require(out.length<=2097152) { "接口响应过大" } }; out.toString() } ?: ""
   if(code !in 200..299) {
    val j=runCatching { JSONObject(text) }.getOrNull()
    if(j?.optString("code")?.startsWith("TOKEN_KEY_EMAIL_VERIFICATION_")==true) throw error(j)
    throw TokenApiError(when(code) { 401,403 -> "管理身份过期或无权限，请重新网页登录并检查用户 ID"; 429 -> "请求过于频繁，请稍后操作"; in 300..399 -> "管理接口重定向，请使用最终站点地址"; else -> "管理接口返回 HTTP $code，请刷新核实" })
   }
   checked(text); return text
  } finally { c.disconnect() }
 }
 fun list(s: Site,page: Int,search: String)=parsePage(request(s,listPath(page,search=search)),page)
 fun detail(s: Site,id: Long): JSONObject { require(id>0); return checked(request(s,"/api/token/$id")).getJSONObject("data").also { require(it.getLong("id")==id) { "返回令牌 ID 不一致" } } }
 fun fullKey(s: Site,id: Long): String { require(id>0); return key(request(s,"/api/token/$id/key","POST")) }
 fun safeError(t: Throwable): String=when(t) { is TokenApiError -> t.message ?: "站点拒绝请求"; is java.net.SocketTimeoutException -> "连接超时，请刷新核实"; is java.net.UnknownHostException -> "无法解析站点地址"; is javax.net.ssl.SSLException -> "安全连接失败，请检查证书"; else -> "操作失败，请检查网络、输入或站点协议" }
}
