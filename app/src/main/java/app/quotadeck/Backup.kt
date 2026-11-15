package app.quotadeck

import org.json.JSONArray
import org.json.JSONObject
data class BackupData(val sites: List<Site>, val theme: String)

object BackupCodec {
 const val MAX_BYTES=4*1024*1024
 private fun validate(data: BackupData) {
  require(data.theme in listOf("system","light","dark"))
  require(data.sites.size<=10000 && data.sites.map { it.id }.toSet().size==data.sites.size)
  data.sites.forEach { require(it.id.isNotBlank() && it.name.isNotBlank() && it.unit.isFinite() && it.unit>0); val uri=java.net.URI(it.url); require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null) }
 }
 fun encode(data: BackupData): ByteArray {
  validate(data)
  val sites=JSONArray()
  data.sites.forEach { s -> sites.put(JSONObject().put("id",s.id).put("name",s.name).put("url",s.url).put("key",s.key).put("account",s.account).put("userId",s.userId).put("unit",s.unit).put("subscription",s.subscription).put("cookie",s.cookie)) }
  val bytes=JSONObject().put("format","QuotaDeck-backup").put("version",2).put("theme",data.theme).put("sites",sites).toString().toByteArray(Charsets.UTF_8)
  require(bytes.size<=MAX_BYTES)
  return bytes
 }
 fun decode(bytes: ByteArray): BackupData {
  require(bytes.size<=MAX_BYTES)
  val root=JSONObject(String(bytes,Charsets.UTF_8))
  require(root.getString("format")=="QuotaDeck-backup" && root.getInt("version")==2) { "备份格式不支持，请使用无密码备份" }
   val a=root.getJSONArray("sites"); require(a.length()<=10000)
   val sites=(0 until a.length()).map { i -> val s=a.getJSONObject(i); Site(s.getString("id"),s.getString("name"),s.getString("url"),s.getString("key"),s.getBoolean("account"),s.getString("userId"),s.getDouble("unit"),s.getBoolean("subscription"),s.getString("cookie")) }
   return BackupData(sites,root.getString("theme")).also(::validate)
 }
}
