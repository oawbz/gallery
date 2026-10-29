/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.api

import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ApiGenerationTest {
  private fun request(extra: String = "") = ApiProtocol.parse("""{"model":"test","messages":[{"role":"user","content":"hello"}]$extra}""","test")
  private fun backend(block: ((ApiDelta) -> Unit) -> Unit) = object : ChatBackend {
    override val modelId = "test"
    override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) = block(emit)
    override fun close() {}
  }
  @Test fun stopAcrossNativeChunksCancelsWithoutLeakingDelimiterAndKeepsCounters() {
    val engine = backend { emit ->
      try { emit(ApiDelta.Text("hello<EN")); emit(ApiDelta.Text("D>hidden")); fail("Must stop generating") }
      finally { emit(ApiDelta.Stats(ApiStats(ApiUsage(12,8),40.0))) }
    }
    val chunks = mutableListOf<ApiDelta>()
    val result = complete(engine,request(",\"stop\":[\"<END>\"]"),chunks::add)
    assertEquals("hello",result.text); assertEquals("stop",result.finish)
    assertEquals(20,result.stats!!.usage!!.wire()["total_tokens"])
    assertEquals("hello",chunks.filterIsInstance<ApiDelta.Text>().joinToString("") { it.text })
  }
  @Test fun partialStopPrefixIsFlushedWhenModelCompletesNormally() {
    val result = complete(backend { it(ApiDelta.Text("答案🌟<EN")) },request(",\"stop\":\"<END>\"")) {}
    assertEquals("答案🌟<EN",result.text)
    val filter = ApiStopFilter(listOf("🌟结束","END"))
    assertEquals("答案",filter.push("答案🌟"))
    assertEquals("",filter.push("结束余下")); assertTrue(filter.stopped)
  }
  @Test fun realDecodeCountDeterminesLengthWithoutCountingCharacters() {
    val result = complete(backend { emit -> emit(ApiDelta.Text("你好")); emit(ApiDelta.Stats(ApiStats(ApiUsage(13,4)))) },request(",\"max_tokens\":4")) {}
    assertEquals("length",result.finish)
    assertEquals(4,result.stats!!.usage!!.completionTokens)
    val unknown = complete(backend { it(ApiDelta.Text("你好")) },request()) {}
    assertNull(unknown.stats); assertEquals("stop",unknown.finish)
  }
  @Test fun runtimeMetricsStayUnknownWhenUnavailable() {
    val valid = LiteRtProtocol.stats(BenchmarkInfo(0.1,0.2,11,7,100.0,25.0))
    assertEquals(18,valid.usage!!.wire()["total_tokens"])
    assertEquals(200.0,valid.nativeTtftMs!!,0.001)
    val missing = LiteRtProtocol.stats(BenchmarkInfo(0.0,0.0,0,0,Double.NaN,-1.0))
    assertNull(missing.usage); assertNull(missing.prefillTps); assertNull(missing.decodeTps)
  }
  @Test fun strictToolArgumentsAreCheckedBeforeDelivery() {
    val r = request(""", "tools":[{"type":"function","function":{"name":"weather","strict":true,"parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}}}]""")
    var delivered = false
    try {
      complete(backend { it(ApiDelta.Tools(listOf(ApiToolCall("call_1","weather","{\"city\":12}")))) },r) { delivered = true }
      fail("Expected schema rejection")
    } catch (e: ApiFailure) { assertEquals(422,e.status) }
    assertFalse(delivered)
    val result = complete(backend { it(ApiDelta.Tools(listOf(ApiToolCall("call_1","weather","{\"city\":\"北京\"}")))) },r) {}
    assertEquals("tool_calls",result.finish)
  }
  @Test fun structuredOutputIsConstrainedAndValidated() {
    val r = request(""", "reasoning_effort":"none","response_format":{"type":"json_schema","json_schema":{"name":"answer","strict":true,"schema":{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"],"additionalProperties":false}}}""")
    val config = LiteRtProtocol.conversationConfig(r,LiteRtProtocol.messages(r) { error("No images") })
    assertTrue(config.enableResponseFormat)
    assertNotNull(LiteRtProtocol.responseFormat(r))
    assertEquals("stop",complete(backend { it(ApiDelta.Text("{\"answer\":42}")) },r) {}.finish)
    try { complete(backend { it(ApiDelta.Text("{\"answer\":\"wrong\"}")) },r) {}; fail("Expected schema mismatch") }
    catch (e: ApiFailure) { assertEquals(422,e.status) }
  }
  @Test fun schemaReferencesAndConstraintsAreEnforced() {
    val schema = JsonParser.parseString("""{"type":"object","properties":{"items":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/entry"},"minItems":1,"uniqueItems":true}},"required":["items"],"additionalProperties":false,"${'$'}defs":{"entry":{"anyOf":[{"type":"integer","minimum":1,"maximum":3},{"type":"string","minLength":1,"maxLength":2}]}}}""").asJsonObject
    ApiJsonSchema.check(schema)
    fun matches(json: String) = ApiJsonSchema.matches(JsonParser.parseString(json),schema)
    assertTrue(matches("""{"items":[1,"🌟"]}"""))
    assertFalse(matches("""{"items":[4]}""")); assertFalse(matches("""{"items":[1,1]}"""))
    assertFalse(matches("""{"items":[],"extra":1}"""))
    try { ApiJsonSchema.check(JsonParser.parseString("""{"type":"string","pattern":".*"}""").asJsonObject); fail("Unsupported constraint must not be ignored") }
    catch (e: ApiFailure) { assertEquals(400,e.status) }
  }
}
