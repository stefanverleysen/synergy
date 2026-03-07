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

package org.symless.synergy.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.symless.synergy.ui.components.preview.PreviewSynergyThemedRoot
import org.symless.synergy.ui.screens.SettingsScreenRoute
import org.symless.synergy.ui.screens.SettingsUiState

@Serializable data object HomeScreenRoute

fun NavController.navigateToHomeScreen(navOptions: NavOptions) =
  navigate(route = HomeScreenRoute, navOptions)

fun NavGraphBuilder.homeScreen(appState: IAppState) {
  composable<HomeScreenRoute> { HomeScreen() }
}

@Composable
fun HomeScreen(appState: IAppState = LocalAppState.current) {
  val showSettings by appState.showSettings.collectAsStateWithLifecycle()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(8.dp),
  ) {
    ConnectionStatusWidget(
      style = synergyCardWidgetStyleDefaults(
        containerModifier = Modifier.fillMaxWidth(),
      ),
    )

    Crossfade(
      targetState = showSettings,
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(top = 8.dp),
      label = "content-swap",
    ) { settings ->
      if (settings) {
        SettingsScreenRoute(appState = appState)
      } else {
        LogsViewerWidget(
          style = synergyCardWidgetStyleDefaults(
            containerModifier = Modifier.fillMaxSize(),
          ),
        )
      }
    }
  }
}

@Preview(device = Devices.TABLET)
@Preview(device = Devices.PHONE)
@Composable
fun HomeScreenPreview(darkTheme: Boolean = true) {
  PreviewSynergyThemedRoot(darkTheme = darkTheme) { _ -> HomeScreen() }
}

@Preview(device = Devices.TABLET)
@Preview(device = Devices.PHONE)
@Composable
fun HomeScreenPreviewLight() {
  HomeScreenPreview(darkTheme = false)
}
