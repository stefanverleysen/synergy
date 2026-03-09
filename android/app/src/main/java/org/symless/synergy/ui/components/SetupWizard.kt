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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.symless.synergy.R

private val SynergyBlue = Color(0xFF3B67D3)
private val StepGreen = Color(0xFF2E7D32)
private val StepGray = Color(0xFF616161)

data class SetupStep(
  val stepNumber: Int,
  val totalSteps: Int,
  val title: String,
  val instructions: List<String>,
  val buttonText: String,
  val isCompleted: Boolean,
  val onAction: () -> Unit,
)

@Composable
fun SetupWizard(
  step: SetupStep,
  onSkip: (() -> Unit)? = null,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp)
      .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(16.dp))

    // Logo
    Image(
      painter = painterResource(id = R.drawable.synergy_icon_fit),
      contentDescription = "Synergy",
      modifier = Modifier.size(48.dp),
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "Synergy Android Setup",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Step indicator dots
    Row(
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxWidth(),
    ) {
      for (i in 1..step.totalSteps) {
        val color = when {
          i < step.stepNumber -> StepGreen
          i == step.stepNumber -> SynergyBlue
          else -> StepGray.copy(alpha = 0.3f)
        }
        Box(
          modifier = Modifier
            .size(if (i == step.stepNumber) 12.dp else 10.dp)
            .clip(CircleShape)
            .background(color)
        )
        if (i < step.totalSteps) {
          Spacer(modifier = Modifier.width(8.dp))
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = "Step ${step.stepNumber} of ${step.totalSteps}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Step card
    Surface(
      shape = RoundedCornerShape(12.dp),
      tonalElevation = 4.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
      ) {
        Text(
          text = step.title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Numbered instructions
        step.instructions.forEachIndexed { index, instruction ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
          ) {
            // Step number circle
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(SynergyBlue.copy(alpha = 0.15f)),
            ) {
              Text(
                text = "${index + 1}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SynergyBlue,
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = instruction,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
          onClick = step.onAction,
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = SynergyBlue,
            contentColor = Color.White,
          ),
        ) {
          Text(
            text = step.buttonText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
          )
        }

        if (onSkip != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Button(
            onClick = onSkip,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color.Transparent,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          ) {
            Text(
              text = "Skip Setup (grant permissions via ADB)",
              fontWeight = FontWeight.Normal,
              fontSize = 14.sp,
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "After completing this step, return to Synergy Android. The app will detect the change automatically.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 16.dp),
    )
  }
}
