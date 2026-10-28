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
    override fun generate(request: ChatRequest, emit: (String) -> Unit) {
      requests.add(request); emit("你好"); emit("，世界")
    }
    override fun close() {}
  }
  private fun request(server: OpenAiServer, path: String, body: String? = null, token: String? = key): Pair<Int,String> {
    val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
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
      val bad = listOf("{", body(",\"tools\":[]"), body(",\"stream\":\"yes\""),body(",\"max_tokens\":1.5"),
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
      override fun generate(request: ChatRequest, emit: (String) -> Unit) { entered.countDown(); release.await(3,TimeUnit.SECONDS); emit("ok") }
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
      override fun generate(request: ChatRequest, emit: (String) -> Unit) { throw IllegalStateException("private filesystem path") }
    }
    OpenAiServer("127.0.0.1",0,key,backend).use { server ->
      val (status,text) = request(server,"/v1/chat/completions",body())
      assertEquals(500,status); assertTrue(text.contains("server_error")); assertFalse(text.contains("private filesystem"))
      val (streamStatus,stream) = request(server,"/v1/chat/completions",body(",\"stream\":true"))
      assertEquals(200,streamStatus); assertTrue(stream.contains("server_error")); assertTrue(stream.contains("[DONE]"))
    }
  }
}
