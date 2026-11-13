package app.quotadeck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Only reveal the action: releasing a swipe never deletes data.
internal fun clampedSiteSwipe(offset: Float,delta: Float,width: Float): Float =
 (offset+delta).coerceIn(-width,0f)
internal fun siteSwipeRevealed(offset: Float,width: Float): Boolean = offset<=-width/2f

@Composable fun SwipeSiteCard(site: Site,expanded: Boolean,onExpanded:(Boolean)->Unit,onDelete:()->Unit,content: @Composable ()->Unit) {
 val width=with(LocalDensity.current) { 96.dp.toPx() }
 var dragging by remember(site.id) { mutableStateOf(false) }
 var dragOffset by remember(site.id) { mutableFloatStateOf(0f) }
 val currentExpanded by rememberUpdatedState(expanded)
 val changeExpanded by rememberUpdatedState(onExpanded)
 val animated by animateFloatAsState(if(expanded) -width else 0f,label="site delete reveal")
 val offset=if(dragging) dragOffset else animated
 Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).semantics {
  stateDescription=if(expanded) "${site.name}，删除按钮已展开" else "${site.name}，可左滑显示删除按钮"
  customActions=listOf(
   CustomAccessibilityAction(if(expanded) "收起删除按钮" else "显示删除按钮") { onExpanded(!expanded); true },
   CustomAccessibilityAction("删除本地站点 ${site.name}") { onDelete(); true }
  )
 }.pointerInput(site.id,width) {
  // Horizontal touch slop arbitration lets the parent keep ordinary vertical scrolling.
  detectHorizontalDragGestures(
   onDragStart={dragOffset=if(currentExpanded) -width else 0f; dragging=true},
   onHorizontalDrag={change,delta->change.consume(); dragOffset=clampedSiteSwipe(dragOffset,delta,width)},
   onDragEnd={changeExpanded(siteSwipeRevealed(dragOffset,width)); dragging=false},
   onDragCancel={dragging=false}
  )
 }) {
  if(offset< -1f) {
   Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.matchParentSize()) {}
   TextButton(onClick=onDelete,modifier=Modifier.align(Alignment.CenterEnd).width(96.dp).heightIn(min=64.dp)) {
    Text("删除",color=MaterialTheme.colorScheme.onErrorContainer)
   }
  }
  Box(Modifier.absoluteOffset { IntOffset(offset.roundToInt(),0) }) { content() }
 }
}

@Composable fun SiteDeleteDialog(site: Site,onDismiss:()->Unit,onDelete:()->Unit) {
 AlertDialog(onDismissRequest=onDismiss,title={Text("删除 ${site.name}？")},
  text={Text("仅移除此站点在本机保存的地址、凭据与查询结果。不会删除远程令牌或站点账户。")},
  confirmButton={TextButton(onClick=onDelete) { Text("确认删除") }},
  dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}
