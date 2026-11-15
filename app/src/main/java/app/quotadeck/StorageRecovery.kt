package app.quotadeck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class StorageStatus { LOADING, READY, FAILED }
enum class StorageStage { PREFERENCES, FORMAT, KEYSTORE, DECRYPT, PARSE, THEME }
class StorageReadException(val stage: StorageStage, cause: Exception): Exception("Local configuration read failed", cause)

internal inline fun <T> storageStep(stage: StorageStage, block: () -> T): T = try {
 block()
} catch (e: CancellationException) { throw e
} catch (e: Exception) { throw StorageReadException(stage,e) }

// Only fixed labels may reach logs/UI. Exception messages can contain plaintext configuration.
internal fun storageDiagnostic(error: Throwable): String {
 val stage=(error as? StorageReadException)?.stage?.name ?: "UNKNOWN"
 val cause=error.cause ?: error
 val category=when(cause) {
  is javax.crypto.AEADBadTagException -> "AUTHENTICATION"
  is java.security.GeneralSecurityException -> "SECURITY"
  is org.json.JSONException -> "JSON"
  is IllegalArgumentException -> "FORMAT"
  is IllegalStateException -> "UNAVAILABLE"
  else -> "OTHER"
 }
 return "$stage/$category"
}

internal suspend fun <T> readStorageWithRetry(
 read: suspend () -> T,
 onFailure: (Int, String) -> Unit,
 pause: suspend (Long) -> Unit = { delay(it) }
): Result<T> {
 val waits=listOf(400L,1200L)
 for(attempt in 0..waits.size) {
  try { return Result.success(read()) }
  catch(e: CancellationException) { throw e }
  catch(e: Exception) {
   onFailure(attempt+1,storageDiagnostic(e))
   if(attempt==waits.size) return Result.failure(e)
  }
  pause(waits[attempt])
 }
 error("Unreachable")
}
