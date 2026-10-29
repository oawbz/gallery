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

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.BackendType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns a separate engine; API conversations never mutate Gallery chat history. */
internal class LocalModelBackend(file: File, gpu: Boolean, override val contextTokens: Int = DEFAULT_CONTEXT_TOKENS) : ChatBackend {
  init { require(validContextTokens(contextTokens)) { "Context must be between 1024 and 262144 tokens" } }
  override val modelId = file.nameWithoutExtension
  private val capabilities = ModelInfo.from(file.absolutePath).use { info ->
    val vision = info.inputModalities().vision
    vision to if (vision) info.supportedBackends(Modality.VISION) else emptyList()
  }
  private val engine = Engine(EngineConfig(
    modelPath = file.absolutePath, backend = if (gpu) Backend.GPU() else Backend.CPU(),
    visionBackend = if (!capabilities.first) null else when {
      BackendType.GPU in capabilities.second -> Backend.GPU()
      BackendType.CPU in capabilities.second -> Backend.CPU()
      else -> throw IllegalArgumentException("No supported vision backend")
    },
    maxNumImages = if (capabilities.first) MAX_IMAGES else null,
    maxNumTokens = contextTokens,
  ))
  init {
    try { engine.initialize() } catch (e: Exception) { engine.close(); throw e }
  }

  override fun validate(request: ChatRequest) {
    if (request.messages.any { m -> m.parts.any { it is ApiPart.Image } } && !capabilities.first) {
      throw ApiFailure(400, "Selected model does not support images")
    }
    // supportsFunctionCalling() is an optional protobuf declaration, not a runtime probe.
    // Missing/false declarations must not prevent native tool conversations from being tried.
    // Do not infer actual support from the model filename either.
  }

  @Synchronized
  override fun generate(request: ChatRequest, emit: (ApiDelta) -> Unit) {
    validate(request)
    val images = ApiImages()
    val messages = LiteRtProtocol.messages(request, images::load)
    engine.createConversation(LiteRtProtocol.conversationConfig(request, messages)).use { conversation ->
      val chunks = LinkedBlockingQueue<ApiDelta>(256)
      val done = CountDownLatch(1)
      val error = AtomicReference<Throwable?>()
      var submitted = false
      try {
        conversation.sendMessageAsync(messages.last(), object : MessageCallback {
          override fun onMessage(message: Message) {
            LiteRtProtocol.deltas(message).forEach { delta ->
              if (!chunks.offer(delta)) error.compareAndSet(null, IllegalStateException("Client is too slow"))
            }
          }
          override fun onDone() { done.countDown() }
          override fun onError(throwable: Throwable) { error.set(throwable); done.countDown() }
        })
        submitted = true
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (done.count > 0 || chunks.isNotEmpty()) {
          if (System.nanoTime() > deadline) throw ApiFailure(504, "Inference timed out")
          error.get()?.let { throw it }
          chunks.poll(100, TimeUnit.MILLISECONDS)?.let(emit)
        }
        error.get()?.let { throw it }
      } finally {
        if (submitted && done.count > 0) {
          conversation.cancelProcess()
          // Native callbacks complete before closing the conversation on this worker thread.
          var interrupted = false
          while (done.count > 0) {
            try { done.await() } catch (_: InterruptedException) { interrupted = true }
          }
          if (interrupted) Thread.currentThread().interrupt()
        }
      }
    }
  }
  @Synchronized override fun close() { engine.close() }
}
