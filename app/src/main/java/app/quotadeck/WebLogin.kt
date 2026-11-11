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
import org.json.JSONObject

private const val ORIGIN="https://api.sbbbbbbbbb.xyz"
fun isNecoOrigin(value: String?): Boolean = runCatching {
 val u=java.net.URI(value ?: "")
 u.scheme=="https" && u.host=="api.sbbbbbbbbb.xyz" && (u.port==-1 || u.port==443) && u.userInfo==null
}.getOrDefault(false)
@SuppressLint("SetJavaScriptEnabled")
@Composable fun NecoLogin(onDismiss:()->Unit,onBound:(String,String,String)->Unit) {
 var web by remember { mutableStateOf<WebView?>(null) }; var error by remember { mutableStateOf<String?>(null) }
 var reading by remember { mutableStateOf(false) }
 DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy() } }
 Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
 Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
 Column(Modifier.safeDrawingPadding()) {
 Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
 TextButton(onClick=onDismiss) { Text("取消") }
 TextButton(enabled=!reading,onClick={
 val view=web
 if(view==null || !isNecoOrigin(view.url)) error="请在 Neco Api 站点完成登录" else {
 reading=true
 view.evaluateJavascript("(function(){try{var u=JSON.parse(localStorage.getItem('user')||'{}');return JSON.stringify({id:u.id||'',token:u.access_token||u.token||''})}catch(e){return '{}'}})()") { result ->
 reading=false
 if(!isNecoOrigin(view.url)) { error="页面地址已变化，请重试"; return@evaluateJavascript }
 runCatching {
 val decoded=org.json.JSONTokener(result).nextValue() as? String ?: "{}"
 val user=JSONObject(decoded); val id=user.optString("id"); val token=user.optString("token")
 val cookie=CookieManager.getInstance().getCookie(ORIGIN) ?: ""
 require((id.toLongOrNull() ?: 0)>0 && (cookie.isNotBlank() || token.isNotBlank()))
 onBound(id,token,cookie)
 }.onFailure { error="未读取到登录身份。请登录后进入控制台，再点完成绑定。" }
 }
 }
 }) {Text(if(reading) "读取中" else "完成绑定")}
 }
 Text("仅限 api.sbbbbbbbbb.xyz · 登录后点击完成绑定",modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
 error?.let {Text(it,modifier=Modifier.padding(16.dp))}
 AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={context ->
 WebView(context).apply {
 settings.javaScriptEnabled=true; settings.domStorageEnabled=true
 settings.allowFileAccess=false; settings.allowContentAccess=false
 settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
 CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
 webViewClient=object: WebViewClient() {
 override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean = !isNecoOrigin(request.url.toString())
 }
 web=this; loadUrl("$ORIGIN/login")
 }
 })
 }
 }
 }
}
