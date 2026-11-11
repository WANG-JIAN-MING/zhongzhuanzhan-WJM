package app.quotadeck

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

fun overviewAmount(site: Site,query: QueryState?): String {
 // Do not show a stale value as the current amount after a failed refresh.
 if(query?.error!=null) return "—"
 val balance=query?.balance ?: return "—"
 return if(balance.unlimited) "不限额" else "$ "+amount(balance.remaining,site.unit)
}

fun overviewProgress(query: QueryState?): Float? {
 if(query?.error!=null) return null
 val b=query?.balance ?: return null
 if(b.unlimited) return null
 val total=if(b.subscriptions.isNotEmpty()) b.subscriptions.sumOf { it.total } else b.remaining+b.used
 if(!total.isFinite() || total<=0 || !b.used.isFinite()) return null
 return (b.used/total).toFloat().coerceIn(0f,1f)
}

@Composable fun QuotaOverview(state: AppState) {
 Panel(spacing=10.dp) {
  if(state.sites.any { state.queries[it.id]?.loading==true }) {
   Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.primary)
    Text("正在刷新…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  state.sites.forEach { site ->
   Column(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    if(site.account && !site.subscription) AccountOverviewRow(site,state.queries[site.id]) else {
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      Text(site.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
      Text(overviewAmount(site,state.queries[site.id]),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
     }
     val progress=overviewProgress(state.queries[site.id])
     LinearProgressIndicator(progress={progress ?: 0f},modifier=Modifier.fillMaxWidth().height(6.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
    }
   }
  }
  if(state.sites.any { it.account && !it.subscription }) {
   Text("今日消耗含订阅抵扣",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable private fun AccountOverviewRow(site: Site, query: QueryState?) {
 val daily=if(query?.error==null) todayUsageAmount(query?.balance,site.unit) else "—"
 val dailyText="今日 $daily"
 val balance=overviewAmount(site,query)
 val dailyStyle=MaterialTheme.typography.bodySmall
 val balanceStyle=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.SemiBold)
 val measurer=rememberTextMeasurer()
 val density=LocalDensity.current
 val red=if(MaterialTheme.colorScheme.surface.luminance()<0.5f) Color(0xFFFF8A80) else Color(0xFFB3261E)
 BoxWithConstraints(Modifier.fillMaxWidth()) {
  val amountWidth=measurer.measure(dailyText,style=dailyStyle,softWrap=false).size.width+measurer.measure(balance,style=balanceStyle,softWrap=false).size.width
  val available=with(density) { (maxWidth-56.dp).toPx() }.coerceAtLeast(1f)
  val factor=if(amountWidth>0) (available/amountWidth).coerceIn(0.1f,1f) else 1f
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   Text(site.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
   Text(dailyText,style=dailyStyle.copy(fontSize=(dailyStyle.fontSize.value*factor).sp),color=if(daily=="—") MaterialTheme.colorScheme.onSurfaceVariant else red,maxLines=1,softWrap=false)
   Text(balance,style=balanceStyle.copy(fontSize=(balanceStyle.fontSize.value*factor).sp),maxLines=1,softWrap=false)
  }
 }
}
