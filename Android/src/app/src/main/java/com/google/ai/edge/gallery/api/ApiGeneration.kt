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

internal data class ApiUsage(val promptTokens: Int, val completionTokens: Int) {
  init { require(promptTokens >= 0 && completionTokens >= 0) }
  fun wire() = mapOf("prompt_tokens" to promptTokens, "completion_tokens" to completionTokens,
    "total_tokens" to promptTokens + completionTokens)
}
internal data class ApiStats(val usage: ApiUsage?, val decodeTps: Double? = null,
  val prefillTps: Double? = null, val nativeTtftMs: Double? = null)
internal class ApiStopGeneration : RuntimeException()

/** Holds a possible stop prefix across native chunks; never emits the matched stop text. */
internal class ApiStopFilter(private val stops: List<String>) {
  private var pending = ""
  var stopped = false; private set
  fun push(text: String): String {
    if (stopped) return ""
    if (stops.isEmpty()) return text
    pending += text
    val match = stops.map { pending.indexOf(it) }.filter { it >= 0 }.minOrNull()
    if (match != null) {
      val ready = pending.substring(0,match)
      pending = ""; stopped = true
      return ready
    }
    val held = stops.maxOf { stop ->
      (minOf(stop.length - 1,pending.length) downTo 1).firstOrNull { size -> pending.endsWith(stop.take(size)) } ?: 0
    }
    val ready = pending.dropLast(held)
    pending = pending.takeLast(held)
    return ready
  }
  fun finish(): String = pending.also { pending = "" }
}

internal data class ApiCompletion(
  val text: String, val reasoning: String, val calls: List<ApiToolCall>, val finish: String,
  val stats: ApiStats?, val elapsedMs: Double, val firstTokenMs: Double?,
) {
  fun message(): Map<String,Any?> = buildMap {
    put("role","assistant"); put("content",text.takeIf { it.isNotEmpty() || calls.isEmpty() })
    put("refusal",null)
    if (calls.isNotEmpty()) put("tool_calls",calls.map { it.wire() })
  }
  fun timings(): Map<String,Double> = buildMap {
    put("total_ms",elapsedMs)
    firstTokenMs?.let { put("time_to_first_token_ms",it) }
    stats?.decodeTps?.let { put("decode_tokens_per_second",it) }
    stats?.prefillTps?.let { put("prefill_tokens_per_second",it) }
    stats?.nativeTtftMs?.let { put("native_time_to_first_token_ms",it) }
  }
}

/** One backend invocation, shared by JSON and SSE transports. */
internal fun complete(backend: ChatBackend, request: ChatRequest, emit: (ApiDelta) -> Unit): ApiCompletion {
  val started = System.nanoTime()
  var first: Double? = null
  val text = StringBuilder(); val reasoning = StringBuilder(); val calls = mutableListOf<ApiToolCall>()
  var stats: ApiStats? = null
  val stop = ApiStopFilter(request.stop)
  val allowed = request.activeTools.map { it["name"].asString }.toSet()
  fun deliver(delta: ApiDelta) {
    if (delta !is ApiDelta.Stats && first == null) first = (System.nanoTime() - started) / 1_000_000.0
    emit(delta)
  }
  try {
    backend.generate(request) { delta -> when (delta) {
      is ApiDelta.Text -> {
        val visible = stop.push(delta.text)
        if (visible.isNotEmpty()) { text.append(visible); deliver(ApiDelta.Text(visible)) }
        if (stop.stopped) throw ApiStopGeneration()
      }
      is ApiDelta.Reasoning -> { reasoning.append(delta.text); deliver(delta) }
      is ApiDelta.Tools -> {
        if (delta.calls.any { it.name !in allowed } || (!request.parallelToolCalls && calls.size + delta.calls.size > 1))
          throw ApiFailure(422,"Model returned calls outside the requested tool choice")
        delta.calls.filter { it.name in request.strictTools }.forEach { call ->
          val schema = request.tools.first { it["name"].asString == call.name }.getAsJsonObject("parameters")
          val args = try { parseJson(call.arguments) } catch (_: Exception) { null }
          if (args == null || !ApiJsonSchema.matches(args,schema)) throw ApiFailure(422,"Generated tool arguments do not match the strict schema")
        }
        calls.addAll(delta.calls); deliver(delta)
      }
      is ApiDelta.Stats -> { stats = delta.stats }
    } }
  } catch (_: ApiStopGeneration) { /* An explicitly requested stop is successful completion. */ }
  val remaining = stop.finish()
  if (remaining.isNotEmpty()) { text.append(remaining); deliver(ApiDelta.Text(remaining)) }
  if (request.toolChoice == "required" && calls.isEmpty())
    throw ApiFailure(422,"Model did not produce the required tool call; retry or change the prompt")
  val finish = when {
    calls.isNotEmpty() -> "tool_calls"
    stop.stopped -> "stop"
    request.maxTokens != null && stats?.usage?.completionTokens?.let { it >= request.maxTokens } == true -> "length"
    else -> "stop"
  }
  if (request.responseFormat != null && request.responseFormat["type"].asString != "text" && finish != "length") {
    val value = try { parseJson(text.toString()) } catch (_: Exception) { null }
    if (value == null || !value.isJsonObject) throw ApiFailure(422,"Model did not return a complete JSON object")
    if (request.responseFormat["type"].asString == "json_schema" && !ApiJsonSchema.matches(value,
        request.responseFormat.getAsJsonObject("json_schema").getAsJsonObject("schema")))
      throw ApiFailure(422,"Generated response does not match the JSON schema")
  }
  return ApiCompletion(text.toString(),reasoning.toString(),calls,finish,stats,
    (System.nanoTime() - started) / 1_000_000.0,first)
}
