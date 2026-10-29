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

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ApiImagesTest {
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

  @Test fun httpImageDownloadsAreBoundedAndDoNotSendApiCredentials() {
    ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
      val executor = Executors.newSingleThreadExecutor()
      try {
        val headers = executor.submit<String> {
          server.accept().use { socket ->
            socket.soTimeout = 3000
            val reader = socket.getInputStream().bufferedReader()
            val lines = mutableListOf<String>()
            while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".toByteArray())
            lines.joinToString("\n")
          }
        }
        assertArrayEquals("hello".toByteArray(),ApiImages { it }.load("http://127.0.0.1:${server.localPort}/image"))
        assertFalse(headers.get(3,TimeUnit.SECONDS).contains("Authorization",ignoreCase = true))
      } finally { executor.shutdownNow() }
    }
  }

  @Test fun declaredOversizedDownloadIsRejectedBeforeDecode() {
    ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
      val executor = Executors.newSingleThreadExecutor()
      try {
        val sent = executor.submit {
          server.accept().use { socket ->
            socket.soTimeout = 3000
            val reader = socket.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) { }
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${MAX_IMAGE_BYTES + 1}\r\nConnection: close\r\n\r\n".toByteArray())
          }
        }
        try { ApiImages { error("Must not decode") }.load("http://127.0.0.1:${server.localPort}/image"); fail("Expected limit") }
        catch (e: ApiFailure) { assertEquals(413,e.status) }
        sent.get(3,TimeUnit.SECONDS)
      } finally { executor.shutdownNow() }
    }
  }
}
