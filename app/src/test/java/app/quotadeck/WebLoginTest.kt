package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class WebLoginTest {
 @Test fun onlyExactHttpsOriginAllowed() {
 assertTrue(isNecoOrigin("https://api.sbbbbbbbbb.xyz/console/topup"))
 assertFalse(isNecoOrigin("https://api.sbbbbbbbbb.xyz.evil.example/"))
 assertFalse(isNecoOrigin("http://api.sbbbbbbbbb.xyz/"))
 assertFalse(isNecoOrigin("https://api.sbbbbbbbbb.xyz:444/"))
 assertFalse(isNecoOrigin("https://user@api.sbbbbbbbbb.xyz/"))
 }
 @Test fun userIdFailureExplained() {assertTrue(QuotaApi.authError("New-Api-User mismatch").contains("用户 ID"))}
 @Test fun loginFailureExplained() {assertTrue(QuotaApi.authError("无权进行此操作，未登录且未提供 access token").contains("登录状态"))}
}
