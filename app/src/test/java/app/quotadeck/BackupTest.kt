package app.quotadeck

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BackupTest {
 private val sample=BackupData(listOf(Site("b","同名","https://example.com","credential",true,"12",123.0,true,"session"),Site("a","同名","https://other.example","token")),"dark")
 @Test fun portableRoundTripPreservesEveryFieldAndOrder() { assertEquals(sample,BackupCodec.decode(BackupCodec.encode(sample))) }
 @Test fun credentialsAreIncludedInPlainBackup() { val bytes=BackupCodec.encode(sample); assertTrue(String(bytes).contains("credential")); assertTrue(String(bytes).contains("session")) }
 @Test fun emptyBackupSupported() { val data=BackupData(emptyList(),"system"); assertEquals(data,BackupCodec.decode(BackupCodec.encode(data))) }
 @Test(expected=Exception::class) fun duplicateSitesRejected() { BackupCodec.encode(sample.copy(sites=listOf(sample.sites[0],sample.sites[0]))) }
 @Test(expected=Exception::class) fun unsupportedVersionRejected() { val obj=JSONObject(String(BackupCodec.encode(sample))).put("version",3); BackupCodec.decode(obj.toString().toByteArray()) }
 @Test(expected=Exception::class) fun oversizedInputRejected() { BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES+1)) }
}
