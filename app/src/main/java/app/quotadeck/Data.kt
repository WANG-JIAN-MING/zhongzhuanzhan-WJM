package app.quotadeck

import android.app.Application
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Site(val id: String = UUID.randomUUID().toString(), val name: String, val url: String, val key: String, val account: Boolean = false, val userId: String = "", val unit: Double = 500000.0, val subscription: Boolean = false, val cookie: String = "")
data class Balance(val remaining: Double, val used: Double, val unlimited: Boolean, val time: Long, val expires: Long = -1, val subscriptions: List<SubscriptionQuota> = emptyList(), val todayUsed: Double? = null, val todayError: String? = null, val todayDate: String? = null, val walletRemaining: Double? = null, val walletError: String? = null, val walletTodayUsed: Double? = null, val walletTodayError: String? = null, val walletTodayDate: String? = null)
data class SubscriptionQuota(val title: String, val base: Double, val total: Double, val used: Double, val advance: Double, val nextReset: Long, val advanceReset: Long, val start: Long, val end: Long, val resetMode: String, val advanceMessage: String, val id: Long = 0, val canAdvance: Boolean = false, val todayUsed: Double? = null, val todayDate: String? = null, val todayError: String? = null) {
 val remaining: Double get()=(total-used).coerceAtLeast(0.0)
}
data class QueryState(val loading: Boolean = false, val balance: Balance? = null, val error: String? = null)
data class AppState(val sites: List<Site> = emptyList(), val queries: Map<String, QueryState> = emptyMap(), val theme: String = "system", val storageError: String? = null, val advancing: Set<String> = emptySet(), val advanceNotices: Map<String,String> = emptyMap(), val advanceBlocked: Set<String> = emptySet(), val pet: Map<String,NecoPetState> = emptyMap(), val petNotices: Map<String,String> = emptyMap(), val storageStatus: StorageStatus = StorageStatus.READY)

class Vault private constructor(
 preferences: () -> SharedPreferences,
 private val encryptOverride: ((String, Boolean) -> String)?
) {
 constructor(app: Application): this({ app.getSharedPreferences("quota_v1", 0) },null)
 // Exercise the real write/restore paths offline without an Android Keystore.
 internal constructor(prefs: SharedPreferences, encrypt: (String, Boolean) -> String): this({ prefs },encrypt)
 private val prefs by lazy(preferences)
 private fun key(create: Boolean): SecretKey {
  val store = try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
   catch (e: Exception) { throw StorageKeyUnavailableException(e) }
  val exists = try { store.containsAlias("quota_sites_v1") }
   catch (e: Exception) { throw StorageKeyUnavailableException(e) }
  if(exists) {
   val stored = try { store.getKey("quota_sites_v1", null) as? SecretKey }
    catch (e: Exception) { throw StorageKeyUnavailableException(e) }
   return stored ?: throw StorageKeyUnavailableException(IllegalStateException())
  }
  if(!create) throw StorageKeyMissingException()
  return try { KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("quota_sites_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
  } catch (e: Exception) { throw StorageKeyUnavailableException(e) }
 }
 fun read(): List<Site> {
  val raw = storageStep(StorageStage.PREFERENCES) { prefs.getString("sites", null) } ?: return emptyList()
  val parts = storageStep(StorageStage.FORMAT) {
   raw.split(":").also { check(it.size == 2 && it.all(String::isNotEmpty)) }
    .map { Base64.decode(it, Base64.NO_WRAP) }
  }
  val secret = storageStep(StorageStage.KEYSTORE) { key(create = false) }
  val plaintext = storageStep(StorageStage.DECRYPT) {
   Cipher.getInstance("AES/GCM/NoPadding").run {
    init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, parts[0]))
    String(doFinal(parts[1]), Charsets.UTF_8)
   }
  }
  return storageStep(StorageStage.PARSE) {
   val a = JSONArray(plaintext)
   (0 until a.length()).map { i -> val j=a.getJSONObject(i); Site(j.getString("id"),j.getString("name"),j.getString("url"),j.getString("key"),j.optBoolean("account"),j.optString("userId"),j.optDouble("unit",500000.0),j.optBoolean("subscription",false),j.optString("cookie","")) }
  }
 }
 fun write(sites: List<Site>, restoredTheme: String? = null) {
  val plaintext=serializeSites(sites)
  // Existing ciphertext must remain readable before it may be replaced.
  if(prefs.contains("sites")) read()
  commitSites(plaintext,restoredTheme)
 }
 fun restoreAfterReadFailure(sites: List<Site>, restoredTheme: String) {
  val plaintext=serializeSites(sites)
  val previous=preserveUnreadableConfig(prefs.getString("sites",null),prefs.getString("theme","system"),readPreservedConfig())
  val editor=prefs.edit().putString("sites",encodeSites(plaintext,createKey=true)).putString("theme",restoredTheme)
  if(previous!=null) editor.putString("recovery_previous_sites",previous.ciphertext).putString("recovery_previous_theme",previous.theme)
  check(editor.commit()) { "保存失败" }
 }
 private fun serializeSites(sites: List<Site>): String {
  val a=JSONArray(); sites.forEach { s -> a.put(JSONObject().put("id",s.id).put("name",s.name).put("url",s.url).put("key",s.key).put("account",s.account).put("userId",s.userId).put("unit",s.unit).put("subscription",s.subscription).put("cookie",s.cookie)) }
  return a.toString()
 }
 private fun readPreservedConfig(): PreservedEncryptedConfig? {
  val ciphertext=prefs.getString("recovery_previous_sites",null) ?: return null
  return PreservedEncryptedConfig(ciphertext,prefs.getString("recovery_previous_theme","system") ?: "system")
 }
 private fun encodeSites(plaintext: String, createKey: Boolean = !prefs.contains("sites")): String {
  encryptOverride?.let { return it(plaintext,createKey) }
  val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key(create = createKey))
  val encrypted=c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
  return Base64.encodeToString(c.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)
 }
 private fun commitSites(plaintext: String, restoredTheme: String?) {
  val editor=prefs.edit().putString("sites",encodeSites(plaintext))
  if(restoredTheme!=null) editor.putString("theme",restoredTheme)
  check(editor.commit()) { "保存失败" }
 }
 fun theme()=prefs.getString("theme","system") ?: "system"
 fun theme(value: String) { check(prefs.edit().putString("theme",value).commit()) }
}

object QuotaApi {
 fun publicUnit(base: String): Double {
  val uri=java.net.URI(base)
  require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null) { "请输入 HTTPS 站点地址" }
  val conn=URL(base.trimEnd('/')+"/api/status").openConnection() as HttpURLConnection
  try {
   conn.connectTimeout=10000; conn.readTimeout=10000; conn.instanceFollowRedirects=false
   require(conn.responseCode==200) { "无法读取站点公开配置" }
   return parseUnit(conn.inputStream.bufferedReader().use { it.readTextLimited() })
  } finally { conn.disconnect() }
 }
 fun parseUnit(text: String): Double {
  val root=JSONObject(text)
  require(root.optBoolean("success")) { "站点配置接口返回失败" }
  val unit=root.getJSONObject("data").getDouble("quota_per_unit")
  require(unit.isFinite() && unit>0) { "站点换算值无效" }
  return unit
 }
 fun fetch(s: Site, onCredentials: (Site)->Unit = {}): Balance {
  val session=AccountSession(s,{ target,path -> read(target,path) },::requestSessionRenewal,onCredentials)
  return fetch(s,usageDay(),session::read)
 }
 // Inject the transport for offline endpoint/authentication and error-isolation tests.
 internal fun fetch(s: Site, day: UsageDay, read: (Site,String) -> String): Balance {
  val path=if(s.subscription) "/api/subscription/self" else if(s.account) "/api/user/self" else "/api/usage/token/"
  val balance=parse(s,read(s,path))
  if(!s.account && !s.subscription) return balance
  if(s.subscription) {
   // Keep this site's exact credentials; never borrow another account at the same origin.
   val walletSite=s.copy(subscription=false,account=true)
   val wallet=runCatching { parse(walletSite,read(walletSite,"/api/user/self")) }
   val walletDaily=runCatching { walletTodayUsage(day) { read(walletSite,it) } }
   val daily=runCatching { if(balance.subscriptions.isEmpty()) emptyMap() else subscriptionTodayUsage(day) { read(s,it) } }
   return balance.copy(
    walletRemaining=wallet.getOrNull()?.remaining,
    walletError=wallet.exceptionOrNull()?.let(::subscriptionWalletError),
    walletTodayUsed=walletDaily.getOrNull(),walletTodayDate=day.date,
    walletTodayError=walletDaily.exceptionOrNull()?.let(::walletUsageError),
    subscriptions=balance.subscriptions.map { sub ->
     val error=if(sub.id<=0) "今日订阅消耗：订阅信息缺少有效 ID" else daily.exceptionOrNull()?.let(::subscriptionUsageError)
     sub.copy(todayUsed=if(error==null) daily.getOrThrow()[sub.id] ?: 0.0 else null,todayDate=day.date,todayError=error)
    })
  }
  val daily=runCatching { walletTodayUsage(day) { read(s,it) } }
  return balance.copy(todayUsed=daily.getOrNull(),todayError=daily.exceptionOrNull()?.let(::walletUsageError),todayDate=day.date)
 }
 fun parseTodayUsage(text: String): Double {
  val root=JSONObject(text)
  require(root.optBoolean("success")) { "消费统计接口返回失败" }
  val value=root.getJSONObject("data").getDouble("quota")
  require(value.isFinite() && value>=0) { "今日消费统计数值无效" }
  return value
 }
 private fun read(s: Site,path: String): String {
  val conn=URL(s.url.trimEnd('/')+path).openConnection() as HttpURLConnection
  try {
   conn.connectTimeout=15000; conn.readTimeout=15000; conn.instanceFollowRedirects=false
   if(s.key.isNotBlank()) conn.setRequestProperty("Authorization","Bearer "+s.key.trim().removePrefix("Bearer "))
   if(s.cookie.isNotBlank()) conn.setRequestProperty("Cookie",s.cookie)
   if((s.account || s.subscription) && s.userId.isNotBlank()) conn.setRequestProperty("New-Api-User",s.userId)
   conn.setRequestProperty("Accept","application/json")
   val code=conn.responseCode
   if(code !in 200..299) throw QuotaHttpException(code,when(code) { 401 -> "未授权：请检查凭据"; 403 -> "无访问权限，请检查用户 ID 或站点限制"; in 300..399 -> "接口发生重定向，请填写最终站点地址"; 429 -> "请求过于频繁，请稍后重试"; else -> "服务器返回 HTTP $code" })
   val text=conn.inputStream.bufferedReader().use { it.readTextLimited() }
   return text
  } finally { conn.disconnect() }
 }
 fun advanceBody(id: Long): String { require(id>0) { "订阅 ID 无效" }; return JSONObject().put("subscription_id",id).toString() }
 fun checkAdvanceResponse(text: String) {
  val root=JSONObject(text)
  check(root.optBoolean("success")) { "站点拒绝透支：可能已提前领取、处于订阅最后一天或当前无权限，请刷新状态后查看站点说明" }
 }
 fun advance(s: Site,id: Long) {
  val body=advanceBody(id).toByteArray(Charsets.UTF_8)
  val conn=URL(s.url.trimEnd('/')+"/api/subscription/self/advance-quota").openConnection() as HttpURLConnection
  try {
   conn.requestMethod="POST"; conn.doOutput=true; conn.connectTimeout=15000; conn.readTimeout=15000; conn.instanceFollowRedirects=false
   if(s.key.isNotBlank()) conn.setRequestProperty("Authorization","Bearer "+s.key.trim().removePrefix("Bearer "))
   if(s.cookie.isNotBlank()) conn.setRequestProperty("Cookie",s.cookie)
   if(s.userId.isNotBlank()) conn.setRequestProperty("New-Api-User",s.userId)
   conn.setRequestProperty("Content-Type","application/json; charset=utf-8"); conn.setRequestProperty("Accept","application/json")
   conn.setFixedLengthStreamingMode(body.size)
   conn.outputStream.use { it.write(body) }
   check(conn.responseCode in 200..299) { "透支接口返回 HTTP ${conn.responseCode}，请刷新状态后核实" }
   checkAdvanceResponse(conn.inputStream.bufferedReader().use { it.readTextLimited() })
  } finally { conn.disconnect() }
 }
 fun authError(message: String): String = when {
  message.contains("user",true) && (message.contains("id",true) || message.contains("New-Api",true)) -> "用户 ID 缺失或不匹配，请使用网页登录重新绑定"
  message.contains("access token",true) || message.contains("未登录") || message.contains("无权") || message.contains("令牌") -> "登录状态无效或凭据类型不支持，请在站点编辑中使用 Neco 网页登录"
  else -> "订阅接口拒绝查询，请重新网页登录；也可能是账户权限或站点接口限制"
 }
 fun parseSubscriptions(text: String): Balance {
  val root=JSONObject(text)
  check(root.optBoolean("success")) { authError(root.optString("message")) }
  val data=root.getJSONObject("data")
  val items=data.optJSONArray("all_subscriptions") ?: data.optJSONArray("subscriptions")
  require(items!=null) { "接口缺少订阅列表，请确认订阅接口兼容性" }
  val list=(0 until items.length()).mapNotNull { i ->
   val item=items.getJSONObject(i); val sub=item.getJSONObject("subscription")
   if(sub.optString("status")!="active") return@mapNotNull null
   val base=sub.getDouble("amount_total"); val used=sub.getDouble("amount_used"); val advance=sub.optDouble("quota_advance_amount",0.0)
   val total=if(item.has("effective_amount_total") && !item.isNull("effective_amount_total")) item.getDouble("effective_amount_total") else base+advance
   require(listOf(base,used,advance,total).all { it.isFinite() && it>=0 }) { "订阅额度数值无效" }
   SubscriptionQuota(item.optString("plan_title","订阅套餐"),base,total,used,advance,sub.optLong("next_reset_time"),sub.optLong("quota_advance_reset_time"),sub.optLong("start_time"),sub.optLong("end_time"),sub.optString("quota_reset_mode","calendar"),item.optString("advance_unavailable_message"),sub.optLong("id"),item.optBoolean("can_advance_quota",false))
  }
  val limited=list.filter { it.total>0 }
  return Balance(limited.sumOf { it.remaining },limited.sumOf { it.used },list.any { it.total==0.0 },System.currentTimeMillis(),subscriptions=list)
 }
 fun parse(s: Site,text: String): Balance {
   if(s.subscription) return parseSubscriptions(text)
   val root=JSONObject(text)
   if(!(if(s.account) root.optBoolean("success",false) else root.optBoolean("code",false))) throw IllegalStateException("未授权或接口拒绝查询，请检查凭据和站点配置")
   val d=root.getJSONObject("data")
   if(s.account && s.userId.isNotBlank() && d.has("id")) {
    require(d.getLong("id").toString()==s.userId.trim()) { "返回账户与配置用户 ID 不一致，请重新网页登录绑定" }
   }
   val remain=if(s.account) "quota" else "total_available"
   val used=if(s.account) "used_quota" else "total_used"
   require(d.has(remain) && d.has(used)) { "接口缺少额度字段，请确认站点兼容 New API" }
   val available=d.getDouble(remain); val consumed=d.getDouble(used)
   require(available.isFinite() && consumed.isFinite()) { "额度数值无效" }
   return Balance(available,consumed,!s.account && d.optBoolean("unlimited_quota"),System.currentTimeMillis(),d.optLong("expires_at",0))
 }
 private fun java.io.Reader.readTextLimited(): String { val b=CharArray(4096); val out=StringBuilder(); while(true) { val n=read(b); if(n<0) break; out.append(b,0,n); require(out.length<=1048576) { "接口响应过大" } }; return out.toString() }
}

private class PersistentNecoStartupStore(app: Application) : NecoStartupStore {
 private val prefs=app.getSharedPreferences("neco_startup_v1", 0)
 override fun lastClaimDate(): String? = prefs.getString("claimed_local_date", null)
 override fun saveClaimDate(date: String): Boolean = prefs.edit().putString("claimed_local_date", date).commit()
}

class QuotaModel(app: Application): AndroidViewModel(app) {
 private val vault=Vault(app)
 private val mutable=MutableStateFlow(AppState(storageStatus=StorageStatus.LOADING))
 val state=mutable.asStateFlow()
 private val storageLock=Mutex()
 // Only mutating Neco actions serialize; read-only quota/pet queries run concurrently.
 private val petOperationLock=Mutex()
 private val startupPetGate=NecoStartupGate(PersistentNecoStartupStore(app))
 private var startupPetAllowed=false
 private var startupPetInProgress=false
 // Keep requests reserved until their results are published, even if queries are reset.
 private val refreshing=java.util.Collections.synchronizedSet(mutableSetOf<Site>())
 private var storageLoad: Job? = null
 private suspend fun storageReady(): Boolean = state.first { it.storageStatus!=StorageStatus.LOADING }.storageStatus==StorageStatus.READY
 init { retryStorage() }
 fun authorizeColdStartPetActions() {
  if(mutable.value.storageStatus==StorageStatus.LOADING) startupPetAllowed=true
 }
 fun retryStorage() {
  if(storageLoad?.isActive==true || mutable.value.storageStatus==StorageStatus.READY) return
  mutable.value=mutable.value.copy(storageStatus=StorageStatus.LOADING,storageError=null)
  storageLoad=viewModelScope.launch {
   val result=storageLock.withLock {
    readStorageWithRetry(read={ withContext(Dispatchers.IO) {
     AppState(sites=vault.read(),theme=storageStep(StorageStage.THEME) { vault.theme() })
    } },onFailure={ attempt,diagnostic -> android.util.Log.w("QuotaStorage","read attempt=$attempt code=$diagnostic") })
   }
   result.fold({ mutable.value=it },{ error -> mutable.value=mutable.value.copy(storageStatus=StorageStatus.FAILED,storageError="无法读取本地加密配置，原数据已保留，已禁止覆盖。请勿卸载或清除应用数据；可点击重试。诊断：${storageDiagnostic(error)}") })
   if(result.isSuccess) {
    if(startupPetAllowed && !startupPetInProgress) {
     startupPetInProgress=true
     val sites=mutable.value.sites
     viewModelScope.launch { runStartupPetActions(sites) }
    }
    // Includes Neco immediately; no wait for signin/draws and no automatic follow-up refresh.
    refreshAll()
   }
  }
 }
 fun save(site: Site, done: (String?)->Unit) { viewModelScope.launch {
  if(!storageReady()) { done("本地配置读取失败，已阻止覆盖原数据，请点击重试读取"); return@launch }
  val error=storageLock.withLock {
   val list=mutable.value.sites.toMutableList(); val index=list.indexOfFirst { it.id==site.id }; if(index<0) list.add(site) else list[index]=site
   val result=withContext(Dispatchers.IO) { runCatching { vault.write(list) } }
   if(result.isSuccess) { mutable.value=mutable.value.copy(sites=list,queries=mutable.value.queries-site.id); null } else "保存失败，原配置未主动清除，请重试"
  }
  done(error)
  if(error==null) refresh(site)
 } }
 fun delete(site: Site) { viewModelScope.launch {
  if(!storageReady()) return@launch
  storageLock.withLock {
   val list=mutable.value.sites.filterNot { it.id==site.id }
   val ok=withContext(Dispatchers.IO) { runCatching { vault.write(list) }.isSuccess }
   if(ok) mutable.value=mutable.value.copy(sites=list,queries=mutable.value.queries-site.id) else mutable.value=mutable.value.copy(storageError="删除失败，请重试")
  }
 } }
 fun reorder(ids: List<String>, done: (Boolean)->Unit) { viewModelScope.launch {
  if(!storageReady()) { done(false); return@launch }
  val ok=storageLock.withLock {
   val current=mutable.value.sites
   val result=withContext(Dispatchers.IO) { runCatching {
    val ordered=orderedSites(current,ids)
    vault.write(ordered)
    ordered
   } }
   result.fold({ mutable.value=mutable.value.copy(sites=it); true },{ mutable.value=mutable.value.copy(storageError="排序保存失败，已保留原顺序，请重试"); false })
  }
  done(ok)
 } }
 suspend fun backupSnapshot(): BackupData {
  check(storageReady()) { "本地配置未能读取，无法备份" }
  return storageLock.withLock { BackupData(mutable.value.sites.toList(),mutable.value.theme) }
 }
 suspend fun restoreBackup(data: BackupData): Boolean {
  if(!storageReady()) return false
  return storageLock.withLock {
   val ok=withContext(Dispatchers.IO) { runCatching { vault.write(data.sites,data.theme) }.isSuccess }
   if(ok) mutable.value=AppState(sites=data.sites,theme=data.theme)
   ok
  }
 }
 suspend fun restoreBackupAfterStorageFailure(data: BackupData): Boolean {
  val ok=storageLock.withLock {
   withContext(Dispatchers.IO) { runCatching { vault.restoreAfterReadFailure(data.sites,data.theme) }.isSuccess }
  }
  if(ok) {
   mutable.value=AppState(sites=data.sites,theme=data.theme)
   refreshAll()
  }
  return ok
 }
 fun theme(value: String) { viewModelScope.launch {
  if(!storageReady()) return@launch
  storageLock.withLock { if(withContext(Dispatchers.IO) { runCatching { vault.theme(value) }.isSuccess }) mutable.value=mutable.value.copy(theme=value) }
 } }
 private suspend fun runStartupPetActions(sites: List<Site>) {
  // Even an empty target list consumes today's global startup batch. Persist before any request.
  val claimed=withContext(Dispatchers.IO) { startupPetGate.claim(mutable.value.storageStatus==StorageStatus.READY) }
  if(!claimed) return
  val targets=sites.filter { isNecoOrigin(it.url) }
  targets.forEach { site ->
   mutable.value=mutable.value.copy(pet=mutable.value.pet+(site.id to NecoPetState(loading=true)),petNotices=mutable.value.petNotices-site.id)
  }
  val results=withContext(Dispatchers.IO) {
   petOperationLock.withLock {
    targets.mapNotNull { site ->
     if(mutable.value.storageStatus!=StorageStatus.READY || mutable.value.sites.none { it==site }) return@mapNotNull null
     site.id to runCatching { startupPetGate.executeSite(site) { target,draw -> if(draw) NecoPetApi.draw(target) else NecoPetApi.signin(target) } }
    }.toMap()
   }
  }
  targets.forEach { site ->
   val result=results[site.id] ?: return@forEach
   val notice=result.fold({ r ->
    when {
     r.outcomeUnknown -> "启动自动操作结果未知（${r.failedStage}）；未重试，请核实 Neco 页面"
     r.failedStage!=null -> "启动自动操作被站点拒绝（${r.failedStage}）；已停止该站点后续抽奖"
     else -> "启动自动签到 1 次、抽奖 ${r.draws.size} 次已完成"
    }
   },{ "启动自动操作请求结果未知；未重试，请核实 Neco 页面" })
   val summary=result.getOrNull()
   if(summary?.outcomeUnknown==true || result.isFailure) android.widget.Toast.makeText(getApplication(), "${site.name}：$notice", android.widget.Toast.LENGTH_LONG).show()
   mutable.value=mutable.value.copy(
    pet=mutable.value.pet+(site.id to NecoPetState(loading=false,message=summary?.draws?.lastOrNull()?.message)),
    petNotices=mutable.value.petNotices+(site.id to notice)
   )
  }
 }
 fun refreshPet(s: Site) {
  val st=mutable.value
  if(!isNecoOrigin(s.url) || st.storageStatus!=StorageStatus.READY || st.sites.none { it==s } || st.pet[s.id]?.loading==true || s.id in st.advancing) return
  mutable.value=st.copy(pet=st.pet+(s.id to (st.pet[s.id] ?: NecoPetState()).copy(loading=true,error=null)))
  viewModelScope.launch {
   val result=withContext(Dispatchers.IO) { runCatching { NecoPetApi.self(s) } }
   if(mutable.value.sites.none { it==s }) return@launch
   mutable.value=mutable.value.copy(pet=mutable.value.pet+(s.id to result.fold({ it },{ NecoPetState(error=refreshError(it)) })))
  }
 }
 fun petAction(s: Site, draw: Boolean) {
  val st=mutable.value
  if(!isNecoOrigin(s.url) || st.storageStatus!=StorageStatus.READY || st.sites.none { it==s } || st.pet[s.id]?.loading==true || s.id in st.advancing || st.queries[s.id]?.loading==true) return
  mutable.value=st.copy(pet=st.pet+(s.id to (st.pet[s.id] ?: NecoPetState()).copy(loading=true,error=null)),petNotices=st.petNotices-s.id)
  viewModelScope.launch {
   val result=withContext(Dispatchers.IO) { petOperationLock.withLock { runCatching { if(draw) NecoPetApi.draw(s) else NecoPetApi.signin(s) } } }
   val current=mutable.value
   if(current.sites.none { it==s }) return@launch
   val state=result.fold({ action -> NecoPetState(loading=false,message=action.message) },{ error -> NecoPetState(loading=false,error=refreshError(error)) })
   val notice=result.fold({ action ->
    val detail=if(draw) action.reward?.text else action.message
    when {
     !action.success -> "站点拒绝了本次操作，请核实状态后再决定是否重试"
     draw && action.reward==null -> "抽奖请求已返回但奖励字段不明确，请核实 Neco 页面后再决定是否操作"
     detail!=null -> (if(draw) "抽奖结果：" else "签到结果：")+detail
     else -> "操作已返回；请刷新核实状态"
    }
   },{ "操作结果未能确认，请先刷新 Neco 页面核实；不要立即重复提交" })
   mutable.value=current.copy(pet=current.pet+(s.id to state),petNotices=current.petNotices+(s.id to notice))
   if(result.isSuccess && result.getOrThrow().success) {
    refresh(s)
    refreshPet(s)
   }
  }
 }
 fun advance(s: Site,id: Long) {
  val st=mutable.value
  val item=st.queries[s.id]?.balance?.subscriptions?.firstOrNull { it.id==id }
  if(!s.subscription || item?.canAdvance!=true || id<=0 || s.id in st.advancing || s.id in st.advanceBlocked || st.queries[s.id]?.loading==true || st.queries[s.id]?.error!=null || st.sites.none { it==s }) return
  mutable.value=st.copy(advancing=st.advancing+s.id,advanceNotices=st.advanceNotices-s.id)
  viewModelScope.launch {
   val result=withContext(Dispatchers.IO) { runCatching { QuotaApi.advance(s,id) } }
   val current=mutable.value
   mutable.value=current.copy(advancing=current.advancing-s.id,advanceBlocked=current.advanceBlocked+s.id,advanceNotices=current.advanceNotices+(s.id to if(result.isSuccess) "透支成功，正在刷新额度" else "透支未确认或被站点拒绝；请先刷新核实，不要重复提交"))
   if(current.sites.any { it==s }) refresh(s)
  }
 }
 fun refreshError(t: Throwable): String = when(t) {
  is javax.net.ssl.SSLException -> "安全连接失败，请检查站点证书"
  is java.net.SocketTimeoutException -> "连接超时，请重试"
  is java.net.UnknownHostException -> "无法解析站点地址"
  is IllegalStateException, is IllegalArgumentException -> t.message ?: "查询失败，请检查站点配置"
  else -> "查询失败，请检查网络与接口兼容性"
 }
 fun refreshAll() {
  if(mutable.value.storageStatus!=StorageStatus.READY) return
  viewModelScope.launch {
   val initial=mutable.value
   val sites=initial.sites.filter { it.id !in initial.advancing && refreshing.add(it) }
   if(sites.isEmpty()) return@launch
   val loading=sites.associate { it.id to (initial.queries[it.id] ?: QueryState()).copy(loading=true,error=null) }
   mutable.value=initial.copy(queries=initial.queries+loading)
   val results=sites.map { site -> async { site.id to fetchAndPersistSession(site) } }.awaitAll().toMap()
   refreshing.removeAll(sites.toSet())
   val current=mutable.value
   val queries=current.queries.toMutableMap(); val blocked=current.advanceBlocked.toMutableSet(); val notices=current.advanceNotices.toMutableMap()
   sites.forEach { site ->
    val (published,result)=results[site.id] ?: return@forEach
    if(current.sites.none { it==published } || site.id in current.advancing) return@forEach
    val old=queries[site.id]?.balance
    queries[site.id]=result.fold({ QueryState(balance=it) },{ QueryState(balance=old,error=refreshError(it)) })
    val previous=notices[site.id]
    val notice=when { previous=="透支成功，正在刷新额度" -> if(result.isSuccess) "透支成功，额度已刷新" else "透支已成功，但额度刷新失败，请手动刷新"; result.isSuccess && previous!=null -> "状态已刷新，请核对已领取额度与抵扣时间"; else -> previous }
    if(notice!=null) notices[site.id]=notice
    if(result.isSuccess) blocked.remove(site.id)
   }
   mutable.value=mutable.value.copy(queries=queries,advanceBlocked=blocked,advanceNotices=notices)
  }
 }
 private suspend fun fetchAndPersistSession(s: Site): Pair<Site,Result<Balance>> {
   var renewed: Site?=null
   var result=withContext(Dispatchers.IO) { runCatching { QuotaApi.fetch(s) { renewed=it } } }
   var published=s
   // Persist rotation even if the retry failed. Never let an old request undo an edit.
   val credentials=renewed
   if(credentials!=null) storageLock.withLock {
    val list=renewedSites(mutable.value.sites,s,credentials)
    if(list!=null) {
     val saved=withContext(Dispatchers.IO) { runCatching { vault.write(list) }.isSuccess }
     if(saved) {
      mutable.value=mutable.value.copy(sites=list)
      published=credentials
     } else {
      val message="登录会话已续期，但更新凭据保存失败，请重试或重新网页登录绑定"
      mutable.value=mutable.value.copy(storageError=message)
      result=Result.failure(IllegalStateException(message))
     }
    }
   }
   return published to result
 }
 fun refresh(s: Site) {
  if(mutable.value.storageStatus!=StorageStatus.READY || mutable.value.sites.none { it==s } || mutable.value.queries[s.id]?.loading==true || s.id in mutable.value.advancing || !refreshing.add(s)) return
  mutable.value=mutable.value.copy(queries=mutable.value.queries+(s.id to (mutable.value.queries[s.id] ?: QueryState()).copy(loading=true,error=null)))
  viewModelScope.launch {
   val (published,result)=fetchAndPersistSession(s)
   refreshing.remove(s)
   if(mutable.value.sites.none { it==published }) return@launch
   val old=mutable.value.queries[s.id]?.balance
   val q=result.fold({ QueryState(balance=it) },{ QueryState(balance=old,error=when(it) { is javax.net.ssl.SSLException -> "安全连接失败，请检查站点证书"; is java.net.SocketTimeoutException -> "连接超时，请重试"; is java.net.UnknownHostException -> "无法解析站点地址"; is IllegalStateException, is IllegalArgumentException -> it.message; else -> "查询失败，请检查网络与接口兼容性" }) })
   val previousNotice=mutable.value.advanceNotices[s.id]
   val notice=when {
    previousNotice=="透支成功，正在刷新额度" -> if(result.isSuccess) "透支成功，额度已刷新" else "透支已成功，但额度刷新失败，请手动刷新"
    result.isSuccess && previousNotice!=null -> "状态已刷新，请核对已领取额度与抵扣时间"
    else -> previousNotice
   }
   mutable.value=mutable.value.copy(queries=mutable.value.queries+(s.id to q),advanceBlocked=if(result.isSuccess) mutable.value.advanceBlocked-s.id else mutable.value.advanceBlocked,advanceNotices=if(notice!=null) mutable.value.advanceNotices+(s.id to notice) else mutable.value.advanceNotices)
  }
 }
}
