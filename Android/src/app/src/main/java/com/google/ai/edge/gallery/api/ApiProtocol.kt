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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

internal const val MAX_REQUEST_BYTES = 12 * 1024 * 1024
internal const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
internal const val MAX_TOTAL_IMAGE_BYTES = 8 * 1024 * 1024
internal const val MAX_IMAGES = 4
internal sealed interface ApiPart {
  data class Text(val text: String) : ApiPart
  data class Image(val url: String) : ApiPart
}
internal data class ApiToolCall(val id: String, val name: String, val arguments: String) {
  fun wire(index: Int? = null): Map<String, Any> = buildMap {
    if (index != null) put("index", index)
    put("id", id); put("type", "function")
    put("function", mapOf("name" to name, "arguments" to arguments))
  }
}
internal data class ApiMessage(
  val role: String, val content: String, val parts: List<ApiPart> = listOf(ApiPart.Text(content)),
  val toolCalls: List<ApiToolCall> = emptyList(), val toolCallId: String? = null,
)
internal data class ChatRequest(
  val messages: List<ApiMessage>, val stream: Boolean,
  val temperature: Double, val topP: Double, val maxTokens: Int?,
  val tools: List<JsonObject> = emptyList(), val toolChoice: String = "auto",
  val selectedTool: String? = null, val parallelToolCalls: Boolean = true,
) {
  val activeTools get() = if (toolChoice == "none") emptyList() else
    tools.filter { selectedTool == null || it["name"].asString == selectedTool }
}
internal sealed interface ApiDelta {
  data class Text(val text: String) : ApiDelta
  data class Tools(val calls: List<ApiToolCall>) : ApiDelta
}
internal fun invalid(message: String): Nothing = throw ApiFailure(400, message)
internal fun string(o: JsonObject, key: String): String {
  val v = o[key] ?: invalid("Missing $key")
  if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) invalid("$key must be a string")
  return v.asString
}
internal fun decodeDataImage(url: String): ByteArray {
  val comma = url.indexOf(',')
  if (comma < 0 || url.substring(0, comma).lowercase() !in setOf(
      "data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64")) {
    invalid("Use a Base64 PNG, JPEG or WebP data URL")
  }
  val encoded = url.substring(comma + 1)
  if (encoded.length > ((MAX_IMAGE_BYTES + 2) / 3) * 4) throw ApiFailure(413, "Image exceeds 4 MiB")
  val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
    invalid("Invalid image Base64")
  }
  if (bytes.isEmpty()) invalid("Image is empty")
  if (bytes.size > MAX_IMAGE_BYTES) throw ApiFailure(413, "Image exceeds 4 MiB")
  return bytes
}

/** Validates the tool-result state machine before any native inference or image downloads. */
internal object ApiProtocol {
  fun parse(body: String, modelId: String): ChatRequest {
    try {
      val obj = JsonParser.parseString(body).asJsonObject
      if (string(obj, "model") != modelId) throw ApiFailure(404, "Unknown model; use GET /v1/models")
      val supported = setOf("model", "messages", "stream", "temperature", "top_p", "max_tokens",
        "max_completion_tokens", "tools", "tool_choice", "parallel_tool_calls")
      obj.keySet().firstOrNull { it !in supported }?.let { invalid("Unsupported parameter: $it") }
      if (obj.has("max_tokens") && obj.has("max_completion_tokens")) invalid("Specify only one token limit")
      val tools = obj.getAsJsonArray("tools")?.map { element ->
        val tool = element.asJsonObject
        if (string(tool, "type") != "function" || tool.keySet().any { it !in setOf("type", "function") }) invalid("Only function tools are supported")
        val f = tool.getAsJsonObject("function") ?: invalid("Missing tool function")
        if (!Regex("[a-zA-Z0-9_-]{1,64}").matches(string(f, "name"))) invalid("Invalid tool name")
        if (f.keySet().any { it !in setOf("name", "description", "parameters", "strict") }) invalid("Unsupported function field")
        if (f.has("description")) string(f, "description")
        if (f.has("strict") && (!f["strict"].isJsonPrimitive || !f["strict"].asJsonPrimitive.isBoolean || f["strict"].asBoolean)) invalid("Strict tool schemas are not supported")
        val parameters = f.getAsJsonObject("parameters") ?: JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        if (string(parameters, "type") != "object") invalid("Tool parameters must be an object schema")
        f.deepCopy().apply { remove("strict"); add("parameters", parameters) }
      } ?: emptyList()
      val names = tools.map { it["name"].asString }
      if (tools.size > 64 || names.distinct().size != names.size) invalid("Use at most 64 uniquely named tools")
      val choice = obj["tool_choice"]
      val selected = if (choice?.isJsonObject == true) {
        val c = choice.asJsonObject
        if (string(c, "type") != "function" || c.keySet().any { it !in setOf("type", "function") }) invalid("Invalid tool_choice")
        val f = c.getAsJsonObject("function") ?: invalid("Missing selected function")
        if (f.keySet() != setOf("name")) invalid("Invalid selected function")
        string(f, "name").also { if (it !in names) invalid("Selected tool is not defined") }
      } else null
      val mode = if (selected != null) "required" else if (choice == null) "auto" else {
        if (!choice.isJsonPrimitive || !choice.asJsonPrimitive.isString) invalid("Invalid tool_choice")
        choice.asString.also { if (it !in setOf("none", "auto", "required")) invalid("Invalid tool_choice") }
      }
      if (mode == "required" && tools.isEmpty()) invalid("tool_choice requires tools")
      fun boolean(key: String, default: Boolean): Boolean = obj[key]?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid("$key must be boolean")
        it.asBoolean
      } ?: default
      val array = obj.getAsJsonArray("messages") ?: invalid("messages is required")
      if (array.size() !in 1..256) invalid("messages must contain 1 to 256 items")
      var images = 0
      val messages = array.map { element ->
        val m = element.asJsonObject
        val role = string(m, "role")
        if (role !in setOf("system", "user", "assistant", "tool")) invalid("Unsupported message role")
        val fields = when (role) {
          "assistant" -> setOf("role", "content", "tool_calls")
          "tool" -> setOf("role", "content", "tool_call_id")
          else -> setOf("role", "content")
        }
        if (m.keySet().any { it !in fields }) invalid("Unsupported message field")
        val calls = m.getAsJsonArray("tool_calls")?.map { parseCall(it.asJsonObject) } ?: emptyList()
        if (calls.size > 64) invalid("Too many tool calls")
        val content = m["content"]
        val parts: List<ApiPart> = when {
          content == null || content.isJsonNull -> {
            if (role != "assistant" || calls.isEmpty()) invalid("Missing content")
            emptyList()
          }
          content.isJsonPrimitive && content.asJsonPrimitive.isString -> listOf(ApiPart.Text(content.asString))
          content.isJsonArray -> {
            if (content.asJsonArray.size() !in 1..64) invalid("Use 1 to 64 content parts")
            content.asJsonArray.map { parsePart(it, role).also { p -> if (p is ApiPart.Image) images++ } }
          }
          else -> invalid("content must be a string or content parts")
        }
        ApiMessage(role, parts.filterIsInstance<ApiPart.Text>().joinToString("") { it.text }, parts, calls,
          if (role == "tool") string(m, "tool_call_id") else null)
      }
      if (images > MAX_IMAGES) throw ApiFailure(413, "Use at most 4 images per request")
      validateHistory(messages)
      fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val v = obj[key] ?: return default
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) invalid("$key must be numeric")
        val n = v.asDouble
        if (!n.isFinite() || n !in range) invalid("$key is out of range")
        return n
      }
      val tokenKey = if (obj.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
      val tokens = if (obj.has(tokenKey)) number(tokenKey, 512.0, 1.0..4096.0).also {
        if (it % 1.0 != 0.0) invalid("Token limit must be an integer")
      }.toInt() else null
      return ChatRequest(messages, boolean("stream", false), number("temperature", 0.7, 0.0..2.0),
        number("top_p", 0.95, 0.0..1.0), tokens, tools, mode, selected, boolean("parallel_tool_calls", true))
    } catch (e: ApiFailure) { throw e }
    catch (_: Exception) { invalid("Invalid JSON request") }
  }

  private fun parsePart(element: JsonElement, role: String): ApiPart {
    val p = element.asJsonObject
    return when (string(p, "type")) {
      "text" -> {
        if (p.keySet() != setOf("type", "text")) invalid("Unsupported text part field")
        ApiPart.Text(string(p, "text"))
      }
      "image_url" -> {
        if (role != "user") invalid("Images are supported only in user messages")
        if (p.keySet() != setOf("type", "image_url")) invalid("Unsupported image part field")
        val image = p.getAsJsonObject("image_url") ?: invalid("Missing image_url")
        if (image.keySet().any { it !in setOf("url", "detail") }) invalid("Unsupported image_url field")
        if (image.has("detail") && string(image, "detail") !in setOf("auto", "low", "high")) invalid("Invalid image detail")
        val url = string(image, "url")
        if (url.startsWith("data:")) decodeDataImage(url)
        else {
          val uri = java.net.URI(url)
          if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) invalid("Use an HTTP(S) URL or Base64 data URL")
        }
        ApiPart.Image(url)
      }
      else -> invalid("Unsupported content part")
    }
  }

  private fun parseCall(c: JsonObject): ApiToolCall {
    if (c.keySet() != setOf("id", "type", "function") || string(c, "type") != "function") invalid("Invalid tool call")
    val f = c.getAsJsonObject("function") ?: invalid("Missing tool call function")
    if (f.keySet() != setOf("name", "arguments")) invalid("Invalid tool function fields")
    val args = string(f, "arguments")
    if (!JsonParser.parseString(args).isJsonObject) invalid("Tool arguments must encode a JSON object")
    val id = string(c, "id")
    if (id.isBlank() || id.length > 128) invalid("Invalid tool call id")
    val name = string(f, "name")
    if (!Regex("[a-zA-Z0-9_-]{1,64}").matches(name)) invalid("Invalid tool name")
    return ApiToolCall(id, name, args)
  }

  private fun validateHistory(messages: List<ApiMessage>) {
    val pending = mutableSetOf<String>()
    val used = mutableSetOf<String>()
    var expected = "user"
    messages.forEachIndexed { index, m ->
      when (m.role) {
        "system" -> if (index != 0) invalid("System message must be first")
        "user" -> {
          if (expected != "user" || pending.isNotEmpty()) invalid("Expected assistant or tool results")
          expected = "assistant"
        }
        "assistant" -> {
          if (expected != "assistant" || pending.isNotEmpty()) invalid("Unexpected assistant message")
          m.toolCalls.forEach { if (!used.add(it.id)) invalid("Duplicate tool call id") else pending.add(it.id) }
          expected = if (pending.isEmpty()) "user" else "tool"
        }
        "tool" -> {
          if (expected != "tool" || !pending.remove(m.toolCallId)) invalid("Unknown or duplicate tool_call_id")
          if (pending.isEmpty()) expected = "assistant"
        }
      }
    }
    if (pending.isNotEmpty()) invalid("Missing tool results")
    if (expected != "assistant") invalid("Last message must be a user message or completed tool results")
  }
}
