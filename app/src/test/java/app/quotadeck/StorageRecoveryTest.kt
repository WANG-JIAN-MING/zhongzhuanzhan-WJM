package app.quotadeck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageRecoveryTest {
 @Test fun transientFailureRecoversWithoutRestart() = runBlocking {
  var attempts=0
  val waits=mutableListOf<Long>()
  val diagnostics=mutableListOf<String>()
  val sites=listOf(Site(name="saved",url="https://example.com",key="secret"))
  val result=readStorageWithRetry(read={ if(++attempts<3) throw StorageReadException(StorageStage.KEYSTORE,java.security.KeyStoreException("private detail")); sites },onFailure={ _,code -> diagnostics.add(code) },pause={ waits.add(it) })
  assertEquals(sites,result.getOrThrow())
  assertEquals(3,attempts)
  assertEquals(listOf(400L,1200L),waits)
  assertEquals(listOf("KEYSTORE/SECURITY/KEYSTORE_ERROR","KEYSTORE/SECURITY/KEYSTORE_ERROR"),diagnostics)
 }
 @Test fun permanentFailureStopsAtThreeReads() = runBlocking {
  var attempts=0
  val result=readStorageWithRetry(read={ attempts++; throw IllegalStateException("bad") },onFailure={ _,_ -> },pause={ })
  assertTrue(result.isFailure)
  assertEquals(3,attempts)
 }
 @Test fun manualRetryCanRecoverAfterExhaustion() = runBlocking {
  var available=false
  val read: suspend () -> String = { check(available); "original config" }
  assertTrue(readStorageWithRetry(read,{ _,_ -> },{}).isFailure)
  available=true
  assertEquals("original config",readStorageWithRetry(read,{ _,_ -> },{}).getOrThrow())
 }
 @Test fun emptyConfigurationIsSuccessfulAndDoesNotRetry() = runBlocking {
  var attempts=0
  val result=readStorageWithRetry(read={ attempts++; emptyList<Site>() },onFailure={ _,_ -> fail("Unexpected failure") },pause={ fail("Unexpected retry") })
  assertTrue(result.getOrThrow().isEmpty())
  assertEquals(1,attempts)
 }
 @Test fun cancellationIsNotRetriedOrConvertedToFailure() = runBlocking {
  var attempts=0
  try {
   readStorageWithRetry(read={ attempts++; throw CancellationException("cancel") },onFailure={ _,_ -> fail("Cancellation was logged") },pause={ fail("Cancellation was retried") })
   fail("Expected cancellation")
  } catch(_: CancellationException) { assertEquals(1,attempts) }
 }
 @Test fun missingKeyIsDistinctFromUnavailableProvider() {
  assertEquals("KEYSTORE/KEY_MISSING",storageDiagnostic(StorageReadException(StorageStage.KEYSTORE,StorageKeyMissingException())))
  assertEquals("KEYSTORE/KEY_UNAVAILABLE/PROVIDER_ERROR",storageDiagnostic(StorageReadException(StorageStage.KEYSTORE,StorageKeyUnavailableException(java.security.ProviderException("private")))))
 }
 @Test fun wrappedSecurityFailuresRetainSafeReasonOnly() {
  val error=StorageReadException(StorageStage.KEYSTORE,StorageKeyUnavailableException(java.security.UnrecoverableKeyException("secret").apply { initCause(SecurityException("cookie")) }))
  assertEquals("KEYSTORE/KEY_UNAVAILABLE/ACCESS_DENIED",storageDiagnostic(error))
  assertFalse(error.message!!.contains("secret"))
 }
 @Test fun diagnosticNeverIncludesExceptionText() {
  val secret="Bearer abc cookie=value https://secret.example"
  assertEquals("PARSE/JSON",storageDiagnostic(StorageReadException(StorageStage.PARSE,org.json.JSONException(secret))))
  assertEquals("DECRYPT/AUTHENTICATION",storageDiagnostic(StorageReadException(StorageStage.DECRYPT,javax.crypto.AEADBadTagException(secret))))
  assertEquals("UNKNOWN/OTHER",storageDiagnostic(Exception(secret)))
 }
 @Test fun storageStepsRetainStageWithoutExposingCauseMessage() {
  try { storageStep(StorageStage.PREFERENCES) { throw ClassCastException("secret") }; fail("Expected error") }
  catch(e: StorageReadException) {
   assertEquals(StorageStage.PREFERENCES,e.stage)
   assertFalse(e.message!!.contains("secret"))
   assertEquals("PREFERENCES/OTHER",storageDiagnostic(e))
  }
 }
}
