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

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class ApiImagesTest {
  private class ImageConnection(
    private val status: Int = 200,
    private val length: Long = 5,
    private val location: String? = null,
    private val bytes: ByteArray = "hello".toByteArray(),
  ) : HttpURLConnection(URL("https://images.example/image")) {
    var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentLengthLong() = length
    override fun getHeaderField(name: String?) = if (name == "Location") location else null
    override fun getInputStream() = ByteArrayInputStream(bytes)
  }

  @Test fun inlineImageBytesReachDecoderAndRespectAggregateBudget() {
    var decoded = 0
    val loader = ApiImages { decoded++; it }
    assertArrayEquals("hello".toByteArray(),loader.load("data:image/png;base64,aGVsbG8="))
    assertEquals(1,decoded)
    val image = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(MAX_IMAGE_BYTES))
    loader.load(image)
    try { loader.load(image); fail("Expected aggregate limit") }
    catch (e: ApiFailure) { assertEquals(413,e.status) }
    assertEquals(2,decoded)
  }

  @Test fun httpsImageDownloadsSetLimitsAndDoNotSendApiCredentials() {
    val connection = ImageConnection()
    val loader = ApiImages(connect = { connection }) { it }
    assertArrayEquals("hello".toByteArray(),loader.load("https://images.example/image"))
    assertFalse(connection.instanceFollowRedirects)
    assertEquals(10_000,connection.connectTimeout)
    assertEquals(10_000,connection.readTimeout)
    assertFalse(connection.requestProperties.keys.any { it.equals("Authorization",true) })
    assertTrue(connection.disconnected)
  }

  @Test fun declaredAndUndeclaredOversizedDownloadsAreRejectedBeforeDecode() {
    for (connection in listOf(ImageConnection(length = MAX_IMAGE_BYTES + 1L),
        ImageConnection(length = -1, bytes = ByteArray(MAX_IMAGE_BYTES + 1)))) {
      try {
        ApiImages(connect = { connection }, normalize = { error("Must not decode") }).load("https://images.example/image")
        fail("Expected limit")
      } catch (e: ApiFailure) { assertEquals(413,e.status) }
      assertTrue(connection.disconnected)
    }
  }

  @Test fun httpsRedirectsCannotDowngradeToCleartext() {
    var connections = 0
    val connection = ImageConnection(status = 302, location = "http://images.example/image")
    try {
      ApiImages(connect = { connections++; connection }) { it }.load("https://images.example/image")
      fail("Expected HTTPS-only policy")
    } catch (e: ApiFailure) { assertEquals(400,e.status) }
    assertEquals(1,connections)
    assertTrue(connection.disconnected)
  }
  @Test fun explicitlyProvidedHttpImageUrlsAreAccepted() {
    val connection = ImageConnection()
    var requested = ""
    val loader = ApiImages(connect = { requested = it.toString(); connection }) { it }
    assertArrayEquals("hello".toByteArray(),loader.load("http://192.168.1.2/image.png"))
    assertEquals("http://192.168.1.2/image.png",requested)
    assertTrue(connection.disconnected)
  }

}
