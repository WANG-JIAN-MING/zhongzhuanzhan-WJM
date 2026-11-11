package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
class SiteConfigTest {
 @Test fun topupUrlNormalizesToRoot() { assertEquals("https://api.sbbbbbbbbb.xyz",normalizeBase("https://api.sbbbbbbbbb.xyz/console/topup")) }
 @Test fun existingSubpathSurvives() { assertEquals("https://example.com/newapi",normalizeBase("https://example.com/newapi/")) }
 @Test fun necoPublicConfigUnit() { assertEquals(500000.0,QuotaApi.parseUnit("""{"success":true,"data":{"quota_display_type":"USD","quota_per_unit":500000,"price":0.03}}"""),0.0) }
 @Test(expected=IllegalArgumentException::class) fun zeroUnitRejected() { QuotaApi.parseUnit("""{"success":true,"data":{"quota_per_unit":0}}""") }
 @Test(expected=IllegalArgumentException::class) fun failedConfigRejected() { QuotaApi.parseUnit("""{"success":false,"data":{"quota_per_unit":500000}}""") }
}
