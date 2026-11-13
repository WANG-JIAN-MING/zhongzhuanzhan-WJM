package app.quotadeck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun TokenOptions(site: Site,detail: JSONObject?,onGroup:(String)->Unit,onModels:(List<String>)->Unit,target: String,enabled: Boolean=true,onDismiss:()->Unit={}) {
 var groups by remember { mutableStateOf<List<TokenGroup>>(emptyList()) }
 var models by remember { mutableStateOf<List<String>>(emptyList()) }
 var error by remember { mutableStateOf<String?>(null) }
 var loading by remember { mutableStateOf(false) }
 val selectedGroup=detail?.optString("group") ?: ""
 var selectedModels by remember { mutableStateOf(TokenApi.modelSelection(detail)) }
 var restricted by remember { mutableStateOf(detail?.optBoolean("model_limits_enabled") ?: false) }
 var changed by remember { mutableStateOf(false) }
 var search by remember { mutableStateOf("") }
 val scope=rememberCoroutineScope()
 fun load() {
  if(loading) return
  loading=true; error=null
  scope.launch { try {
   if(target=="group") {
    val result=withContext(Dispatchers.IO) { runCatching { TokenApi.groups(site) } }
    result.fold({groups=it},{error="分组加载失败，已保留当前配置。"+TokenApi.safeError(it)})
   } else {
    val result=withContext(Dispatchers.IO) { runCatching { TokenApi.models(site) } }
    result.fold({models=it},{error="模型加载失败，已保留当前配置。"+TokenApi.safeError(it)})
   }
  } finally { loading=false } }
 }
 LaunchedEffect(site,target) { load() }
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  if(target=="group") {
   Box(Modifier.fillMaxWidth().height(4.dp)) {
    if(loading) LinearProgressIndicator(Modifier.fillMaxSize())
   }
  } else if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Secondary(it) }
  OutlinedTextField(search,{search=it},label={Text(if(target=="group") "搜索分组" else "搜索模型")},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
  if(target=="group") {
   if(selectedGroup=="auto" && detail?.optJSONArray("auto_groups")?.length()?.let { it>0 }==true) Secondary("原自动路由顺序："+(0 until detail.getJSONArray("auto_groups").length()).joinToString(" → ") { detail.getJSONArray("auto_groups").getString(it) })
   val entries=(listOf(TokenGroup("","默认 · 跟随用户分组"))+groups+if(selectedGroup.isNotEmpty() && groups.none { it.value==selectedGroup }) listOf(TokenGroup(selectedGroup,"$selectedGroup · 原值，当前列表未返回")) else emptyList()).distinctBy { it.value }.filter { it.label.contains(search,true) || it.value.contains(search,true) }
   entries.forEach { g ->
    DropdownMenuItem(enabled=enabled,onClick={if(g.value==selectedGroup) onDismiss() else onGroup(g.value)},
     leadingIcon={RadioButton(selectedGroup==g.value,onClick=null,enabled=enabled)},
     text={Text(if(g.value=="auto") "auto · 继承全局自动分组" else g.label+if(g.value.isNotEmpty() && g.label!=g.value) " (${g.value})" else "")})
   }
   Secondary("点选分组立即保存；保持当前分组将保留原自动路由配置。")
  } else {
   Row(verticalAlignment=Alignment.CenterVertically) {
    Switch(!restricted,{restricted=!it; changed=true},enabled=enabled)
    Spacer(Modifier.width(8.dp)); Text("不限制模型")
   }
   Secondary(if(restricted) "已选 ${selectedModels.size} 个："+selectedModels.joinToString("、") else "当前不限制模型")
   val entries=(selectedModels+models).distinct().filter { it.contains(search,true) }
   LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
    items(entries,key={it}) { model ->
     val checked=restricted && model in selectedModels
     Row(Modifier.fillMaxWidth().clickable(enabled=enabled) {
      selectedModels=if(checked) selectedModels-model else (selectedModels+model).distinct()
      restricted=selectedModels.isNotEmpty(); changed=true
     }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
      Checkbox(checked,onCheckedChange=null,enabled=enabled); Spacer(Modifier.width(8.dp)); Text(model+if(model !in models) " · 原值，当前列表未返回" else "")
     }
    }
    if(entries.isEmpty()) item { Secondary(if(loading) "正在获取模型" else "没有匹配模型；可刷新重试，原配置仍保留。") }
   }
   Secondary("模型多选后点击完成保存；空选择按站点协议表示不限制。")
  }
  Row {
   TextButton(enabled=enabled && !loading,onClick={load()}) { Text("刷新选项") }
   if(target=="models") TextButton(enabled=enabled,onClick={if(changed) onModels(if(restricted) selectedModels else emptyList()) else onDismiss()}) { Text("完成") }
   TextButton(enabled=enabled,onClick=onDismiss) { Text(if(target=="models") "取消" else "收起") }
  }
 }
}
