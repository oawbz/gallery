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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Request-scoped, bounded downloads. Never forwards the API bearer key to image hosts. */
internal class ApiImages(private val normalize: (ByteArray) -> ByteArray = ::normalizeImage) {
  private var total = 0
  fun load(url: String): ByteArray {
    val bytes = if (url.startsWith("data:")) decodeDataImage(url) else download(url)
    total += bytes.size
    if (total > MAX_TOTAL_IMAGE_BYTES) throw ApiFailure(413, "Total image data exceeds 8 MiB")
    return normalize(bytes)
  }

  private fun download(initial: String): ByteArray {
    var url = initial
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
    repeat(4) {
      val uri = URI(url)
      if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) invalid("Invalid image URL")
      val connection = uri.toURL().openConnection() as HttpURLConnection
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp")
        val status = connection.responseCode
        if (status in setOf(301, 302, 303, 307, 308)) {
          val location = connection.getHeaderField("Location") ?: invalid("Image redirect has no location")
          url = uri.resolve(location).toString()
          if (System.nanoTime() > deadline) throw ApiFailure(422, "Image download timed out")
        } else {
          if (status != 200) throw ApiFailure(422, "Image server did not return HTTP 200")
          if (connection.contentLengthLong > MAX_IMAGE_BYTES) throw ApiFailure(413, "Image exceeds 4 MiB")
          return connection.inputStream.use { input ->
            ByteArrayOutputStream().use { output ->
              val buffer = ByteArray(8192)
              while (true) {
                if (System.nanoTime() > deadline) throw ApiFailure(422, "Image download timed out")
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > MAX_IMAGE_BYTES) throw ApiFailure(413, "Image exceeds 4 MiB")
                output.write(buffer, 0, n)
              }
              output.toByteArray()
            }
          }
        }
      } catch (e: ApiFailure) { throw e }
      catch (_: Exception) { throw ApiFailure(422, "Image could not be downloaded") }
      finally { connection.disconnect() }
    }
    throw ApiFailure(422, "Too many image redirects")
  }
}

private fun normalizeImage(bytes: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) invalid("Invalid PNG, JPEG or WebP image")
    if (bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L) throw ApiFailure(413, "Image exceeds 32 megapixels")
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: invalid("Image could not be decoded")
    return try {
      ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) invalid("Image could not be decoded")
        output.toByteArray()
      }
    } finally { bitmap.recycle() }
}
