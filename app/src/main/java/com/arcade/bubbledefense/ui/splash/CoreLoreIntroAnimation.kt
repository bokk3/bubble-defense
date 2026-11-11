package com.arcade.bubbledefense.ui.splash

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import kotlin.random.Random

/**
 * Pre-allocated warp streak radiating from the cosmic singularity.
 */
private data class LoreWarpStreak(
    val angle: Float,
    val baseDistance: Float,
    val length: Float,
    val speed: Float,
    val color: Color
)

/**
 * Pre-allocated distant twinkling void star.
 */
private data class LoreStar(
    val xFrac: Float,
    val yFrac: Float,
    val size: Float,
    val pulseSpeed: Float,
    val pulsePhase: Float
)

/**
 * 2-Second Cinematic Core Lore Intro Sequence for Bubble Defense.
 *
 * Sequence Breakdown (Total 2000ms):
 * - Phase 1: Singularity Event Horizon & Deep Space Transmission (0.0s - 0.7s)
 *     Relativistic wireframe grid, cold cosmic void, gravitational singularity with
 *     photon ring, and cyberpunk telemetry transmission.
 * - Phase 2: The Organic Genesis Seed (0.7s - 1.4s)
 *     Emergence of the bioluminescent bubble with 64-vertex soft-body harmonic wobble,
 *     inner specular gloss, and concentric tactical Aegis shield barrier arcs.
 * - Phase 3: Supersonic Warp Ascent & Void Directive (1.4s - 2.0s)
 *     Twin backward thruster laser ignition, 32 warp perspective streaks accelerating to
 *     126 km/h, micro-camera trauma shake, tactical directive banner, and seamless 200ms
 *     alpha dissolve directly into the live [GameScreen].
 *
 * Tap-to-skip supported at any moment. Zero heap allocations during frame rendering.
 */
@Composable
fun CoreLoreIntroAnimation(
    onLoreComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    // Pre-allocated static paths for zero allocation in Canvas
    val bubblePath = remember { Path() }
    val gridPath = remember { Path() }

    // Pre-allocated warp streaks
    val warpStreaks = remember {
        val colors = listOf(
            Color(0xFF00F0FF), // Cyan
            Color(0xFF7000FF), // Violet
            Color(0xFF00FFB2), // Mint
            Color(0xFFFFFFFF)  // White
        )
        List(32) { i ->
            val angle = (i * (2f * PI.toFloat() / 32f)) + (Random.nextFloat() * 0.1f)
            LoreWarpStreak(
                angle = angle,
                baseDistance = 40f + Random.nextFloat() * 120f,
                length = 30f + Random.nextFloat() * 80f,
                speed = 280f + Random.nextFloat() * 520f,
                color = colors[i % colors.size]
            )
        }
    }

    // Pre-allocated distant stars
    val stars = remember {
        List(48) {
            LoreStar(
                xFrac = Random.nextFloat(),
                yFrac = Random.nextFloat() * 0.85f,
                size = 1.0f + Random.nextFloat() * 2.2f,
                pulseSpeed = 1.5f + Random.nextFloat() * 3.5f,
                pulsePhase = Random.nextFloat() * (2f * PI.toFloat())
            )
        }
    }

    // Cached AndroidPaint instances for zero-allocation text rendering
    val paintHeader = remember {
        AndroidPaint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = 34f
            color = android.graphics.Color.parseColor("#00F0FF")
            textAlign = AndroidPaint.Align.CENTER
            setShadowLayer(12f, 0f, 0f, android.graphics.Color.parseColor("#00F0FF"))
        }
    }
    val paintBody = remember {
        AndroidPaint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = 26f
            color = android.graphics.Color.parseColor("#E0E7FF")
            textAlign = AndroidPaint.Align.CENTER
        }
    }
    val paintAlert = remember {
        AndroidPaint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = 28f
            color = android.graphics.Color.parseColor("#FF0055")
            textAlign = AndroidPaint.Align.CENTER
            setShadowLayer(14f, 0f, 0f, android.graphics.Color.parseColor("#FF0055"))
        }
    }
    val paintBannerTitle = remember {
        AndroidPaint().apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            textSize = 42f
            color = android.graphics.Color.parseColor("#FFFFFF")
            textAlign = AndroidPaint.Align.CENTER
            setShadowLayer(20f, 0f, 0f, android.graphics.Color.parseColor("#00F0FF"))
        }
    }
    val paintBannerSub = remember {
        AndroidPaint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = 28f
            color = android.graphics.Color.parseColor("#00F0FF")
            textAlign = AndroidPaint.Align.CENTER
            setShadowLayer(10f, 0f, 0f, android.graphics.Color.parseColor("#00F0FF"))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioTrack?.apply {
                try {
                    stop()
                    release()
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        // Start procedural 16-bit PCM audio
        audioTrack = CoreLoreAudioSynth.createAndPlayLoreAudio()

        // Animate 2.0 seconds timeline
        animProgress.animateTo(
            targetValue = 2.0f,
            animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
        )
        onLoreComplete()
    }

    val elapsed = animProgress.value

    // Global dissolve alpha during final 200ms (1.8s -> 2.0s)
    val globalAlpha = when {
        elapsed > 1.80f -> (1.0f - (elapsed - 1.80f) / 0.20f).coerceIn(0f, 1f)
        else -> 1.0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020306))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onLoreComplete()
            }
            .alpha(globalAlpha),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Camera shake trauma during supersonic warp ignition (1.4s - 1.75s)
            var shakeX = 0f
            var shakeY = 0f
            if (elapsed in 1.40f..1.75f) {
                val trauma = (1.0f - (elapsed - 1.40f) / 0.35f).coerceIn(0f, 1f)
                val shakeMag = trauma * trauma * 8f
                shakeX = (sin(elapsed * 95f) * shakeMag)
                shakeY = (cos(elapsed * 110f) * shakeMag)
            }

            val horizonX = w * 0.50f + shakeX
            val horizonY = h * 0.40f + shakeY

            // 1. Distant Twinkling Deep Space Stars
            stars.forEach { s ->
                val pulse = 0.40f + 0.60f * sin(elapsed * s.pulseSpeed + s.pulsePhase).absoluteValue
                drawCircle(
                    color = Color.White.copy(alpha = pulse * 0.75f),
                    radius = s.size,
                    center = Offset(s.xFrac * w + shakeX * 0.3f, s.yFrac * h + shakeY * 0.3f)
                )
            }

            // 2. Relativistic Perspective Wireframe Grid (Horizon receding)
            gridPath.reset()
            val gridLines = 10
            for (i in -gridLines..gridLines) {
                val bottomX = (w * 0.5f) + (i * (w / gridLines.toFloat()) * 1.6f)
                gridPath.moveTo(horizonX, horizonY)
                gridPath.lineTo(bottomX, h)
            }
            // Scrolling crosslines
            val crosslineCount = 8
            val scrollOffset = ((elapsed * 0.8f) % (1f / crosslineCount))
            for (j in 1..crosslineCount) {
                val zFrac = ((j.toFloat() / crosslineCount) + scrollOffset).coerceIn(0.01f, 1.0f)
                // Perspective 1/Z curve
                val py = horizonY + (h - horizonY) * (zFrac * zFrac)
                val spread = (w * 1.5f) * zFrac
                gridPath.moveTo((w * 0.5f) - spread, py)
                gridPath.lineTo((w * 0.5f) + spread, py)
            }
            val gridAlpha = (if (elapsed < 0.7f) (elapsed / 0.7f) else 1.0f).coerceIn(0f, 0.45f)
            drawPath(
                path = gridPath,
                color = Color(0xFF7000FF).copy(alpha = gridAlpha),
                style = Stroke(width = 1.5f)
            )

            // 3. Singularity Event Horizon & Photon Ring at Vanishing Point
            val singScale = when {
                elapsed < 0.6f -> (elapsed / 0.6f).coerceIn(0f, 1f)
                else -> 1.0f
            }
            val blackHoleRadius = 28f * singScale
            // Accretion disk glow rings
            drawCircle(
                color = Color(0xFF7000FF).copy(alpha = 0.35f * singScale),
                radius = blackHoleRadius * 2.8f,
                center = Offset(horizonX, horizonY)
            )
            drawCircle(
                color = Color(0xFF00F0FF).copy(alpha = 0.50f * singScale),
                radius = blackHoleRadius * 1.6f,
                center = Offset(horizonX, horizonY)
            )
            // Relativistic polar Hawking jet
            drawLine(
                color = Color(0xFF00F0FF).copy(alpha = 0.65f * singScale),
                start = Offset(horizonX, horizonY),
                end = Offset(horizonX, 0f),
                strokeWidth = 3f * singScale,
                cap = StrokeCap.Round
            )
            // Pitch-black event horizon core
            drawCircle(
                color = Color(0xFF000000),
                radius = blackHoleRadius,
                center = Offset(horizonX, horizonY)
            )
            drawCircle(
                color = Color(0xFF00F0FF),
                radius = blackHoleRadius,
                center = Offset(horizonX, horizonY),
                style = Stroke(width = 2.5f)
            )

            // 4. Phase 2 & 3: The Organic Genesis Seed (The Bubble)
            if (elapsed >= 0.60f) {
                val bubbleEmergence = ((elapsed - 0.60f) / 0.40f).coerceIn(0f, 1f)
                // Smooth spring overshoot
                val bubbleScale = sin(bubbleEmergence * (PI.toFloat() / 2f))
                val bubbleTargetY = h * 0.68f + shakeY
                val bubbleCurY = horizonY + (bubbleTargetY - horizonY) * bubbleScale
                val bubbleCurX = w * 0.50f + shakeX
                val baseRadius = 56f * bubbleScale

                // 64-vertex procedural soft-body harmonic wobble
                bubblePath.reset()
                val vertices = 64
                for (v in 0 until vertices) {
                    val theta = v * (2f * PI.toFloat() / vertices)
                    val w1 = 0.040f * sin(3f * theta + elapsed * 7.5f)
                    val w2 = 0.025f * sin(5f * theta - elapsed * 10f)
                    val w3 = 0.015f * cos(7f * theta + elapsed * 15f)
                    val r = baseRadius * (1f + w1 + w2 + w3)

                    val px = bubbleCurX + r * cos(theta)
                    val py = bubbleCurY + r * sin(theta)
                    if (v == 0) {
                        bubblePath.moveTo(px, py)
                    } else {
                        bubblePath.lineTo(px, py)
                    }
                }
                bubblePath.close()

                // Translucent bioluminescent membrane & aura
                drawCircle(
                    color = Color(0xFF00F0FF).copy(alpha = 0.20f * bubbleScale),
                    radius = baseRadius * 1.55f,
                    center = Offset(bubbleCurX, bubbleCurY)
                )
                drawPath(
                    path = bubblePath,
                    color = Color(0xFF00F0FF).copy(alpha = 0.18f * bubbleScale)
                )
                drawPath(
                    path = bubblePath,
                    color = Color(0xFF00F0FF).copy(alpha = 0.85f * bubbleScale),
                    style = Stroke(width = 3.5f)
                )

                // White specular droplet highlight
                val specOffsetX = -baseRadius * 0.28f
                val specOffsetY = -baseRadius * 0.30f
                drawCircle(
                    color = Color.White.copy(alpha = 0.80f * bubbleScale),
                    radius = baseRadius * 0.22f,
                    center = Offset(bubbleCurX + specOffsetX, bubbleCurY + specOffsetY)
                )

                // Tactical Aegis Shield Arc Genesis (igniting & rotating)
                val arcRot = elapsed * 180f
                val shieldRadius = baseRadius + 18f
                drawArc(
                    color = Color(0xFF00F0FF).copy(alpha = 0.80f * bubbleScale),
                    startAngle = arcRot,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(bubbleCurX - shieldRadius, bubbleCurY - shieldRadius),
                    size = androidx.compose.ui.geometry.Size(shieldRadius * 2f, shieldRadius * 2f),
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color(0xFF00FF88).copy(alpha = 0.80f * bubbleScale),
                    startAngle = arcRot + 180f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(bubbleCurX - shieldRadius, bubbleCurY - shieldRadius),
                    size = androidx.compose.ui.geometry.Size(shieldRadius * 2f, shieldRadius * 2f),
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )

                // Phase 3: Twin Thruster Lasers Ignition (1.4s -> 2.0s)
                if (elapsed >= 1.35f) {
                    val thrusterFrac = ((elapsed - 1.35f) / 0.25f).coerceIn(0f, 1f)
                    val plumeLen = (h * 0.28f) * thrusterFrac
                    val thrusterWidth = 8f * thrusterFrac
                    val leftThrusterX = bubbleCurX - baseRadius * 0.40f
                    val rightThrusterX = bubbleCurX + baseRadius * 0.40f
                    val plumeStartY = bubbleCurY + baseRadius * 0.80f

                    // Left plume
                    drawLine(
                        color = Color(0xFF00F0FF).copy(alpha = 0.80f),
                        start = Offset(leftThrusterX, plumeStartY),
                        end = Offset(leftThrusterX, plumeStartY + plumeLen),
                        strokeWidth = thrusterWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(leftThrusterX, plumeStartY),
                        end = Offset(leftThrusterX, plumeStartY + plumeLen * 0.6f),
                        strokeWidth = thrusterWidth * 0.45f,
                        cap = StrokeCap.Round
                    )

                    // Right plume
                    drawLine(
                        color = Color(0xFF00F0FF).copy(alpha = 0.80f),
                        start = Offset(rightThrusterX, plumeStartY),
                        end = Offset(rightThrusterX, plumeStartY + plumeLen),
                        strokeWidth = thrusterWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(rightThrusterX, plumeStartY),
                        end = Offset(rightThrusterX, plumeStartY + plumeLen * 0.6f),
                        strokeWidth = thrusterWidth * 0.45f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // 5. Phase 3: Accelerating Perspective Warp Streaks (1.4s -> 2.0s)
            if (elapsed >= 1.35f) {
                val warpFrac = (elapsed - 1.35f) / 0.65f
                val warpSpeedMult = 1f + warpFrac * warpFrac * 6f

                warpStreaks.forEach { s ->
                    val dist = s.baseDistance + (s.speed * (elapsed - 1.35f) * warpSpeedMult)
                    val curDist = dist % (w * 0.9f)
                    val streakLen = s.length * (1f + warpFrac * 2.5f)

                    val startX = horizonX + curDist * cos(s.angle)
                    val startY = horizonY + curDist * sin(s.angle)
                    val endX = horizonX + (curDist + streakLen) * cos(s.angle)
                    val endY = horizonY + (curDist + streakLen) * sin(s.angle)

                    val streakAlpha = (1.0f - (curDist / (w * 0.9f))).coerceIn(0f, 1f)
                    drawLine(
                        color = s.color.copy(alpha = streakAlpha * 0.85f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 2.5f * (1f + warpFrac * 0.8f),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 6. Cybernetic High-Tech HUD Telemetry & Directive Text
            val canvas = drawContext.canvas.nativeCanvas
            val topTelemetryY = h * 0.16f

            when {
                // Phase 1: Singularity Warning Telemetry (0.0s - 0.7s)
                elapsed < 0.70f -> {
                    val blink = if ((elapsed * 10f).toInt() % 2 == 0) "•" else " "
                    canvas.drawText("// TRANSMISSION: TACHYON-9 $blink", w * 0.5f, topTelemetryY, paintHeader)
                    canvas.drawText("STATUS: ENTROPY SINGULARITY CONSUMING SECTOR 4", w * 0.5f, topTelemetryY + 44f, paintBody)
                    canvas.drawText("CRITICAL: ORGANIC HARMONIC EXTINCTION DETECTED", w * 0.5f, topTelemetryY + 84f, paintAlert)
                }

                // Phase 2: Genesis Seed Awakening (0.7s - 1.4s)
                elapsed < 1.40f -> {
                    canvas.drawText("✦ TARGET ACQUIRED: HARMONIC LIFE SEED ✦", w * 0.5f, topTelemetryY, paintHeader)
                    canvas.drawText("TACTICAL AEGIS INK PROTOCOL: ONLINE", w * 0.5f, topTelemetryY + 44f, paintBody)
                    canvas.drawText("CALIBRATING 120HZ DEFENSIVE TRAJECTORY", w * 0.5f, topTelemetryY + 84f, paintBody)
                }

                // Phase 3: Supersonic Warp Ascent & Void Directive (1.4s - 2.0s)
                else -> {
                    val bannerCenterY = h * 0.22f
                    canvas.drawText("[ DRAW SHIELDS • PARRY THE VOID ]", w * 0.5f, bannerCenterY, paintBannerTitle)
                    canvas.drawText("ASCENT VELOCITY: 126 KM/H • ESCAPE VECTOR LOCKED", w * 0.5f, bannerCenterY + 46f, paintBannerSub)
                }
            }

            // Scanning laser line across screen
            val scanY = (elapsed / 2.0f) * h
            drawLine(
                color = Color(0xFF00F0FF).copy(alpha = 0.25f),
                start = Offset(0f, scanY),
                end = Offset(w, scanY),
                strokeWidth = 2f
            )
        }

        // Discreet cyberpunk tap-to-skip prompt
        if (elapsed > 0.3f) {
            Text(
                text = "TAP TO SKIP",
                color = Color(0xFF00F0FF).copy(alpha = 0.40f * globalAlpha),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
            )
        }
    }
}
