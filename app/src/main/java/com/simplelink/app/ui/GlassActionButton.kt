package com.simplelink.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GlassActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, label = "press")
    val shape = RoundedCornerShape(if (compact) 18.dp else 22.dp)

    val fill = if (primary) {
        Brush.linearGradient(
            listOf(
                Color(0xFFA6F4C2),
                Color(0xFF78DEA0)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color(0xFF1A1F25),
                Color(0xFF12161B)
            )
        )
    }

    val foreground = if (primary) Color(0xFF071009) else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(
                when {
                    compact -> 54.dp
                    subtitle != null -> 76.dp
                    else -> 64.dp
                }
            )
            .scale(scale)
            .alpha(if (enabled) 1f else 0.42f)
            .background(fill, shape)
            .border(
                BorderStroke(
                    1.dp,
                    if (primary) Color.White.copy(alpha = 0.32f)
                    else Color.White.copy(alpha = 0.09f)
                ),
                shape
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = text,
                    color = foreground,
                    fontSize = if (compact) 15.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null && !compact) {
                    Text(
                        text = subtitle,
                        color = if (primary) Color(0xFF183522) else Color(0xFF8E98A5),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(if (compact) 30.dp else 36.dp)
                    .clip(CircleShape)
                    .background(
                        if (primary) Color.Black.copy(alpha = 0.08f)
                        else Color.White.copy(alpha = 0.055f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "→",
                    color = foreground,
                    fontSize = if (compact) 17.sp else 19.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
