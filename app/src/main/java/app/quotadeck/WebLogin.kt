package app.quotadeck

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

internal fun isLoginOrigin(value: String?, origin: String): Boolean = runCatching {
 val u=URI(value ?: ""); val o=URI(origin)
 u.scheme=="https" && o.scheme=="https" && !u.host.isNullOrBlank() && u.host.equals(o.host,true) &&
 (if(u.port==-1) 443 else u.port)==(if(o.port==-1) 443 else o.port) && u.userInfo==null && o.userInfo==null
}.getOrDefault(false)
fun isNecoOrigin(value: String?): Boolean = isLoginOrigin(value,"https://api.sbbbbbbbbb.xyz")

private fun resetWebLoginSession(origin: String, onComplete: ()->Unit) {
 val cookies=webLoginSessionResetCookies(origin)
 if(cookies.isEmpty()) { onComplete(); return }
 val manager=CookieManager.getInstance()
 var pending=cookies.size
 var finished=false
 cookies.forEach { (url,value) -> manager.setCookie(url,value) {
  pending--
  if(pending==0 && !finished) { finished=true; manager.flush(); onComplete() }
 } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun SiteWebLogin(origin: String, onDismiss:()->Unit,onBound:(String,String,String)->Unit) {
 var web by remember { mutableStateOf<WebView?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 val modernSession=remember(origin) { isCookieSessionOrigin(origin) }
 var reading by remember { mutableStateOf(false) }
 var attached by remember(origin) { mutableStateOf(true) }
 var handedOff by remember(origin) { mutableStateOf(false) }
 var bindingCredentials by remember(origin) { mutableStateOf<Triple<String,String,String>?>(null) }
 val scope=rememberCoroutineScope()
 val observedToken=remember(origin) { java.util.concurrent.atomic.AtomicReference("") }
 DisposableEffect(origin) { onDispose { web?.stopLoading(); web?.destroy() } }
 LaunchedEffect(bindingCredentials,handedOff) {
  val credentials=bindingCredentials ?: return@LaunchedEffect
  if(!handedOff) return@LaunchedEffect
  val (id,token,cookie)=credentials
  var bound=Site(name="登录验证",url=origin,key=token,account=true,userId=id,cookie=cookie)
  val verified=withContext(Dispatchers.IO) { runCatching {
   bound=prepareSessionBinding(bound)
   QuotaApi.fetch(bound) { bound=it }
  } }
  // The WebView no longer owns this rotating cookie. Saved sites keep their
  // credentials in Vault; a later web login starts a separate server session.
  resetWebLoginSession(origin) {}
  bindingCredentials=null; reading=false
  verified.fold({onBound(bound.userId,bound.key,bound.cookie)}, {failure ->
   error=(failure as? SessionRenewalException)?.message ?: "未能验证账户钱包，请重新登录后重试。"
  })
 }
 Dialog(onDismissRequest={if(!reading) onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
 Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
 Column(Modifier.safeDrawingPadding()) {
 Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
 TextButton(onClick=onDismiss,enabled=!reading) { Text("取消") }
 TextButton(enabled=!reading,onClick={
 val view=web
 if(!attached) { error=null; observedToken.set(""); handedOff=false; attached=true }
 else if(view==null || !isLoginOrigin(view.url,origin)) error="请返回本站并完成登录" else {
 reading=true; error=null
 view.evaluateJavascript("(function(){try{var u=JSON.parse(localStorage.getItem('user')||sessionStorage.getItem('user')||'{}');u=u.data||u;return JSON.stringify({id:u.id||'',token:u.access_token||u.token||''})}catch(e){return '{}'}})()") { result ->
 if(!isLoginOrigin(view.url,origin)) { reading=false; error="页面地址已变化，请重试"; return@evaluateJavascript }
 val credentials=runCatching {
 val decoded=org.json.JSONTokener(result).nextValue() as? String ?: "{}"
 val user=JSONObject(decoded); val id=user.optString("id"); val token=user.optString("token").ifBlank { observedToken.get() }
 val manager=CookieManager.getInstance()
 // A refresh cookie may be scoped to /api/user/auth and absent at the root URL.
 val cookie=mergeCookieHeaders(manager.getCookie(origin).orEmpty(),manager.getCookie("$origin/api/user/auth/refresh").orEmpty())
 manager.flush()
 require(cookie.isNotBlank() || token.isNotBlank())
 Triple(id,token,cookie)
 }.getOrNull()
 if(credentials==null) {reading=false; error="未读取到登录状态。请登录并进入个人中心，再点完成绑定。"}
 else if(modernSession) {
  // Expire the opaque browser cookie as well, so the webview cannot retain
  // a stale bearer across successful binding or a corrected re-login attempt.
  view.stopLoading(); view.loadUrl("about:blank"); attached=false
  resetWebLoginSession(origin) {}
  bindingCredentials=credentials
 } else scope.launch {
 val (id,token,cookie)=credentials
 var bound=Site(name="登录验证",url=origin,key=token,account=true,userId=id,cookie=cookie)
 val verified=withContext(Dispatchers.IO) { runCatching {
 QuotaApi.fetch(bound) { bound=it }
 } }
 reading=false
 verified.fold({onBound(bound.userId,bound.key,bound.cookie)}, {error="未能验证账户钱包：登录可能已过期，或本站不兼容 New API / One API 账户接口。可返回重试或使用手动凭据。"})
 }
 }
 }
 }) {Text(if(reading) "验证中…" else if(!attached) "重新登录" else "完成绑定")}
 }
 Text("${URI(origin).host} · 登录后点击完成绑定",modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
 error?.let {Text(it,modifier=Modifier.padding(16.dp))}
 if(attached) AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={context ->
 WebView(context).apply {
 settings.javaScriptEnabled=true; settings.domStorageEnabled=true
 settings.allowFileAccess=false; settings.allowContentAccess=false
 settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
 CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
 webViewClient=object: WebViewClient() {
 override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
 if(isLoginOrigin(request.url.toString(),origin) && request.url.path=="/api/user/self") {
 val auth=request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization",true) }?.value.orEmpty()
 if(auth.startsWith("Bearer ",true)) observedToken.set(auth.substring(7).trim())
 }
 return null
 }
 override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean {
 val allowed=isLoginOrigin(request.url.toString(),origin)
 if(!allowed && request.isForMainFrame) error="此链接离开当前站点。第三方登录暂不支持自动回传，请使用本站登录方式或手动凭据。"
 return !allowed
 }
 }
 val view=this
 web=view
 resetWebLoginSession(origin) { if(web===view && attached) view.loadUrl("$origin/login") }
 }
 },onRelease={view ->
  view.stopLoading(); view.destroy()
  if(web===view) web=null
  if(modernSession) handedOff=true
 },update={})
 }
 }
 }
}
