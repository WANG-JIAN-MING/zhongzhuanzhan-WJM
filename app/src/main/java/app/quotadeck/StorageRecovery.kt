package app.quotadeck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class StorageStatus { LOADING, READY, FAILED }
enum class StorageStage { PREFERENCES, FORMAT, KEYSTORE, DECRYPT, PARSE, THEME }
class StorageReadException(val stage: StorageStage, cause: Exception): Exception("Local configuration read failed", cause)
internal class StorageKeyMissingException: Exception()
internal class StorageKeyUnavailableException(cause: Exception): Exception(null, cause)

internal data class PreservedEncryptedConfig(val ciphertext: String, val theme: String)
internal fun preserveUnreadableConfig(
 currentCiphertext: String?, currentTheme: String?, existing: PreservedEncryptedConfig?
): PreservedEncryptedConfig? = existing ?: currentCiphertext?.let {
 PreservedEncryptedConfig(it, currentTheme ?: "system")
}

internal inline fun <T> storageStep(stage: StorageStage, block: () -> T): T = try {
 block()
} catch (e: CancellationException) { throw e
} catch (e: Exception) { throw StorageReadException(stage,e) }

// Only fixed labels may reach logs/UI. Exception messages can contain plaintext configuration.
internal fun storageDiagnostic(error: Throwable): String {
 val stage=(error as? StorageReadException)?.stage?.name ?: "UNKNOWN"
 val cause=error.cause ?: error
 val category=when(cause) {
  is StorageKeyMissingException -> "KEY_MISSING"
  is StorageKeyUnavailableException -> "KEY_UNAVAILABLE"
  is javax.crypto.AEADBadTagException -> "AUTHENTICATION"
  is java.security.GeneralSecurityException -> "SECURITY"
  is org.json.JSONException -> "JSON"
  is IllegalArgumentException -> "FORMAT"
  is IllegalStateException -> "UNAVAILABLE"
  else -> "OTHER"
 }
 val chain=generateSequence(cause) { it.cause }.take(12).toList()
 val detail=when {
  chain.any { it.javaClass.name=="android.security.keystore.UserNotAuthenticatedException" } -> "USER_NOT_AUTHENTICATED"
  chain.any { it.javaClass.name=="android.security.keystore.KeyPermanentlyInvalidatedException" } -> "KEY_INVALIDATED"
  chain.any { it is SecurityException } -> "ACCESS_DENIED"
  chain.any { it is java.security.UnrecoverableKeyException } -> "UNRECOVERABLE_KEY"
  chain.any { it is java.security.KeyStoreException } -> "KEYSTORE_ERROR"
  chain.any { it is java.security.ProviderException } -> "PROVIDER_ERROR"
  else -> null
 }
 return "$stage/$category" + (detail?.let { "/$it" } ?: "")
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
