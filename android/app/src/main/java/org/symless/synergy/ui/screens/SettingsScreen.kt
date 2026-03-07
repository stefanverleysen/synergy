/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.symless.synergy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.symless.synergy.R
import org.symless.synergy.client.util.logging.KLoggingManager
import org.symless.synergy.data.aidl.ScreenState
import org.symless.synergy.data.aidl.ServerState
import org.symless.synergy.ui.annotations.PreviewAll
import org.symless.synergy.ui.components.AppState
import org.symless.synergy.ui.components.ClientCertificateDialog
import org.symless.synergy.ui.components.SynergyCard
import org.symless.synergy.ui.components.SynergyCardSubtitle
import org.symless.synergy.ui.components.SynergyCardTitle
import org.symless.synergy.ui.components.SynergyCardWidgetTitle
import org.symless.synergy.ui.components.SynergyFillSpacer
import org.symless.synergy.ui.components.SynergyCardWidget
import org.symless.synergy.ui.components.Toolbar
import org.symless.synergy.ui.components.IAppState
import org.symless.synergy.ui.components.LocalSnackbarHostState
import org.symless.synergy.ui.components.synergyCardDefaultContainerModifier
import org.symless.synergy.ui.components.synergyCardStyleDefaults
import org.symless.synergy.ui.components.synergyCardWidgetStyleDefaults
import org.symless.synergy.ui.components.preview.PreviewSynergyThemedRoot
import org.symless.synergy.data.ClientCertificateManager
import org.symless.synergy.data.TrustStore
import org.symless.synergy.client.net.FingerprintManager

private val log = KLoggingManager.logger("SettingsScreen")

@Serializable data object SettingsScreenRoute

fun NavController.navigateToSettingsScreen(navOptions: NavOptions) =
  navigate(route = SettingsScreenRoute, navOptions)

fun NavGraphBuilder.settingsScreen(appState: IAppState) {
  composable<SettingsScreenRoute> { SettingsScreenRoute(appState = appState) }
}

@Composable
internal fun SettingsScreenRoute(appState: IAppState) {
  val state by appState.connectionStateFlow.collectAsStateWithLifecycle()
  val screenState = state.screen
  val isConnected = state.isConnected
  val snackbarHost = LocalSnackbarHostState.current
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  var isRegenerating by remember { mutableStateOf(false) }

  SettingsScreen(
    uiState =
      if (screenState == null) SettingsUiState.Loading
      else SettingsUiState.Success(isConnected, screenState),
    onChange = { newScreenState ->
      if (appState !is AppState) return@SettingsScreen

      appState.serviceClient.updateScreenState(newScreenState)
      scope.launch {
        snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
      }

    },
    isRegenerating = isRegenerating,
    onRegenerateCertificate = {
      if (appState !is AppState) return@SettingsScreen
      if (isRegenerating) return@SettingsScreen

      scope.launch {
        isRegenerating = true
        try {
          val result = appState.serviceClient.regenerateClientCertificate()
          if (result?.ok == true) {
            snackbarHost.showSnackbar(ctx.getString(R.string.client_cert_regenerated))
          } else {
            snackbarHost.showSnackbar(ctx.getString(R.string.client_cert_regenerate_error))
          }
        } finally {
          isRegenerating = false
        }
      }
    },
    onCancel = { appState.toggleSettings() },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onChange: (ScreenState) -> Unit,
  isRegenerating: Boolean,
  onRegenerateCertificate: () -> Unit,
  onCancel: () -> Unit,
) {
  val composableScope = rememberCoroutineScope()

  when (uiState) {
    is SettingsUiState.Loading -> {
      CircularProgressIndicator(
        modifier = Modifier.width(64.dp),
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }

    is SettingsUiState.Success -> {
      val textFieldColors =
        TextFieldDefaults
          .colors(
            unfocusedIndicatorColor = Color.Transparent
          )
      val focusRequester = remember { FocusRequester() }
      val screenValue = uiState.screen
      var screenName by remember(screenValue) { mutableStateOf(screenValue.name) }
      var address by remember(screenValue) { mutableStateOf(screenValue.server.address) }
      var port by remember(screenValue) { mutableIntStateOf(screenValue.server.port) }
      var useTls by remember(screenValue) { mutableStateOf(screenValue.server.useTls) }
      var disconnectOnScreenOff by remember(screenValue) { mutableStateOf(screenValue.disconnectOnScreenOff) }
      var isDirty by remember(screenValue) { mutableStateOf(false) }

      val saveChanges = {
        onChange(
          ScreenState().apply {
            name = screenName
            this.disconnectOnScreenOff = disconnectOnScreenOff
            server =
              ServerState().apply {
                this.address = address
                this.port = port
                this.useTls = useTls
              }
          }
        )
      }

      val textStyle = MaterialTheme.typography.bodyMedium
      val textStyleCharWidthDp =
        with(LocalDensity.current) { textStyle.fontSize.toDp() }

      val innerScrollState = rememberScrollState()
      var showClientCertDialog by remember { mutableStateOf(false) }

      SynergyCardWidget(
        style = synergyCardWidgetStyleDefaults(
          containerModifier = Modifier.fillMaxSize(),
        ),
        header = {
          Toolbar {
            SynergyCardWidgetTitle(
              text = stringResource(R.string.settings_screen_title),
            )
            SynergyFillSpacer()
            Button(
              onClick = { onCancel() },
              shape = MaterialTheme.shapes.small,
              colors = ButtonDefaults.textButtonColors(),
            ) {
              Text(stringResource(R.string.button_cancel))
            }
            Button(
              onClick = {
                when {
                  isDirty -> { saveChanges(); onCancel() }
                  else -> onCancel()
                }
              },
              shape = MaterialTheme.shapes.small,
              colors = ButtonDefaults.filledTonalButtonColors(),
              enabled = isDirty,
            ) {
              Text(stringResource(R.string.button_save))
            }
          }
        },
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier =
            Modifier.fillMaxWidth()
              .weight(1f)
              .padding(horizontal = 16.dp, vertical = 12.dp)
              .verticalScroll(innerScrollState),
        ) {
          TextField(
            label = {
              Text(
                stringResource(R.string.settings_screen_screen_name_label)
              )
            },
            singleLine = true,
            value = screenName,
            colors = textFieldColors,
            onValueChange = {
              screenName = it
              isDirty = true
            },
            placeholder = {
              Text(
                stringResource(
                  R.string.settings_screen_screen_name_placeholder
                )
              )
            },
            modifier =
              Modifier.focusRequester(focusRequester)
                .background(Color.Transparent)
                .fillMaxWidth(),
          )

          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            TextField(
              value = address,
              maxLines = 1,
              label = {
                Text(
                  stringResource(
                    R.string.settings_screen_screen_address_label
                  )
                )
              },
              onValueChange = {
                address = it
                isDirty = true
              },
              placeholder = {
                Text(
                  stringResource(
                    R.string.settings_screen_screen_address_placeholder
                  )
                )
              },
              singleLine = true,
              colors = textFieldColors,
              modifier = Modifier.background(Color.Transparent).weight(1f),
            )

            TextField(
              value = port.toString(),
              colors = textFieldColors,
              label = {
                Text(
                  stringResource(R.string.settings_screen_screen_port_label)
                )
              },
              onValueChange = { newPort ->
                if (newPort.all { it.isDigit() }) {
                  port = newPort.toInt()
                  isDirty = true
                }
              },
              placeholder = {
                Text(
                  stringResource(
                    R.string.settings_screen_screen_port_placeholder
                  )
                )
              },
              keyboardOptions =
                KeyboardOptions(
                  keyboardType = KeyboardType.Number,
                  imeAction = ImeAction.Done,
                ),
              singleLine = true,
              modifier =
                Modifier.background(Color.Transparent)
                  .width(textStyleCharWidthDp * 6f),
            )

            Column(
              verticalArrangement =
                Arrangement.spacedBy(
                  space = 0.dp,
                  alignment = Alignment.CenterVertically,
                ),
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(top = 2.dp),
            ) {
              Text(
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                text =
                  stringResource(R.string.app_prefs_screen_server_use_tls),
              )
              Checkbox(
                checked = useTls,
                onCheckedChange = {
                  useTls = it
                  isDirty = true
                },
              )
            }
          }

          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          ) {
            Column(
              modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
              Text(
                text = stringResource(R.string.settings_screen_disconnect_on_screen_off_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.settings_screen_disconnect_on_screen_off_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = disconnectOnScreenOff,
              onCheckedChange = {
                disconnectOnScreenOff = it
                isDirty = true
              },
            )
          }

          HorizontalDivider()

          Column(
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = stringResource(R.string.tls_settings_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = stringResource(R.string.tls_settings_description),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Column(
              modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
              Text(
                text = stringResource(R.string.server_fingerprints_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.server_fingerprints_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
              )

              val ctx = LocalContext.current
              val snackbarHost = LocalSnackbarHostState.current

              Button(
                onClick = {
                  composableScope.launch {
                    val trustStore = TrustStore(ctx)
                    trustStore.clearAllFingerprints()
                    snackbarHost.showSnackbar(ctx.getString(R.string.server_fingerprints_cleared))
                  }
                },
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.padding(top = 8.dp),
              ) {
                Text(stringResource(R.string.server_fingerprints_clear_all))
              }
            }

            Column(
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = stringResource(R.string.client_cert_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.client_cert_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
              )

              val ctx = LocalContext.current
              val snackbarHost = LocalSnackbarHostState.current

              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
              ) {
                Button(
                  onClick = {
                    composableScope.launch {
                      val certManager = ClientCertificateManager(ctx)
                      val cert = certManager.getCertificate()
                      if (cert != null) {
                        showClientCertDialog = true
                      } else {
                        snackbarHost.showSnackbar(ctx.getString(R.string.client_cert_not_available))
                      }
                    }
                  },
                  shape = MaterialTheme.shapes.small,
                  colors = ButtonDefaults.outlinedButtonColors(),
                ) {
                  Text(stringResource(R.string.client_cert_view))
                }

                Button(
                  onClick = { onRegenerateCertificate() },
                  enabled = !isRegenerating,
                  shape = MaterialTheme.shapes.small,
                  colors = ButtonDefaults.outlinedButtonColors(),
                ) {
                  if (isRegenerating) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(16.dp),
                      strokeWidth = 2.dp,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                  }
                  Text(stringResource(R.string.client_cert_regenerate))
                }
              }
            }
          }

          if (showClientCertDialog) {
            val ctx = LocalContext.current
            val certManager = ClientCertificateManager(ctx)
            val cert = certManager.getCertificate()
            cert?.let {
              val fingerprint = FingerprintManager.computeFingerprint(it)
              ClientCertificateDialog(
                fingerprint = fingerprint,
                onDismiss = { showClientCertDialog = false }
              )
            }
          }
        }
      }
    }
  }
}

@Preview
@Composable
fun SettingsScreenPreviewLoading() {
  PreviewSynergyThemedRoot {
    SettingsScreen(
      uiState = SettingsUiState.Loading,
      onChange = {},
      isRegenerating = false,
      onRegenerateCertificate = {},
      onCancel = {},
    )
  }
}

@PreviewAll
@Composable
fun SettingsScreenPreviewSuccess() {
  PreviewSynergyThemedRoot { appState ->
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbarHost = LocalSnackbarHostState.current

    var screenStateValue by remember {
      mutableStateOf(
        ScreenState().apply {
          name = "AndroidScreen"
          disconnectOnScreenOff = false
          server =
            ServerState().apply {
              address = "localhost"
              port = 24800
              useTls = false
            }
        }
      )
    }
    SettingsScreen(
      uiState = SettingsUiState.Success(true, screenStateValue),
      onChange = { screenState ->
        screenStateValue = screenState
        scope.launch {
          snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
        }
      },
      isRegenerating = false,
      onRegenerateCertificate = {},
      onCancel = {},
    )
  }
}

sealed class SettingsUiState {
  data object Loading : SettingsUiState()

  data class Success(val isConnected: Boolean, val screen: ScreenState) :
    SettingsUiState()
}
