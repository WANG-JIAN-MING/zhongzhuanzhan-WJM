package app.quotadeck

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TokenOptionsTest {
 private fun detail()=JSONObject("""{"id":7,"group":"auto","auto_groups":["vip","default"],"cross_group_retry":false,"model_limits_enabled":true,"model_limits":"retired,active","allow_ips":"10.0.0.1","future_option":{"enabled":true},"key":"SECRET","used_quota":99}""")
 private fun draft(group: String?=null,models: List<String>?=null)=TokenDraft("Edited",123,false,-1,group,models)
 @Test fun groupsUseDescriptionsWithRawAndKeyFallback() {
  val groups=TokenApi.parseGroups("""{"success":true,"data":{"vip":{"desc":"VIP group","raw_desc":"raw"},"default":{"desc":"","raw_desc":"Raw default"},"auto":{}}}""").associate { it.value to it.label }
  assertEquals(mapOf("vip" to "VIP group","default" to "Raw default","auto" to "auto"),groups)
 }
 @Test fun emptyDiscoveryDoesNotInventGroupsOrModels() {
  assertTrue(TokenApi.parseGroups("""{"success":true,"data":{}}""").isEmpty())
  assertTrue(TokenApi.parseModels("""{"success":true,"data":[]}""").isEmpty())
 }
 @Test fun modelsKeepExactNamesAndDeduplicate() {
  assertEquals(listOf("vendor/model-v1","模型"),TokenApi.parseModels("""{"success":true,"data":["vendor/model-v1","模型","vendor/model-v1"]}"""))
 }
 @Test fun commaInModelRejectedBeforeItCanChangeRestrictionMeaning() {
  try { TokenApi.parseModels("""{"success":true,"data":["first,second"]}"""); fail() } catch(_:IllegalArgumentException) {}
 }
 @Test fun failedDiscoveryLeavesEveryAdvancedFieldUntouched() {
  try { TokenApi.parseGroups("""{"success":false,"message":"private server details"}"""); fail() } catch(_:TokenApiError) {}
  val original=detail(); val body=JSONObject(TokenApi.body(draft(),original))
  listOf("group","auto_groups","cross_group_retry","model_limits_enabled","model_limits","allow_ips","future_option").forEach { field->assertEquals(field,original.get(field).toString(),body.get(field).toString()) }
  assertFalse(body.has("key")); assertEquals("SECRET",original.getString("key"))
 }
 @Test fun changingModelsAlonePreservesCustomAutoRouteOrderAndRetry() {
  val body=JSONObject(TokenApi.body(draft(models=listOf("new-model","new-model")),detail()))
  assertEquals("new-model",body.getString("model_limits")); assertTrue(body.getBoolean("model_limits_enabled"))
  assertEquals("[\"vip\",\"default\"]",body.getJSONArray("auto_groups").toString()); assertFalse(body.getBoolean("cross_group_retry")); assertEquals("auto",body.getString("group"))
 }
 @Test fun explicitUnrestrictedClearsEnabledFlagAndLimits() {
  val body=JSONObject(TokenApi.body(draft(models=emptyList()),detail()))
  assertFalse(body.getBoolean("model_limits_enabled")); assertEquals("",body.getString("model_limits")); assertEquals("auto",body.getString("group"))
 }
 @Test fun selectingAutoUsesInheritedGroupsAndNoUiOnlyRouteMode() {
  val body=JSONObject(TokenApi.body(draft(group="auto"),detail().put("group","vip").put("auto_route_mode","none")))
  assertEquals("auto",body.getString("group")); assertEquals(0,body.getJSONArray("auto_groups").length()); assertTrue(body.getBoolean("cross_group_retry")); assertFalse(body.has("auto_route_mode")); assertEquals("retired,active",body.getString("model_limits"))
 }
 @Test fun choosingDefaultOrSpecificGroupClearsOldAutoRouting() {
  listOf("","vip").forEach { group->
   val body=JSONObject(TokenApi.body(draft(group=group),detail()))
   assertEquals(group,body.getString("group")); assertEquals(0,body.getJSONArray("auto_groups").length()); assertFalse(body.getBoolean("cross_group_retry")); assertEquals("10.0.0.1",body.getString("allow_ips"))
  }
 }
 @Test fun disabledOriginalModelLimitsRemainStoredWhenUntouched() {
  val body=JSONObject(TokenApi.body(draft(),detail().put("model_limits_enabled",false)))
  assertFalse(body.getBoolean("model_limits_enabled")); assertEquals(listOf("retired","active"),TokenApi.modelSelection(body))
 }
 @Test fun cardModelWritePreservesExactBaseFieldsAndRoute() {
  val original=detail().put("name","Existing").put("remain_quota",-17L).put("expired_time",123L).put("status",3).put("unlimited_quota",false).put("auto_route_mode","custom").put("key_masked","MASKED").put("key_display","DISPLAY")
  val body=JSONObject(TokenApi.optionsBody(original,null,listOf("active")))
  listOf("id","name","remain_quota","expired_time","status","unlimited_quota","group","auto_groups","cross_group_retry","auto_route_mode","allow_ips","future_option").forEach { field->assertEquals(field,original.get(field).toString(),body.get(field).toString()) }
  listOf("key","key_masked","key_display","used_quota").forEach { assertFalse(body.has(it)) }
  assertEquals("active",body.getString("model_limits")); assertTrue(body.getBoolean("model_limits_enabled"))
 }
 @Test fun cardGroupWritePreservesStoredDisabledModelLimits() {
  val original=detail().put("model_limits_enabled",false)
  val body=JSONObject(TokenApi.optionsBody(original,"vip",null))
  assertEquals("vip",body.getString("group")); assertFalse(body.getBoolean("model_limits_enabled")); assertEquals("retired,active",body.getString("model_limits"))
  assertEquals("10.0.0.1",body.getString("allow_ips")); assertEquals("auto",original.getString("group"))
 }
 @Test fun cardNoChangesKeepsAllAdvancedConfiguration() {
  val original=detail().put("auto_route_mode","custom")
  val body=JSONObject(TokenApi.optionsBody(original,null,null))
  listOf("group","auto_groups","cross_group_retry","auto_route_mode","model_limits","model_limits_enabled","allow_ips","future_option").forEach { field->assertEquals(field,original.get(field).toString(),body.get(field).toString()) }
 }
 @Test fun failedModelDiscoveryAndNoSelectionPreserveExactCardConfiguration() {
  try { TokenApi.parseModels("""{"success":false,"message":"private"}"""); fail() } catch(_:TokenApiError) {}
  val original=detail().put("name","Existing").put("remain_quota",9007199254740993L).put("expired_time",123L).put("status",2).put("unlimited_quota",true).put("auto_route_mode","custom")
  val body=JSONObject(TokenApi.optionsBody(original,null,null))
  original.keys().asSequence().filter { it !in listOf("key","used_quota") }.forEach { field -> assertEquals(field,original.get(field).toString(),body.get(field).toString()) }
 }
 @Test fun cardGroupWritePreservesExactBaseAndUnrelatedFields() {
  val original=detail().put("name","Existing").put("remain_quota",9007199254740993L).put("expired_time",123L).put("status",4).put("unlimited_quota",false)
  val body=JSONObject(TokenApi.optionsBody(original,"vip",null))
  listOf("id","name","remain_quota","expired_time","status","unlimited_quota","model_limits","model_limits_enabled","allow_ips","future_option").forEach { field -> assertEquals(field,original.get(field).toString(),body.get(field).toString()) }
 }
 @Test fun creationWithoutOptionsUsesDefaultGroupAndUnrestrictedModels() {
  val body=JSONObject(TokenApi.body(draft()))
  assertEquals("",body.getString("group")); assertEquals("",body.getString("model_limits")); assertFalse(body.getBoolean("model_limits_enabled")); assertEquals("none",body.getString("auto_route_mode"))
 }
 @Test fun creationSerializesSelectedGroupAndModels() {
  val body=JSONObject(TokenApi.body(draft("vip",listOf("model-a","model-b"))))
  assertEquals("vip",body.getString("group")); assertEquals("model-a,model-b",body.getString("model_limits")); assertTrue(body.getBoolean("model_limits_enabled")); assertFalse(body.has("id"))
 }
}
