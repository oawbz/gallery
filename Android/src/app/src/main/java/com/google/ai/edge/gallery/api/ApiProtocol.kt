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
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal fun parseJson(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
  reader.strictness = Strictness.STRICT
  val value = JsonParser.parseReader(reader)
  if (reader.peek() != JsonToken.END_DOCUMENT) invalid("Trailing JSON content")
  value
}

internal const val DEFAULT_CONTEXT_TOKENS = 32768
internal const val DEFAULT_THINKING_BUDGET = 2048
internal fun validThinkingBudget(value: Int?, context: Int) = value != null && value in 1..(context / 2)
internal const val MIN_CONTEXT_TOKENS = 1024
internal const val MAX_CONTEXT_TOKENS = 262144
internal fun validContextTokens(value: Int?) = value != null && value in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS

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
  val reasoningContent: String? = null,
)
internal data class ChatRequest(
  val messages: List<ApiMessage>, val stream: Boolean,
  val temperature: Double, val topP: Double, val maxTokens: Int?,
  val tools: List<JsonObject> = emptyList(), val toolChoice: String = "auto",
  val selectedTool: String? = null, val parallelToolCalls: Boolean = true,
  val enableThinking: Boolean = true, val thinkingBudget: Int = DEFAULT_THINKING_BUDGET,
  val includeUsage: Boolean = false, val stop: List<String> = emptyList(), val seed: Int = 0,
  val presencePenalty: Float = 0f, val frequencyPenalty: Float = 0f,
  val responseFormat: JsonObject? = null, val suppressedTokens: List<Int> = emptyList(),
  val metadata: JsonObject? = null, val strictTools: Set<String> = emptySet(),
) {
  val activeTools get() = if (toolChoice == "none") emptyList() else
    tools.filter { selectedTool == null || it["name"].asString == selectedTool }
}
internal sealed interface ApiDelta {
  data class Text(val text: String) : ApiDelta
  data class Reasoning(val text: String) : ApiDelta
  data class Stats(val stats: ApiStats) : ApiDelta
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
  fun parse(body: String, modelId: String, contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    defaultThinkingEnabled: Boolean = true, defaultThinkingBudget: Int = DEFAULT_THINKING_BUDGET,
    allowReasoningHistory: Boolean = false): ChatRequest {
    try {
      val obj = parseJson(body).asJsonObject
      if (string(obj, "model") != modelId) throw ApiFailure(404, "Unknown model; use GET /v1/models")
      val supported = setOf("model", "messages", "stream", "temperature", "top_p", "max_tokens",
        "max_completion_tokens", "tools", "tool_choice", "parallel_tool_calls",
        "reasoning_effort", "stream_options", "stop", "seed",
        "presence_penalty", "frequency_penalty", "response_format", "n", "logit_bias", "logprobs",
        "top_logprobs", "store", "metadata", "user", "safety_identifier", "service_tier")
      obj.keySet().firstOrNull { it !in supported }?.let { invalid("Unsupported parameter: $it") }
      if (obj["max_tokens"]?.takeUnless { it.isJsonNull } != null && obj["max_completion_tokens"]?.takeUnless { it.isJsonNull } != null) invalid("Specify only one token limit")
      val strictTools = mutableSetOf<String>()
      val tools = obj["tools"]?.takeUnless { it.isJsonNull }?.asJsonArray?.map { element ->
        val tool = element.asJsonObject
        if (string(tool, "type") != "function" || tool.keySet().any { it !in setOf("type", "function") }) invalid("Only function tools are supported")
        val f = tool.getAsJsonObject("function") ?: invalid("Missing tool function")
        if (!Regex("[a-zA-Z0-9_-]{1,64}").matches(string(f, "name"))) invalid("Invalid tool name")
        if (f.keySet().any { it !in setOf("name", "description", "parameters", "strict") }) invalid("Unsupported function field")
        if (f.has("description")) string(f, "description")
        if (f.has("strict") && (!f["strict"].isJsonPrimitive || !f["strict"].asJsonPrimitive.isBoolean)) invalid("strict must be boolean")
        val parameters = f.getAsJsonObject("parameters") ?: JsonObject().apply { addProperty("type", "object"); add("properties", JsonObject()) }
        if (string(parameters, "type") != "object") invalid("Tool parameters must be an object schema")
        if (f["strict"]?.asBoolean == true) { ApiJsonSchema.check(parameters); strictTools.add(f["name"].asString) }
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
      fun boolean(key: String, default: Boolean): Boolean = obj[key]?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid("$key must be boolean")
        it.asBoolean
      } ?: default
      val array = obj.getAsJsonArray("messages") ?: invalid("messages is required")
      if (array.size() !in 1..256) invalid("messages must contain 1 to 256 items")
      var images = 0
      val parsedMessages = array.map { element ->
        val m = element.asJsonObject
        val role = string(m, "role").let { if (it == "developer") "system" else it }
        if (role !in setOf("system", "user", "assistant", "tool")) invalid("Unsupported message role")
        val fields = when (role) {
          "assistant" -> setOf("role", "content", "tool_calls", "refusal") + if (allowReasoningHistory) setOf("reasoning_content") else emptySet()
          "tool" -> setOf("role", "content", "tool_call_id")
          else -> setOf("role", "content")
        }
        if (m.keySet().any { it !in fields }) invalid("Unsupported message field")
        val calls = m.getAsJsonArray("tool_calls")?.map { parseCall(it.asJsonObject) } ?: emptyList()
        if (calls.size > 64) invalid("Too many tool calls")
        val refusal = m["refusal"]?.takeUnless { it.isJsonNull }?.let { string(m,"refusal") }
        val content = m["content"]
        val parts: List<ApiPart> = when {
          content == null || content.isJsonNull -> {
            if (role != "assistant" || (calls.isEmpty() && refusal == null)) invalid("Missing content")
            emptyList()
          }
          content.isJsonPrimitive && content.asJsonPrimitive.isString -> listOf(ApiPart.Text(content.asString))
          content.isJsonArray -> {
            if (content.asJsonArray.size() !in 1..64) invalid("Use 1 to 64 content parts")
            content.asJsonArray.map { parsePart(it, role).also { p -> if (p is ApiPart.Image) images++ } }
          }
          else -> invalid("content must be a string or content parts")
        }
        val allParts = if (refusal != null) parts + ApiPart.Text(refusal) else parts
        ApiMessage(role, allParts.filterIsInstance<ApiPart.Text>().joinToString("") { it.text }, allParts, calls,
          if (role == "tool") string(m, "tool_call_id") else null,
          m["reasoning_content"]?.takeUnless { it.isJsonNull }?.let { string(m, "reasoning_content") })
      }
      val prefix = parsedMessages.takeWhile { it.role == "system" }
      val messages = if (prefix.size <= 1) parsedMessages else listOf(ApiMessage("system",prefix.joinToString("\n\n") { it.content })) + parsedMessages.drop(prefix.size)
      if (images > MAX_IMAGES) throw ApiFailure(413, "Use at most 4 images per request")
      validateHistory(messages)
      fun number(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
        val v = obj[key]?.takeUnless { it.isJsonNull } ?: return default
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) invalid("$key must be numeric")
        val n = v.asDouble
        if (!n.isFinite() || n !in range) invalid("$key is out of range")
        return n
      }
      val stream = boolean("stream",false)
      val streamOptions = obj["stream_options"]?.takeUnless { it.isJsonNull }?.asJsonObject
      if (streamOptions != null && (!stream || streamOptions.keySet().any { it != "include_usage" })) invalid("stream_options requires streaming and supports only include_usage")
      val includeUsage = streamOptions?.get("include_usage")?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid("include_usage must be boolean")
        it.asBoolean
      } ?: false
      if (number("n",1.0,1.0..1.0) != 1.0) invalid("Only n=1 is supported")
      if (boolean("logprobs",false) || number("top_logprobs",0.0,0.0..0.0) != 0.0) invalid("Token log probabilities are unavailable in this runtime")
      if (boolean("store",false)) invalid("Stored completions are not supported; use store:false")
      for (field in listOf("user","safety_identifier")) if (obj.has(field)) string(obj,field)
      if (obj.has("service_tier") && string(obj,"service_tier") !in setOf("auto","default")) invalid("Only default service tier is available")
      val metadata = obj["metadata"]?.takeUnless { it.isJsonNull }?.asJsonObject?.also { m ->
        if (m.size() > 16 || m.entrySet().any { (k,v) -> k.length > 64 || !v.isJsonPrimitive || !v.asJsonPrimitive.isString || v.asString.length > 512 }) invalid("metadata supports at most 16 string pairs")
      }
      val stops = obj["stop"]?.takeUnless { it.isJsonNull }?.let { value ->
        val values = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
        if (values.size !in 0..4 || values.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.isEmpty() || it.asString.length > 256 }) invalid("stop must contain at most 4 nonempty strings, at most 256 characters each")
        values.map { it.asString }.distinct()
      } ?: emptyList()
      val seed = number("seed",0.0,Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()).also {
        if (it % 1.0 != 0.0) invalid("seed must be a signed 32-bit integer")
      }.toInt()
      val suppressed = obj["logit_bias"]?.takeUnless { it.isJsonNull }?.asJsonObject?.entrySet()?.map { (k,v) ->
        val id = k.toIntOrNull()?.takeIf { it >= 0 } ?: invalid("logit_bias keys must be nonnegative token IDs")
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber || v.asDouble !in setOf(-100.0,0.0)) invalid("This runtime supports only logit_bias -100 (suppress) or 0")
        if (v.asDouble == -100.0) id else null
      }?.filterNotNull() ?: emptyList()
      if (suppressed.size > 1024) invalid("Too many suppressed token IDs")
      val format = obj["response_format"]?.takeUnless { it.isJsonNull }?.asJsonObject?.also { f ->
        when (string(f,"type")) {
          "text", "json_object" -> if (f.keySet() != setOf("type")) invalid("Unsupported response_format field")
          "json_schema" -> {
            if (f.keySet() != setOf("type","json_schema")) invalid("Invalid response_format")
            val schema = f.getAsJsonObject("json_schema") ?: invalid("Missing json_schema")
            if (schema.keySet().any { it !in setOf("name","description","schema","strict") }) invalid("Unsupported json_schema field")
            if (!Regex("[a-zA-Z0-9_-]{1,64}").matches(string(schema,"name"))) invalid("Invalid schema name")
            if (schema.has("description")) string(schema,"description")
            if (schema.has("strict") && (!schema["strict"].isJsonPrimitive || !schema["strict"].asJsonPrimitive.isBoolean)) invalid("strict must be boolean")
            val definition = schema.getAsJsonObject("schema") ?: invalid("Missing schema definition")
            if (string(definition,"type") != "object") invalid("Root JSON schema must have object type")
            ApiJsonSchema.check(definition)
          }
          else -> invalid("Unsupported response_format type")
        }
        if (f["type"].asString != "text" && tools.isNotEmpty() && mode != "none") invalid("Combining structured response_format and active tools is unsupported by this runtime")
      }
      val effort = obj["reasoning_effort"]?.takeUnless { it.isJsonNull }?.let { string(obj, "reasoning_effort") }
      if (effort != null && effort !in setOf("none", "low", "medium", "high")) invalid("reasoning_effort must be none, low, medium or high")
      val thinking = if (effort != null) effort != "none" else defaultThinkingEnabled
      val budget = when (effort) {
        "low" -> 512; "medium" -> 2048; "high" -> 8192
        else -> defaultThinkingBudget.coerceIn(1,contextTokens - 1)
      }
      val tokenKey = if (obj["max_completion_tokens"]?.takeUnless { it.isJsonNull } != null) "max_completion_tokens" else "max_tokens"
      val defaultOutput = if (thinking) minOf(contextTokens, maxOf(4096, budget * 2)) else minOf(contextTokens,512)
      val output = number(tokenKey, defaultOutput.toDouble(), 1.0..contextTokens.toDouble()).also {
        if (it % 1.0 != 0.0) invalid("Token limit must be an integer")
      }.toInt()
      if (thinking && output < 2) invalid("Thinking requires at least 2 output tokens")
      val effectiveBudget = if (!thinking) 0 else minOf(budget,maxOf(1,output / 2))
      return ChatRequest(messages, boolean("stream", false), number("temperature", 0.7, 0.0..2.0),
        number("top_p", 0.95, 0.0..1.0), output, tools, mode, selected, boolean("parallel_tool_calls", true), thinking, effectiveBudget,
        includeUsage, stops, seed, number("presence_penalty",0.0,-2.0..2.0).toFloat(),
        number("frequency_penalty",0.0,-2.0..2.0).toFloat(), format, suppressed, metadata, strictTools)

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
    if (!parseJson(args).isJsonObject) invalid("Tool arguments must encode a JSON object")
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
          if (pending.isNotEmpty()) invalid("Expected tool results")
          expected = "assistant"
        }
        "assistant" -> {
          if (pending.isNotEmpty()) invalid("Expected tool results")
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
