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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class ApiResponsesTest {
  private val key = "test-gallery-key-123456"
  private open class Backend : ChatBackend {
    override val modelId = "test"
    val requests = mutableListOf<ChatRequest>()
    override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
      requests.add(request)
      emit(ApiDelta.Reasoning("分析")); emit(ApiDelta.Text("答案"))
      emit(ApiDelta.Stats(ApiStats(ApiUsage(20,10),20.0)))
    }
    override fun close() {}
  }
  private fun request(server: OpenAiServer, body: String): Pair<Int,String> {
    val connection = URL("http://127.0.0.1:${server.port}/v1/responses").openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 3000; connection.readTimeout = 3000
      connection.requestMethod = "POST"; connection.doOutput = true
      connection.setRequestProperty("Authorization","Bearer $key")
      connection.setRequestProperty("Content-Type","application/json")
      connection.outputStream.use { it.write(body.toByteArray()) }
      val status = connection.responseCode
      return status to (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
  }
  @Test fun textResponseHasTypedOutputAndTruthfulCounters() {
    val backend = Backend()
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (status,text) = request(server,"""{"model":"test","input":"hello","instructions":"中文","store":false}""")
      assertEquals(200,status)
      val result = JsonParser.parseString(text).asJsonObject
      assertEquals("response",result["object"].asString); assertEquals("completed",result["status"].asString)
      val output = result.getAsJsonArray("output")
      assertEquals("reasoning",output[0].asJsonObject["type"].asString)
      assertEquals("分析",output[0].asJsonObject.getAsJsonArray("content")[0].asJsonObject["text"].asString)
      assertEquals("答案",output[1].asJsonObject.getAsJsonArray("content")[0].asJsonObject["text"].asString)
      assertFalse(result.has("x_gallery_usage")); assertFalse(result.has("timings"))
      assertTrue(result["usage"].isJsonNull); assertFalse(result.has("store"))
      assertEquals("system",backend.requests.single().messages.first().role)
      // Read by the optional official SDK contract check; this is generated test output.
      java.io.File((System.getProperty("gallery.contractFixtures") ?: "build/contract-fixtures")).mkdirs()
      java.io.File((System.getProperty("gallery.contractFixtures") ?: "build/contract-fixtures"),"response.json").writeText(text)
    }
  }
  @Test fun responseSseEventsAreOrderedAndEndWithCompletedResponse() {
    OpenAiServer("127.0.0.1",0,key,Backend()).use { server ->
      val (status,text) = request(server,"""{"model":"test","input":"hello","stream":true}""")
      assertEquals(200,status)
      val events = text.split("\n\n").filter { it.startsWith("event: ") }.map { block ->
        val lines = block.lines()
        val value = JsonParser.parseString(lines.first { it.startsWith("data: ") }.removePrefix("data: ")).asJsonObject
        assertEquals(lines.first().removePrefix("event: "),value["type"].asString)
        value
      }
      assertEquals("response.created",events.first()["type"].asString)
      assertTrue(events.any { it["type"].asString == "response.reasoning_text.delta" })
      assertTrue(events.any { it["type"].asString == "response.output_text.delta" })
      assertEquals("response.completed",events.last()["type"].asString)
      assertEquals(events.indices.toList(),events.map { it["sequence_number"].asInt })
      assertFalse(text.contains("[DONE]"))
      val fixtures = java.io.File((System.getProperty("gallery.contractFixtures") ?: "build/contract-fixtures")).apply { mkdirs() }
      java.io.File(fixtures,"response-events.json").writeText(Gson().toJson(events))
    }
  }
  @Test fun functionCallsAndReasoningCanBeReplayedStatelessly() {
    val backend = object : Backend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
        requests.add(request)
        if (request.messages.last().role == "tool") emit(ApiDelta.Text("已查询")) else {
          emit(ApiDelta.Reasoning("需要查天气"))
          emit(ApiDelta.Tools(listOf(ApiToolCall("call_1","weather","{\"city\":\"北京\"}"))))
        }
      }
    }
    val tools = """[{"type":"function","name":"weather","parameters":{"type":"object"}}]"""
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (_,text) = request(server,"""{"model":"test","input":"天气","tools":$tools}""")
      val fixtures = java.io.File((System.getProperty("gallery.contractFixtures") ?: "build/contract-fixtures")).apply { mkdirs() }
      java.io.File(fixtures,"response-tools.json").writeText(text)
      val first = JsonParser.parseString(text).asJsonObject
      val items = first.getAsJsonArray("output")
      assertEquals("function_call",items[1].asJsonObject["type"].asString)
      val input = com.google.gson.JsonArray().apply {
        add(JsonParser.parseString("""{"role":"user","content":"天气"}"""))
        items.forEach { add(it) }
        add(JsonParser.parseString("""{"type":"function_call_output","call_id":"call_1","output":"晴"}"""))
      }
      val (status,answer) = request(server,"""{"model":"test","input":$input,"tools":$tools}""")
      assertEquals(200,status); assertTrue(answer.contains("已查询"))
      assertEquals("需要查天气",backend.requests.last().messages[1].reasoningContent)
      assertEquals("tool",backend.requests.last().messages.last().role)
      val (_,stream) = request(server,"""{"model":"test","input":"天气","tools":$tools,"stream":true}""")
      val events = stream.split("\n\n").filter { it.startsWith("event: ") }.map {
        JsonParser.parseString(it.lines().first { line -> line.startsWith("data: ") }.removePrefix("data: ")).asJsonObject
      }
      assertTrue(events.any { it["type"].asString == "response.function_call_arguments.delta" })
      assertTrue(events.any { it["type"].asString == "response.function_call_arguments.done" })
      java.io.File(fixtures,"response-tool-events.json").writeText(Gson().toJson(events))
    }
  }
  @Test fun responseImagesAndNativeJsonFormatAreMapped() {
    val source = """{"model":"test","input":[{"role":"user","content":[{"type":"input_text","text":"描述"},{"type":"input_image","image_url":"data:image/png;base64,aGVsbG8="}]}],"text":{"format":{"type":"json_schema","name":"answer","strict":true,"schema":{"type":"object"}}},"reasoning":{"effort":"none"}}"""
    val r = ApiResponses.parse(source,Backend()).chat
    assertTrue(r.messages.single().parts[1] is ApiPart.Image)
    assertEquals("json_schema",r.responseFormat!!["type"].asString)
    assertFalse(r.enableThinking)
  }
  @Test fun hostedStateAndToolsAreRejectedExplicitly() {
    for (extra in listOf(",\"store\":true",",\"background\":true",",\"previous_response_id\":\"resp_old\"",
      ",\"tools\":[{\"type\":\"web_search\"}]",",\"include\":[\"reasoning.encrypted_content\"]",
      ",\"reasoning\":{\"summary\":\"auto\"}",",\"truncation\":\"auto\"")) {
      try { ApiResponses.parse("""{"model":"test","input":"hello"$extra}""",Backend()); fail(extra) }
      catch (e: ApiFailure) { assertEquals(400,e.status) }
    }
  }
  @Test fun responsesReportLengthAndStreamFailures() {
    val limited = object : Backend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
        emit(ApiDelta.Text("part")); emit(ApiDelta.Stats(ApiStats(ApiUsage(10,4))))
      }
    }
    OpenAiServer("127.0.0.1",0,key,limited).use { server ->
      val (status,text) = request(server,"""{"model":"test","input":"hello","max_output_tokens":4}""")
      assertEquals(200,status)
      val result = JsonParser.parseString(text).asJsonObject
      assertEquals("incomplete",result["status"].asString)
      assertEquals("max_output_tokens",result.getAsJsonObject("incomplete_details")["reason"].asString)
    }
    val failed = object : Backend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) { throw IllegalStateException("private path") }
    }
    OpenAiServer("127.0.0.1",0,key,failed).use { server ->
      val (status,text) = request(server,"""{"model":"test","input":"hello","stream":true}""")
      assertEquals(200,status); assertTrue(text.contains("event: response.failed")); assertTrue(text.contains("event: error"))
      assertFalse(text.contains("private path"))
      val events = text.split("\n\n").filter { it.startsWith("event: ") }.map {
        JsonParser.parseString(it.lines().first { line -> line.startsWith("data: ") }.removePrefix("data: ")).asJsonObject
      }
      val fixtures = java.io.File(System.getProperty("gallery.contractFixtures") ?: "build/contract-fixtures").apply { mkdirs() }
      java.io.File(fixtures,"response-error-events.json").writeText(Gson().toJson(events))
    }
  }
}
