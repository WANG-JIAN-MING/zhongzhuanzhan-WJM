package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class WalletSourceTest {
 @Test fun walletNeverUsesSubscriptionOrTokenAmount() {
 val site=Site(name="钱包",url="https://example.com",key="test",account=true,subscription=false)
 val b=QuotaApi.parse(site,"""{"success":true,"data":{"quota":843160000,"used_quota":3882730000,"total_available":172250,"subscriptions":[{"amount_total":200000000,"amount_used":199827750}]}}""")
 assertEquals("$ 1,686.32",overviewAmount(site,QueryState(balance=b)))
 assertTrue(b.subscriptions.isEmpty())
 }
}
