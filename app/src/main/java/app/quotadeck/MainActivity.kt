package app.quotadeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { val model: QuotaModel=viewModel(); val state by model.state.collectAsStateWithLifecycle(); QuotaTheme(state.theme) { QuotaApp(model,state) } } }
}
@Composable fun QuotaTheme(mode: String, content: @Composable ()->Unit) {
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val colors=if(dark) darkColorScheme(primary=Color(0xFFF0F1F3),onPrimary=Color(0xFF171719),background=Color(0xFF151517),onBackground=Color(0xFFF0F1F3),surface=Color(0xFF232326),onSurface=Color(0xFFF0F1F3),onSurfaceVariant=Color(0xFFA7A8B0),outline=Color(0xFF45454B),outlineVariant=Color(0xFF38383E),secondaryContainer=Color(0xFF333337),onSecondaryContainer=Color(0xFFF0F1F3),error=Color(0xFFF0F1F3))
 else lightColorScheme(primary=Color(0xFF171719),onPrimary=Color.White,background=Color(0xFFD1D2D6),onBackground=Color(0xFF171719),surface=Color(0xFFF0F1F3),onSurface=Color(0xFF171719),onSurfaceVariant=Color(0xFF65666D),outline=Color(0xFFB9BAC0),outlineVariant=Color(0xFFD3D4D9),secondaryContainer=Color(0xFFE1E2E6),onSecondaryContainer=Color(0xFF171719),error=Color(0xFF171719))
 MaterialTheme(colorScheme=colors, shapes=Shapes(small=RoundedCornerShape(16.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
@Composable fun LineIcon(kind: String, modifier: Modifier=Modifier, tint: Color=MaterialTheme.colorScheme.onSurface) {
 Canvas(modifier.size(22.dp)) { val u=size.width/24; val stroke=Stroke(1.5f*u); fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(tint,Offset(x*u,y*u),Offset(a*u,b*u),1.5f*u)
 when(kind) {
 "预览" -> { drawRoundRect(tint,Offset(3*u,4*u),Size(18*u,16*u),androidx.compose.ui.geometry.CornerRadius(3*u),style=stroke); line(3f,10f,21f,10f); line(10f,10f,10f,20f) }
 "额度" -> { drawRoundRect(tint,Offset(3*u,4*u),Size(18*u,16*u),androidx.compose.ui.geometry.CornerRadius(3*u),style=stroke); line(7f,9f,17f,9f); line(7f,14f,12f,14f) }
 "站点" -> { drawCircle(tint,9*u,Offset(12*u,12*u),style=stroke); drawOval(tint,Offset(8*u,3*u),Size(8*u,18*u),style=stroke); line(3f,12f,21f,12f) }
 "设置" -> { listOf(6f,12f,18f).forEachIndexed { i,y -> line(3f,y,21f,y); drawCircle(tint,2.2f*u,Offset((if(i==1)8 else 16)*u,y*u),style=stroke) } }
 "添加" -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
 "刷新" -> { drawArc(tint,40f,290f,false,Offset(4*u,4*u),Size(16*u,16*u),style=stroke); line(20f,3f,20f,9f); line(14f,9f,20f,9f) }
 else -> { val p=Path().apply { moveTo(8*u,4*u); lineTo(16*u,12*u); lineTo(8*u,20*u) }; drawPath(p,tint,style=stroke) }
 }
 }
}
private val MaterialThemeColorFallback=Color.Transparent
@Composable fun Panel(spacing: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.()->Unit) { Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(0.7.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(spacing),content=content) } }
@Composable fun Secondary(text: String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium) }
@Composable fun QuotaApp(model: QuotaModel,state: AppState) {
 var tab by rememberSaveable { mutableIntStateOf(0) }; var editing by remember { mutableStateOf<Site?>(null) }; var adding by rememberSaveable { mutableStateOf(false) }
 val scrolls=listOf(rememberScrollState(),rememberScrollState(),rememberScrollState(),rememberScrollState())
 BackHandler(enabled=tab!=0 && !adding && editing==null) { tab=0 }
 Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
 Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
 Surface(shape=RoundedCornerShape(50),color=MaterialTheme.colorScheme.surface,border=BorderStroke(0.7.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.widthIn(max=480.dp).fillMaxWidth()) {
 Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("预览","额度","站点","设置").forEachIndexed { i,title ->
 val selected=tab==i
 Surface(onClick={ tab=i },shape=RoundedCornerShape(50),color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,contentColor=if(selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f)) {
 Column(Modifier.heightIn(min=56.dp).padding(horizontal=4.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) { LineIcon(title,tint=LocalContentColor.current); Text(title,style=MaterialTheme.typography.labelMedium) }
 }
 } }
 }
 }
 }) { insets ->
 Column(Modifier.padding(insets).consumeWindowInsets(insets).fillMaxSize().verticalScroll(scrolls[tab]).padding(horizontal=16.dp).padding(top=20.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(listOf("预览","额度","站点","设置")[tab],fontSize=30.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.height(1.dp)); Secondary(listOf("New API · ${state.sites.size} 个站点","New API · ${state.sites.size} 个站点","管理地址与访问凭据","外观与本地数据")[tab]) }; if(tab<3) IconButton(onClick={ if(tab<=1 && state.sites.isNotEmpty()) model.refreshAll() else adding=true }) { LineIcon(if(tab<=1 && state.sites.isNotEmpty()) "刷新" else "添加") } }
 state.storageError?.let { Panel { Text(it) } }
 when(tab) {
 0 -> if(state.sites.isNotEmpty()) QuotaOverview(state) else Panel {
  Text("尚未添加站点",style=MaterialTheme.typography.titleLarge)
  Button(onClick={adding=true},modifier=Modifier.heightIn(min=48.dp)) { Text("添加站点") }
 }
 1 -> Home(state,onAdd={adding=true},onRefresh={model.refresh(it)},onAdvance=model::advance)
 2 -> Sites(state,onAdd={adding=true},onEdit={editing=it})
 3 -> Settings(state,onTheme=model::theme)
 }
 }
 }
 if(adding || editing!=null) SiteDialog(editing,onDismiss={adding=false; editing=null},onSave={ s,done -> model.save(s) { error -> if(error==null) { adding=false; editing=null }; done(error) } },onDelete={ model.delete(it); adding=false; editing=null })
}
fun amount(value: Double,unit: Double)=String.format(Locale.US,"%,.2f",value/unit)
fun timestamp(time: Long)=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(time))
@Composable fun Home(state: AppState,onAdd:()->Unit,onRefresh:(Site)->Unit,onAdvance:(Site,Long)->Unit) {
 if(state.sites.isEmpty()) { Panel { LineIcon("额度",Modifier.size(36.dp)); Text("添加你的第一个站点",style=MaterialTheme.typography.titleLarge); Secondary("连接 New API，查看令牌额度或账户余额。"); Button(onClick=onAdd,modifier=Modifier.heightIn(min=48.dp)) { Text("添加站点") } }; return }
 if(state.sites.any { !it.subscription }) Panel { Secondary("已查询的非订阅剩余额度 · USD"); val available=state.sites.filterNot { it.subscription }.mapNotNull { s -> state.queries[s.id]?.balance?.takeUnless { it.unlimited }?.let { it.remaining/s.unit } }; Text(if(available.isEmpty()) "—" else "$ "+String.format(Locale.US,"%,.2f",available.sum()),fontSize=34.sp,fontWeight=FontWeight.SemiBold); Secondary("${state.queries.values.count { it.balance!=null }} / ${state.sites.size} 个站点有查询结果 · 无限令牌不计入合计") }
 state.sites.sortedByDescending { it.subscription }.forEach { site -> val q=state.queries[site.id] ?: QueryState(); val b=q.balance
 if(site.subscription) { SubscriptionPanel(site,q,onRefresh,onAdvance,site.id in state.advancing,site.id in state.advanceBlocked,state.advanceNotices[site.id]); return@forEach }
 Panel {
 Row(verticalAlignment=Alignment.CenterVertically) { LineIcon("站点"); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(site.name,style=MaterialTheme.typography.titleMedium); Spacer(Modifier.height(1.dp)); Secondary(if(site.account) "账户余额" else "令牌额度") }; IconButton(onClick={onRefresh(site)},enabled=!q.loading) { LineIcon("刷新") } }
 Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Secondary(if(site.account) "当前余额 · USD" else "剩余 · USD"); Text(if(b==null) "—" else if(b.unlimited) "无限额度" else "$ "+amount(b.remaining,site.unit),fontSize=30.sp,fontWeight=FontWeight.SemiBold) }
 if(site.account) {
  Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Secondary("今日总消耗 · USD")
   Text(todayUsageAmount(b,site.unit),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
   Secondary("含钱包消费与订阅抵扣 · 按设备时区统计今日零点至本次查询")
   if(!q.loading && b!=null) {
    if(b.todayDate!=usageDay().date) Secondary("今日消耗待刷新")
    else b.todayError?.let { Secondary(it) }
   }
  }
 }
 HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Column { Secondary(if(site.account) "历史消耗 · USD" else "已使用 · USD"); Text(b?.let { "$ "+amount(it.used,site.unit) } ?: "—") }; Column(horizontalAlignment=Alignment.End) { Secondary("更新于"); Text(b?.let { timestamp(it.time) } ?: "尚未查询") } }
 if(b!=null && b.expires>0) Secondary("令牌到期：${timestamp(b.expires*1000)}")
 when { q.loading -> Row(verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.5.dp); Spacer(Modifier.width(12.dp)); Secondary("检测中") }; q.error!=null -> Text(q.error,style=MaterialTheme.typography.bodyMedium); b!=null -> Secondary("已授权") }
 if(q.error!=null && b!=null) Secondary("显示上次成功查询结果")
 }
 }
}
