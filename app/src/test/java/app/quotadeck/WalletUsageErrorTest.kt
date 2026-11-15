package app.quotadeck

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class WalletUsageErrorTest {
 @Test fun jsonErrorsDoNotExposeResponse() {
  val text=walletUsageError(JSONException("credential-sensitive-response"))
  assertTrue(text.contains("格式不兼容"))
  assertFalse(text.contains("credential-sensitive-response"))
 }
 @Test fun missingSourceIsDistinctFromPermission() {
  assertTrue(walletUsageError(IllegalArgumentException("站点未提供计费来源，无法单独统计钱包消费")).contains("计费来源"))
  assertTrue(walletUsageError(IllegalStateException("无访问权限，请检查用户 ID 或站点限制")).contains("访问权限"))
 }
 @Test fun unknownExceptionsDoNotExposeDetails() {
  assertFalse(walletUsageError(IllegalArgumentException("credential-sensitive-response")).contains("credential-sensitive-response"))
 }
 @Test fun businessRejectionIsClassifiedWithoutEchoingMessage() {
  val error=runCatching { walletTodayUsage(UsageDay("test",0,100)) { """{"success":false,"message":"未登录 credential-sensitive-response"}""" } }.exceptionOrNull()!!
  val text=walletUsageError(error)
  assertTrue(text.contains("重新网页登录"))
  assertFalse(text.contains("credential-sensitive-response"))
 }
}
