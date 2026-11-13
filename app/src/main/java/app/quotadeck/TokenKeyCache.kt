package app.quotadeck

/** Page-lifetime memory only. Closing also rejects late results from an old identity. */
class TokenKeyCache(site: Site) {
 private var owner: Site? = site
 private val keys = mutableMapOf<Long,String>()
 fun get(site: Site,id: Long): String? = if(owner==site) keys[id] else null
 fun put(site: Site,id: Long,key: String) {
  if(owner==site && id>0 && key.isNotBlank()) keys[id]=key
 }
 fun remove(id: Long) { keys.remove(id) }
 fun close() { keys.clear(); owner=null }
}
