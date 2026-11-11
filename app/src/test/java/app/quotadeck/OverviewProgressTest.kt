package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class OverviewProgressTest {
 @Test fun usedRatioMatchesDetail() {assertEquals(0.75f,overviewProgress(QueryState(balance=Balance(25.0,75.0,false,0)))!!,0.00001f)}
 @Test fun noDataHasNoRatio() {assertNull(overviewProgress(null))}
 @Test fun unlimitedHasNoRatio() {assertNull(overviewProgress(QueryState(balance=Balance(0.0,10.0,true,0))))}
 @Test fun failedQueryHasNoRatio() {assertNull(overviewProgress(QueryState(balance=Balance(25.0,75.0,false,0),error="failed")))}
 @Test fun zeroTotalHasNoRatio() {assertNull(overviewProgress(QueryState(balance=Balance(0.0,0.0,false,0))))}
 @Test fun exhaustedQuotaClampsAtOne() {assertEquals(1f,overviewProgress(QueryState(balance=Balance(-10.0,110.0,false,0)))!!,0f)}
}
