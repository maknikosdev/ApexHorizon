package com.apexhorizon.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apexhorizon.app.R
import com.apexhorizon.app.ui.theme.Apex

/** Page background with the faint horizon glow used across the app. */
@Composable
fun ApexBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Apex.NavyDeep, Apex.Navy, Apex.Surface))),
    ) { content() }
}

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ApexBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Apex.TextPrimary)
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Apex.TextPrimary)
            }
            content()
        }
    }
}

@Composable
fun ApexCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Apex.Surface.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, Apex.Outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Apex.NeonOrange, contentColor = Apex.NavyDeep,
            disabledContainerColor = Apex.Outline, disabledContentColor = Apex.TextDim,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, if (enabled) Apex.CyberBlue else Apex.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Apex.CyberBlue, disabledContentColor = Apex.TextDim),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(50)))
}
