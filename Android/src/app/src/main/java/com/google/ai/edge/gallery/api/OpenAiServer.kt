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

internal interface ChatBackend : Closeable {
  val modelId: String
  fun validate(request: ChatRequest) {}
  fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit)
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
      if (length !in 1..MAX_REQUEST_BYTES) fail(413, "Request body must be between 1 byte and 12 MiB")
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
        backend.validate(request)
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: Map<String, Any>, finish: String? = null) = mapOf(
          "id" to id, "object" to "chat.completion.chunk", "created" to created,
          "model" to backend.modelId,
          "choices" to listOf(mapOf("index" to 0, "delta" to delta, "finish_reason" to finish)))
        val text = StringBuilder()
        val calls = mutableListOf<ApiToolCall>()
        if (request.stream) {
          head(output, 200, "text/event-stream; charset=utf-8")
          streaming = true
          event(output, chunk(mapOf("role" to "assistant")))
        }
        backend.generate(request) { delta ->
          when (delta) {
            is ApiDelta.Text -> {
              text.append(delta.text)
              if (request.stream) event(output, chunk(mapOf("content" to delta.text)))
            }
            is ApiDelta.Tools -> {
              val allowed = request.activeTools.map { it["name"].asString }.toSet()
              if (delta.calls.any { it.name !in allowed } ||
                  (!request.parallelToolCalls && calls.size + delta.calls.size > 1)) {
                throw ApiFailure(422, "Model returned calls outside the requested tool choice")
              }
              val offset = calls.size
              calls.addAll(delta.calls)
              if (request.stream && delta.calls.isNotEmpty()) event(output, chunk(mapOf(
                "tool_calls" to delta.calls.mapIndexed { index, call -> call.wire(offset + index) })))
            }
          }
        }
        if (request.toolChoice == "required" && calls.isEmpty()) {
          throw ApiFailure(422, "Model did not produce the required tool call; retry or change the prompt")
        }
        val finish = if (calls.isEmpty()) "stop" else "tool_calls"
        if (request.stream) {
          event(output, chunk(emptyMap(), finish))
          output.write("data: [DONE]\n\n".toByteArray()); output.flush()
        } else {
          val message = mutableMapOf<String, Any?>("role" to "assistant",
            "content" to text.toString().takeIf { it.isNotEmpty() || calls.isEmpty() })
          if (calls.isNotEmpty()) message["tool_calls"] = calls.map { it.wire() }
          json(output, 200, mapOf("id" to id, "object" to "chat.completion", "created" to created,
            "model" to backend.modelId, "choices" to listOf(mapOf("index" to 0,
              "message" to message, "finish_reason" to finish))))
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

  internal fun parse(body: String): ChatRequest = ApiProtocol.parse(body, backend.modelId)

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
