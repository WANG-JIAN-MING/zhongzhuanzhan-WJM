package app.quotadeck
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fullTime(seconds: Long)=if(seconds>0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date(seconds*1000)) else "—"
private fun advanceStatus(s: SubscriptionQuota,unit: Double): String = "提前领取状态："+when {
 s.advance>0 -> "已领取次日 $ ${amount(s.advance,unit)}"
 s.advanceMessage.isNotBlank() -> s.advanceMessage
 s.canAdvance -> "可领取次日额度"
 else -> "暂不可领取"
}
@Composable private fun SingleLineDetail(text: String) {
 val measurer=rememberTextMeasurer()
 val density=LocalDensity.current
 val style=MaterialTheme.typography.bodySmall.copy(fontSize=12.sp)
 BoxWithConstraints(Modifier.fillMaxWidth()) {
  val width=with(density) { maxWidth.toPx() }
  val measured=measurer.measure(text,style=style,softWrap=false).size.width
  val factor=if(measured>0) (width/measured).coerceAtMost(1f) else 1f
  Text(text,style=style.copy(fontSize=(12f*factor).sp),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,softWrap=false)
 }
}
@Composable fun SubscriptionPanel(site: Site,q: QueryState,onRefresh:(Site)->Unit,onAdvance:(Site,Long)->Unit,advancing: Boolean,blocked: Boolean,notice: String?) {
 var pending by remember { mutableStateOf<SubscriptionQuota?>(null) }
 val busy=q.loading || advancing
 Panel(spacing=10.dp) {
 notice?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
 Row(verticalAlignment=Alignment.CenterVertically) { LineIcon("额度"); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {Text(site.name,style=MaterialTheme.typography.titleMedium); Secondary("我的订阅 · 当日 / 周期用量")}; IconButton(onClick={onRefresh(site)},enabled=!busy) {LineIcon("刷新")} }
 if(q.loading) { Row(verticalAlignment=Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(12.dp)); Secondary("检测中") } }
 q.error?.let { Text(it); if(q.balance!=null) Secondary("以下为上次成功查询结果") }
 val balance=q.balance
 if(balance==null && !q.loading && q.error==null) Secondary("尚未查询")
 if(balance!=null) {
 if(balance.subscriptions.isEmpty()) Secondary("暂无生效中的订阅")
 balance.subscriptions.forEachIndexed { i,s ->
 if(i>0) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 Text(s.title,style=MaterialTheme.typography.titleLarge)
 Secondary("今日此订阅消耗 · USD")
 Text(if(q.error==null) subscriptionTodayAmount(s,site.unit) else "—",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
 if(!q.loading) {
  s.todayError?.let { Secondary(it) }
  if(s.todayError==null && s.todayDate!=usageDay().date) Secondary("今日消耗待刷新")
 }
 SingleLineDetail("生效中 · 有效期 ${fullTime(s.start)} 至 ${fullTime(s.end)}")
 Secondary("当前周期剩余 · USD")
 Text(if(s.total==0.0) "不限额" else "$ "+amount(s.remaining,site.unit),fontSize=32.sp,fontWeight=FontWeight.SemiBold)
 if(s.total>0) {
 LinearProgressIndicator(progress={(s.used/s.total).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(6.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("已用 $ "+amount(s.used,site.unit)); Text(String.format(Locale.US,"%.1f%%",s.used/s.total*100))}
 Secondary("本周期可用 $ ${amount(s.total,site.unit)} · 基础额度 $ ${amount(s.base,site.unit)}")
 }
 SingleLineDetail(advanceStatus(s,site.unit))
 SingleLineDetail((if(s.advance>0) "抵扣时间：${fullTime(s.advanceReset)} · " else "")+"下次额度刷新：${if(s.nextReset>0) fullTime(s.nextReset) else "不重置"}")
 Secondary("刷新方式：${if(s.resetMode=="calendar") "0 点刷新" else if(s.resetMode=="purchase_time") "按兑换时间刷新" else s.resetMode}")

 Button(onClick={pending=s},enabled=s.canAdvance && s.id>0 && !busy && !blocked && q.error==null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(advancing) "正在提交透支…" else "透支次日额度") }

 }
 Secondary("更新于 ${timestamp(balance.time)} · 时间按设备时区显示")
 }
 }
 pending?.let { selected ->
 val allowed=q.balance?.subscriptions?.any { it.id==selected.id && it.canAdvance }==true && !busy && !blocked && q.error==null
 AlertDialog(onDismissRequest={pending=null},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,title={Text("确认透支次日额度？")},text={Text("${selected.title}\n透支后会立即获得下一天的订阅额度，下一天不会重复发放。每次最多提前一天，订阅最后一天不能透支。实际额度与资格以站点为准。")},confirmButton={TextButton(enabled=allowed,onClick={pending=null; onAdvance(site,selected.id)}) {Text("确认透支")}},dismissButton={TextButton(onClick={pending=null}) {Text("取消")}})
 }
}
