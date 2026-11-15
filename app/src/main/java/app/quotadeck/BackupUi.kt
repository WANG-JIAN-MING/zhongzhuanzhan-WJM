package app.quotadeck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun BackupPanel(model: QuotaModel) {
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var busy by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf("") }
 var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
 var restored by remember { mutableStateOf<BackupData?>(null) }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  val bytes=exportBytes; exportBytes=null
  if(uri!=null && bytes!=null) scope.launch {
   busy=true
   val ok=withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) } }.isSuccess }
   bytes.fill(0); busy=false; message=if(ok) "备份已导出，请妥善保存文件" else "导出失败，请重新选择保存位置；未完成文件请删除"
  } else { bytes?.fill(0); busy=false }
 }
 val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri==null) busy=false else scope.launch {
   val result=withContext(Dispatchers.IO) { runCatching {
    context.contentResolver.openInputStream(uri)!!.use { input ->
     val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
     while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=BackupCodec.MAX_BYTES); out.write(buffer,0,n) }
     BackupCodec.decode(out.toByteArray())
    }
   } }
   busy=false
   result.fold({ restored=it },{ message="无法读取备份：文件损坏、超过 4 MB 或版本不支持。旧版加密备份不支持直接导入。" })
  }
 }
 Panel {
  Text("备份与恢复",style=MaterialTheme.typography.titleMedium)
  Secondary("备份包含站点地址、访问凭据、排列顺序和外观设置，文件不加密，可用于重装或换机。文件中包含明文凭据，请妥善保管。余额和查询缓存不备份。")
  Row(horizontalArrangement=Arrangement.spacedBy(androidx.compose.ui.unit.Dp(12f))) {
   Button(enabled=!busy && restored==null,onClick={ busy=true; message=""; scope.launch {
    try {
     val snapshot=model.backupSnapshot()
     exportBytes=withContext(Dispatchers.Default) { BackupCodec.encode(snapshot) }
     export.launch("QuotaDeck-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(java.util.Date())}.qdbackup")
    } catch(_: Exception) { busy=false; message="备份失败，请确认本地数据可读取" }
   } }) { Text("导出备份") }
   OutlinedButton(enabled=!busy && restored==null,onClick={ busy=true; message=""; pick.launch(arrayOf("*/*")) }) { Text("恢复备份") }
  }
  if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(message.isNotEmpty()) Text(message)
 }
 restored?.let { data -> AlertDialog(onDismissRequest={if(!busy) restored=null},title={Text("确认恢复备份")},text={Text("备份包含 ${data.sites.size} 个站点，将替换当前全部站点、凭据、顺序及外观设置。此操作不会修改远程账户。")},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch {
  val ok=model.restoreBackup(data); restored=null;busy=false;message=if(ok) "已恢复 ${data.sites.size} 个站点，请刷新查询最新额度" else "保存失败，未完成恢复，请重启应用后重试"
 }}){Text("替换并恢复")}},dismissButton={TextButton(enabled=!busy,onClick={restored=null}){Text("取消")}}) }
}
