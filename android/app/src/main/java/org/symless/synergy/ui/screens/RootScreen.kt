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

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalPermissionsApi::class)

package org.symless.synergy.ui.screens

import android.content.Context
import androidx.compose.animation.Crossfade
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.symless.synergy.R
import org.symless.synergy.client.util.logging.KLoggingManager
import org.symless.synergy.ext.canDrawOverlays
import org.symless.synergy.ext.isAccessibilityServiceEnabled
import org.symless.synergy.ext.isInputMethodServiceEnabled
import org.symless.synergy.ext.isTelevision
import org.symless.synergy.ext.launchInputMethodServiceSettings
import org.symless.synergy.ext.observeAccessibilityStatus
import org.symless.synergy.ext.requestAccessibilityEnabled
import org.symless.synergy.ext.requestOverlayPermission
import org.symless.synergy.ext.registerForServiceDisconnectionEvents
import org.symless.synergy.services.GlobalInputService
import org.symless.synergy.ui.annotations.PreviewAll
import org.symless.synergy.ui.components.AppState
import org.symless.synergy.ui.components.AppToolbar
import org.symless.synergy.ui.components.IAppState
import org.symless.synergy.ui.components.LifecycleEventHookEffect
import org.symless.synergy.ui.components.LocalAppState
import org.symless.synergy.ui.components.LocalSnackbarHostState
import org.symless.synergy.ui.components.PermissionsNeededDialog
import org.symless.synergy.ui.components.AccessibilityServiceRestartDialog
import org.symless.synergy.ui.components.RootNavHost
import org.symless.synergy.ui.components.SetupStep
import org.symless.synergy.ui.components.SetupWizard
import org.symless.synergy.ui.components.FingerprintVerificationDialog
import org.symless.synergy.ui.models.FingerprintVerificationState
import org.symless.synergy.ui.components.currentDeviceConfig
import org.symless.synergy.data.ClientCertificateManager
import org.symless.synergy.client.net.FingerprintManager
import org.symless.synergy.ui.components.preview.PreviewAppState
import org.symless.synergy.ui.theme.SynergyTheme
import org.symless.synergy.ui.theme.LocalSynergyExtendedColorScheme

private val log = KLoggingManager.logger("RootScreen")

@Composable
fun RootScreenRoute(appState: IAppState) {
  RootScreen(appState = appState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(appState: IAppState) {
  val context = LocalContext.current
  val (isPortrait, isXL, isLarge) = currentDeviceConfig()
  val scope = rememberCoroutineScope()

  UpdatePermissionsEffect(appState = appState)
  val snackbarHostState = remember { SnackbarHostState() }

  // Track if we should show the accessibility restart dialog
  val showAccessibilityRestartDialog = remember { mutableStateOf(false) }

  // Listen for accessibility service status changes
  DisposableEffect(Unit) {
    val closeChecker = context.observeAccessibilityStatus(GlobalInputService::class.java, scope = scope) { isEnabled ->
      val isServiceEnabled = context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
      log.info { "Accessibility service enabled: isEnabled=$isEnabled,isServiceEnabled=$isServiceEnabled" }

      appState.updatePermissions(
        canDrawOverlays = context.canDrawOverlays(),
        accessibilityEnabled = isServiceEnabled,
        imeEnabled = context.isInputMethodServiceEnabled(),
      )

      if (isServiceEnabled)
        context.startService(Intent(context, GlobalInputService::class.java))
    }

    onDispose { closeChecker() }
  }

  // Listen for service disconnection events (crashes/unbinds while still enabled)
  DisposableEffect(Unit) {
    val disconnectionListener = context.registerForServiceDisconnectionEvents(
      GlobalInputService::class
    ) {
      log.warn { "Accessibility service disconnected unexpectedly" }
      // Check if service is still enabled in settings but not actually running
      val isStillEnabledInSettings = context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
      if (isStillEnabledInSettings) {
        log.warn { "Service is enabled in settings but not running - showing restart dialog" }
        showAccessibilityRestartDialog.value = true
      }
    }

    onDispose { disconnectionListener.close() }
  }

  CompositionLocalProvider(
    LocalSnackbarHostState provides snackbarHostState,
    LocalAppState provides appState,
  ) {
    SynergyTheme {
      val extColorScheme = LocalSynergyExtendedColorScheme.current
      Surface(
        color = extColorScheme.toolbar,
        modifier = Modifier.fillMaxSize(),
      ) {
        Scaffold(
          snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
          topBar = { AppToolbar() },
          bottomBar = {},
          modifier =
            Modifier.windowInsetsPadding(WindowInsets.Companion.statusBars)
              .imePadding(),
        ) { innerPadding ->

          // COMPLEX PERMISSIONS
          val imeEnabled by
            appState.permissionIMEEnabled.collectAsStateWithLifecycle()
          val canDrawOverlays by
            appState.permissionCanDrawOverlays.collectAsStateWithLifecycle()
          val accessibilityEnabled by
            appState.permissionAccessibilityEnabled
              .collectAsStateWithLifecycle()

          // REGULAR PERMISSIONS (API 33+)
          val needsRuntimePermissions =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
          val notificationsPermissionState =
            if (needsRuntimePermissions)
              rememberPermissionState(
                android.Manifest.permission.POST_NOTIFICATIONS
              )
            else null
          val nearbyDevicesPermissionState =
            if (needsRuntimePermissions)
              rememberPermissionState(
                android.Manifest.permission.NEARBY_WIFI_DEVICES
              )
            else null

          // Count total required steps
          val totalSteps = 3 +
            (if (notificationsPermissionState != null && !notificationsPermissionState.status.isGranted) 1 else 0) +
            (if (nearbyDevicesPermissionState != null && !nearbyDevicesPermissionState.status.isGranted) 1 else 0)

          // Determine current step number (completed steps + 1)
          val completedSteps =
            (if (canDrawOverlays) 1 else 0) +
            (if (canDrawOverlays && accessibilityEnabled) 1 else 0) +
            (if (canDrawOverlays && accessibilityEnabled && imeEnabled) 1 else 0)

          val allCoreGranted = canDrawOverlays && accessibilityEnabled && imeEnabled
          val isTV = context.isTelevision()
          var setupSkipped by remember { mutableStateOf(false) }
          val onSkipSetup = { setupSkipped = true }

          if (isTV && !accessibilityEnabled && !setupSkipped) {
            SetupWizard(
              onSkip = onSkipSetup,
              step = SetupStep(
                stepNumber = 1,
                totalSteps = 1,
                title = "Enable Accessibility Service",
                instructions = listOf(
                  "Press the button below to open Accessibility settings.",
                  "Find \"Synergy Android\" and select it.",
                  "Turn the service ON and confirm.",
                  "Press Back to return here.",
                ),
                buttonText = "Open Accessibility Settings",
                isCompleted = false,
                onAction = { context.requestAccessibilityEnabled() },
              ),
            )
          } else if (!isTV && !setupSkipped && (!allCoreGranted || (notificationsPermissionState != null && !notificationsPermissionState.status.isGranted) || (nearbyDevicesPermissionState != null && !nearbyDevicesPermissionState.status.isGranted))) {
            Crossfade(
              targetState = completedSteps,
              modifier = Modifier.padding(innerPadding).fillMaxSize(),
              label = "setup-wizard",
            ) { _ ->
              when {
                !canDrawOverlays -> SetupWizard(
                  onSkip = onSkipSetup,
                  step = SetupStep(
                    stepNumber = 1,
                    totalSteps = totalSteps,
                    title = "Allow Display Over Other Apps",
                    instructions = listOf(
                      "Tap the button below to open system settings.",
                      "Find \"Synergy Android\" in the app list.",
                      "Tap on it and toggle \"Allow display over other apps\" to ON.",
                      "Press Back or switch back to Synergy Android.",
                    ),
                    buttonText = "Open Overlay Settings",
                    isCompleted = false,
                    onAction = { context.requestOverlayPermission() },
                  ),
                )
                !accessibilityEnabled -> SetupWizard(
                  onSkip = onSkipSetup,
                  step = SetupStep(
                    stepNumber = 2,
                    totalSteps = totalSteps,
                    title = "Enable Accessibility Service",
                    instructions = listOf(
                      "Tap the button below to open Accessibility settings.",
                      "Scroll down to \"Downloaded apps\" or \"Installed services\".",
                      "Find \"Synergy Android\" and tap on it.",
                      "Toggle the service ON and confirm the dialog.",
                    ),
                    buttonText = "Open Accessibility Settings",
                    isCompleted = false,
                    onAction = { context.requestAccessibilityEnabled() },
                  ),
                )
                !imeEnabled -> SetupWizard(
                  onSkip = onSkipSetup,
                  step = SetupStep(
                    stepNumber = 3,
                    totalSteps = totalSteps,
                    title = "Enable Synergy Keyboard",
                    instructions = listOf(
                      "Tap the button below to open keyboard settings.",
                      "Find \"Synergy\" in the list of keyboards.",
                      "Toggle it ON and confirm any warning dialog.",
                      "Synergy uses this to relay keystrokes from your server PC.",
                    ),
                    buttonText = "Open Keyboard Settings",
                    isCompleted = false,
                    onAction = { context.launchInputMethodServiceSettings() },
                  ),
                )
                notificationsPermissionState != null && !notificationsPermissionState.status.isGranted -> SetupWizard(
                  onSkip = onSkipSetup,
                  step = SetupStep(
                    stepNumber = completedSteps + 1,
                    totalSteps = totalSteps,
                    title = "Allow Notifications",
                    instructions = listOf(
                      "Tap the button below to show the permission prompt.",
                      "Tap \"Allow\" to let Synergy show connection status notifications.",
                      "This keeps you informed when your devices connect or disconnect.",
                    ),
                    buttonText = "Grant Notification Permission",
                    isCompleted = false,
                    onAction = { notificationsPermissionState.launchPermissionRequest() },
                  ),
                )
                nearbyDevicesPermissionState != null && !nearbyDevicesPermissionState.status.isGranted -> SetupWizard(
                  onSkip = onSkipSetup,
                  step = SetupStep(
                    stepNumber = completedSteps + 1,
                    totalSteps = totalSteps,
                    title = "Allow Nearby Device Discovery",
                    instructions = listOf(
                      "Tap the button below to show the permission prompt.",
                      "Tap \"Allow\" so Synergy can find your server on the network.",
                      "This is needed for automatic server discovery.",
                    ),
                    buttonText = "Grant Nearby Devices Permission",
                    isCompleted = false,
                    onAction = { nearbyDevicesPermissionState.launchPermissionRequest() },
                  ),
                )
              }
            }
          } else {
            RootNavHost(
              appState = appState,
              modifier = Modifier.padding(innerPadding).fillMaxHeight(),
            )
          }
        }
      }
    }
  }

  // Fingerprint verification dialog - shown on main screen
  val fingerprintVerificationState = FingerprintVerificationState.getInstance()
  val pendingVerification = fingerprintVerificationState.pendingVerification.collectAsState()

  pendingVerification.value?.let { result ->
    // Get client certificate fingerprint
    val certManager = ClientCertificateManager(context)
    val clientCert = certManager.getCertificate()
    val clientFingerprint = clientCert?.let { FingerprintManager.computeFingerprint(it) }

    FingerprintVerificationDialog(
      result = result,
      clientCertificateFingerprint = clientFingerprint,
      onAccept = {
        scope.launch {
          fingerprintVerificationState.acceptFingerprint()
        }
      },
      onReject = {
        scope.launch {
          fingerprintVerificationState.rejectFingerprint()
        }
      },
    )
  }

  // Accessibility service restart dialog - shown when service crashes/disconnects
  if (showAccessibilityRestartDialog.value) {
    AccessibilityServiceRestartDialog(
      onOpenSettings = {
        context.requestAccessibilityEnabled()
      },
      onDismiss = {
        showAccessibilityRestartDialog.value = false
      }
    )
  }
}

private fun updatePermissions(
  context: Context,
  appState: IAppState,
  permissionsGranted: MutableState<Boolean>,
) {
  val canDrawOverlays = context.canDrawOverlays()
  val imeEnabled = context.isInputMethodServiceEnabled()
  val isAccessibilityEnabled =
    context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
  log.info {
    "updatePermissions(canDrawOverlays=$canDrawOverlays,isAccessibilityEnabled=$isAccessibilityEnabled)"
  }
  permissionsGranted.value =
    appState.updatePermissions(
      canDrawOverlays,
      isAccessibilityEnabled,
      imeEnabled,
    )
}

@VisibleForTesting()
@Composable
internal fun UpdatePermissionsEffect(
  appState: IAppState = LocalAppState.current
) {
  if (appState !is AppState) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val permissionsGranted = remember { mutableStateOf(false) }
  val job = remember { mutableStateOf<Job?>(null) }

  DisposableEffect(Unit) {
    job.value =
      scope.launch {
        while (!permissionsGranted.value && job.value?.isActive == true) {
          log.info { "Checking permissions..." }
          updatePermissions(context, appState, permissionsGranted)
          delay(1000L)
        }
      }

    onDispose { job.value?.cancel() }
  }

  LifecycleEventHookEffect(Lifecycle.Event.ON_RESUME, fireImmediate = true) {
    updatePermissions(context, appState, permissionsGranted)
  }
}

@PreviewAll
@Composable
fun RootScreenRoutePreviewTablet() {
  PreviewAppState { appState -> RootScreenRoute(appState = appState) }
}
