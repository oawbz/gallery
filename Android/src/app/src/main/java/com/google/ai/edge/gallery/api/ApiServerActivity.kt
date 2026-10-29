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
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.ui.common.CopyableSettingValue
import com.google.ai.edge.gallery.ui.common.SettingsSection
import com.google.ai.edge.gallery.ui.common.copySettingValue
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
  private var pendingStart: Intent? = null
  private var requestingPermissions by mutableStateOf(false)
  private var startError by mutableIntStateOf(0)
  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    requestingPermissions = false
    val intent = pendingStart
    pendingStart = null
    if (intent != null) {
      if (needsLocalNetworkPermission(intent)) startError = R.string.api_permission_denied
      else startServiceAfterPermissions(intent)
    }
  }

  private fun needsLocalNetworkPermission(intent: Intent): Boolean =
    Build.VERSION.SDK_INT >= 37 && intent.getBooleanExtra("lan", false) &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

  private fun requestStart(intent: Intent) {
    if (requestingPermissions) return
    startError = 0
    val missing = mutableListOf<String>()
    if (needsLocalNetworkPermission(intent)) missing.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    if (Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      missing.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (missing.isEmpty()) startServiceAfterPermissions(intent)
    else {
      pendingStart = intent
      requestingPermissions = true
      permissions.launch(missing.toTypedArray())
    }
  }

  private fun startServiceAfterPermissions(intent: Intent) {
    // Notification permission is optional for an FGS, but Android 17 LAN access is not.
    try { ContextCompat.startForegroundService(this, intent) }
    catch (_: Exception) { startError = R.string.api_error }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      GalleryTheme {
        Surface { ApiServerPanel(requestingPermissions, startError, ::requestStart) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiServerPanel(requestingPermissions: Boolean, startError: Int, onStart: (Intent) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences("api_server", 0) }
  var key by remember { mutableStateOf(prefs.getString("key", null) ?: "sk-${UUID.randomUUID()}".also {
    prefs.edit().putString("key", it).apply()
  }) }
  var port by remember { mutableStateOf(prefs.getInt("port", 8080).toString()) }
  var lan by remember { mutableStateOf(prefs.getBoolean("lan", false)) }
  var gpu by remember { mutableStateOf(prefs.getBoolean("gpu", false)) }
  var contextTokens by remember { mutableStateOf(prefs.getInt("context_tokens", DEFAULT_CONTEXT_TOKENS).toString()) }
  var thinking by remember { mutableStateOf(prefs.getBoolean("thinking", true)) }
  var thinkingBudget by remember { mutableStateOf(prefs.getInt("thinking_budget", DEFAULT_THINKING_BUDGET).toString()) }
  var files by remember { mutableStateOf<List<File>>(emptyList()) }
  var selected by remember { mutableStateOf(prefs.getString("path", "") ?: "") }
  var expanded by remember { mutableStateOf(false) }
  var showKey by remember { mutableStateOf(false) }
  var refresh by remember { mutableIntStateOf(0) }
  val state by ApiServerService.state.collectAsState()
  val locked = state.active || state.loading || requestingPermissions
  LaunchedEffect(refresh) {
    files = withContext(Dispatchers.IO) {
      getModelStorageDir(context).walkTopDown().filter { it.isFile && it.extension == "litertlm" }.toList()
    }
    if (files.none { it.absolutePath == selected }) selected = files.firstOrNull()?.absolutePath ?: ""
  }
  val error = when { startError != 0 -> startError; state.error -> state.errorResource; else -> 0 }
  val statusText = stringResource(when {
    error != 0 -> error
    requestingPermissions -> R.string.api_requesting_permissions
    state.loading -> R.string.api_loading
    state.active -> R.string.api_running
    else -> R.string.api_stopped
  })
  val copyKeyLabel = stringResource(R.string.api_key_label)
  val statusColor = when {
    error != 0 -> MaterialTheme.colorScheme.errorContainer
    state.active -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
  }
  Scaffold(
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.api_title),
        leftAction = AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = { (context as? android.app.Activity)?.finish() }),
      )
    },
    bottomBar = {
      Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
          if (state.active || state.loading) {
            OutlinedButton(onClick = { context.stopService(Intent(context, ApiServerService::class.java)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
              Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.api_stop))
            }
          } else {
            Button(
              enabled = !locked && selected.isNotEmpty() && validContextTokens(contextTokens.toIntOrNull()) && (!thinking || validThinkingBudget(thinkingBudget.toIntOrNull(),contextTokens.toIntOrNull() ?: 0)) && port.toIntOrNull() in 1024..65535 && key.length >= 16 && key.all { it in '!'..'~' },
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
              onClick = {
                val budget = thinkingBudget.toIntOrNull()?.takeIf { validThinkingBudget(it,contextTokens.toInt()) }
                  ?: minOf(DEFAULT_THINKING_BUDGET,contextTokens.toInt() / 2)
                prefs.edit().putString("key", key).putInt("port", port.toInt()).putString("path", selected).putBoolean("lan", lan).putBoolean("gpu", gpu).putInt("context_tokens", contextTokens.toInt()).putBoolean("thinking", thinking).putInt("thinking_budget", budget).apply()
                onStart(Intent(context, ApiServerService::class.java)
                  .putExtra("path", selected).putExtra("port", port.toInt()).putExtra("key", key).putExtra("lan", lan).putExtra("gpu", gpu).putExtra("context_tokens", contextTokens.toInt()).putExtra("thinking", thinking).putExtra("thinking_budget", budget))
              },
            ) {
              Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.api_start))
            }
          }
        }
      }
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Card(colors = CardDefaults.cardColors(containerColor = statusColor), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.loading || requestingPermissions) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(when { error != 0 -> Icons.Rounded.ErrorOutline; state.active -> Icons.Rounded.CheckCircle; else -> Icons.Rounded.PowerSettingsNew }, contentDescription = null)
            Text(statusText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
          }
          if (state.active) Text(stringResource(R.string.api_context_active, state.contextTokens), style = MaterialTheme.typography.bodyMedium)
          if (state.active && state.thinkingEnabled) Text(stringResource(R.string.api_thinking_active,state.thinkingBudget), style = MaterialTheme.typography.bodyMedium)
          if (state.active) Text(stringResource(R.string.api_listening, if (state.lan) "0.0.0.0" else "127.0.0.1", state.port), style = MaterialTheme.typography.bodyMedium)
          else if (error == 0) Text(stringResource(R.string.api_service_summary), style = MaterialTheme.typography.bodyMedium)
          if (error == R.string.api_permission_denied) TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
          }) { Text(stringResource(R.string.api_open_permissions)) }
        }
      }

      SettingsSection(title = stringResource(R.string.api_model_section), icon = Icons.Rounded.Memory,
        action = { IconButton(onClick = { refresh++ }, enabled = !locked) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.api_refresh)) } }) {
        Box {
          OutlinedButton(
            onClick = { expanded = true }, enabled = !locked && files.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
          ) {
            Text(File(selected).nameWithoutExtension.ifEmpty { stringResource(R.string.api_no_models) }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
          }
          DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            files.forEach { file -> DropdownMenuItem(text = { Text(file.relativeTo(getModelStorageDir(context)).path) }, onClick = { selected = file.absolutePath; expanded = false }) }
          }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
          listOf("CPU", "GPU").forEachIndexed { index, label ->
            SegmentedButton(selected = gpu == (index == 1), onClick = { gpu = index == 1 }, enabled = !locked,
              shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(label) })
          }
        }
        OutlinedTextField(
          value = contextTokens, onValueChange = { contextTokens = it },
          label = { Text(stringResource(R.string.api_context_label)) },
          supportingText = { Text(stringResource(R.string.api_context_hint)) },
          enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
          isError = !validContextTokens(contextTokens.toIntOrNull()),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(8192,16384,32768,65536,131072,262144).forEach { tokens ->
            FilterChip(selected = contextTokens.toIntOrNull() == tokens,
              onClick = { contextTokens = tokens.toString() }, enabled = !locked,
              label = { Text("${tokens / 1024}K") })
          }
        }
        Text(stringResource(R.string.api_model_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      SettingsSection(title = stringResource(R.string.api_thinking_section), icon = Icons.Rounded.Psychology) {
        Row(Modifier.fillMaxWidth().toggleable(value = thinking, enabled = !locked, role = Role.Switch, onValueChange = { thinking = it }),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.api_thinking_label),style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.api_thinking_hint),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Switch(checked = thinking,onCheckedChange = null,enabled = !locked)
        }
        OutlinedTextField(value = thinkingBudget,onValueChange = { thinkingBudget = it },
          enabled = !locked && thinking, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
          label = { Text(stringResource(R.string.api_thinking_budget_label)) },
          supportingText = { Text(stringResource(R.string.api_thinking_budget_hint)) },
          isError = thinking && !validThinkingBudget(thinkingBudget.toIntOrNull(),contextTokens.toIntOrNull() ?: 0),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
      }

      SettingsSection(title = stringResource(R.string.api_network_section), icon = Icons.Rounded.Wifi) {
        Row(
          Modifier.fillMaxWidth().toggleable(value = lan, enabled = !locked, role = Role.Switch, onValueChange = { lan = it }),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.api_lan), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.api_lan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Switch(checked = lan, onCheckedChange = null, enabled = !locked)
        }
        OutlinedTextField(value = port, onValueChange = { port = it },
          label = { Text(stringResource(R.string.api_port_label)) }, supportingText = { Text("1024–65535") },
          enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
          isError = port.toIntOrNull() !in 1024..65535, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
      }

      SettingsSection(title = copyKeyLabel, icon = Icons.Rounded.Key) {
        OutlinedTextField(
          value = key, onValueChange = { key = it }, readOnly = locked, modifier = Modifier.fillMaxWidth(),
          singleLine = true, shape = MaterialTheme.shapes.medium,
          visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
          label = { Text(stringResource(R.string.api_key_label)) },
          supportingText = { Text(stringResource(R.string.api_key_hint)) },
          trailingIcon = {
            Row {
              IconButton(onClick = { showKey = !showKey }) {
                Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = stringResource(if (showKey) R.string.api_hide_secret else R.string.api_show_secret))
              }
              IconButton(onClick = { copySettingValue(context, copyKeyLabel, key, sensitive = true) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.api_copy_value, copyKeyLabel))
              }
            }
          },
        )
      }
      ApiServerAddresses(
        port = if (state.active) state.port else port.toIntOrNull() ?: 8080,
        lan = if (state.active) state.lan else lan,
        active = state.active, modelId = File(selected).nameWithoutExtension,
      )
      Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.api_network_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun ApiServerAddresses(port: Int, lan: Boolean, active: Boolean, modelId: String) {
  var refresh by remember { mutableIntStateOf(0) }
  var addresses by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
  LaunchedEffect(refresh, active) {
    addresses = withContext(Dispatchers.IO) {
      runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
          .flatMap { network ->
            network.inetAddresses.toList().filterIsInstance<Inet4Address>()
              .filter { it.isSiteLocalAddress }.mapNotNull { ip -> ip.hostAddress?.let { network.name to it } }
          }.distinct()
      }.getOrDefault(emptyList())
    }
  }
  SettingsSection(title = stringResource(R.string.api_connection_section), icon = Icons.Rounded.Link,
    action = { IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.api_refresh_addresses)) } }) {
    if (!active) Text(stringResource(R.string.api_address_preview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (lan) {
      if (addresses.isEmpty()) Text(stringResource(R.string.api_no_network_address), style = MaterialTheme.typography.bodyMedium)
      addresses.forEach { (network, ip) ->
        CopyableSettingValue(stringResource(R.string.api_lan_url, network), "http://$ip:$port/v1")
        CopyableSettingValue(stringResource(R.string.api_health_url), "http://$ip:$port/health")
      }
      Text(stringResource(R.string.api_lan_address_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      CopyableSettingValue(stringResource(R.string.api_phone_url), "http://127.0.0.1:$port/v1")
      Text(stringResource(R.string.api_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (modelId.isNotEmpty()) CopyableSettingValue(stringResource(R.string.api_model_id), modelId)
  }
}
