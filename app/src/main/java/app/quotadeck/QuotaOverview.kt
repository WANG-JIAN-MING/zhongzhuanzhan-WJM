package app.quotadeck

import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview

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
  AnimatedVisibility(visible=state.sites.any { state.queries[it.id]?.loading==true },enter=fadeIn(tween(160))+expandVertically(tween(200)),exit=fadeOut(tween(120))+shrinkVertically(tween(180))) {
   Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
    CircularProgressIndicator(modifier=Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.primary)
    Text("正在刷新…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  state.sites.forEach { site ->
   Column(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
    if(site.account && !site.subscription) AccountOverviewRow(site,state.queries[site.id]) else if(site.subscription) SubscriptionOverview(site,state.queries[site.id]) else {
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      Text(site.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
      GentleValue(overviewAmount(site,state.queries[site.id]),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.SemiBold))
     }
     val progress=overviewProgress(state.queries[site.id])
     val animatedProgress by animateFloatAsState(progress ?: 0f,tween(260),label="quota progress")
     LinearProgressIndicator(progress={animatedProgress},modifier=Modifier.fillMaxWidth().height(6.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
    }
   }
  }
  if(state.sites.any { it.account && !it.subscription }) {
   Text("个人中心今日消耗仅统计钱包扣款，不含订阅抵扣",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   state.sites.filter { it.account && !it.subscription }.forEach { site ->
    state.queries[site.id]?.balance?.todayError?.let { error ->
     Text("${site.name}：$error",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
   }
  }
 }
}

fun subscriptionWalletAmount(query: QueryState?, unit: Double): String {
 if(query?.error!=null) return "—"
 val balance=query?.balance ?: return "—"
 return balance.walletRemaining?.takeIf { balance.walletError==null }?.let { "$"+amount(it,unit) } ?: "—"
}

fun subscriptionWalletTodayAmount(query: QueryState?, unit: Double, day: UsageDay=usageDay()): String {
 if(query?.error!=null) return "—"
 val balance=query?.balance ?: return "—"
 return balance.walletTodayUsed?.takeIf { balance.walletTodayError==null && balance.walletTodayDate==day.date }?.let { "$"+amount(it,unit) } ?: "—"
}

@Composable private fun SubscriptionSummaryRow(middleWidth: Dp, rightWidth: Dp, left: String, middle: String, right: String, style: TextStyle, label: Boolean=false) {
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
  val nameMeasurer=rememberTextMeasurer()
  val nameDensity=LocalDensity.current
  val namePixels=nameMeasurer.measure(left,style=style,softWrap=false).size.width
  val availablePixels=with(nameDensity) { rightWidth.toPx() }
  val nameScale=if(namePixels>0) (availablePixels/namePixels).coerceIn(.1f,1f) else 1f
  Text(left,Modifier.width(rightWidth),style=style.copy(fontSize=(style.fontSize.value*nameScale).sp),maxLines=1,softWrap=false)
  val color=if(label) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
  Text(middle,Modifier.width(middleWidth),style=style,color=color,maxLines=1,softWrap=false,textAlign=TextAlign.Center)
  Text(right,Modifier.width(rightWidth),style=style,color=color,maxLines=1,softWrap=false,textAlign=TextAlign.End)
 }
}

@Composable private fun SubscriptionOverview(site: Site, query: QueryState?) {
 val balance=overviewAmount(site,query).replace("$ ","$")
 val walletText=subscriptionWalletAmount(query,site.unit)
 val walletToday=subscriptionWalletTodayAmount(query,site.unit)
 val subs=query?.balance?.subscriptions.orEmpty()
 val dailyTexts=subs.map { if(query?.error==null) subscriptionTodayAmount(it,site.unit).replace("$ ","$") else "—" }
 val style=MaterialTheme.typography.bodySmall
 val measurer=rememberTextMeasurer()
 val density=LocalDensity.current
 // Allocate by measured amount width, then use these exact columns for every row.
 // Symmetric side columns keep the wallet centered; names shrink to fit one line.
 BoxWithConstraints(Modifier.fillMaxWidth()) {
  fun textWidth(text: String)=with(density) { measurer.measure(text,style=style,softWrap=false).size.width.toDp() }
  val naturalMiddle=maxOf(textWidth(walletText),textWidth(walletToday),60.dp)+2.dp
  val naturalRight=(listOf(balance,"剩余额度")+dailyTexts).maxOf { textWidth(it) }+2.dp
  val available=(maxWidth-12.dp).coerceAtLeast(1.dp)
  val sideNatural=maxOf(naturalRight,60.dp)
  val scale=(available/(sideNatural*2+naturalMiddle)).coerceIn(.1f,1f)
  val rightWidth=sideNatural*scale
  val middleWidth=(available-rightWidth*2).coerceAtLeast(1.dp)
  val fittedStyle=style.copy(fontSize=(style.fontSize.value*scale).sp)
  Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
   SubscriptionSummaryRow(middleWidth,rightWidth,"站点名称","钱包","剩余额度",fittedStyle,label=true)
   SubscriptionSummaryRow(middleWidth,rightWidth,site.name,walletText,balance,fittedStyle)
   if(subs.isEmpty()) {
    SubscriptionSummaryRow(middleWidth,rightWidth,"",walletToday,"",fittedStyle)
   } else subs.forEachIndexed { index,sub ->
    SubscriptionSummaryRow(middleWidth,rightWidth,sub.title,if(index==0) walletToday else "",dailyTexts[index],fittedStyle)
   }
   if(query?.error==null) {
    query?.balance?.walletError?.let { Text(it,style=style,color=MaterialTheme.colorScheme.error) }
    query?.balance?.walletTodayError?.let { Text(it,style=style,color=MaterialTheme.colorScheme.error) }
    subs.mapNotNull { it.todayError }.distinct().forEach { Text(it,style=style,color=MaterialTheme.colorScheme.error) }
   }
   val progress=overviewProgress(query)
   if(progress!=null) LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().padding(top=2.dp).height(6.dp),color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant)
  }
 }
}

@Preview(name="Subscription 320dp",widthDp=320,showBackground=true)
@Composable private fun SubscriptionOverviewPreview() {
 val day=usageDay().date
 val sub=SubscriptionQuota("Pro月度套餐",10000.0,10000.0,8200.0,0.0,0,0,0,0,"calendar","",id=1,todayUsed=215.0,todayDate=day)
 MaterialTheme { Surface { Box(Modifier.padding(20.dp)) {
  SubscriptionOverview(Site(name="Claude服务",url="https://example.invalid",key="",subscription=true,unit=100.0),QueryState(balance=Balance(1800.0,8200.0,false,0,subscriptions=listOf(sub),walletRemaining=20000.0,walletTodayUsed=342.0,walletTodayDate=day)))
 } } }
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
   GentleValue(dailyText,style=dailyStyle.copy(fontSize=(dailyStyle.fontSize.value*factor).sp),color=if(daily=="—") MaterialTheme.colorScheme.onSurfaceVariant else red)
   GentleValue(balance,style=balanceStyle.copy(fontSize=(balanceStyle.fontSize.value*factor).sp))
  }
 }
}
