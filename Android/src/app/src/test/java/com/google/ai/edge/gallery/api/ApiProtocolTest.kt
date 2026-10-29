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

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ApiProtocolTest {
  private val tool = """{"type":"function","function":{"name":"weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}"""
  private val user = """{"role":"user","content":"天气如何"}"""
  private val assistant = """{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\"city\":\"北京\"}"}},{"id":"call_2","type":"function","function":{"name":"weather","arguments":"{\"city\":\"上海\"}"}}]}"""
  private val result1 = """{"role":"tool","tool_call_id":"call_1","content":"北京晴"}"""
  private val result2 = """{"role":"tool","tool_call_id":"call_2","content":"上海雨"}"""
  private fun parse(messages: String = user, extra: String = ""): ChatRequest =
    ApiProtocol.parse("""{"model":"test","messages":[$messages]$extra}""", "test")
  private fun bad(messages: String = user, extra: String = "", status: Int = 400) {
    try { parse(messages, extra); fail("Expected rejection") }
    catch (e: ApiFailure) { assertEquals(status, e.status) }
  }

  @Test fun toolChoiceAndDefinitionsAreValidated() {
    val r = parse(extra = """, "tools":[$tool],"tool_choice":{"type":"function","function":{"name":"weather"}},"parallel_tool_calls":false""")
    assertEquals("required", r.toolChoice); assertEquals("weather", r.selectedTool)
    assertFalse(r.parallelToolCalls); assertEquals(1, r.activeTools.size)
    assertTrue(parse(extra = """, "tools":[$tool],"tool_choice":"none"""").activeTools.isEmpty())
    bad(extra = """, "tool_choice":"required"""")
    bad(extra = """, "tools":[$tool,$tool]""")
    bad(extra = """, "tools":[${tool.replace("\"parameters\":", "\"strict\":true,\"parameters\":")}]""")
    bad(extra = """, "tools":[$tool],"tool_choice":{"type":"function","function":{"name":"missing"}}""")
    bad(extra = """, "tools":[{"type":"custom"}]""")
  }

  @Test fun completeToolHistoryPreservesIdsAndNativeResultOrder() {
    val request = parse("$user,$assistant,$result2,$result1", """, "tools":[$tool]""")
    assertEquals("call_2", request.messages[2].toolCallId)
    val native = LiteRtProtocol.messages(request) { error("No images expected") }
    assertEquals(3, native.size)
    assertEquals("北京", native[1].toolCalls[0].arguments["city"])
    val responses = native.last().contents.contents.map { it as Content.ToolResponse }
    assertEquals(listOf("weather", "weather"), responses.map { it.name })
    assertEquals(listOf("北京晴", "上海雨"), responses.map { it.response })
    assertEquals(1, LiteRtProtocol.tools(request).size)
  }

  @Test fun unresolvedUnknownAndDuplicateToolResultsAreRejected() {
    bad("$user,$assistant,$result1")
    bad("$user,$assistant,$result1,$result1")
    bad("$user,$result1")
    bad("$user,$assistant,$result1,$result2,$result2")
    bad("$user,$assistant,$user")
    bad("$user,$assistant,$result1,$result2,${assistant.replace("call_2", "call_1")}")
    bad("$user,${assistant.replace("{\\\"city\\\":\\\"北京\\\"}", "[]")},$result1,$result2")
    assertEquals(6, parse("$user,$assistant,$result1,$result2,{\"role\":\"assistant\",\"content\":\"结果\"},$user").messages.size)
  }

  @Test fun imagePartsPreserveInterleavingAndBecomeNativeBytes() {
    val data = "data:image/png;base64,aGVsbG8="
    val request = parse("""{"role":"user","content":[{"type":"text","text":"前"},{"type":"image_url","image_url":{"url":"$data","detail":"high"}},{"type":"text","text":"后"}]}""")
    assertEquals("前后", request.messages.single().content)
    val native = LiteRtProtocol.messages(request, ::decodeDataImage).single().contents.contents
    assertEquals("前", (native[0] as Content.Text).text)
    assertArrayEquals("hello".toByteArray(), (native[1] as Content.ImageBytes).bytes)
    assertEquals("后", (native[2] as Content.Text).text)
    assertEquals("hello", String(decodeDataImage(data)))
    val remote = parse("""{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/image.png"}}]}""")
    assertTrue(remote.messages.single().parts.single() is ApiPart.Image)
    assertTrue(parse("""{"role":"user","content":[{"type":"image_url","image_url":{"url":"http://192.168.1.2/image.png"}}]}""").messages.single().parts.single() is ApiPart.Image)
  }

  @Test fun invalidImagesAndNonUserImagesAreRejected() {
    fun image(url: String, role: String = "user") = """{"role":"$role","content":[{"type":"image_url","image_url":{"url":"$url"}}]}"""
    bad(image("file:///etc/passwd"))
    bad(image("https://user:password@example.com/i.png"))
    bad(image("data:image/svg+xml;base64,aGVsbG8="))
    bad(image("data:image/png;base64,%%%"))
    bad(image("data:image/png;base64,"))
    bad(image("data:image/png;base64,aGVsbG8=", "system") + ",$user")
    val p = """{"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8="}}"""
    bad("""{"role":"user","content":[${List(5) { p }.joinToString(",")}]}""", status = 413)
    bad(image("data:image/png;base64," + "A".repeat(((MAX_IMAGE_BYTES + 2) / 3) * 4 + 4)), status = 413)
  }

  @Test fun nativeToolCallsAreStructuredAndThoughtChannelsAreNotLeaked() {
    val native = Message.model(Contents.of(Content.Text("检查")),
      listOf(ToolCall("weather", mapOf("city" to "北京"))), mapOf("analysis" to "private thoughts"))
    val deltas = LiteRtProtocol.deltas(native)
    assertEquals(ApiDelta.Text("检查"), deltas[0])
    val call = (deltas[1] as ApiDelta.Tools).calls.single()
    assertTrue(call.id.startsWith("call_")); assertEquals("weather", call.name)
    assertEquals("北京", JsonParser.parseString(call.arguments).asJsonObject["city"].asString)
    assertFalse(deltas.toString().contains("private thoughts"))
    assertEquals(0, call.wire(0)["index"])
  }
  @Test fun nativeToolConversationIsPreparedWithoutMetadataOptIn() {
    // Reproduce the reported curl: tool request plus a model file whose metadata does
    // not declare function calling. Request preparation needs no opt-in flag/name match.
    val request = parse("""{"role":"system","content":"用中文回答"},$user""",
      """, "tools":[$tool],"tool_choice":"required","temperature":0,"max_tokens":512""")
    val messages = LiteRtProtocol.messages(request) { error("No image expected") }
    val config = LiteRtProtocol.conversationConfig(request,messages)
    assertEquals(1,config.tools.size)
    assertFalse(config.automaticToolCalling)
    assertTrue(config.initialMessages.isEmpty())
    assertTrue(config.systemInstruction.toString().startsWith("用中文回答"))
    assertTrue(config.systemInstruction.toString().contains("must call"))
    assertEquals(512,config.maxOutputToken)
  }

  @Test fun nativeToolContinuationRetainsAssistantCallAndResumesFromResults() {
    val request = parse("$user,$assistant,$result2,$result1", """, "tools":[$tool]""")
    val messages = LiteRtProtocol.messages(request) { error("No image expected") }
    val config = LiteRtProtocol.conversationConfig(request,messages)
    assertEquals(1,config.tools.size)
    assertFalse(config.automaticToolCalling)
    assertEquals(2,config.initialMessages.size)
    assertEquals(2,config.initialMessages.last().toolCalls.size)
    assertEquals(2,messages.last().contents.contents.filterIsInstance<Content.ToolResponse>().size)
    val none = request.copy(toolChoice = "none")
    assertTrue(LiteRtProtocol.conversationConfig(none,messages).tools.isEmpty())
  }

}
