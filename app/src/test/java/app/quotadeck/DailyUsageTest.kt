package app.quotadeck
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
class DailyUsageTest {
 @Test fun readsDailyStatInsteadOfLifetimeOrWalletBalance() {
  assertEquals(172250.0,QuotaApi.parseTodayUsage("""{"success":true,"data":{"quota":172250,"used_quota":999999999,"rpm":2}}"""),0.0)
 }
 @Test fun zeroDailyUsageIsValid() { assertEquals(0.0,QuotaApi.parseTodayUsage("""{"success":true,"data":{"quota":0}}"""),0.0) }
 @Test(expected=IllegalArgumentException::class) fun rejectsFailureEvenWithQuota() { QuotaApi.parseTodayUsage("""{"success":false,"data":{"quota":0}}""") }
 @Test(expected=IllegalArgumentException::class) fun rejectsNegativeQuota() { QuotaApi.parseTodayUsage("""{"success":true,"data":{"quota":-1}}""") }
 @Test(expected=org.json.JSONException::class) fun missingQuotaIsNotZero() { QuotaApi.parseTodayUsage("""{"success":true,"data":{}}""") }
 @Test fun localMidnightAndCurrentSecond() {
  val now=Instant.parse("2026-11-11T10:20:30Z").toEpochMilli()
  val day=usageDay(now,ZoneId.of("Asia/Shanghai"))
  assertEquals("2026-11-11",day.date)
  assertEquals(Instant.parse("2026-11-10T16:00:00Z").epochSecond,day.start)
  assertEquals(now/1000,day.end)
 }
 @Test fun daylightSavingUsesCalendarDay() {
  val day=usageDay(Instant.parse("2026-03-09T03:59:59Z").toEpochMilli(),ZoneId.of("America/New_York"))
  assertEquals("2026-03-08",day.date)
  assertEquals(23*3600L-1,day.end-day.start)
 }
 @Test fun hidesYesterdayAndFormatsTodayToTwoDecimals() {
  val b=Balance(0.0,0.0,false,0,todayUsed=172250.0,todayDate="2026-11-11")
  assertEquals("$ 0.34",todayUsageAmount(b,500000.0,UsageDay("2026-11-11",0,0)))
  assertEquals("—",todayUsageAmount(b,500000.0,UsageDay("2026-11-12",0,0)))
  assertEquals("—",todayUsageAmount(b.copy(todayUsed=null),500000.0,UsageDay("2026-11-11",0,0)))
  assertEquals("—",todayUsageAmount(b.copy(todayError="统计失败"),500000.0,UsageDay("2026-11-11",0,0)))
 }
}
