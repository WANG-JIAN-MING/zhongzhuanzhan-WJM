package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AdvanceTest {
 @Test fun bodyUsesExactSubscriptionId() { val j=JSONObject(QuotaApi.advanceBody(123)); assertEquals(123L,j.getLong("subscription_id")); assertEquals(1,j.length()) }
 @Test(expected=IllegalArgumentException::class) fun invalidIdRejected() { QuotaApi.advanceBody(0) }
 @Test fun successAccepted() { QuotaApi.checkAdvanceResponse("""{"success":true}""") }
 @Test(expected=IllegalStateException::class) fun http200BusinessFailureRejected() { QuotaApi.checkAdvanceResponse("""{"success":false,"message":"already advanced"}""") }
 @Test(expected=IllegalStateException::class) fun missingSuccessRejected() { QuotaApi.checkAdvanceResponse("""{"message":"ok"}""") }
 @Test fun serverEligibilityAndIdParsed() {
 val b=QuotaApi.parseSubscriptions("""{"success":true,"data":{"subscriptions":[{"can_advance_quota":true,"subscription":{"id":42,"status":"active","amount_total":100,"amount_used":20}},{"subscription":{"id":43,"status":"active","amount_total":100,"amount_used":10}}]}}""")
 assertEquals(42L,b.subscriptions[0].id); assertTrue(b.subscriptions[0].canAdvance); assertFalse(b.subscriptions[1].canAdvance)
 }
}
