package app.quotadeck

import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VaultRecoveryTest {
 private val sites=listOf(
  Site("first","站点一","https://one.example","test-key",true,"42",123456.0,true,"test-cookie"),
  Site("second","站点二","https://two.example","another-key")
 )

 @Test fun recoveryArchivesOriginalCiphertextAndThemeWithReplacementInOneCommit() {
  val prefs=MemoryPreferences(mapOf("sites" to "old:unreadable","theme" to "dark","unrelated" to "keep"))
  var allowCreate=false
  val vault=Vault(prefs) { _,create -> allowCreate=create; "new:encrypted" }
  vault.restoreAfterReadFailure(sites,"light")
  assertTrue(allowCreate)
  assertEquals(1,prefs.commitCount)
  assertEquals(mapOf("sites" to "new:encrypted","theme" to "light","recovery_previous_sites" to "old:unreadable","recovery_previous_theme" to "dark"),prefs.lastChanges)
  assertEquals("keep",prefs.getString("unrelated",null))
  assertEquals("old:unreadable",prefs.persisted["recovery_previous_sites"])
 }

 @Test fun repeatedRecoveryDoesNotReplaceFirstArchive() {
  val prefs=MemoryPreferences(mapOf("sites" to "intermediate:ciphertext","theme" to "light","recovery_previous_sites" to "original:ciphertext","recovery_previous_theme" to "dark"))
  Vault(prefs) { _,_ -> "latest:ciphertext" }.restoreAfterReadFailure(sites,"system")
  assertEquals("original:ciphertext",prefs.persisted["recovery_previous_sites"])
  assertEquals("dark",prefs.persisted["recovery_previous_theme"])
  assertEquals("latest:ciphertext",prefs.persisted["sites"])
 }

 @Test fun absentCurrentConfigurationDoesNotCreateAnArchive() {
  val prefs=MemoryPreferences(emptyMap())
  var plaintext=""
  Vault(prefs) { text,create -> assertTrue(create); plaintext=text; "new:empty" }.restoreAfterReadFailure(emptyList(),"system")
  assertEquals("[]",plaintext)
  assertFalse(prefs.contains("recovery_previous_sites"))
  assertFalse(prefs.contains("recovery_previous_theme"))
 }

 @Test fun missingOriginalOrArchivedThemeDefaultsToSystem() {
  val prefs=MemoryPreferences(mapOf("sites" to "old:ciphertext"))
  Vault(prefs) { _,_ -> "new:ciphertext" }.restoreAfterReadFailure(sites,"light")
  assertEquals("system",prefs.persisted["recovery_previous_theme"])
  val alreadyArchived=MemoryPreferences(mapOf("sites" to "current:ciphertext","theme" to "dark","recovery_previous_sites" to "original:ciphertext"))
  Vault(alreadyArchived) { _,_ -> "latest:ciphertext" }.restoreAfterReadFailure(sites,"light")
  assertEquals("original:ciphertext",alreadyArchived.persisted["recovery_previous_sites"])
  assertEquals("system",alreadyArchived.persisted["recovery_previous_theme"])
 }

 @Test fun encryptionFailureNeverCommitsOrChangesOriginalPreferences() {
  val original=mapOf("sites" to "old:ciphertext","theme" to "dark")
  val prefs=MemoryPreferences(original)
  val failure=StorageKeyUnavailableException(java.security.ProviderException("test failure"))
  try {
   Vault(prefs) { _,_ -> throw failure }.restoreAfterReadFailure(sites,"light")
   fail("Expected encryption failure")
  } catch(e: StorageKeyUnavailableException) { assertSame(failure,e) }
  assertEquals(0,prefs.commitCount)
  assertEquals(original,prefs.all)
  assertEquals(original,prefs.persisted)
 }

 @Test fun rejectedDiskCommitReportsFailureAndKeepsOriginalInMemoryArchive() {
  val original=mapOf("sites" to "old:ciphertext","theme" to "dark")
  val prefs=MemoryPreferences(original).apply { commitSucceeds=false }
  try {
   Vault(prefs) { _,_ -> "new:ciphertext" }.restoreAfterReadFailure(sites,"light")
   fail("Expected save failure")
  } catch(e: IllegalStateException) { assertEquals("保存失败",e.message) }
  assertEquals(1,prefs.commitCount)
  assertEquals(original,prefs.persisted)
  // SharedPreferences can publish changes in memory even if disk persistence fails.
  assertEquals("new:ciphertext",prefs.getString("sites",null))
  assertEquals("old:ciphertext",prefs.getString("recovery_previous_sites",null))
  assertEquals("dark",prefs.getString("recovery_previous_theme",null))
 }

 @Test fun commitExceptionIsNotReportedAsSuccess() {
  val prefs=MemoryPreferences(mapOf("sites" to "old:ciphertext"))
  val failure=IllegalStateException("simulated disk failure")
  prefs.commitFailure=failure
  try {
   Vault(prefs) { _,_ -> "new:ciphertext" }.restoreAfterReadFailure(sites,"light")
   fail("Expected commit failure")
  } catch(e: IllegalStateException) { assertSame(failure,e) }
  assertEquals(1,prefs.commitCount)
  assertEquals("old:ciphertext",prefs.lastChanges["recovery_previous_sites"])
  assertEquals("old:ciphertext",prefs.persisted["sites"])
 }

 @Test fun ordinaryWriteStillRefusesToReplaceUnreadableConfiguration() {
  val original=mapOf("sites" to "invalid-format","theme" to "dark")
  val prefs=MemoryPreferences(original)
  val vault=Vault(prefs) { _,_ -> fail("Must validate old data before encrypting"); "unused" }
  try {
   vault.write(sites)
   fail("Expected read failure")
  } catch(e: StorageReadException) { assertEquals(StorageStage.FORMAT,e.stage) }
  assertEquals(0,prefs.commitCount)
  assertEquals(original,prefs.all)
 }

 @Test fun bothWritePathsSerializeAllFieldsWithoutChangingValuesOrOrder() {
  val expected=sites.map { it.copy(key=it.key+"\"\\\n令牌",cookie=it.cookie+"\"\\\n会话") }
  var written=""
  var recovered=""
  Vault(MemoryPreferences(emptyMap())) { text,_ -> written=text; "normal:ciphertext" }.write(expected)
  Vault(MemoryPreferences(mapOf("sites" to "unreadable"))) { text,_ -> recovered=text; "restored:ciphertext" }.restoreAfterReadFailure(expected,"system")
  for(text in listOf(written,recovered)) {
   val array=JSONArray(text)
   assertEquals(expected.size,array.length())
   expected.forEachIndexed { i,site ->
    val json=array.getJSONObject(i)
    assertEquals(setOf("id","name","url","key","account","userId","unit","subscription","cookie"),json.keySet())
    assertEquals(site.id,json.getString("id"))
    assertEquals(site.name,json.getString("name"))
    assertEquals(site.url,json.getString("url"))
    assertEquals(site.key,json.getString("key"))
    assertEquals(site.account,json.getBoolean("account"))
    assertEquals(site.userId,json.getString("userId"))
    assertEquals(site.unit,json.getDouble("unit"),0.0)
    assertEquals(site.subscription,json.getBoolean("subscription"))
    assertEquals(site.cookie,json.getString("cookie"))
   }
  }
 }

 @Test fun ordinaryEmptyWriteCreatesKeyWithoutChangingThemeOrArchive() {
  val prefs=MemoryPreferences(mapOf("theme" to "dark","recovery_previous_sites" to "original:ciphertext","recovery_previous_theme" to "light"))
  var plaintext=""
  Vault(prefs) { text,create -> assertTrue(create); plaintext=text; "new:empty" }.write(emptyList())
  assertEquals("[]",plaintext)
  assertEquals(mapOf("sites" to "new:empty"),prefs.lastChanges)
  assertEquals("dark",prefs.getString("theme",null))
  assertEquals("original:ciphertext",prefs.getString("recovery_previous_sites",null))
  assertEquals("light",prefs.getString("recovery_previous_theme",null))
 }

 @Test fun existingArchiveSurvivesRecoveryWhenCurrentConfigurationIsAbsent() {
  val prefs=MemoryPreferences(mapOf("recovery_previous_sites" to "original:ciphertext","recovery_previous_theme" to "dark"))
  Vault(prefs) { _,_ -> "new:ciphertext" }.restoreAfterReadFailure(sites,"light")
  assertEquals("original:ciphertext",prefs.persisted["recovery_previous_sites"])
  assertEquals("dark",prefs.persisted["recovery_previous_theme"])
 }

 // Models separate in-memory and disk snapshots: commit(false) may still publish in memory.
 private class MemoryPreferences(initial: Map<String,String>): SharedPreferences {
  private val values=initial.mapValues { it.value as Any? }.toMutableMap()
  var persisted: Map<String,Any?> = values.toMap()
   private set
  var commitSucceeds=true
  var commitFailure: RuntimeException?=null
  var commitCount=0
   private set
  var lastChanges: Map<String,Any?> = emptyMap()
   private set
  override fun getAll(): MutableMap<String,*> = values.toMutableMap()
  override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
  override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = error("Unused in Vault")
  override fun getInt(key: String?, defValue: Int): Int = error("Unused in Vault")
  override fun getLong(key: String?, defValue: Long): Long = error("Unused in Vault")
  override fun getFloat(key: String?, defValue: Float): Float = error("Unused in Vault")
  override fun getBoolean(key: String?, defValue: Boolean): Boolean = error("Unused in Vault")
  override fun contains(key: String?): Boolean = values.containsKey(key)
  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  override fun edit(): SharedPreferences.Editor = object: SharedPreferences.Editor {
   private val changes=mutableMapOf<String,Any?>()
   private var clearAll=false
   override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { changes[requireNotNull(key)]=value }
   override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = error("Unused in Vault")
   override fun putInt(key: String?, value: Int): SharedPreferences.Editor = error("Unused in Vault")
   override fun putLong(key: String?, value: Long): SharedPreferences.Editor = error("Unused in Vault")
   override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = error("Unused in Vault")
   override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = error("Unused in Vault")
   override fun remove(key: String?): SharedPreferences.Editor = apply { changes[requireNotNull(key)]=null }
   override fun clear(): SharedPreferences.Editor = apply { clearAll=true }
   override fun commit(): Boolean {
    commitCount++
    lastChanges=changes.toMap()
    commitFailure?.let { throw it }
    if(clearAll) values.clear()
    changes.forEach { (key,value) -> if(value==null) values.remove(key) else values[key]=value }
    if(commitSucceeds) persisted=values.toMap()
    return commitSucceeds
   }
   override fun apply(): Unit = error("Vault writes must use commit()")
  }
 }
}
