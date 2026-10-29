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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

internal data class ApiResponseRequest(val chat: ChatRequest, val source: JsonObject)

/** Stateless Responses adapter. No fabricated remote state, hosted tools or encrypted reasoning. */
internal object ApiResponses {
  fun parse(body: String, backend: ChatBackend): ApiResponseRequest {
    try {
      val src = parseJson(body).asJsonObject
      val allowed = setOf("model","input","instructions","tools","tool_choice","parallel_tool_calls","stream",
        "max_output_tokens","temperature","top_p","reasoning","text","metadata","store","background",
        "previous_response_id","truncation","include","user","safety_identifier","service_tier")
      src.keySet().firstOrNull { it !in allowed }?.let { invalid("Unsupported Responses parameter: $it") }
      fun present(key: String) = src[key]?.takeUnless { it.isJsonNull }
      for (key in listOf("store","background")) present(key)?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean || it.asBoolean) invalid("Responses supports only $key:false")
      }
      if (present("previous_response_id") != null) invalid("Responses is stateless; resend input history instead of previous_response_id")
      if (present("truncation")?.asString?.let { it != "disabled" } == true) invalid("Automatic truncation is unavailable")
      present("include")?.let { if (!it.isJsonArray || it.asJsonArray.size() != 0) invalid("Responses include expansions are unavailable") }
      val chat = JsonObject().apply {
        for (key in listOf("model","parallel_tool_calls","stream","temperature","top_p","metadata","user",
            "safety_identifier","service_tier"))
          src[key]?.let { add(key,it.deepCopy()) }
        src["max_output_tokens"]?.let { add("max_completion_tokens",it.deepCopy()) }
        addProperty("store",false)
      }
      present("reasoning")?.asJsonObject?.let { reasoning ->
        if (reasoning.keySet().any { it !in setOf("effort","summary") } || reasoning["summary"]?.takeUnless { it.isJsonNull } != null) invalid("Responses supports reasoning.effort; summaries/encryption are unavailable")
        reasoning["effort"]?.let { chat.add("reasoning_effort",it.deepCopy()) }
      }
      present("text")?.asJsonObject?.let { text ->
        if (text.keySet().any { it != "format" }) invalid("Responses supports only text.format")
        text["format"]?.asJsonObject?.let { format ->
          if (string(format,"type") == "json_schema") {
            chat.add("response_format",JsonObject().apply {
              addProperty("type","json_schema"); add("json_schema",format.deepCopy().apply { remove("type") })
            })
          } else chat.add("response_format",format.deepCopy())
        }
      }
      present("tools")?.asJsonArray?.let { tools ->
        chat.add("tools",JsonArray().apply { tools.forEach { value ->
          val f = value.asJsonObject
          if (string(f,"type") != "function") invalid("Only client-executed function tools are available")
          add(JsonObject().apply { addProperty("type","function"); add("function",f.deepCopy().apply { remove("type") }) })
        } })
      }
      present("tool_choice")?.let { choice ->
        if (choice.isJsonObject) {
          val c = choice.asJsonObject
          if (c.keySet() != setOf("type","name") || string(c,"type") != "function") invalid("Unsupported Responses tool_choice")
          chat.add("tool_choice",JsonObject().apply {
            addProperty("type","function"); add("function",JsonObject().apply { addProperty("name",string(c,"name")) })
          })
        } else chat.add("tool_choice",choice.deepCopy())
      }
      val messages = JsonArray()
      present("instructions")?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalid("instructions must be a string")
        messages.add(JsonObject().apply { addProperty("role","system"); addProperty("content",it.asString) })
      }
      var pendingReasoning = ""
      fun attachReasoning(message: JsonObject) {
        if (pendingReasoning.isNotEmpty()) {
          if (message["role"].asString != "assistant") invalid("Reasoning must precede an assistant message or function call")
          message.addProperty("reasoning_content",pendingReasoning); pendingReasoning = ""
        }
      }
      val input = present("input") ?: invalid("input is required")
      if (input.isJsonPrimitive && input.asJsonPrimitive.isString) {
        messages.add(JsonObject().apply { addProperty("role","user"); addProperty("content",input.asString) })
      } else if (input.isJsonArray) input.asJsonArray.forEach { value ->
        val item = value.asJsonObject
        val type = item["type"]?.asString ?: "message"
        when (type) {
          "message" -> {
            if (item.keySet().any { it !in setOf("type","role","content","id","status") }) invalid("Unsupported Responses message field")
            val m = JsonObject().apply { addProperty("role",string(item,"role")); add("content",content(item["content"] ?: invalid("Missing message content"))) }
            attachReasoning(m); messages.add(m)
          }
          "function_call" -> {
            if (item.keySet().any { it !in setOf("type","id","call_id","name","arguments","status") }) invalid("Unsupported function_call field")
            var m = messages.lastOrNull()?.takeIf { it.isJsonObject && it.asJsonObject["role"]?.asString == "assistant" }?.asJsonObject
            if (m == null) { m = JsonObject().apply { addProperty("role","assistant"); add("content",JsonNull.INSTANCE) }; messages.add(m) }
            attachReasoning(m)
            val calls = m.getAsJsonArray("tool_calls") ?: JsonArray().also { m.add("tool_calls",it) }
            calls.add(JsonObject().apply {
              addProperty("id",string(item,"call_id")); addProperty("type","function")
              add("function",JsonObject().apply { addProperty("name",string(item,"name")); addProperty("arguments",string(item,"arguments")) })
            })
          }
          "function_call_output" -> {
            if (pendingReasoning.isNotEmpty()) invalid("Unexpected reasoning before tool result")
            if (item.keySet().any { it !in setOf("type","id","call_id","output","status") }) invalid("Unsupported function_call_output field")
            val output = item["output"] ?: invalid("Missing tool output")
            if (!output.isJsonPrimitive || !output.asJsonPrimitive.isString) invalid("Tool output must be a string")
            messages.add(JsonObject().apply {
              addProperty("role","tool"); addProperty("tool_call_id",string(item,"call_id")); addProperty("content",output.asString)
            })
          }
          "reasoning" -> {
            if (item.keySet().any { it !in setOf("type","id","summary","content","status","encrypted_content") }) invalid("Unsupported reasoning field")
            if (item["encrypted_content"]?.takeUnless { it.isJsonNull } != null) invalid("Encrypted reasoning cannot be replayed by this local runtime")
            val parts = item["content"]?.takeUnless { it.isJsonNull }?.asJsonArray ?: item["summary"]?.takeUnless { it.isJsonNull }?.asJsonArray ?: JsonArray()
            pendingReasoning += parts.joinToString("") {
              val p = it.asJsonObject
              if (string(p,"type") !in setOf("reasoning_text","summary_text")) invalid("Unsupported reasoning content")
              string(p,"text")
            }
          }
          else -> invalid("Unsupported Responses input item: $type")
        }
      } else invalid("input must be a string or item array")
      if (pendingReasoning.isNotEmpty()) invalid("Reasoning has no following assistant message")
      chat.add("messages",messages)
      return ApiResponseRequest(ApiProtocol.parse(chat.toString(),backend.modelId,backend.contextTokens,
        backend.defaultThinkingEnabled,backend.defaultThinkingBudget,allowReasoningHistory = true),src)
    } catch (e: ApiFailure) { throw e }
    catch (_: Exception) { invalid("Invalid Responses request") }
  }
  private fun content(value: JsonElement): JsonElement {
    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.deepCopy()
    if (!value.isJsonArray) invalid("Message content must be text or parts")
    return JsonArray().apply { value.asJsonArray.forEach { part ->
      val p = part.asJsonObject
      when(string(p,"type")) {
        "input_text","output_text" -> {
          if (p.keySet().any { it !in setOf("type","text","annotations","logprobs") }) invalid("Unsupported text part field")
          add(JsonObject().apply { addProperty("type","text"); addProperty("text",string(p,"text")) })
        }
        "input_image" -> {
          if (p.keySet().any { it !in setOf("type","image_url","detail") }) invalid("Image file IDs are unavailable; use image_url")
          add(JsonObject().apply { addProperty("type","image_url"); add("image_url",JsonObject().apply {
            addProperty("url",string(p,"image_url")); p["detail"]?.let { add("detail",it.deepCopy()) }
          }) })
        }
        else -> invalid("Unsupported Responses content part")
      }
    } }
  }
}

/** Real-time typed SSE events and the same final object for non-streaming requests. */
internal class ApiResponseOutput(
  private val request: ApiResponseRequest, private val model: String,
  private val onEvent: ((String,Map<String,Any?>) -> Unit)? = null,
) {
  private val id = "resp_${UUID.randomUUID().toString().replace("-","")}"
  private val created = System.currentTimeMillis() / 1000
  private var sequence = 0
  private val output = mutableListOf<MutableMap<String,Any?>>()
  private var textIndex: Int? = null
  private var reasoningIndex: Int? = null
  private fun event(type: String, data: Map<String,Any?>) {
    onEvent?.invoke(type,data + mapOf("type" to type,"sequence_number" to sequence++))
  }
  fun begin() { event("response.created",mapOf("response" to response(null,"in_progress"))); event("response.in_progress",mapOf("response" to response(null,"in_progress"))) }
  private fun part(kind: String, text: String): Map<String,Any?> = if (kind == "message")
    mapOf("type" to "output_text","text" to text,"annotations" to emptyList<Any>(),"logprobs" to emptyList<Any>()) else mapOf("type" to "reasoning_text","text" to text)
  private fun text(delta: String, reasoning: Boolean) {
    val kind = if (reasoning) "reasoning" else "message"
    var index = if (reasoning) reasoningIndex else textIndex
    if (index == null) {
      index = output.size
      val item = mutableMapOf<String,Any?>("id" to "${if (reasoning) "rs" else "msg"}_${UUID.randomUUID()}",
        "type" to kind,"status" to "in_progress","content" to emptyList<Any>())
      if (reasoning) item["summary"] = emptyList<Any>() else item["role"] = "assistant"
      output.add(item)
      if (reasoning) reasoningIndex = index else textIndex = index
      event("response.output_item.added",mapOf("output_index" to index,"item" to item.toMap()))
      item["content"] = listOf(part(kind,""))
      event("response.content_part.added",mapOf("item_id" to item["id"],"output_index" to index,"content_index" to 0,"part" to part(kind,"")))
    }
    val item = output[index]
    @Suppress("UNCHECKED_CAST") val old = (item["content"] as List<Map<String,Any?>>)[0]["text"] as String
    item["content"] = listOf(part(kind,old + delta))
    val data = mutableMapOf<String,Any?>("item_id" to item["id"],"output_index" to index,"content_index" to 0,"delta" to delta)
    if (!reasoning) data["logprobs"] = emptyList<Any>()
    event(if (reasoning) "response.reasoning_text.delta" else "response.output_text.delta",data)
  }
  fun delta(delta: ApiDelta) { when(delta) {
    is ApiDelta.Text -> text(delta.text,false)
    is ApiDelta.Reasoning -> text(delta.text,true)
    is ApiDelta.Tools -> delta.calls.forEach { call ->
      val index = output.size
      val item = mutableMapOf<String,Any?>("id" to "fc_${UUID.randomUUID()}","type" to "function_call",
        "status" to "in_progress","call_id" to call.id,"name" to call.name,"arguments" to "")
      output.add(item)
      event("response.output_item.added",mapOf("output_index" to index,"item" to item.toMap()))
      event("response.function_call_arguments.delta",mapOf("item_id" to item["id"],"output_index" to index,"delta" to call.arguments))
      item["arguments"] = call.arguments
    }
    is ApiDelta.Stats -> Unit
  } }
  fun finish(result: ApiCompletion): Map<String,Any?> {
    val status = if (result.finish == "length") "incomplete" else "completed"
    output.forEachIndexed { index,item ->
      item["status"] = status
      if (item["type"] == "function_call") {
        event("response.function_call_arguments.done",mapOf("item_id" to item["id"],"output_index" to index,"arguments" to item["arguments"]))
      } else {
        @Suppress("UNCHECKED_CAST") val content = (item["content"] as List<Map<String,Any?>>)[0]
        val base = mapOf("item_id" to item["id"],"output_index" to index,"content_index" to 0)
        val textDone = base + mapOf("text" to content["text"]) + if (item["type"] == "reasoning") emptyMap() else mapOf("logprobs" to emptyList<Any>())
        event(if (item["type"] == "reasoning") "response.reasoning_text.done" else "response.output_text.done",textDone)
        event("response.content_part.done",base + mapOf("part" to content))
      }
      event("response.output_item.done",mapOf("output_index" to index,"item" to item.toMap()))
    }
    return response(result,status).also { event(if (status == "completed") "response.completed" else "response.incomplete",mapOf("response" to it)) }
  }
  fun failed(message: String,code: String) {
    output.forEach { it["status"] = "incomplete" }
    val error = mapOf("code" to if (code == "rate_limit_exceeded") code else "server_error","message" to message)
    event("response.failed",mapOf("response" to (response(null,"failed") + mapOf("error" to error))))
    event("error",error + mapOf("param" to null))
  }
  private fun response(result: ApiCompletion?, status: String): Map<String,Any?> = mapOf(
    "id" to id,"object" to "response","created_at" to created,"model" to model,"status" to status,
    "error" to null,"incomplete_details" to if (status == "incomplete") mapOf("reason" to "max_output_tokens") else null,
    "output" to output.map { it.toMap() },"instructions" to request.source["instructions"],
    "parallel_tool_calls" to request.chat.parallelToolCalls,"tools" to (request.source["tools"]?.takeUnless { it.isJsonNull } ?: emptyList<Any>()),
    "tool_choice" to (request.source["tool_choice"]?.takeUnless { it.isJsonNull } ?: "auto"),"temperature" to request.chat.temperature,"top_p" to request.chat.topP,
    "max_output_tokens" to request.chat.maxTokens,"background" to false,
    "previous_response_id" to null,"truncation" to "disabled","service_tier" to "default",
    "reasoning" to (request.source["reasoning"]?.takeUnless { it.isJsonNull } ?: mapOf("effort" to null,"summary" to null)),
    "text" to (request.source["text"]?.takeUnless { it.isJsonNull } ?: mapOf("format" to mapOf("type" to "text"))),
    "metadata" to (request.chat.metadata ?: JsonObject()),
    // Full Responses usage requires reasoning/cache breakdowns that this JNI does not expose.
    "usage" to null,
  )
}
