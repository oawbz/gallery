/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.home

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.api.ApiServerActivity
import com.google.ai.edge.gallery.api.ApiServerService
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.CopyableSettingValue
import com.google.ai.edge.gallery.ui.common.SettingsSection
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  curFirebaseAnalytics: Boolean,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val locale = LocalConfiguration.current.locales[0]
  val dateFormatter = remember(locale) {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale).withZone(ZoneId.systemDefault())
  }
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var selectedFirebaseAnalytics by remember { mutableStateOf(curFirebaseAnalytics) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  var customHfToken by remember { mutableStateOf("") }
  var showToken by remember { mutableStateOf(false) }
  var showTos by remember { mutableStateOf(false) }
  val apiState by ApiServerService.state.collectAsState()
  val handleSaveToken = {
    if (customHfToken.isNotBlank()) {
      modelManagerViewModel.saveAccessToken(
        accessToken = customHfToken.trim(), refreshToken = "",
        expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
      )
      hfToken = modelManagerViewModel.getTokenStatusAndData().data
      customHfToken = ""
      focusManager.clearFocus()
    }
  }

  Dialog(onDismissRequest = onDismissed, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.drawer_settings_label), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
              style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          IconButton(onClick = onDismissed) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close)) }
        }
        Column(
          Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Card(
            onClick = { context.startActivity(Intent(context, ApiServerActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
          ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Icon(Icons.Rounded.Api, contentDescription = null, modifier = Modifier.size(28.dp))
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.api_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (apiState.active) R.string.api_running else R.string.settings_api_hint), style = MaterialTheme.typography.bodySmall)
              }
              Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
          }
          SettingsSection(title = stringResource(R.string.settings_appearance), icon = Icons.Rounded.Palette) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
              THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                  shape = SegmentedButtonDefaults.itemShape(index, THEME_OPTIONS.size),
                  selected = theme == selectedTheme,
                  onClick = {
                    selectedTheme = theme
                    ThemeSettings.themeOverride.value = theme
                    modelManagerViewModel.saveThemeOverride(theme)
                    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(when (theme) {
                      Theme.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                      Theme.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                      else -> UiModeManager.MODE_NIGHT_AUTO
                    })
                  },
                  label = { Text(stringResource(themeLabelRes(theme))) },
                )
              }
            }
          }
          SettingsSection(title = stringResource(R.string.settings_privacy), icon = Icons.Rounded.Shield) {
            Row(
              Modifier.fillMaxWidth().toggleable(value = selectedFirebaseAnalytics, role = Role.Switch, onValueChange = {
                selectedFirebaseAnalytics = it
                modelManagerViewModel.saveFirebaseAnalytics(it)
              }),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_dialog_firebase_analytics_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_dialog_firebase_analytics_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Switch(checked = selectedFirebaseAnalytics, onCheckedChange = null)
            }
          }
          SettingsSection(title = stringResource(R.string.settings_hf_token), icon = Icons.Rounded.Key) {
            val currentToken = hfToken
            if (currentToken != null && currentToken.accessToken.isNotEmpty()) {
              CopyableSettingValue(stringResource(R.string.settings_saved_token), currentToken.accessToken, sensitive = true)
              Text(stringResource(R.string.settings_token_expires, dateFormatter.format(Instant.ofEpochMilli(currentToken.expiresAtMs))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              Text(stringResource(R.string.settings_token_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
              value = customHfToken, onValueChange = { customHfToken = it }, modifier = Modifier.fillMaxWidth(),
              singleLine = true, shape = MaterialTheme.shapes.medium,
              label = { Text(stringResource(R.string.settings_token_manual)) },
              visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
              trailingIcon = { IconButton(onClick = { showToken = !showToken }) {
                Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = stringResource(if (showToken) R.string.api_hide_secret else R.string.api_show_secret))
              } },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
              TextButton(onClick = { modelManagerViewModel.clearAccessToken(); hfToken = null }, enabled = currentToken != null) {
                Text(stringResource(R.string.clear))
              }
              FilledTonalButton(onClick = handleSaveToken, enabled = customHfToken.isNotBlank()) { Text(stringResource(R.string.save)) }
            }
          }
          SettingsSection(title = stringResource(R.string.settings_about), icon = Icons.Rounded.Info) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, OssLicensesMenuActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.view_licenses))
            }
            OutlinedButton(onClick = { showTos = true }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.settings_dialog_view_app_terms_of_service))
            }
            ClickableLink(url = "https://ai.google.dev/gemma/terms", linkText = stringResource(R.string.tos_dialog_title_gemma), textAlign = TextAlign.Start)
            ClickableLink(url = "https://ai.google.dev/gemma/prohibited_use_policy", linkText = stringResource(R.string.settings_dialog_gemma_prohibited_use_policy), textAlign = TextAlign.Start)
          }
        }
      }
    }
  }
  if (showTos) AppTosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
}

@StringRes
private fun themeLabelRes(theme: Theme): Int = when (theme) {
  Theme.THEME_AUTO -> R.string.theme_auto
  Theme.THEME_LIGHT -> R.string.theme_light
  Theme.THEME_DARK -> R.string.theme_dark
  else -> R.string.unknown
}
