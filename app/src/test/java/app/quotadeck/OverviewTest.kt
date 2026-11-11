package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class OverviewTest {
 private val site=Site(name="自定义名称",url="https://example.com",key="test",subscription=true)
 @Test fun remainingUsesSiteUnit() { assertEquals("$ 0.34",overviewAmount(site,QueryState(balance=Balance(172250.0,0.0,false,0)))) }
 @Test fun unknownNotAssumedZero() { assertEquals("—",overviewAmount(site,null)) }
 @Test fun failedRefreshHidesStaleAmount() { assertEquals("—",overviewAmount(site,QueryState(balance=Balance(500000.0,0.0,false,0),error="failed"))) }
 @Test fun unlimitedNotConvertedToMoney() { assertEquals("不限额",overviewAmount(site,QueryState(balance=Balance(0.0,0.0,true,0)))) }
}
