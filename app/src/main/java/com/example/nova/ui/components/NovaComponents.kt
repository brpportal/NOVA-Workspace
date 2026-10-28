package com.example.nova.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.browser.AICursorState
import com.example.nova.browser.AIKeystrokeState
import com.example.nova.browser.BrowserControlMode
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning

@Composable
fun NovaBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun NovaSkeleton(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

/**
 * Animated visual AI cursor overlay rendered directly over the browser viewport.
 * Simulates real-time agent presence with smooth position, distinct "AI" marker, and click ripple.
 */
@Composable
fun AICursorOverlay(
    cursorState: AICursorState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = cursorState.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val clickTransition = rememberInfiniteTransition(label = "clickRipple")
        val rippleRadius by clickTransition.animateFloat(
            initialValue = 12f,
            targetValue = 32f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ripple"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(cursorState.x.toInt(), cursorState.y.toInt()) }
        ) {
            // Click Ripple Effect
            if (cursorState.isClicking) {
                Box(
                    modifier = Modifier
                        .size(rippleRadius.dp)
                        .clip(CircleShape)
                        .background(WarmAccent.copy(alpha = 0.35f))
                        .align(Alignment.Center)
                )
            }

            // Distinct AI Cursor Pointer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .background(WarmAccent, RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Cursor",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = cursorState.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        }
    }
}

/**
 * Small transient chip showing live keystrokes when AI is typing.
 */
@Composable
fun AIKeystrokeOverlay(
    keystrokeState: AIKeystrokeState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = keystrokeState.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, WarmAccent.copy(alpha = 0.6f)),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = WarmAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Typing: ",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StoneMuted)
                )
                Text(
                    text = "\"${keystrokeState.text}\"",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = WarmAccent)
                )
            }
        }
    }
}

/**
 * Live Control Banner showing whether Human or AI is currently in control, with animated glow.
 */
@Composable
fun LiveControlBanner(
    controlMode: BrowserControlMode,
    onTakeOver: () -> Unit,
    onReturnToAI: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAgentActive = controlMode == BrowserControlMode.AGENT_ACTIVE
    val isManual = controlMode == BrowserControlMode.MANUAL_CONTROL
    val isPaused = controlMode == BrowserControlMode.PAUSED
    val isWaiting = controlMode == BrowserControlMode.WAITING_APPROVAL

    // Glowing border for viewport when AI controls
    val transition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.5.dp,
            if (isAgentActive) WarmAccent.copy(alpha = glowAlpha)
            else if (isManual) WarmWarning.copy(alpha = 0.8f)
            else MaterialTheme.colorScheme.outline
        ),
        shadowElevation = if (isAgentActive) 4.dp else 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isAgentActive -> WarmAccent
                                isManual -> WarmWarning
                                isPaused -> StoneLight
                                isWaiting -> WarmError
                                else -> WarmSuccess
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = when {
                            isAgentActive -> "AI IS CONTROLLING"
                            isManual -> "You're in control"
                            isPaused -> "SESSION PAUSED"
                            isWaiting -> "Needs you"
                            // STANDBY means no agent is driving: the user
                            // holds the browser. Say exactly that — a bare
                            // "STANDBY" left users unsure who was in control.
                            else -> "You're in control"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = when {
                                isAgentActive -> WarmAccent
                                isManual -> WarmWarning
                                isWaiting -> WarmError
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    )
                    Text(
                        text = when {
                            isAgentActive -> "Vision automation active"
                            isManual -> "Needs you: Manual input / CAPTCHA step"
                            isPaused -> "Agent paused"
                            isWaiting -> "Needs you: Pending approval"
                            else -> "Standby — Spark takes over when a task uses the browser"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = StoneLight)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isAgentActive) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, WarmWarning.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clickable { onTakeOver() }
                            .testTag("take_control_button")
                    ) {
                        Text(
                            text = "Take control",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmWarning,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else if (isManual) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, WarmAccent),
                        modifier = Modifier
                            .clickable { onReturnToAI() }
                            .testTag("return_to_ai_button")
                    ) {
                        Text(
                            text = "Hand back to the agent",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    // Standby / paused / waiting: the user already has
                    // control, so Hand back is shown disabled (with the
                    // state text explaining why) instead of disappearing —
                    // the control bar must always say who is in control.
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("return_to_ai_disabled")
                    ) {
                        Text(
                            text = "Hand back to the agent",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoneLight,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Big Red Emergency Stop Kill Switch
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WarmError.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, WarmError.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .clickable { onEmergencyStop() }
                        .testTag("emergency_stop_button")
                ) {
                    Text(
                        text = "Stop",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarmError,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
