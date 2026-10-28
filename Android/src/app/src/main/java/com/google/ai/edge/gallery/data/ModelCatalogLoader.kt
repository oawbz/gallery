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

package com.google.ai.edge.gallery.data

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/** Catalog compatibility is independent of the APK display/version suffix. */
internal object ModelCatalogLoader {
  const val VERSION = "1_0_20"
  const val ASSET = "model_allowlist.json"
  const val URL = "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists/$VERSION.json"
  enum class Source { REMOTE, CACHE, BUNDLED }
  data class Result(val catalog: ModelAllowlist, val source: Source)

  fun load(
    remote: () -> String?, cached: () -> String?, bundled: () -> String?,
    saveRemote: (String) -> Unit = {},
  ): Result? {
    for ((source, read) in listOf(Source.REMOTE to remote, Source.CACHE to cached, Source.BUNDLED to bundled)) {
      try {
        val text = read() ?: continue
        val catalog = Gson().fromJson(text, ModelAllowlist::class.java) ?: continue
        if (catalog.models.isNullOrEmpty() || catalog.models.any { it == null || it.name.isNullOrBlank() || it.modelId.isNullOrBlank() }) continue
        if (source == Source.REMOTE) {
          try { saveRemote(text) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        return Result(catalog, source)
      } catch (e: CancellationException) { throw e }
      catch (_: Exception) { /* Continue with the next validated source. */ }
    }
    return null
  }

  fun fetch(url: String = URL): String? {
    val connection = java.net.URL(url).openConnection() as HttpURLConnection
    return try {
      connection.connectTimeout = 5_000
      connection.readTimeout = 5_000
      connection.requestMethod = "GET"
      if (connection.responseCode != HttpURLConnection.HTTP_OK) null
      else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally { connection.disconnect() }
  }
}
