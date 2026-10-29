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

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OpenAiServerTest {
  private val key = "test-gallery-key-123456"
  private open class FakeBackend : ChatBackend {
    override val modelId = "local-test"
    val requests = mutableListOf<ChatRequest>()
    override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
      requests.add(request); emit(ApiDelta.Text("你好")); emit(ApiDelta.Text("，世界"))
    }
    override fun close() {}
  }
  private fun request(server: OpenAiServer, path: String, body: String? = null, token: String? = key, host: String = "127.0.0.1"): Pair<Int,String> {
    val connection = URL("http://$host:${server.port}$path").openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 3000; connection.readTimeout = 3000
      token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
      if (body != null) {
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      }
      val status = connection.responseCode
      val text = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
      return status to text
    } finally { connection.disconnect() }
  }
  private fun body(extra: String = "") = """{"model":"local-test","messages":[{"role":"user","content":"你好"}]$extra}"""

  @Test fun healthProbeIsPublicButContainsNoModelOrKey() {
    OpenAiServer("127.0.0.1",0,key,FakeBackend()).use { server ->
      val (status, text) = request(server,"/health",token=null)
      assertEquals(200,status)
      assertEquals("ok",JsonParser.parseString(text).asJsonObject["status"].asString)
      assertFalse(text.contains(key)); assertFalse(text.contains("local-test"))
      assertEquals(401,request(server,"/v1/models",token=null).first)
    }
  }

  @Test fun lanBindingAcceptsConnectionsToNonLoopbackAddress() {
    val host = java.net.NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { it.inetAddresses.toList() }
      .filterIsInstance<java.net.Inet4Address>()
      .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    org.junit.Assume.assumeNotNull(host)
    OpenAiServer("0.0.0.0",0,key,FakeBackend()).use { server ->
      assertEquals(200,request(server,"/health",token=null,host=host!!).first)
      assertEquals(200,request(server,"/v1/models",host=host).first)
      assertEquals(401,request(server,"/v1/models",token=null,host=host).first)
    }
  }

  @Test fun modelsRequireAuthentication() {
    OpenAiServer("127.0.0.1",0,key,FakeBackend()).use { server ->
      assertEquals(401, request(server,"/v1/models",token=null).first)
      assertEquals(401, request(server,"/v1/models",token="wrong").first)
      val (status, text) = request(server,"/v1/models")
      assertEquals(200,status)
      assertEquals("local-test", JsonParser.parseString(text).asJsonObject.getAsJsonArray("data")[0].asJsonObject["id"].asString)
      assertEquals(404,request(server,"/missing").first)
      assertEquals(405,request(server,"/v1/chat/completions").first)
    }
  }
  @Test fun nonStreamingAndHistoryRemainRequestScoped() {
    val backend = FakeBackend()
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val history = """{"model":"local-test","messages":[{"role":"system","content":"用中文"},{"role":"user","content":"第一问"},{"role":"assistant","content":"第一答"},{"role":"user","content":"第二问"}],"temperature":0.2,"max_tokens":32}"""
      val (status, text) = request(server,"/v1/chat/completions",history)
      assertEquals(200,status)
      val result = JsonParser.parseString(text).asJsonObject
      assertEquals("chat.completion",result["object"].asString)
      assertEquals("你好，世界",result.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")["content"].asString)
      assertEquals(4,backend.requests[0].messages.size)
      assertEquals(32,backend.requests[0].maxTokens)
      assertEquals(200,request(server,"/v1/chat/completions",body()).first)
      assertEquals(1,backend.requests[1].messages.size)
    }
  }
  @Test fun streamHasOpenAiChunksAndDoneSentinel() {
    OpenAiServer("127.0.0.1",0,key,FakeBackend()).use { server ->
      val (status,text) = request(server,"/v1/chat/completions",body(",\"stream\":true"))
      assertEquals(200,status)
      val events = text.split("\n\n").filter { it.isNotBlank() }.map { it.removePrefix("data: ") }
      assertEquals("[DONE]",events.last())
      val chunks = events.dropLast(1).map { JsonParser.parseString(it).asJsonObject }
      assertEquals(1,chunks.map { it["id"].asString }.distinct().size)
      assertEquals("assistant", chunks.first().getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")["role"].asString)
      assertEquals("stop", chunks.last().getAsJsonArray("choices")[0].asJsonObject["finish_reason"].asString)
      assertTrue(text.contains("你好"))
    }
  }
  @Test fun invalidAndUnsupportedInputsFailBeforeInference() {
    val backend = FakeBackend()
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val bad = listOf("{", body(",\"unknown\":[]"), body(",\"stream\":\"yes\""),body(",\"max_tokens\":1.5"),
        body(",\"temperature\":-1"),body(",\"top_p\":2"),body(",\"max_tokens\":2,\"max_completion_tokens\":3"),
        body().replace("\"你好\"","[]"),body().replace("\"user\"","\"assistant\""))
      bad.forEach { assertEquals(it,400,request(server,"/v1/chat/completions",it).first) }
      assertEquals(404,request(server,"/v1/chat/completions",body().replace("local-test","missing")).first)
      assertTrue(backend.requests.isEmpty())
    }
  }
  @Test fun concurrentInferenceReturnsBusyButModelsStayAvailable() {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val backend = object : FakeBackend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) { entered.countDown(); release.await(3,TimeUnit.SECONDS); emit(ApiDelta.Text("ok")) }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val first = java.util.concurrent.Executors.newSingleThreadExecutor()
      try {
        val result = first.submit<Pair<Int,String>> { request(server,"/v1/chat/completions",body()) }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        assertEquals(429,request(server,"/v1/chat/completions",body()).first)
        assertEquals(200,request(server,"/v1/models").first)
        release.countDown(); assertEquals(200,result.get(3,TimeUnit.SECONDS).first)
      } finally { release.countDown(); first.shutdownNow() }
    }
  }
  @Test fun backendFailuresAreStructuredAndDoNotExposeDetails() {
    val backend = object : FakeBackend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) { throw IllegalStateException("private filesystem path") }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (status,text) = request(server,"/v1/chat/completions",body())
      assertEquals(500,status); assertTrue(text.contains("server_error")); assertFalse(text.contains("private filesystem"))
      val (streamStatus,stream) = request(server,"/v1/chat/completions",body(",\"stream\":true"))
      assertEquals(200,streamStatus); assertTrue(stream.contains("server_error")); assertTrue(stream.contains("[DONE]"))
    }
  }
  @Test fun toolCallsRoundTripInJsonAndSse() {
    val backend = object : FakeBackend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
        requests.add(request)
        if (request.messages.last().role == "tool") emit(ApiDelta.Text("北京晴"))
        else emit(ApiDelta.Tools(listOf(ApiToolCall("call_1", "weather", "{\"city\":\"北京\"}"))))
      }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val tool = """, "tools":[{"type":"function","function":{"name":"weather","parameters":{"type":"object"}}}]"""
      val (status, text) = request(server,"/v1/chat/completions",body(tool))
      assertEquals(200,status)
      val choice = JsonParser.parseString(text).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
      assertEquals("tool_calls",choice["finish_reason"].asString)
      val message = choice.getAsJsonObject("message")
      assertTrue(message["content"].isJsonNull)
      val call = message.getAsJsonArray("tool_calls")[0].asJsonObject
      assertEquals("call_1",call["id"].asString)
      assertEquals("北京",JsonParser.parseString(call.getAsJsonObject("function")["arguments"].asString).asJsonObject["city"].asString)
      val continuation = """{"model":"local-test","messages":[{"role":"user","content":"天气"},$message,{"role":"tool","tool_call_id":"call_1","content":"晴"}]$tool}"""
      assertEquals(200,request(server,"/v1/chat/completions",continuation).first)
      assertEquals("tool",backend.requests.last().messages.last().role)
      val (streamStatus, stream) = request(server,"/v1/chat/completions",body(tool + ",\"stream\":true"))
      assertEquals(200,streamStatus)
      val events = stream.split("\n\n").filter { it.startsWith("data: {") }.map { JsonParser.parseString(it.removePrefix("data: ")).asJsonObject }
      val toolDelta = events[1].getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").getAsJsonArray("tool_calls")[0].asJsonObject
      assertEquals(0,toolDelta["index"].asInt); assertEquals("call_1",toolDelta["id"].asString)
      assertEquals("tool_calls",events.last().getAsJsonArray("choices")[0].asJsonObject["finish_reason"].asString)
      assertTrue(stream.endsWith("data: [DONE]\n\n"))
    }
  }

  @Test fun requiredToolChoiceDoesNotSilentlyReturnPlainText() {
    OpenAiServer("127.0.0.1",0,key,FakeBackend()).use { server ->
      val extra = """, "tools":[{"type":"function","function":{"name":"weather"}}],"tool_choice":"required""""
      assertEquals(422,request(server,"/v1/chat/completions",body(extra)).first)
      val (status, stream) = request(server,"/v1/chat/completions",body(extra + ",\"stream\":true"))
      assertEquals(200,status); assertTrue(stream.contains("422")); assertFalse(stream.contains("\"finish_reason\":\"stop\""))
    }
  }

  @Test fun unsupportedModelCapabilitiesFailBeforeSseHeaders() {
    val backend = object : FakeBackend() {
      override fun validate(request: ChatRequest) { throw ApiFailure(400,"Selected model does not support images") }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (status,text) = request(server,"/v1/chat/completions",body(",\"stream\":true"))
      assertEquals(400,status); assertFalse(text.contains("[DONE]")); assertTrue(backend.requests.isEmpty())
    }
  }

  @Test fun imageContentReachesBackendWithTextAndLargeBody() {
    val backend = FakeBackend()
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val data = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(800_000))
      val body = """{"model":"local-test","messages":[{"role":"user","content":[{"type":"text","text":"描述图片"},{"type":"image_url","image_url":{"url":"$data"}}]}]}"""
      assertTrue(body.toByteArray().size > 1048576)
      assertEquals(200,request(server,"/v1/chat/completions",body).first)
      assertEquals("描述图片",backend.requests.single().messages.single().content)
      assertEquals(data,(backend.requests.single().messages.single().parts[1] as ApiPart.Image).url)
    }
  }

  @Test fun forbiddenOrParallelModelCallsAreRejected() {
    val backend = object : FakeBackend() {
      override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
        emit(ApiDelta.Tools(listOf(ApiToolCall("call_1","weather","{}"),ApiToolCall("call_2","weather","{}"))))
      }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val tools = """, "tools":[{"type":"function","function":{"name":"weather"}}]"""
      assertEquals(422,request(server,"/v1/chat/completions",body(tools + ",\"parallel_tool_calls\":false")).first)
      assertEquals(422,request(server,"/v1/chat/completions",body(tools + ",\"tool_choice\":\"none\"")).first)
      assertEquals(200,request(server,"/v1/chat/completions",body(tools)).first)
    }
  }

  @Test fun configuredContextIsAdvertisedAndControlsOutputLimits() {
    val backend = object : FakeBackend() { override val contextTokens = 131072 }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (_, models) = request(server,"/v1/models")
      assertEquals(131072,JsonParser.parseString(models).asJsonObject.getAsJsonArray("data")[0].asJsonObject["context_length"].asInt)
      assertEquals(200,request(server,"/v1/chat/completions",body(",\"max_tokens\":65536")).first)
      assertEquals(65536,backend.requests.last().maxTokens)
      assertEquals(400,request(server,"/v1/chat/completions",body(",\"max_completion_tokens\":131073")).first)
    }
    val defaultBackend = FakeBackend()
    OpenAiServer("127.0.0.1",0,key,defaultBackend).use { server ->
      assertEquals(32768,defaultBackend.contextTokens)
      assertEquals(200,request(server,"/v1/chat/completions",body(",\"max_tokens\":8192")).first)
      assertEquals(400,request(server,"/v1/chat/completions",body(",\"max_tokens\":32769")).first)
    }
  }

  @Test fun context256kIsAcceptedWithoutChanging32kDefault() {
    val backend = object : FakeBackend() { override val contextTokens = 262144 }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (_, models) = request(server,"/v1/models")
      assertEquals(262144,JsonParser.parseString(models).asJsonObject.getAsJsonArray("data")[0].asJsonObject["context_length"].asInt)
      assertEquals(200,request(server,"/v1/chat/completions",body(",\"max_completion_tokens\":196608")).first)
      assertEquals(196608,backend.requests.last().maxTokens)
      assertEquals(400,request(server,"/v1/chat/completions",body(",\"max_tokens\":262145")).first)
    }
    assertEquals(32768,FakeBackend().contextTokens)
    assertTrue(validContextTokens(262144))
    assertFalse(validContextTokens(262145))
  }

}
