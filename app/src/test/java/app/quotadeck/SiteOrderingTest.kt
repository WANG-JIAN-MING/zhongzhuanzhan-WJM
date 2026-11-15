package app.quotadeck

import org.junit.Assert.*
import org.junit.Test

class SiteOrderingTest {
 private fun site(id: String)=Site(id=id,name="同名",url="https://example.com",key="test-$id")
 @Test fun orderUsesIdsAndPreservesCredentials() {
  val a=site("a"); val b=site("b"); val c=site("c")
  assertEquals(listOf(c,a,b),orderedSites(listOf(a,b,c),listOf("c","a","b")))
 }
 @Test fun concurrentlyAddedSitesAreNotLost() {
  val a=site("a"); val b=site("b"); val added=site("new")
  assertEquals(listOf(b,a,added),orderedSites(listOf(a,b,added),listOf("b","a")))
 }
 @Test fun concurrentlyRemovedSitesAreNotResurrected() {
  val a=site("a"); val c=site("c")
  assertEquals(listOf(c,a),orderedSites(listOf(a,c),listOf("c","b","a")))
 }
 @Test(expected=IllegalArgumentException::class) fun duplicateIdsRejected() { orderedSites(listOf(site("a")),listOf("a","a")) }
}
