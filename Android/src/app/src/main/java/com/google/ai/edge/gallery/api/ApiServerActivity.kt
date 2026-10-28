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

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApiServerActivity : ComponentActivity() {
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { GalleryTheme { Surface { ApiServerPanel {
      if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } } } }
  }
}

@Composable
private fun ApiServerPanel(requestNotification: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences("api_server", 0) }
  var key by remember { mutableStateOf(prefs.getString("key", null) ?: "sk-${UUID.randomUUID()}".also {
    prefs.edit().putString("key", it).apply()
  }) }
  var port by remember { mutableStateOf(prefs.getInt("port", 8080).toString()) }
  var lan by remember { mutableStateOf(prefs.getBoolean("lan", false)) }
  var gpu by remember { mutableStateOf(prefs.getBoolean("gpu", false)) }
  var files by remember { mutableStateOf<List<File>>(emptyList()) }
  var selected by remember { mutableStateOf(prefs.getString("path", "") ?: "") }
  var expanded by remember { mutableStateOf(false) }
  var refresh by remember { mutableIntStateOf(0) }
  var launchError by remember { mutableStateOf(false) }
  val state by ApiServerService.state.collectAsState()
  val locked = state.active || state.loading
  LaunchedEffect(refresh) {
    files = withContext(Dispatchers.IO) {
      getModelStorageDir(context).walkTopDown().filter { it.isFile && it.extension == "litertlm" }.toList()
    }
    if (files.none { it.absolutePath == selected }) selected = files.firstOrNull()?.absolutePath ?: ""
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.api_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.api_description))
    Text(stringResource(when { state.error || launchError -> R.string.api_error; state.loading -> R.string.api_loading; state.active -> R.string.api_running; else -> R.string.api_stopped }))
    Box {
      OutlinedButton(onClick = { expanded = true }, enabled = !locked && files.isNotEmpty()) {
        Text(File(selected).name.ifEmpty { stringResource(R.string.api_no_models) })
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        files.forEach { file -> DropdownMenuItem(text = { Text(file.relativeTo(getModelStorageDir(context)).path) }, onClick = { selected = file.absolutePath; expanded = false }) }
      }
    }
    OutlinedButton(onClick = { refresh++ }, enabled = !locked) { Text(stringResource(R.string.api_refresh)) }
    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.api_port)) }, enabled = !locked, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.api_key)) }, enabled = !locked, singleLine = true)
    Row { Checkbox(checked = lan, onCheckedChange = { lan = it }, enabled = !locked); Text(stringResource(R.string.api_lan)) }
    Row { Checkbox(checked = gpu, onCheckedChange = { gpu = it }, enabled = !locked); Text(stringResource(R.string.api_gpu)) }
    Text(stringResource(R.string.api_network_notice), style = MaterialTheme.typography.bodySmall)
    val addresses = remember { runCatching {
      NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList()) }
    SelectionContainer {
      Column {
        Text("http://127.0.0.1:$port/v1")
        if (lan) addresses.forEach { Text("http://$it:$port/v1") }
        if (selected.isNotEmpty()) Text("model: ${File(selected).nameWithoutExtension}")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(enabled = !locked && selected.isNotEmpty() && port.toIntOrNull() in 1024..65535 && key.length >= 16 && key.all { it in '!'..'~' },
        onClick = {
          launchError = false
          prefs.edit().putString("key", key).putInt("port", port.toInt()).putString("path", selected).putBoolean("lan", lan).putBoolean("gpu", gpu).apply()
          requestNotification()
          try {
            ContextCompat.startForegroundService(context, Intent(context, ApiServerService::class.java)
              .putExtra("path", selected).putExtra("port", port.toInt()).putExtra("key", key).putExtra("lan", lan).putExtra("gpu", gpu))
          } catch (_: Exception) { launchError = true }
        }) { Text(stringResource(R.string.api_start)) }
      OutlinedButton(enabled = locked, onClick = { context.stopService(Intent(context, ApiServerService::class.java)) }) { Text(stringResource(R.string.api_stop)) }
    }
    TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text(stringResource(R.string.close)) }
  }
}
