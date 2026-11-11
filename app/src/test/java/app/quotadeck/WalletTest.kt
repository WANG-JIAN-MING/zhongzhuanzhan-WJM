package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class WalletTest {
 private val site=Site(name="Wallet",url="https://example.com",key="test",account=true,userId="8142")
 private val response="""{"success":true,"data":{"id":8142,"quota":843160000,"used_quota":3882730000,"total_available":1,"unlimited_quota":true}}"""
 @Test fun personalCurrentBalanceUsesQuotaOnly() {
 val b=QuotaApi.parse(site,response)
 assertEquals("1,686.32",amount(b.remaining,site.unit)); assertEquals("7,765.46",amount(b.used,site.unit)); assertFalse(b.unlimited); assertTrue(b.subscriptions.isEmpty())
 }
 @Test(expected=IllegalArgumentException::class) fun otherIdentityRejected() {QuotaApi.parse(site.copy(userId="999"),response)}
 @Test fun identityWithoutConfiguredIdStillSupported() {assertEquals(843160000.0,QuotaApi.parse(site.copy(userId=""),response).remaining,0.0)}
}
