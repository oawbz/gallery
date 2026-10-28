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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.getModelStorageDir
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ApiServerState(
  val active: Boolean = false, val loading: Boolean = false, val error: Boolean = false,
  val errorResource: Int = R.string.api_error, val lan: Boolean = false, val port: Int = 0,
)

class ApiServerService : Service() {
  private val worker = Executors.newSingleThreadExecutor()
  private val stopping = AtomicBoolean(false)
  @Volatile private var server: OpenAiServer? = null
  private var backend: LocalModelBackend? = null

  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
    if (state.value.active || state.value.loading) return START_NOT_STICKY
    if (Build.VERSION.SDK_INT >= 37 && intent?.getBooleanExtra("lan", false) == true &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
      state.value = ApiServerState(error = true, errorResource = R.string.api_permission_denied)
      stopSelf()
      return START_NOT_STICKY
    }
    state.value = ApiServerState(loading = true)
    try {
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.api_title), NotificationManager.IMPORTANCE_LOW))
      val stop = PendingIntent.getService(this, 0, Intent(this, ApiServerService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
      val open = PendingIntent.getActivity(this, 0, Intent(this, ApiServerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
      startForeground(7001, NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.api_title)).setContentText(getString(R.string.api_notification))
        .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.api_stop), stop).build())
    } catch (_: Exception) { state.value = ApiServerState(error = true); stopSelf(); return START_NOT_STICKY }
    val path = intent?.getStringExtra("path") ?: ""
    val port = intent?.getIntExtra("port", 8080) ?: 8080
    val key = intent?.getStringExtra("key") ?: ""
    val lan = intent?.getBooleanExtra("lan", false) ?: false
    val gpu = intent?.getBooleanExtra("gpu", false) ?: false
    worker.execute {
      try {
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(getModelStorageDir(this).canonicalFile.toPath()))
        require(file.isFile && file.extension == "litertlm")
        require(port in 1024..65535 && key.length >= 16)
        backend = LocalModelBackend(file, gpu)
        if (!stopping.get()) {
          server = OpenAiServer(if (lan) "0.0.0.0" else "127.0.0.1", port, key, backend!!)
          state.value = ApiServerState(active = true, lan = lan, port = server!!.port)
        }
      } catch (_: Exception) {
        if (!stopping.get()) { state.value = ApiServerState(error = true); stopSelf() }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopping.set(true)
    // Stop accepting requests immediately; model teardown follows any in-flight initialization.
    server?.close()
    worker.execute {
      server?.close()
      runCatching { backend?.close() }
      if (!state.value.error) state.value = ApiServerState()
    }
    worker.shutdown()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  companion object {
    internal val state = MutableStateFlow(ApiServerState())
    private const val CHANNEL = "openai_api"
    private const val STOP = "gallery.api.STOP"
  }
}
