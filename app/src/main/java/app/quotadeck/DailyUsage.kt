package app.quotadeck

import java.time.Instant
import java.time.ZoneId

// Calendar-day boundaries use the device timezone, including DST transitions.
data class UsageDay(val date: String, val start: Long, val end: Long)
fun usageDay(nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): UsageDay {
 val date=Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
 return UsageDay(date.toString(),date.atStartOfDay(zone).toEpochSecond(),nowMillis/1000)
}
fun todayUsageAmount(balance: Balance?, unit: Double, day: UsageDay = usageDay()): String {
 val value=balance?.todayUsed?.takeIf { balance.todayDate==day.date && balance.todayError==null } ?: return "—"
 return "$ "+amount(value,unit)
}
