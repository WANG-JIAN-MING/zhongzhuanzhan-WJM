package app.quotadeck

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp

fun orderedSites(current: List<Site>, ids: List<String>): List<Site> {
 require(ids.size==ids.toSet().size) { "排序包含重复站点" }
 val positions=ids.withIndex().associate { it.value to it.index }
 return current.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
}

@Composable fun Sites(state: AppState,onAdd:()->Unit,onEdit:(Site)->Unit,onReorder:(List<String>,(Boolean)->Unit)->Unit) {
 val listState=rememberLazyListState()
 var order by remember { mutableStateOf(state.sites) }
 var dragging by remember { mutableStateOf<String?>(null) }
 var center by remember { mutableFloatStateOf(0f) }
 var finger by remember { mutableFloatStateOf(0f) }
 var saving by remember { mutableStateOf(false) }
 val latestSites by rememberUpdatedState(state.sites)
 val saveOrder by rememberUpdatedState(onReorder)
 LaunchedEffect(state.sites,dragging,saving) { if(dragging==null && !saving) order=state.sites }
 fun moveAtCenter() {
  val id=dragging ?: return
  val target=listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key!=id && it.key is String && it.key in order.map { s -> s.id } && center>=it.offset && center<=it.offset+it.size } ?: return
  val from=order.indexOfFirst { it.id==id }; val to=order.indexOfFirst { it.id==target.key }
  val midpoint=target.offset+target.size/2f
  if(from>=0 && to>=0 && ((to>from && center>midpoint) || (to<from && center<midpoint))) order=order.toMutableList().apply { add(to,removeAt(from)) }
 }
 LaunchedEffect(dragging) {
  if(dragging!=null) {
   var previous=withFrameNanos { it }
   while(dragging!=null) {
    val now=withFrameNanos { it }; val seconds=((now-previous)/1_000_000_000f).coerceAtMost(0.05f); previous=now
    val info=listState.layoutInfo; val edge=100f
    val speed=when { finger<info.viewportStartOffset+edge -> -600f; finger>info.viewportEndOffset-edge -> 600f; else -> 0f }
    if(speed!=0f) { listState.scrollBy(speed*seconds); moveAtCenter() }
   }
  }
 }
 if(state.sites.isEmpty()) {
  Panel { Text("尚未添加站点",style=MaterialTheme.typography.titleLarge); Secondary("支持多个 New API 站点，凭据加密保存在本机。"); Button(onClick=onAdd) { Text("添加站点") } }
 } else {
  LazyColumn(state=listState,modifier=Modifier.fillMaxSize().pointerInput(Unit) {
   detectDragGesturesAfterLongPress(
    onDragStart={ point ->
     if(!saving) listState.layoutInfo.visibleItemsInfo.firstOrNull { point.y>=it.offset && point.y<=it.offset+it.size && order.any { s -> s.id==it.key } }?.let {
      dragging=it.key as String; center=it.offset+it.size/2f; finger=point.y
     }
    },
    onDrag={ change,amount -> if(dragging!=null) { change.consume(); center+=amount.y; finger=change.position.y; moveAtCenter() } },
    onDragEnd={
     if(dragging!=null) {
      val ids=order.map { it.id }; saving=true; dragging=null
      saveOrder(ids) { ok -> if(!ok) order=latestSites; saving=false }
     }
    },
    onDragCancel={ dragging=null; order=latestSites }
   )
  },verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=16.dp)) {
   item(key="sort_hint") { Secondary(if(saving) "正在保存顺序…" else "长按站点卡片上下拖动排序，松手保存") }
   items(order,key={it.id}) { site ->
    val active=dragging==site.id
    val info=listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key==site.id }
    Box(Modifier.fillMaxWidth().zIndex(if(active) 1f else 0f).graphicsLayer {
     translationY=if(active && info!=null) center-info.offset-info.size/2f else 0f
     shadowElevation=if(active) 12.dp.toPx() else 0f
     alpha=if(active) 0.92f else 1f
    }) {
     Panel(animateSize=false) { Row(verticalAlignment=Alignment.CenterVertically) {
      Text("☰",style=MaterialTheme.typography.titleLarge); Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) { Text(site.name,style=MaterialTheme.typography.titleMedium); Secondary(site.url); Secondary(if(site.subscription) "订阅当日 / 周期用量" else if(site.account) "用户管理凭据 · 账户余额" else "模型令牌 · 令牌额度") }
      TextButton(onClick={onEdit(site)},enabled=dragging==null && !saving) { Text("编辑") }
     } }
    }
   }
   item(key="add_site") { OutlinedButton(onClick=onAdd,enabled=dragging==null && !saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("添加站点") } }
  }
 }
}
