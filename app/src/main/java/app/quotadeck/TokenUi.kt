package app.quotadeck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

@Composable fun Tokens(state: AppState,onRefreshQuota:()->Unit) {
 val sources=state.sites.filter { TokenApi.eligible(it) }
 var selected by remember { mutableStateOf<String?>(null) }
 val site=sources.firstOrNull { it.id==selected } ?: sources.firstOrNull()
 Panel(spacing=10.dp,contentPadding=10.dp) {
  Text("按站点管理",style=MaterialTheme.typography.titleMedium)
  Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
   Secondary("复用账户或订阅的网页登录 / 用户管理身份。模型 sk 查询配置不会用作管理身份。")
   if(sources.isEmpty()) Secondary("请先在「站点」添加账户或订阅配置，并完成网页登录或填写用户管理凭据。")
   sources.forEach { s -> FilterChip(selected=site?.id==s.id,onClick={selected=s.id},label={Text(s.name+if(s.subscription) " · 订阅身份" else " · 账户身份")}) }
  }
 }
 if(site!=null) key(site) { TokenSite(site,onRefreshQuota) }
}

@Composable private fun TokenSite(site: Site,onRefreshQuota:()->Unit) {
 var input by remember { mutableStateOf("") }; var search by remember { mutableStateOf("") }
 var page by remember { mutableStateOf<TokenPage?>(null) }; var busy by remember { mutableStateOf(false) }
 var notice by remember { mutableStateOf<String?>(null) }; var fresh by remember { mutableStateOf(false) }
 var editor by remember { mutableStateOf<JSONObject?>(null) }; var creating by remember { mutableStateOf(false) }
 var deleting by remember { mutableStateOf<ManagedToken?>(null) }
 var options by remember { mutableStateOf<JSONObject?>(null) }; var optionId by remember { mutableStateOf<Long?>(null) }; var optionTarget by remember { mutableStateOf("group") }
 var verification by remember { mutableStateOf<ManagedToken?>(null) }; var code by remember { mutableStateOf("") }
 var verificationNotice by remember { mutableStateOf<String?>(null) }; var verifyBusy by remember { mutableStateOf(false) }
 var resendAt by remember { mutableLongStateOf(0L) }
 val clipboard=LocalClipboardManager.current; val scope=rememberCoroutineScope()
 val keyCache=remember(site) { TokenKeyCache(site) }
 DisposableEffect(keyCache) { onDispose { keyCache.close() } }
 suspend fun load(p: Int,q: String) {
  fresh=false
  val result=withContext(Dispatchers.IO) { runCatching { TokenApi.list(site,p,q) } }
  result.fold({ first ->
   // A delete on the final page can move that page beyond the new total.
   val resolved=if(first.items.isEmpty() && first.page>first.lastPage) withContext(Dispatchers.IO) { runCatching { TokenApi.list(site,first.lastPage,q) } } else Result.success(first)
   resolved.fold({page=it; search=q; fresh=true},{notice=TokenApi.safeError(it)})
  },{notice=TokenApi.safeError(it)})
 }
 fun reload(p: Int=page?.page ?: 1,q: String=search) {
  if(busy) return
  busy=true; notice=null; scope.launch { try { load(p,q) } finally { busy=false } }
 }
 fun write(method: String,path: String,body: String?=null,create: Boolean=false) {
  if(busy) return
  if(method=="DELETE") deleting?.let { keyCache.remove(it.id) }
  busy=true; editor=null; creating=false; deleting=null; options=null; optionId=null
  scope.launch {
   try {
    val r=withContext(Dispatchers.IO) { runCatching { TokenApi.request(site,path,method,body) } }
    val outcome=if(r.isSuccess) "操作成功" else if(create) "创建结果未确认或被拒绝；请核对列表，确认未创建后才可再次创建，不会自动重试。${TokenApi.safeError(r.exceptionOrNull()!!)}" else "操作未确认或被拒绝，请核对刷新结果。${TokenApi.safeError(r.exceptionOrNull()!!)}"
    notice=null
    if(create) input=""
    load(if(create) 1 else page?.page ?: 1,if(create) "" else search)
    notice=outcome+if(fresh) " · 列表已刷新" else " · 列表刷新失败，请手动刷新"
    onRefreshQuota()
   } finally { busy=false }
  }
 }
 fun reveal(t: ManagedToken) {
  keyCache.get(site,t.id)?.let { clipboard.setText(AnnotatedString(it)); notice="密钥已复制到剪贴板，卡片保持隐藏"; return }
  if(busy) return
  busy=true; notice=null
  scope.launch {
   try {
    val r=withContext(Dispatchers.IO) { runCatching { TokenApi.fullKey(site,t.id) } }
    r.fold({keyCache.put(site,t.id,it); clipboard.setText(AnnotatedString(it)); notice="密钥已复制到剪贴板，卡片保持隐藏"},{if((it as? TokenApiError)?.verification==true) { verification=t; code=""; verificationNotice="此站点要求邮箱验证码。请发送验证码后填写。" } else notice=TokenApi.safeError(it)})
   } finally { busy=false }
  }
 }
 LaunchedEffect(site) { busy=true; try { load(1,"") } finally { busy=false } }
 // Loading and option-detail updates must not animate the dropdown's ancestors.
 Panel(spacing=10.dp,animateSize=false,contentPadding=10.dp) {
  Text(site.name,style=MaterialTheme.typography.titleLarge); Secondary(site.url)
  Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
   OutlinedTextField(input,{input=it},label={Text("搜索令牌名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
   Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
    OutlinedButton(onClick={reload(1,input.trim())},enabled=!busy) { Text("搜索") }
    TextButton(onClick={input=""; reload(1,"")},enabled=!busy) { Text("全部") }
    TextButton(onClick={reload()},enabled=!busy) { Text("刷新") }
   }
   Button(onClick={creating=true},enabled=!busy && fresh) { Text("创建令牌") }
  }
  Box(Modifier.fillMaxWidth().height(4.dp)) {
   if(busy) LinearProgressIndicator(Modifier.fillMaxSize())
  }
  notice?.let { Text(it) }
  if(!fresh && !busy) Secondary("列表未更新，刷新成功后可管理令牌。")
  page?.let { p -> Secondary("${if(search.isBlank()) "全部" else "搜索：$search"} · 共 ${p.total} 条 · 第 ${p.page} / ${p.lastPage} 页") }
 }
 val ready=!busy && fresh
 page?.items?.forEach { t -> key(t.id) { Panel(spacing=10.dp,animateSize=false,contentPadding=10.dp) {
  Text(t.name,style=MaterialTheme.typography.titleMedium)
  Secondary("#${t.id} · "+when(t.status) { 1->"启用"; 2->"停用"; 3->"已过期"; 4->"额度耗尽"; else->"未知状态 ${t.status}" })
  Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().clickable(enabled=ready || keyCache.get(site,t.id)!=null) { reveal(t) }) {
   Column(Modifier.padding(12.dp).heightIn(min=48.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text("sk-••••••••••••",style=MaterialTheme.typography.bodyLarge)
    Secondary("密钥已隐藏 · 点击获取并复制")
   }
  }
  fun editOptions(target: String) {
   if(!ready) return
   if(optionId==t.id && optionTarget==target && options!=null) { options=null; optionId=null; return }
   options=null; optionId=t.id; optionTarget=target
   // Keep the header notice in place while the anchored menu is opening.
   busy=true
   scope.launch { try {
    val r=withContext(Dispatchers.IO) { runCatching { TokenApi.detail(site,t.id) } }
    r.fold({ detail ->
     options=detail
     page=page?.let { p -> p.copy(items=p.items.map { item -> if(item.id!=t.id) item else item.copy(
      group=if(detail.has("group") && !detail.isNull("group")) detail.getString("group") else item.group,
      models=if(detail.has("model_limits") && !detail.isNull("model_limits")) TokenApi.modelSelection(detail) else item.models,
      modelLimitsEnabled=if(detail.has("model_limits_enabled") && !detail.isNull("model_limits_enabled")) detail.getBoolean("model_limits_enabled") else item.modelLimitsEnabled
     ) }) }
    },{optionId=null; notice=TokenApi.safeError(it)})
   } finally { busy=false } }
  }
  Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Box(Modifier.fillMaxWidth()) {
    TextButton(enabled=ready,onClick={editOptions("group")},modifier=Modifier.fillMaxWidth()) {
     Text("分组："+when(t.group) { null->"点击加载当前配置"; ""->"默认 · 跟随用户分组"; "auto"->"auto · 自动路由"; else->t.group }+" ▾",maxLines=1,overflow=TextOverflow.Ellipsis)
    }
    // The popup position provider uses menu height to choose above/below the anchor.
    // Reserve the same viewport for detail loading, group loading, filtering and errors.
    DropdownMenu(expanded=optionId==t.id && optionTarget=="group",onDismissRequest={if(!busy) {options=null; optionId=null}},modifier=Modifier.width(320.dp).height(440.dp)) {
     val detail=options
     if(detail==null) DropdownMenuItem(text={Text("正在加载当前分组…")},onClick={},enabled=false)
     else key(detail) {
      TokenOptions(site,detail,target="group",enabled=ready,onGroup={ group ->
       if(!busy && group!=detail.optString("group")) {
        val result=runCatching { TokenApi.optionsBody(detail,group,null) }
        result.fold({write("PUT","/api/token/",it)},{notice=TokenApi.safeError(it)})
       }
      },onModels={},onDismiss={options=null; optionId=null})
     }
    }
   }
   TextButton(enabled=ready,onClick={editOptions("models")},modifier=Modifier.fillMaxWidth()) {
    Text("模型："+when(t.modelLimitsEnabled) { null->"点击加载当前配置"; false->"不限制"; true->t.models?.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "点击加载当前配置" },maxLines=1,overflow=TextOverflow.Ellipsis)
   }
  }
  if(optionId==t.id && optionTarget=="models") options?.let { detail ->
   key(detail,optionTarget) {
    TokenOptions(site,detail,target=optionTarget,enabled=ready,onGroup={},onModels={ models ->
     val result=runCatching { TokenApi.optionsBody(detail,null,models) }
     result.fold({write("PUT","/api/token/",it)},{notice=TokenApi.safeError(it)})
    },onDismiss={options=null; optionId=null})
   }
  }
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   TextButton(enabled=ready,onClick={busy=true; notice=null; scope.launch { try {
    val r=withContext(Dispatchers.IO) { runCatching { TokenApi.detail(site,t.id) } }; r.fold({editor=it},{notice=TokenApi.safeError(it)})
   } finally { busy=false } }}) { Text("编辑") }
   TextButton(enabled=ready,onClick={write("PUT","/api/token/?status_only=true",JSONObject().put("id",t.id).put("status",if(t.status==1) 2 else 1).toString())}) { Text(if(t.status==1) "停用" else "启用") }
   TextButton(enabled=ready,onClick={deleting=t}) { Text("删除") }
  }
 } } }
 if(page?.items?.isEmpty()==true && fresh) Panel { Secondary(if(search.isEmpty()) "此账户尚无令牌" else "没有匹配的令牌") }
 page?.let { p -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
  OutlinedButton(enabled=!busy && fresh && p.page>1,onClick={reload(p.page-1)}) { Text("上一页") }
  OutlinedButton(enabled=!busy && fresh && p.hasNext,onClick={reload(p.page+1)}) { Text("下一页") }
 } }
 if(creating || editor!=null) TokenEditor(site,editor,onDismiss={creating=false; editor=null},onSave={draft -> val body=TokenApi.body(draft,editor); write(if(creating) "POST" else "PUT","/api/token/",body,creating) })
 deleting?.let { t -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除 ${t.name}？")},text={Text("删除后密钥将无法继续使用，此操作不可撤销。")},confirmButton={TextButton(onClick={write("DELETE","/api/token/${t.id}/")}) { Text("确认删除") }},dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }}) }
 verification?.let { token ->
  AlertDialog(onDismissRequest={if(!verifyBusy) verification=null},title={Text("邮箱安全验证")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Secondary("使用站点账户已绑定邮箱，不会保存验证码。")
   OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},singleLine=true,label={Text("6 位验证码")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!verifyBusy)
   TextButton(enabled=!verifyBusy,onClick={
    if(System.currentTimeMillis()<resendAt) verificationNotice="请稍后再发送验证码" else { verifyBusy=true; scope.launch { try {
     val r=withContext(Dispatchers.IO) { runCatching { TokenApi.checked(TokenApi.request(site,"/api/token/key_verification/send","POST")) } }
     r.fold({val sec=it.optJSONObject("data")?.optLong("can_resend_after",30) ?: 30; resendAt=System.currentTimeMillis()+sec.coerceIn(1,3600)*1000; verificationNotice="验证码已发送到绑定邮箱"},{verificationNotice=TokenApi.safeError(it)})
    } finally { verifyBusy=false } } }
   }) { Text("发送验证码") }
   verificationNotice?.let { Text(it) }
  }},confirmButton={TextButton(enabled=!verifyBusy && code.length==6,onClick={verifyBusy=true; scope.launch { try {
   val r=withContext(Dispatchers.IO) { runCatching { val j=TokenApi.checked(TokenApi.request(site,"/api/token/key_verification/verify","POST",JSONObject().put("code",code).toString())); require(j.getJSONObject("data").optBoolean("verified")); TokenApi.fullKey(site,token.id) } }
   r.fold({keyCache.put(site,token.id,it); clipboard.setText(AnnotatedString(it)); notice="密钥已复制到剪贴板，卡片保持隐藏"; verification=null; code=""},{verificationNotice="验证或密钥获取失败，请检查验证码是否有效、登录状态与邮箱权限"})
  } finally { verifyBusy=false } }}) { Text(if(verifyBusy) "处理中" else "验证并复制") }},dismissButton={TextButton(enabled=!verifyBusy,onClick={verification=null; code=""}) { Text("取消") }})
 }
}

@Composable private fun TokenEditor(site: Site,detail: JSONObject?,onDismiss:()->Unit,onSave:(TokenDraft)->Unit) {
 var name by remember { mutableStateOf(detail?.optString("name") ?: "") }
 var quota by remember { mutableStateOf(BigDecimal.valueOf(detail?.optLong("remain_quota") ?: 0).divide(BigDecimal.valueOf(site.unit),12,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) }
 var unlimited by remember { mutableStateOf(detail?.optBoolean("unlimited_quota") ?: false) }
 var never by remember { mutableStateOf(detail==null || detail.optLong("expired_time")==-1L) }
 var expiry by remember { mutableStateOf(detail?.optLong("expired_time")?.takeIf { it>0 }?.let { TokenApi.dateFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) } ?: "") }
 var error by remember { mutableStateOf<String?>(null) }
 AlertDialog(onDismissRequest=onDismiss,title={Text(if(detail==null) "创建令牌" else "编辑令牌")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
  OutlinedTextField(name,{name=it},label={Text("名称 · 最多 50 字节")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(quota,{quota=it},label={Text("剩余额度 · USD")},singleLine=true,enabled=!unlimited,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
  Row(verticalAlignment=Alignment.CenterVertically) { Switch(unlimited,{unlimited=it}); Spacer(Modifier.width(12.dp)); Text("不限额") }
  Secondary("令牌不限额仍受账户余额限制。额度设置为剩余额度，不会重置历史消耗。")
  Row(verticalAlignment=Alignment.CenterVertically) { Switch(never,{never=it}); Spacer(Modifier.width(12.dp)); Text("永不过期") }
  if(!never) { OutlinedTextField(expiry,{expiry=it},label={Text("yyyy-MM-dd HH:mm")},singleLine=true,modifier=Modifier.fillMaxWidth()); Secondary("按设备时区 ${ZoneId.systemDefault().id} 解释，可输入未来日期。") }
  if(detail==null) Secondary("按站点默认配置创建，创建后可点击令牌卡片的分组或模型修改。")
  if(detail!=null) Secondary("未修改的模型、分组、IP 与自动路由设置会完整保留。")
  error?.let { Text(it) }
 }},confirmButton={TextButton(onClick={
  val result=runCatching {
   // Retain the exact integer quota if the displayed USD value has not changed.
   val original=detail?.optLong("remain_quota")
   val shown=original?.let { BigDecimal.valueOf(it).divide(BigDecimal.valueOf(site.unit),12,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }
   val q=if(original!=null && quota==shown) original.coerceAtLeast(0) else if(unlimited) 0L else TokenApi.quota(quota,site.unit)
   val originalExpiry=detail?.optLong("expired_time")?.takeIf { it>System.currentTimeMillis()/1000 }
   val originalExpiryText=originalExpiry?.let { TokenApi.dateFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) }
   val expires=if(!never && originalExpiry!=null && expiry==originalExpiryText) originalExpiry else TokenApi.expiry(expiry,never)
   val d=TokenDraft(name.trim(),q,unlimited,expires); TokenApi.body(d,detail); d
  }
  result.fold(onSave,{error=it.message ?: "请检查输入"})
 }) { Text("保存") }},dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}
