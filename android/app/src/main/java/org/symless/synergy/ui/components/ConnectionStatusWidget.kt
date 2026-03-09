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

// import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.symless.synergy.BuildConfig
import org.symless.synergy.R
import org.symless.synergy.data.aidl.ConnectionState
import org.symless.synergy.ext.adjustBrightness
import org.symless.synergy.ext.isTelevision
import org.symless.synergy.ui.components.preview.PreviewAppState
import org.symless.synergy.ui.components.preview.PreviewSynergyThemedRoot
import org.symless.synergy.ui.theme.LocalSynergyExtendedColorScheme

@Composable
fun ConnectionStatusLabel(
  connState: ConnectionState
) {
  val extColorScheme = LocalSynergyExtendedColorScheme.current
  val infiniteTransition = rememberInfiniteTransition()
  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.75f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1000),
      repeatMode = RepeatMode.Reverse
    )
  )

  val (statusColor, statusShadowColor) = when {
    !connState.isEnabled ->
      MaterialTheme.colorScheme.errorContainer
    connState.isConnected && connState.ackReceived ->
      extColorScheme.success
    else -> extColorScheme.warning
  }.let { color ->
    Pair(color.copy(alpha = glowAlpha), color.adjustBrightness(-0.4f).copy(alpha = glowAlpha))
  }

  Text(
    text =
      when {
        !connState.isEnabled ->
          stringResource(
            R.string.connection_status_widget_header_status_stopped
          )
        connState.isConnected && connState.ackReceived ->
          stringResource(
            R.string.connection_status_widget_header_status_connected
          )
        else ->
          stringResource(
            R.string.connection_status_widget_header_status_connecting
          )
      }.uppercase(),
    style =
      MaterialTheme.typography.titleSmall.copy(
        //fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Medium,
      ),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    color =
      when {
        !connState.isEnabled -> MaterialTheme.colorScheme.onErrorContainer
        connState.isConnected && connState.ackReceived ->
          extColorScheme.onSuccess
        else -> extColorScheme.onWarning
      },
    modifier =
      Modifier
        .shadow(
          elevation = 4.dp,
          shape = RoundedCornerShape(4.dp),
          ambientColor = statusShadowColor,
          spotColor = statusShadowColor
        )
        .background(
          brush =
            SolidColor(
              statusColor
            ),
          shape = RoundedCornerShape(4.dp),
          alpha = 1f,
        )
        .padding(horizontal = 8.dp, vertical = 8.dp),
    // .width(100.dp),
  )

}

@Composable
fun ConnectionStatusWidget(
  appState: IAppState = LocalAppState.current,
  style: SynergyCardStyle = synergyCardWidgetStyleDefaults(),
) {
  val context = LocalContext.current
  val extColorScheme = LocalSynergyExtendedColorScheme.current
  val connState by appState.connectionStateFlow.collectAsStateWithLifecycle()
  val deviceType = if (context.isTelevision()) "TV" else "Mobile"
  val deviceInfo = "v${BuildConfig.VERSION_NAME} $deviceType, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

  SynergyCardWidget(
    style =
      style.copy(
        containerModifier = style.containerModifier.wrapContentHeight()
      ),
    adjustStyleHeight = false,
    header = {
      Toolbar {
        Text(
          text = stringResource(R.string.connection_status_widget_title),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = deviceInfo,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp),
        )
        SynergyFillSpacer()
        ConnectionStatusLabel(connState)
      }
    },
  ) {
  }
}

@Preview
@Composable
fun ConnectionStatusWidgetPreview() {
  PreviewSynergyThemedRoot {appState -> ConnectionStatusWidget(appState) }

}
