package app.quotadeck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

@Composable
internal fun UsageLogScreen(sites: List<Site>, initialSiteId: String? = null, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var selectedId by rememberSaveable(initialSiteId) { mutableStateOf(initialSiteId) }
    var selecting by remember { mutableStateOf(false) }
    val site = sites.firstOrNull { it.id == selectedId } ?: sites.firstOrNull()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(
            Modifier.padding(insets).consumeWindowInsets(insets).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("使用日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (site == null) {
                Panel { Text("请先添加站点") }
            } else {
                Box {
                    OutlinedButton(onClick = { selecting = true }) { Text("站点 · ${site.name}") }
                    DropdownMenu(expanded = selecting, onDismissRequest = { selecting = false }) {
                        sites.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = { selectedId = candidate.id; selecting = false }
                            )
                        }
                    }
                }
                key(site) { UsageLogList(site, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun UsageLogList(site: Site, modifier: Modifier) {
    var page by remember { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<UsageLogPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val account = site.account || site.subscription

    LaunchedEffect(page, refresh) {
        loading = true
        result = null
        error = null
        try {
            result = withContext(Dispatchers.IO) { UsageLogApi.fetch(site, page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = usageLogError(failure)
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Secondary(if (account) "账户消费记录" else "当前令牌的近期消费记录") }
            TextButton(onClick = { page = 1; refresh++ }, enabled = !loading) { Text("刷新") }
        }
        val current = result
        val errorText = error
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp))
            }
            errorText != null -> Panel {
                Text(errorText)
                OutlinedButton(onClick = { refresh++ }) { Text("重试") }
            }
            current != null -> {
                LazyColumn(
                    Modifier.weight(1f), state = key(page, refresh) { rememberLazyListState() },
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (current.entries.isEmpty()) item { Panel { Text("暂无消费记录") } }
                    items(current.entries) { entry -> UsageLogCard(entry, site.unit) }
                }
                if (page > 1 || current.hasMore) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page-- }, enabled = page > 1) { Text("上一页") }
                        Secondary("第 $page 页")
                        TextButton(onClick = { page++ }, enabled = current.hasMore) { Text("下一页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLogCard(entry: UsageLogEntry, unit: Double) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Panel(spacing = 6.dp, animateSize = false, contentPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.modelName.ifBlank { "未知模型" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(entry.quota?.let { "$ " + amount(it.toDouble(), unit) } ?: "—", style = MaterialTheme.typography.titleSmall)
        }
        val time = if (entry.createdAt > 0 && entry.createdAt <= Long.MAX_VALUE / 1000) timestamp(entry.createdAt * 1000) else "—"
        Secondary(listOfNotNull(time, entry.tokenName.takeIf { it.isNotBlank() }, entry.useTime?.let { "${it}s" }).joinToString(" · "))
        val input = entry.promptTokens?.let { numbers.format(it) } ?: "—"
        val output = entry.completionTokens?.let { numbers.format(it) } ?: "—"
        Secondary("输入 $input · 输出 $output tokens")
    }
}
