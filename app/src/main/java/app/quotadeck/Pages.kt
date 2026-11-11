package app.quotadeck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URI
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NECO_BASE="https://api.sbbbbbbbbb.xyz"
fun normalizeBase(value: String): String {
 val uri=URI(value.trim())
 val path=(uri.path ?: "").trimEnd('/')
 val rootPath=if(path in listOf("/console/topup","/console","/login","/v1")) "" else path
 return URI(uri.scheme,uri.userInfo,uri.host,uri.port,rootPath,uri.query,uri.fragment).toString().trimEnd('/')
}

@Composable fun Sites(state: AppState,onAdd:()->Unit,onEdit:(Site)->Unit) {
 if(state.sites.isEmpty()) Panel { Text("尚未添加站点",style=MaterialTheme.typography.titleLarge); Secondary("支持多个 New API 站点，凭据加密保存在本机。"); Button(onClick=onAdd,modifier=Modifier.heightIn(min=48.dp)) { Text("添加站点") } }
 state.sites.forEach { s -> Panel { Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(vertical=3.dp)) { LineIcon("站点"); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(s.name,style=MaterialTheme.typography.titleMedium); Spacer(Modifier.height(1.dp)); Secondary(s.url); Secondary(if(s.subscription) "订阅当日 / 周期用量" else if(s.account) "用户管理凭据 · 账户余额" else "模型令牌 · 令牌额度") }; TextButton(onClick={onEdit(s)}) { Text("编辑") } } } }
 if(state.sites.isNotEmpty()) OutlinedButton(onClick=onAdd,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { LineIcon("添加"); Spacer(Modifier.width(12.dp)); Text("添加站点") }
}
@Composable fun Settings(state: AppState,onTheme:(String)->Unit) {
 Panel { Text("外观",style=MaterialTheme.typography.titleMedium)
 listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEachIndexed { index,pair ->
 if(index>0) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 Surface(onClick={onTheme(pair.first)},color=MaterialTheme.colorScheme.surface) { Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=state.theme==pair.first,onClick=null); Spacer(Modifier.width(12.dp)); Text(pair.second) } }
 }
 }
 Panel { Text("本地数据",style=MaterialTheme.typography.titleMedium); Secondary("站点地址和访问凭据使用 Android Keystore 加密保存，系统备份已关闭。应用只向你配置的站点发起额度查询。"); HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant); Text("额度换算",style=MaterialTheme.typography.titleMedium); Secondary("每站可从 /api/status 读取 1 USD 对应的 quota，也可手动填写。Neco Api 当前按 USD 展示，默认换算值 500000。令牌不限额不代表所属账户余额无限。"); Secondary("额度 1.0.18") }
}
@Composable fun SiteDialog(original: Site?,onDismiss:()->Unit,onSave:(Site,(String?)->Unit)->Unit,onDelete:(Site)->Unit) {
 var name by rememberSaveable { mutableStateOf(original?.name ?: "") }; var url by rememberSaveable { mutableStateOf(original?.url ?: "") }
 // Credentials deliberately stay out of saved instance state.
 var key by remember { mutableStateOf(original?.key ?: "") }
 var cookie by remember { mutableStateOf(original?.cookie ?: "") }; var cookieOrigin by remember { mutableStateOf(if(original?.cookie.isNullOrBlank()) "" else original!!.url) }; var webLogin by remember { mutableStateOf(false) }
 var queryMode by rememberSaveable { mutableStateOf(if(original?.subscription==true) "subscription" else if(original?.account==true) "account" else "token") }
 val account=queryMode!="token"
 val subscription=queryMode=="subscription"
 var userId by rememberSaveable { mutableStateOf(original?.userId ?: "") }
 var unit by rememberSaveable { mutableStateOf((original?.unit ?: 500000.0).toString().removeSuffix(".0")) }
 var error by remember { mutableStateOf<String?>(null) }; var saving by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
 val scope=rememberCoroutineScope(); var detecting by remember { mutableStateOf(false) }; var detected by remember { mutableStateOf<String?>(null) }
 AlertDialog(onDismissRequest={if(!saving) onDismiss()},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,title={Text(if(original==null) "添加站点" else "编辑站点")},text={
 Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
 if(original==null) OutlinedButton(onClick={name="Neco Api"; url=NECO_BASE; unit="500000"; detected=null},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("填入 Neco Api 地址（保留查询类型）")}
 OutlinedTextField(name,{name=it},label={Text("站点名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
 OutlinedTextField(url,{url=it},label={Text("站点地址 · HTTPS")},placeholder={Text("https://example.com")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
 Secondary("填写站点根地址，不含 /v1 或接口路径。")
 Column {
  FilterChip(selected=queryMode=="account",onClick={queryMode="account"},label={Text("个人中心当前余额")})
  FilterChip(selected=queryMode=="subscription",onClick={queryMode="subscription"},label={Text("订阅当日 / 周期用量")})
  FilterChip(selected=queryMode=="token",onClick={queryMode="token"},label={Text("令牌额度")})
 }
 Text("将保存："+when(queryMode) { "account" -> "个人中心当前余额"; "subscription" -> "订阅用量"; else -> "令牌额度" },style=MaterialTheme.typography.bodySmall)
 if(account && !subscription) Secondary("显示个人设置中的当前余额，读取用户账户 quota，不包含订阅额度或模型令牌限额。请使用与网页相同的账户登录。")
 if(subscription) Secondary("读取“我的订阅”的已用、剩余、提前领取与下次刷新时间。使用用户访问令牌和用户 ID，不是 sk- 模型密钥。")
 if(isNecoOrigin(url) && (account || subscription)) {
 OutlinedButton(onClick={webLogin=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Neco 网页登录 / 重新绑定") }
 Secondary(if(cookie.isNotBlank()) (if(subscription) "已绑定网页登录状态，保存后查询订阅" else "已绑定网页登录状态，保存后查询当前余额") else "推荐网页登录，自动绑定会话与用户 ID，无需手动寻找令牌。")
 }
 OutlinedTextField(key,{key=it},label={Text(if(account) "用户管理访问令牌" else "模型令牌 · sk-…")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
 if(account) { OutlinedTextField(userId,{userId=it},label={Text("用户 ID · 旧版兼容")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth()); Secondary("使用面板的用户管理凭据，不是模型调用密钥。新版站点用户 ID 可留空。") }
 OutlinedTextField(unit,{unit=it},label={Text("每 1 USD 对应 quota")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
 OutlinedButton(enabled=!detecting && !saving,onClick={
 val base=runCatching { normalizeBase(url) }.getOrNull()
 if(base==null) error="请输入有效的 HTTPS 站点地址" else { detecting=true; detected=null; scope.launch {
 val result=withContext(Dispatchers.IO) { runCatching { QuotaApi.publicUnit(base) } }
 result.fold({unit=it.toString().removeSuffix(".0"); detected="已读取站点换算值 · 余额以 USD 显示"; error=null},{error="读取失败，请检查网络、站点地址或手动填写"}); detecting=false
 } }
 },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(detecting) "检测中…" else "从站点读取额度换算") }
 detected?.let { Secondary(it) }
 Secondary("Neco Api 当前为 500000 quota / USD。充值价格与账户显示额度使用不同单位，请勿将支付汇率填在此处。")
 error?.let { Text(it) }
 if(original!=null) TextButton(onClick={confirmDelete=true},enabled=!saving) { Text("删除站点") }
 }
 },confirmButton={TextButton(enabled=!saving,onClick={
 val clean=runCatching { normalizeBase(url) }.getOrDefault(url.trim().trimEnd('/')); val parsed=runCatching { URI(clean) }.getOrNull(); val rate=unit.toDoubleOrNull(); val secret=key.trim().removePrefix("Bearer ")
 error=when { name.isBlank() -> "请输入站点名称"; parsed==null || parsed.scheme!="https" || parsed.host.isNullOrBlank() || parsed.userInfo!=null || parsed.query!=null || parsed.fragment!=null -> "请输入有效的 HTTPS 站点地址"; (secret.isBlank() && !(cookie.isNotBlank() && cookieOrigin==clean && (account || subscription))) || secret.any { it.isWhitespace() } -> "请完成网页登录或输入有效访问令牌"; userId.isNotBlank() && (userId.toLongOrNull() ?: 0)<=0 -> "用户 ID 应为正整数"; rate==null || !rate.isFinite() || rate<=0 -> "quota 换算值应大于 0"; else -> null }
 if(error==null) { saving=true; onSave(Site(original?.id ?: java.util.UUID.randomUUID().toString(),name.trim(),clean,secret,account,userId.trim(),rate!!,subscription,if(cookieOrigin==clean && (account || subscription)) cookie else "")) { error=it; saving=false } }
 }) { Text(if(saving) "保存中" else "保存并查询") }},dismissButton={TextButton(onClick=onDismiss,enabled=!saving) {Text("取消")}})
 if(webLogin) NecoLogin(onDismiss={webLogin=false},onBound={id,token,session -> userId=id; key=token; cookie=session; cookieOrigin=runCatching { normalizeBase(url) }.getOrDefault(url); webLogin=false; error=null })
 if(confirmDelete && original!=null) AlertDialog(onDismissRequest={confirmDelete=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,title={Text("删除 ${original.name}？")},text={Text("将移除此站点在本机保存的地址与凭据。")},confirmButton={TextButton(onClick={onDelete(original)}) {Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}) {Text("取消")}})
}
