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
  val contextTokens: Int get() = DEFAULT_CONTEXT_TOKENS
  val defaultThinkingEnabled: Boolean get() = true
  val defaultThinkingBudget: Int get() = DEFAULT_THINKING_BUDGET
  fun validate(request: ChatRequest) {}
  fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit)
}
internal class ApiFailure(val status: Int, override val message: String, val param: String? = null) : Exception(message)

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
    var responsesOutput: ApiResponseOutput? = null
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
      if (requestLine[0] == "OPTIONS") { head(output,204,"application/json"); output.flush(); return }
      // A public liveness probe reveals no model data and helps diagnose LAN reachability.
      if (requestLine[0] == "GET" && requestLine[1] == "/health") {
        json(output, 200, mapOf("status" to "ok"))
        return
      }
      val authorization = headers["authorization"] ?: ""
      if (!authorization.substringBefore(' ').equals("Bearer",true) || !MessageDigest.isEqual(
          authorization.substringAfter(' ', "").toByteArray(), apiKey.toByteArray())) {
        fail(401, "Invalid API key")
      }
      val method = requestLine[0]
      val path = requestLine[1].substringBefore('?')
      val model = mapOf("id" to backend.modelId,"object" to "model","created" to 0,"owned_by" to "local")
      if (method == "GET" && path == "/v1/models") {
        json(output,200,mapOf("object" to "list","data" to listOf(model))); return
      }
      if (method == "GET" && path.startsWith("/v1/models/")) {
        val name = java.net.URLDecoder.decode(path.removePrefix("/v1/models/").replace("+","%2B"),"UTF-8")
        if (name != backend.modelId) throw ApiFailure(404,"Unknown model","model")
        json(output,200,model); return
      }
      if (path !in setOf("/v1/chat/completions","/v1/responses")) fail(404, "Unknown endpoint")
      if (method != "POST") fail(405, "Use POST for generation requests")
      if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
        fail(415,"Content-Type must be application/json")
      }
      val bytes = readBody(input,headers)
      val body = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { fail(400,"Request body must be valid UTF-8") }
      val responsesRequest = if (path == "/v1/responses") ApiResponses.parse(body,backend) else null
      val request = responsesRequest?.chat ?: parse(body)
      if (!busy.compareAndSet(false, true)) fail(429, "Model is busy; retry after the current request")
      try {
        backend.validate(request)
        if (responsesRequest != null) {
          if (request.stream) { head(output,200,"text/event-stream; charset=utf-8"); streaming = true }
          val response = ApiResponseOutput(responsesRequest,backend.modelId,
            if (request.stream) { type,data -> responseEvent(output,type,data) } else null)
          responsesOutput = response
          response.begin()
          val result = complete(backend,request,response::delta)
          val value = response.finish(result)
          if (!request.stream) json(output,200,value) else output.flush()
          return
        }
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        fun chunk(delta: Map<String, Any>, finish: String? = null) = mapOf(
          "id" to id, "object" to "chat.completion.chunk", "created" to created,
          "model" to backend.modelId,
          "choices" to listOf(mapOf("index" to 0, "delta" to delta,"logprobs" to null, "finish_reason" to finish))) +
          (if (request.includeUsage) mapOf("usage" to null) else emptyMap())
        var toolIndex = 0
        if (request.stream) {
          head(output,200,"text/event-stream; charset=utf-8"); streaming = true
          event(output,chunk(mapOf("role" to "assistant")))
        }
        val result = complete(backend,request) { delta ->
          if (request.stream) when (delta) {
            is ApiDelta.Text -> event(output,chunk(mapOf("content" to delta.text)))
            is ApiDelta.Reasoning -> Unit // Chat Completions has no standard raw-reasoning field.
            is ApiDelta.Tools -> {
              val indexed = delta.calls.map { it.wire(toolIndex++) }
              if (indexed.isNotEmpty()) event(output,chunk(mapOf("tool_calls" to indexed)))
            }
            is ApiDelta.Stats -> Unit
          }
        }
        if (request.stream) {
          event(output,chunk(emptyMap(),result.finish))
          if (request.includeUsage) event(output,mapOf("id" to id,"object" to "chat.completion.chunk",
            "created" to created,"model" to backend.modelId,"choices" to emptyList<Any>(),
            "usage" to result.stats?.usage?.wire()))
          output.write("data: [DONE]\n\n".toByteArray()); output.flush()
        } else {
          json(output,200,mapOf("id" to id,"object" to "chat.completion","created" to created,
            "model" to backend.modelId,"choices" to listOf(mapOf("index" to 0,
              "message" to result.message(),"logprobs" to null,"finish_reason" to result.finish)),
            "usage" to result.stats?.usage?.wire(),
            "service_tier" to "default","metadata" to request.metadata))
        }
      } finally { busy.set(false) }
    } catch (e: Exception) {
      val status = (e as? ApiFailure)?.status ?: if (e is java.net.SocketTimeoutException) 408 else 500
      val code = when(status) { 401 -> "invalid_api_key"; 404 -> if ((e as? ApiFailure)?.param == "model") "model_not_found" else "not_found";
        408,504 -> "request_timeout"; 413 -> "request_too_large"; 422 -> "model_output_invalid"; 429 -> "rate_limit_exceeded";
        500 -> "inference_error"; else -> "invalid_request" }
      val error = mapOf("error" to mapOf("message" to (if (status == 500) "Local inference failed" else e.message),
        "type" to (if (status >= 500) "server_error" else if (status == 429) "rate_limit_error" else if (status == 401) "authentication_error" else "invalid_request_error"), "code" to code,"param" to (e as? ApiFailure)?.param))
      if (streaming && responsesOutput != null) {
        responsesOutput.failed(if (status == 500) "Local inference failed" else e.message ?: "Request failed",code)
        output.flush()
      } else if (streaming) { event(output, error); output.write("data: [DONE]\n\n".toByteArray()); output.flush() }
      else json(output, status, error)
    }
  }

  internal fun parse(body: String): ChatRequest = ApiProtocol.parse(body, backend.modelId, backend.contextTokens, backend.defaultThinkingEnabled, backend.defaultThinkingBudget)

  private fun readBody(input: InputStream, headers: Map<String,String>): ByteArray {
    val transfer = headers["transfer-encoding"]
    if (transfer != null && headers.containsKey("content-length")) fail(400,"Do not combine Content-Length and Transfer-Encoding")
    fun readExactly(length: Int): ByteArray {
      val bytes = ByteArray(length)
      var offset = 0
      while (offset < length) {
        val n = input.read(bytes,offset,length - offset)
        if (n < 0) fail(400,"Incomplete request body")
        offset += n
      }
      return bytes
    }
    if (transfer == null) {
      val length = headers["content-length"]?.toIntOrNull() ?: fail(411,"Content-Length or chunked encoding required")
      if (length !in 1..MAX_REQUEST_BYTES) fail(413,"Request body must be between 1 byte and 12 MiB")
      return readExactly(length)
    }
    if (!transfer.equals("chunked",true)) fail(400,"Only chunked Transfer-Encoding is supported")
    val body = java.io.ByteArrayOutputStream()
    while (true) {
      val sizeText = line(input).substringBefore(';').trim()
      if (!Regex("[0-9a-fA-F]{1,16}").matches(sizeText)) fail(400,"Invalid chunk size")
      val length = sizeText.toLongOrNull(16) ?: fail(413,"Chunk too large")
      if (length > MAX_REQUEST_BYTES - body.size()) fail(413,"Request body exceeds 12 MiB")
      if (length == 0L) {
        var trailerBytes = 0
        while (true) {
          val trailer = line(input)
          trailerBytes += trailer.length
          if (trailerBytes > 16384) fail(431,"Trailers too large")
          if (trailer.isEmpty()) break
          if (!trailer.contains(':')) fail(400,"Invalid trailer")
        }
        if (body.size() == 0) fail(400,"Empty request body")
        return body.toByteArray()
      }
      body.write(readExactly(length.toInt()))
      if (input.read() != 13 || input.read() != 10) fail(400,"Invalid chunk terminator")
    }
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
  private fun head(out: OutputStream, status: Int, type: String, length: Int? = null) {
    val reason = when(status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; else -> "Error" }
    val extra = buildString {
      if (length != null) append("Content-Length: $length\r\n")
      if (status == 401) append("WWW-Authenticate: Bearer\r\n")
      if (status == 429) append("Retry-After: 1\r\n")
      if (status == 405) append("Allow: POST, OPTIONS\r\n")
    }
    out.write(("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nConnection: close\r\n" +
      "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Request-Id: req_${UUID.randomUUID()}\r\n" +
      "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: Authorization, Content-Type, OpenAI-Beta\r\n" +
      "Access-Control-Expose-Headers: X-Request-Id, Retry-After\r\n$extra\r\n").toByteArray())
  }
  private fun json(out: OutputStream, status: Int, value: Any) {
    val bytes = gson.toJson(value).toByteArray(Charsets.UTF_8)
    head(out,status,"application/json; charset=utf-8",bytes.size); out.write(bytes); out.flush()
  }
  private fun responseEvent(out: OutputStream, type: String, value: Any) {
    out.write("event: $type\ndata: ${gson.toJson(value)}\n\n".toByteArray(Charsets.UTF_8)); out.flush()
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
