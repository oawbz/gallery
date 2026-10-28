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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ApiMessage(val role: String, val content: String)
internal data class ChatRequest(
  val messages: List<ApiMessage>, val stream: Boolean,
  val temperature: Double, val topP: Double, val maxTokens: Int?,
)
internal interface ChatBackend : Closeable {
  val modelId: String
  fun generate(request: ChatRequest, emit: (String) -> Unit)
}
internal class ApiFailure(val status: Int, override val message: String) : Exception(message)

/** A bounded HTTP/1.1 server. Each request owns its connection and inference conversation. */
internal class OpenAiServer(
  host: String, port: Int, private val apiKey: String, private val backend: ChatBackend,
) : Closeable {
  init { require(apiKey.length >= 16 && apiKey.all { it in '!'..'~' }) { "API key must contain at least 16 characters" } }
  private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
  private val listener = ServerSocket().apply {
    reuseAddress = true
    bind(java.net.InetSocketAddress(InetAddress.getByName(host), port))
  }
  val port: Int get() = listener.localPort
  private val sockets = ConcurrentHashMap.newKeySet<Socket>()
  private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
  private val busy = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)

  init {
    Thread({
      while (!closed.get()) {
        val socket = try { listener.accept() } catch (_: Exception) { break }
        sockets.add(socket)
        try {
          workers.execute {
            try { socket.use { handle(it) } } catch (_: Exception) { /* Client disconnected. */ }
            finally { sockets.remove(socket) }
          }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
          sockets.remove(socket)
          socket.close()
        }
      }
    }, "gallery-api-accept").apply { isDaemon = true; start() }
  }

  private fun handle(socket: Socket) {
    socket.soTimeout = 15_000
    val input = socket.getInputStream().buffered()
    val output = socket.getOutputStream()
    var streaming = false
    try {
      val requestLine = line(input).split(' ')
      if (requestLine.size != 3 || requestLine[2] != "HTTP/1.1") fail(400, "Invalid HTTP request")
      val headers = mutableMapOf<String, String>()
      var headerSize = 0
      while (true) {
        val line = line(input)
        headerSize += line.length
        if (headerSize > 16384) fail(431, "Headers too large")
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon < 1) fail(400, "Invalid header")
        val name = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
        if (headers.containsKey(name)) fail(400, "Duplicate header")
        headers[name] = line.substring(colon + 1).trim()
      }
      // A public liveness probe reveals no model data and helps diagnose LAN reachability.
      if (requestLine[0] == "GET" && requestLine[1] == "/health") {
        json(output, 200, mapOf("status" to "ok"))
        return
      }
      if (!MessageDigest.isEqual(
          (headers["authorization"] ?: "").toByteArray(), "Bearer $apiKey".toByteArray())) {
        fail(401, "Invalid API key")
      }
      val method = requestLine[0]
      val path = requestLine[1].substringBefore('?')
      if (method == "GET" && path == "/v1/models") {
        json(output, 200, mapOf("object" to "list", "data" to listOf(mapOf(
          "id" to backend.modelId, "object" to "model", "created" to 0, "owned_by" to "local"))))
        return
      }
      if (path != "/v1/chat/completions") fail(404, "Unknown endpoint")
      if (method != "POST") fail(405, "Use POST for chat completions")
      if (headers.containsKey("transfer-encoding")) fail(400, "Chunked request bodies are unsupported")
      if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
        fail(415, "Content-Type must be application/json")
      }
      val length = headers["content-length"]?.toIntOrNull() ?: fail(411, "Content-Length required")
      if (length !in 1..1048576) fail(413, "Request body must be between 1 byte and 1 MiB")
      val bytes = ByteArray(length)
      var offset = 0
      while (offset < length) {
        val n = input.read(bytes, offset, length - offset)
        if (n < 0) fail(400, "Incomplete request body")
        offset += n
      }
      val request = parse(String(bytes, Charsets.UTF_8))
      if (!busy.compareAndSet(false, true)) fail(429, "Model is busy; retry after the current request")
      try {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: Map<String, String>, finish: String? = null) = mapOf(
          "id" to id, "object" to "chat.completion.chunk", "created" to created,
          "model" to backend.modelId,
          "choices" to listOf(mapOf("index" to 0, "delta" to delta, "finish_reason" to finish)))
        if (request.stream) {
          head(output, 200, "text/event-stream; charset=utf-8")
          streaming = true
          event(output, chunk(mapOf("role" to "assistant")))
          backend.generate(request) { event(output, chunk(mapOf("content" to it))) }
          event(output, chunk(emptyMap(), "stop"))
          output.write("data: [DONE]\n\n".toByteArray()); output.flush()
        } else {
          val text = StringBuilder()
          backend.generate(request) { text.append(it) }
          json(output, 200, mapOf("id" to id, "object" to "chat.completion", "created" to created,
            "model" to backend.modelId, "choices" to listOf(mapOf("index" to 0,
              "message" to mapOf("role" to "assistant", "content" to text.toString()),
              "finish_reason" to "stop"))))
        }
      } finally { busy.set(false) }
    } catch (e: Exception) {
      val status = (e as? ApiFailure)?.status ?: 500
      val error = mapOf("error" to mapOf("message" to (if (status == 500) "Local inference failed" else e.message),
        "type" to (if (status >= 500) "server_error" else "invalid_request_error"), "code" to status))
      if (streaming) { event(output, error); output.write("data: [DONE]\n\n".toByteArray()); output.flush() }
      else json(output, status, error)
    }
  }

  internal fun parse(body: String): ChatRequest {
    try {
      val obj = JsonParser.parseString(body).asJsonObject
      fun string(o: JsonObject, key: String): String {
        val v = o.get(key) ?: fail(400, "Missing $key")
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail(400, "$key must be a string")
        return v.asString
      }
      if (string(obj, "model") != backend.modelId) fail(404, "Unknown model; use GET /v1/models")
      val supported = setOf("model", "messages", "stream", "temperature", "top_p", "max_tokens", "max_completion_tokens")
      obj.keySet().firstOrNull { it !in supported }?.let { fail(400, "Unsupported parameter: $it") }
      if (obj.has("max_tokens") && obj.has("max_completion_tokens")) fail(400, "Specify only one token limit")
      val array = obj.getAsJsonArray("messages") ?: fail(400, "messages is required")
      if (array.size() !in 1..256) fail(400, "messages must contain 1 to 256 items")
      val messages = array.map {
        val m = it.asJsonObject
        val role = string(m, "role")
        if (role !in setOf("system", "user", "assistant")) fail(400, "Only system, user and assistant roles are supported")
        if (m.keySet().any { key -> key !in setOf("role", "content") }) fail(400, "Unsupported message field")
        ApiMessage(role, string(m, "content"))
      }
      if (messages.last().role != "user") fail(400, "Last message must have user role")
      if (messages.drop(1).any { it.role == "system" }) fail(400, "System message must be first")
      val history = messages.drop(if (messages.first().role == "system") 1 else 0)
      if (history.withIndex().any { (i,m) -> m.role != if (i % 2 == 0) "user" else "assistant" }) {
        fail(400, "Messages must alternate user and assistant roles")
      }
      fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val v = obj.get(key) ?: return default
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) fail(400, "$key must be numeric")
        val n = v.asDouble
        if (!n.isFinite() || n !in range) fail(400, "$key is out of range")
        return n
      }
      val tokenKey = if (obj.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
      val tokens = if (obj.has(tokenKey)) number(tokenKey, 512.0, 1.0..4096.0).also {
        if (it % 1.0 != 0.0) fail(400, "Token limit must be an integer")
      }.toInt() else null
      val stream = obj.get("stream")?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) fail(400, "stream must be boolean")
        it.asBoolean
      } ?: false
      return ChatRequest(messages, stream, number("temperature", 0.7, 0.0..2.0), number("top_p", 0.95, 0.0..1.0), tokens)
    } catch (e: ApiFailure) { throw e }
    catch (_: Exception) { fail(400, "Invalid JSON request") }
  }

  private fun line(input: InputStream): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b < 0) fail(400, "Incomplete HTTP headers")
      if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
      if (bytes.size() >= 8192) fail(431, "Header line too long")
      bytes.write(b)
    }
  }
  private fun fail(status: Int, message: String): Nothing = throw ApiFailure(status, message)
  private fun head(out: OutputStream, status: Int, type: String) {
    out.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray())
  }
  private fun json(out: OutputStream, status: Int, value: Any) {
    head(out, status, "application/json; charset=utf-8"); out.write(gson.toJson(value).toByteArray(Charsets.UTF_8)); out.flush()
  }
  private fun event(out: OutputStream, value: Any) {
    out.write("data: ${gson.toJson(value)}\n\n".toByteArray(Charsets.UTF_8)); out.flush()
  }
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    listener.close()
    sockets.forEach { runCatching { it.close() } }
    workers.shutdownNow()
  }
}
