package app.quotadeck

import org.json.JSONException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class WalletUsageFailure(val detail: String): IllegalArgumentException(detail)

// Only fixed application messages reach the UI; never include raw JSON or credentials.
fun walletUsageError(error: Throwable): String = "今日钱包消耗："+when(error) {
 is WalletUsageFailure -> error.detail
 is JSONException -> "消费明细格式不兼容，请核对日志字段（data/items/total/other）"
 is SocketTimeoutException -> "消费明细连接超时，请重试"
 is UnknownHostException -> "消费明细域名解析失败"
 is SSLException -> "消费明细安全连接失败"
 is IllegalStateException -> when {
  error.message=="未授权：请检查凭据" -> "消费明细未授权，请重新网页登录绑定"
  error.message=="无访问权限，请检查用户 ID 或站点限制" -> "没有消费明细访问权限，请检查用户 ID 或站点限制"
  error.message=="接口发生重定向，请填写最终站点地址" -> "日志接口发生重定向，请检查站点地址"
  error.message=="请求过于频繁，请稍后重试" -> "消费明细请求过于频繁，请稍后重试"
  error.message?.matches(Regex("服务器返回 HTTP [0-9]{3}"))==true -> error.message!!
  else -> "消费明细请求失败，请重试"
 }
 is IllegalArgumentException -> if(error.message in walletValidationMessages) error.message!! else "消费明细无法解析，请重试"
 else -> "消费明细查询失败，请检查网络后重试"
}

private val walletValidationMessages=setOf(
 "消费明细发生变化，请重新刷新", "消费明细不完整", "消费明细类型不匹配", "消费明细时间不匹配",
 "消费明细重复，请重新刷新", "钱包消费统计数值无效", "消费明细数量不匹配",
 "消费明细过多，无法完整统计钱包消费", "消费额度无效", "钱包扣款数值无效",
 "站点未提供计费来源，无法单独统计钱包消费", "接口响应过大"
)
