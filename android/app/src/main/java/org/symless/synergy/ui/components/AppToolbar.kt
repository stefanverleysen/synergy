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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.symless.synergy.R
import org.symless.synergy.ui.theme.LocalSynergyExtendedColorScheme

private val SynergyMidnight = Color(0xFF1F2D5D)
private val SynergyBlue = Color(0xFF3B67D3)

@Composable
fun AppToolbar(appState: IAppState = LocalAppState.current) {
  val connectionState by appState.connectionStateFlow.collectAsStateWithLifecycle()
  val isConnected = connectionState.isConnected && connectionState.ackReceived
  val isEnabled = appState.isEnabled
  val extColorScheme = LocalSynergyExtendedColorScheme.current

  val ctx = LocalContext.current
  val versionName = remember {
    try {
      ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    // Top bar: logo + settings
    Row(
      modifier = Modifier
        .background(SynergyMidnight)
        .fillMaxWidth()
        .height(56.dp)
        .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(
        painter = painterResource(id = R.drawable.synergy_icon_fit),
        contentDescription = stringResource(R.string.app_toolbar_logo_desc),
        modifier = Modifier.size(28.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = "Synergy Android",
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
      )
      if (versionName.isNotEmpty()) {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "v$versionName",
          color = Color.White.copy(alpha = 0.5f),
          fontSize = 13.sp,
          fontWeight = FontWeight.Normal,
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      IconButton(onClick = { appState.toggleSettings() }) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = "Settings",
          tint = Color.White.copy(alpha = 0.85f),
        )
      }
    }

    // Connection status bar
    val statusBg = when {
      !isEnabled -> Color(0xFF424242)
      isConnected -> Color(0xFF2E7D32)
      else -> Color(0xFFE65100)
    }
    val statusText = when {
      !isEnabled -> "Stopped"
      isConnected -> "Connected"
      else -> "Connecting..."
    }
    val serverInfo = connectionState.screen.run {
      "${server.address}:${server.port}"
    }
    val screenName = connectionState.screen.name

    Row(
      modifier = Modifier
        .background(statusBg)
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = statusText.uppercase(),
          color = Color.White,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
        )
        if (isEnabled) {
          Text(
            text = "$screenName  \u2022  $serverInfo",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
          )
        }
      }
      Button(
        onClick = { appState.setEnabled(!isEnabled) },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isEnabled) Color.White.copy(alpha = 0.2f) else SynergyBlue,
          contentColor = Color.White,
        ),
        shape = RoundedCornerShape(6.dp),
      ) {
        Text(
          text = if (isEnabled)
            stringResource(R.string.app_toolbar_button_stop)
          else
            stringResource(R.string.app_toolbar_button_start),
          fontWeight = FontWeight.SemiBold,
          fontSize = 14.sp,
        )
      }
    }
  }
}
