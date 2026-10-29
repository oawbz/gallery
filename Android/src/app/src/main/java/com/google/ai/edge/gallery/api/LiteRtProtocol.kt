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
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.ToNumberPolicy
import java.util.UUID

/** Preserves native multimodal/tool messages, without turning calls into ordinary text. */
@OptIn(ExperimentalApi::class)
internal object LiteRtProtocol {
  private val gson = com.google.gson.GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
  @Suppress("UNCHECKED_CAST")
  private fun arguments(json: String): Map<String, Any?> = gson.fromJson(json, Map::class.java) as Map<String, Any?>

  fun tools(request: ChatRequest) = request.activeTools.map { definition ->
    tool(object : OpenApiTool {
      override fun getToolDescriptionJsonString() = definition.toString()
      override fun execute(paramsJsonString: String): String = error("API tools must be executed by the client")
    })
  }

  fun messages(request: ChatRequest, image: (String) -> ByteArray): List<Message> {
    val result = mutableListOf<Message>()
    val calls = request.messages.flatMap { it.toolCalls }.associateBy { it.id }
    var index = 0
    while (index < request.messages.size) {
      val m = request.messages[index]
      if (m.role == "tool") {
        val responses = mutableMapOf<String, ApiMessage>()
        while (index < request.messages.size && request.messages[index].role == "tool") {
          val response = request.messages[index++]
          responses[response.toolCallId!!] = response
        }
        // LiteRT identifies results by function name, so retain the original call order.
        val order = request.messages[index - responses.size - 1].toolCalls
        result.add(Message.tool(Contents.of(order.map { call ->
          Content.ToolResponse(calls.getValue(call.id).name, responses.getValue(call.id).content)
        })))
        continue
      }
      val contents = Contents.of(m.parts.map { part -> when (part) {
        is ApiPart.Text -> Content.Text(part.text)
        is ApiPart.Image -> Content.ImageBytes(image(part.url))
      } })
      result.add(when (m.role) {
        "system" -> Message.system(contents)
        "user" -> Message.user(contents)
        else -> Message.model(contents, m.toolCalls.map { ToolCall(it.name, arguments(it.arguments)) },
          m.reasoningContent?.let { mapOf("thought" to it) } ?: emptyMap())
      })
      index++
    }
    return result
  }

  /** Tool descriptions always reach the native runtime; metadata opt-in is not required. */
  fun conversationConfig(request: ChatRequest, messages: List<Message>): ConversationConfig {
    val system = messages.firstOrNull()?.takeIf { request.messages.first().role == "system" }
    val turns = messages.drop(if (system != null) 1 else 0)
    val toolInstruction = if (request.toolChoice == "required") {
      "You must call ${request.selectedTool ?: "one of the provided tools"} for this turn."
    } else ""
    return ConversationConfig(
      systemInstruction = if (system != null || toolInstruction.isNotEmpty())
        Contents.of(listOfNotNull(system?.toString(), toolInstruction.takeIf { it.isNotEmpty() }).joinToString("\n")) else null,
      initialMessages = turns.dropLast(1),
      tools = tools(request), automaticToolCalling = false,
      samplerConfig = SamplerConfig(topK = 40, topP = request.topP, temperature = request.temperature, seed = request.seed),
      maxOutputToken = request.maxTokens ?: if (request.enableThinking) 4096 else 512,
      thinkingConfig = ThinkingConfig(enableThinking = request.enableThinking, thinkingTokenBudget = request.thinkingBudget),
      enableResponseFormat = responseFormat(request) != null,
    )
  }

  fun responseFormat(request: ChatRequest): ResponseFormat? = request.responseFormat?.let { format ->
    when (format["type"].asString) {
      "json_object" -> ResponseFormat.json("{\"type\":\"object\"}")
      "json_schema" -> ResponseFormat.json(format.getAsJsonObject("json_schema")["schema"].toString())
      else -> null
    }
  }

  fun stats(info: BenchmarkInfo): ApiStats {
    fun positive(value: Double) = value.takeIf { it.isFinite() && it > 0.0 }
    val usage = if (info.lastPrefillTokenCount > 0 && info.lastDecodeTokenCount >= 0)
      ApiUsage(info.lastPrefillTokenCount,info.lastDecodeTokenCount) else null
    return ApiStats(usage,positive(info.lastDecodeTokensPerSecond),positive(info.lastPrefillTokensPerSecond),
      info.timeToFirstTokenInSecond.takeIf { usage != null && it.isFinite() && it >= 0.0 }?.times(1000))
  }

  fun deltas(message: Message): List<ApiDelta> = buildList {
    val reasoning = listOf("thought", "analysis", "reasoning").mapNotNull { message.channels[it] }.joinToString("")
    if (reasoning.isNotEmpty()) add(ApiDelta.Reasoning(reasoning))
    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    if (text.isNotEmpty()) add(ApiDelta.Text(text))
    if (message.toolCalls.isNotEmpty()) add(ApiDelta.Tools(message.toolCalls.map {
      ApiToolCall("call_${UUID.randomUUID().toString().replace("-", "")}", it.name, gson.toJson(it.arguments))
    }))
  }
}
