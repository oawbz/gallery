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
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owns a separate engine; API conversations never mutate Gallery chat history. */
internal class LocalModelBackend(file: File, gpu: Boolean) : ChatBackend {
  override val modelId = file.nameWithoutExtension
  private val engine = Engine(EngineConfig(
    modelPath = file.absolutePath, backend = if (gpu) Backend.GPU() else Backend.CPU(),
    maxNumTokens = 4096,
  ))
  init {
    try { engine.initialize() } catch (e: Exception) { engine.close(); throw e }
  }

  @Synchronized
  override fun generate(request: ChatRequest, emit: (String) -> Unit) {
    val system = request.messages.firstOrNull()?.takeIf { it.role == "system" }
    val turns = request.messages.filter { it.role != "system" }
    val history = turns.dropLast(1).map {
      if (it.role == "user") Message.user(it.content) else Message.model(it.content)
    }
    engine.createConversation(ConversationConfig(
      systemInstruction = system?.let { Contents.of(it.content) }, initialMessages = history,
      samplerConfig = SamplerConfig(topK = 40, topP = request.topP, temperature = request.temperature),
      maxOutputToken = request.maxTokens ?: 512,
    )).use { conversation ->
      val chunks = LinkedBlockingQueue<String>(256)
      val done = CountDownLatch(1)
      val error = AtomicReference<Throwable?>()
      var submitted = false
      try {
        conversation.sendMessageAsync(turns.last().content, object : MessageCallback {
          override fun onMessage(message: Message) {
            if (!chunks.offer(message.toString())) {
              error.compareAndSet(null, IllegalStateException("Client is too slow"))
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
