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
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.ToNumberPolicy
import java.util.UUID

/** Preserves native multimodal/tool messages, without turning calls into ordinary text. */
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
        else -> Message.model(contents, m.toolCalls.map { ToolCall(it.name, arguments(it.arguments)) })
      })
      index++
    }
    return result
  }

  fun deltas(message: Message): List<ApiDelta> = buildList {
    val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    if (text.isNotEmpty()) add(ApiDelta.Text(text))
    if (message.toolCalls.isNotEmpty()) add(ApiDelta.Tools(message.toolCalls.map {
      ApiToolCall("call_${UUID.randomUUID().toString().replace("-", "")}", it.name, gson.toJson(it.arguments))
    }))
  }
}
