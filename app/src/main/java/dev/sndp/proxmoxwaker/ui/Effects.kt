package dev.sndp.proxmoxwaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A slow, breathing wash of the theme accents behind the main screen. It keeps
 * the Omarchy palette but stops the near-black background from feeling static.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.background

    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val strength = (if (dark) 1f else 0.5f) * breathe

    Canvas(modifier = modifier) {
        drawRect(background)

        fun blob(color: Color, center: Offset, radius: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        val w = size.width
        val h = size.height
        blob(
            color = primary,
            center = Offset(w * (0.10f + 0.22f * drift), h * (0.01f + 0.06f * drift)),
            radius = w * 0.85f,
            alpha = 0.16f * strength,
        )
        blob(
            color = secondary,
            center = Offset(w * (0.98f - 0.28f * drift), h * (0.18f + 0.12f * (1f - drift))),
            radius = w * 0.70f,
            alpha = 0.09f * strength,
        )
        blob(
            color = tertiary,
            center = Offset(w * (0.20f + 0.55f * drift), h * (0.96f - 0.08f * drift)),
            radius = w * 0.90f,
            alpha = 0.07f * strength,
        )
    }
}

private data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val gravity: Float,
    val size: Float,
    val color: Color,
    val rotation: Float,
    val spin: Float,
)

/**
 * A short burst of paper confetti when a wake request finally reaches the node.
 * Deliberately hand-drawn with Canvas so the app stays dependency-free.
 */
@Composable
fun ConfettiOverlay(
    active: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!active) return

    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        StatusColors.running,
        MaterialTheme.colorScheme.onSurface,
    )
    val particles = remember(colors) {
        val random = Random.Default
        List(64) {
            ConfettiParticle(
                x = random.nextFloat(),
                y = -0.08f + random.nextFloat() * 0.18f,
                vx = (random.nextFloat() - 0.5f) * 0.55f,
                vy = 0.15f + random.nextFloat() * 0.35f,
                gravity = 0.55f + random.nextFloat() * 0.55f,
                size = 4f + random.nextFloat() * 7f,
                color = colors[random.nextInt(colors.size)],
                rotation = random.nextFloat() * 360f,
                spin = (random.nextFloat() - 0.5f) * 900f,
            )
        }
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2_000, easing = LinearEasing),
        )
        onFinished()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = progress.value
        val fade = (1f - t).coerceIn(0f, 1f)
        particles.forEach { particle ->
            val x = particle.x * size.width + particle.vx * size.width * t
            val y = particle.y * size.height +
                particle.vy * size.height * t +
                particle.gravity * size.height * t * t
            if (x in -40f..(size.width + 40f) && y in -40f..(size.height + 40f)) {
                rotate(degrees = particle.rotation + particle.spin * t, pivot = Offset(x, y)) {
                    drawRect(
                        color = particle.color.copy(alpha = fade),
                        topLeft = Offset(x - particle.size / 2f, y - particle.size / 2f),
                        size = Size(particle.size, particle.size * 0.55f),
                    )
                }
            }
        }
    }
}

/**
 * The little reactor on the node card: a dim ring while asleep, a breathing
 * glow while online, and a spinning arc while it is waking or shutting down.
 */
@Composable
fun PowerCore(
    online: Boolean,
    waking: Boolean,
    shuttingDown: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        shuttingDown -> MaterialTheme.colorScheme.error
        waking -> StatusColors.warn
        online -> StatusColors.running
        else -> StatusColors.stopped
    }
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val busy = waking || shuttingDown

    val transition = rememberInfiniteTransition(label = "powerCore")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corePulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = LinearEasing),
        ),
        label = "coreSpin",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            drawCircle(
                color = accent.copy(
                    alpha = when {
                        online -> 0.10f + 0.10f * pulse
                        busy -> 0.08f + 0.06f * pulse
                        else -> 0.035f + 0.035f * pulse
                    },
                ),
                radius = radius,
            )
            drawCircle(
                color = accent.copy(alpha = if (online) 0.10f * pulse else 0.04f),
                radius = radius * 0.72f,
            )
            drawCircle(
                color = outlineVariant,
                radius = radius * 0.80f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            if (busy) {
                drawArc(
                    color = accent,
                    startAngle = spin,
                    sweepAngle = 105f,
                    useCenter = false,
                    topLeft = Offset(radius * 0.20f, radius * 0.20f),
                    size = Size(radius * 1.60f, radius * 1.60f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            } else {
                drawCircle(
                    color = accent.copy(alpha = if (online) 1f else 0.55f),
                    radius = radius * 0.80f,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
        PowerMark(
            modifier = Modifier.size(22.dp),
            arcColor = onSurface,
            barColor = accent,
        )
    }
}

/**
 * The pull-to-refresh puck. It wakes up as the finger drags, fills its ring with
 * the theme's accent gradient, then spins while the API call is in flight.
 */
@Composable
fun AuroraRefreshIndicator(
    isRefreshing: Boolean,
    distanceFraction: Float,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    val transition = rememberInfiniteTransition(label = "refreshIndicator")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_050, easing = LinearEasing),
        ),
        label = "refreshSpin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "refreshBreathe",
    )

    val pull = distanceFraction.coerceIn(0f, 1f)
    val alpha = if (isRefreshing) 1f else (pull * 2.4f).coerceIn(0f, 1f)
    val scale = if (isRefreshing) breathe else 0.70f + 0.30f * pull
    val arcStart = if (isRefreshing) spin else -90f + pull * 220f

    // One crisp tick when the drag crosses the trigger point, so the user knows
    // the release will actually refresh.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(pull >= 1f, isRefreshing) {
        if (pull >= 1f && !armed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            armed = true
        } else if (pull < 0.98f) {
            armed = false
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f + 2.5.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(primary, secondary, primary)),
                startAngle = arcStart,
                sweepAngle = if (isRefreshing) 250f else 45f + 255f * pull,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = if (isRefreshing) spin else 0f },
            tint = if (isRefreshing) primary else onSurface.copy(alpha = 0.8f),
        )
    }
}

/** A status dot that gently pulses when the thing it describes is alive. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    pulsing: Boolean = false,
) {
    // Only run an animation when the dot is actually alive. A long guest list
    // otherwise keeps one transition per row running forever.
    val pulse = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        val value by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        value
    } else {
        1f
    }

    Box(modifier = modifier.size(size)) {
        if (pulsing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(2.1f)
                    .background(color.copy(alpha = 0.16f * pulse), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color.copy(alpha = if (pulsing) pulse else 1f), CircleShape),
        )
    }
}

/**
 * Fades and lifts a section into place. [order] staggers sibling sections so the
 * screen assembles itself instead of snapping into existence.
 */
@Composable
fun AnimatedEntrance(
    order: Int = 0,
    stepMillis: Long = 60L,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(stepMillis * order)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 380)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                initialOffsetY = { it / 7 },
            ),
    ) {
        content()
    }
}

/** A shimmering placeholder used while the guest list is being read. */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
        ),
        label = "shimmerShift",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)

    Box(
        modifier = modifier.drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(shift * size.width, 0f),
                    end = Offset(shift * size.width + size.width * 0.75f, size.height),
                ),
            )
        },
    )
}

/** A very small vertical bob, for the icon on the empty state. */
@Composable
fun Bobbing(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "bobbing")
    val offsetY by transition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobbingOffset",
    )

    Box(
        modifier = modifier.offset(y = offsetY.dp),
        content = content,
    )
}

/** Squishy press feedback for buttons and tappable rows. */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
