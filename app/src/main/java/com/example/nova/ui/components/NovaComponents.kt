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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status row: colored dot + state label + always-visible Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                                isManual -> "YOU ARE CONTROLLING"
                                isPaused -> "SESSION PAUSED"
                                isWaiting -> "WAITING FOR APPROVAL"
                                else -> "STANDBY"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = when {
                                    isAgentActive -> WarmAccent
                                    isManual -> WarmWarning
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        )
                        Text(
                            text = when {
                                isAgentActive -> "AI may act — agent tasks can drive the browser"
                                isManual -> "You are controlling — AI browser actions are paused"
                                isPaused -> "Agent paused — tap Control to AI to resume"
                                isWaiting -> "Approval pending — review it in the Tasks tab"
                                else -> "Standby — tap Control to AI to let the agent drive"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = StoneLight)
                        )
                    }
                }

                // Big Red Emergency Stop Kill Switch — always visible
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Control row: BOTH hand-over buttons are always visible.
            // Take Control -> BrowserController.takeManualControl() (the agent
            // loop pauses browser work while MANUAL_CONTROL is active).
            // Control to AI -> BrowserController.returnToAI() (mode becomes
            // AGENT_ACTIVE; the page state is re-read before the agent acts).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isManual) MaterialTheme.colorScheme.surfaceVariant else WarmWarning.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, if (isManual) MaterialTheme.colorScheme.outline else WarmWarning.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .weight(1f)
                        .then(if (isManual) Modifier else Modifier.clickable { onTakeOver() })
                        .testTag("take_control_button")
                ) {
                    Text(
                        text = if (isManual) "You Have Control" else "✋ Take Control",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isManual) StoneLight else WarmWarning,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isAgentActive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, if (isAgentActive) MaterialTheme.colorScheme.outline else WarmAccent),
                    modifier = Modifier
                        .weight(1f)
                        .then(if (isAgentActive) Modifier else Modifier.clickable { onReturnToAI() })
                        .testTag("return_to_ai_button")
                ) {
                    Text(
                        text = if (isAgentActive) "AI Has Control" else "🤖 Control to AI",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAgentActive) StoneLight else WarmAccent,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}
