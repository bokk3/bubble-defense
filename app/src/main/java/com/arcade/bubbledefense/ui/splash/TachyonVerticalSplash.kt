package com.arcade.bubbledefense.ui.splash

import android.graphics.PathMeasure
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.arcade.bubbledefense.R
import kotlin.math.*
import kotlin.random.Random

/**
 * Atmospheric micro-dust & debris particle settling after the thermal flash.
 */
data class SplashDustParticle(
    val xFrac: Float,
    val yFrac: Float,
    val size: Float,
    val driftX: Float,
    val fallDist: Float,
    val duration: Float,
    val delay: Float,
    val color: Color,
    val maxAlpha: Float
)

/**
 * Vertical Tachyon Hero Animated Splash Screen in Jetpack Compose.
 *
 * Sequence:
 * - 0.0s - 2.4s: Laser cut border tracing around a faceted vertical badge with cutting head & sparks.
 * - 2.4s - 2.65s: Thermal flashover and snap pop.
 * - 2.65s - 3.75s: Thermal seam cooldown (White -> Amber -> Cyan -> Cobalt), 70° diagonal chromatic
 *   specular sheen sweep, expanding shockwave ring, rotating diamond starburst, and 36 settling dust motes.
 * - 3.75s - 4.15s: Global alpha dissolve (255 -> 0) transitioning into the game.
 */
@Composable
fun TachyonVerticalSplash(
    onSplashComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animProgress = remember { Animatable(0f) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    // 36 palette dust particles matching LaserCutLogo.tsx palette
    val dustPalette = remember {
        listOf(
            Color(0xFF00F3FF), // Cyan #00F3FF
            Color(0xFFFFB800), // Amber #FFB800
            Color(0xFFFF00FF), // Magenta #FF00FF
            Color(0xFFFFFFFF), // White #FFFFFF
            Color(0xFFFF6600), // Orange #FF6600
            Color(0xFF00FFB2)  // Mint #00FFB2
        )
    }

    val dustParticles = remember {
        List(36) { i ->
            SplashDustParticle(
                xFrac = 0.08f + Random.nextFloat() * 0.84f,
                yFrac = 0.25f + Random.nextFloat() * 0.50f,
                size = 2.0f + Random.nextFloat() * 4.5f,
                driftX = (Random.nextFloat() - 0.5f) * 110f,
                fallDist = 60f + Random.nextFloat() * 150f,
                duration = 1.2f + Random.nextFloat() * 0.7f,
                delay = Random.nextFloat() * 0.35f,
                color = dustPalette[i % dustPalette.size],
                maxAlpha = 0.5f + Random.nextFloat() * 0.45f
            )
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
        // Start procedural Web Audio synthesis in microsecond sync
        audioTrack = TachyonAudioSynth.createAndPlaySplashAudio()

        // Master animation timeline: 4.15 seconds
        animProgress.animateTo(
            targetValue = 4.15f,
            animationSpec = tween(durationMillis = 4150, easing = LinearEasing)
        )
        onSplashComplete()
    }

    val elapsed = animProgress.value

    // Global dissolve alpha during final 400ms (3.75s -> 4.15s)
    val globalAlpha = when {
        elapsed > 3.75f -> (1.0f - (elapsed - 3.75f) / 0.40f).coerceIn(0f, 1f)
        else -> 1.0f
    }

    // Cutting progress & progressive vertical unveiling
    val cutP = (elapsed / 2.40f).coerceIn(0f, 1f)
    val revealFrac = if (elapsed < 2.40f) max(0.08f, cutP * 1.10f) else 1.0f
    val isFlash = elapsed in 2.40f..2.65f
    val flashFrac = if (isFlash) (1.0f - (elapsed - 2.40f) / 0.25f) else 0.0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
            .alpha(globalAlpha),
        contentAlignment = Alignment.Center
    ) {
        // 1. Center Hero Logo Container (with progressive vertical clip during laser cut)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight()
                .padding(horizontal = 16.dp)
                .clip(
                    GenericShape { size, _ ->
                        addRect(Rect(0f, 0f, size.width, size.height * revealFrac))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.tachyon_logo),
                contentDescription = "Tachyon Game Studio",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(840f / 470f)
            )

            // Thermal Flash Boost Overlay over logo
            if (isFlash) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = flashFrac * 0.45f))
                )
            }
        }

        // 2. Hardware-Accelerated Procedural Canvas: Laser, Torch, Sparks, Seam Cooldown, Sheen
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Calculate Faceted Border Badge (centered around hero crest)
            val badgeW = (w * 0.90f).coerceAtMost(440.dp.toPx())
            val badgeH = (badgeW * (470f / 840f)) + 32.dp.toPx()
            val left = (w - badgeW) / 2f
            val top = (h - badgeH) / 2f
            val chamfer = 24.dp.toPx()

            val facetedPath = Path().apply {
                moveTo(left + chamfer, top)
                lineTo(left + badgeW - chamfer, top)
                lineTo(left + badgeW, top + chamfer)
                lineTo(left + badgeW, top + badgeH - chamfer)
                lineTo(left + badgeW - chamfer, top + badgeH)
                lineTo(left + chamfer, top + badgeH)
                lineTo(left, top + badgeH - chamfer)
                lineTo(left, top + chamfer)
                close()
            }

            val androidPath = facetedPath.asAndroidPath()
            val pathMeasure = PathMeasure(androidPath, false)
            val totalLength = pathMeasure.length

            // ============================================================
            // PHASE 1: LASER CUT BORDER & SPARKS (0.0s - 2.40s)
            // ============================================================
            if (elapsed < 2.40f) {
                val currentDist = cutP * totalLength

                // Cut subpath segment
                val subPath = android.graphics.Path()
                pathMeasure.getSegment(0f, currentDist, subPath, true)
                val composeSubPath = subPath.asComposePath()

                // Soft outer cyan flame glow (#laser-flame)
                drawPath(
                    path = composeSubPath,
                    color = Color(0xFF00F3FF).copy(alpha = 0.35f),
                    style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // Sharp cyan seam line
                drawPath(
                    path = composeSubPath,
                    color = Color(0xFF00F3FF).copy(alpha = 0.85f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // White-hot core line
                drawPath(
                    path = composeSubPath,
                    color = Color.White,
                    style = Stroke(width = 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Laser Torch Head Position
                val pos = FloatArray(2)
                val tan = FloatArray(2)
                pathMeasure.getPosTan(currentDist, pos, tan)
                val headX = pos[0]
                val headY = pos[1]

                // Glowing Cutting Head
                drawCircle(Color(0xFF00F3FF).copy(alpha = 0.45f), radius = 24f, center = Offset(headX, headY))
                drawCircle(Color(0xFFFF00FF).copy(alpha = 0.90f), radius = 11f, center = Offset(headX, headY))
                drawCircle(Color.White, radius = 5.5f, center = Offset(headX, headY))

                // Directional Sparks matching SVG lines
                val dirSparks = listOf(
                    Offset(-14f, -12f) to Color(0xFFFFB800),
                    Offset(16f, -15f) to Color.White,
                    Offset(-8f, 18f) to Color(0xFF00F3FF),
                    Offset(14f, 14f) to Color(0xFFFF007F),
                    Offset(-18f, 4f) to Color(0xFFFFB800)
                )
                dirSparks.forEach { (offset, sparkCol) ->
                    drawLine(
                        color = sparkCol.copy(alpha = 0.90f),
                        start = Offset(headX, headY),
                        end = Offset(headX + offset.x * 1.5f, headY + offset.y * 1.5f),
                        strokeWidth = 2.8f,
                        cap = StrokeCap.Round
                    )
                }

                // Dynamic Flying Spatter Sparks
                val spatterCols = listOf(Color(0xFFFFB800), Color.White, Color(0xFF00F3FF), Color(0xFFFF007F))
                for (s in 0 until 5) {
                    val ang = (Random.nextFloat() * 360f).toDouble()
                    val dist = 14f + Random.nextFloat() * 22f
                    val sx = headX + (cos(Math.toRadians(ang)) * dist).toFloat()
                    val sy = headY + (sin(Math.toRadians(ang)) * dist).toFloat()
                    drawLine(
                        color = spatterCols[s % spatterCols.size].copy(alpha = 0.85f),
                        start = Offset(headX, headY),
                        end = Offset(sx, sy),
                        strokeWidth = 2.2f,
                        cap = StrokeCap.Round
                    )
                }
            }
            // ============================================================
            // PHASE 2 & 3: FLASH, THERMAL COOLDOWN & SHEEN (2.40s - 3.75s)
            // ============================================================
            else {
                // 1. Thermal Seam Cooldown Color Interpolation
                // White (#FFFFFF) -> Amber (#FFB800) -> Cyan (#00F3FF) -> Cobalt (#0055FF)
                val coolP = ((elapsed - 2.40f) / 1.35f).coerceIn(0f, 1f)
                val (seamColor, seamAlpha) = when {
                    coolP < 0.25f -> {
                        val t = coolP / 0.25f
                        lerp(Color.White, Color(0xFFFFB800), t) to (1.0f - t * 0.10f)
                    }
                    coolP < 0.65f -> {
                        val t = (coolP - 0.25f) / 0.40f
                        lerp(Color(0xFFFFB800), Color(0xFF00F3FF), t) to (0.90f - t * 0.45f)
                    }
                    else -> {
                        val t = (coolP - 0.65f) / 0.35f
                        lerp(Color(0xFF00F3FF), Color(0xFF0055FF), t) to (0.45f * (1.0f - t))
                    }
                }

                if (seamAlpha > 0.02f) {
                    if (coolP < 0.5f) {
                        drawPath(
                            path = facetedPath,
                            color = Color(0xFFFF6600).copy(alpha = seamAlpha * 0.35f),
                            style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    drawPath(
                        path = facetedPath,
                        color = seamColor.copy(alpha = seamAlpha),
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // 2. Specular Sheen Beam Sweep & Starburst (2.60s - 3.75s)
                if (elapsed in 2.60f..3.75f) {
                    val sheenP = ((elapsed - 2.60f) / 1.15f).coerceIn(0f, 1f)
                    val sweepCenterY = -h * 0.25f + sheenP * (h * 1.5f)
                    val sweepHalfH = h * 0.18f

                    // 70-degree diagonal chromatic sheen sweep
                    val sheenBrush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.36f to Color(0xFF00F3FF).copy(alpha = 0.20f), // Cyan leading
                            0.50f to Color.White.copy(alpha = 0.85f),        // White core
                            0.64f to Color(0xFFFF00FF).copy(alpha = 0.25f), // Magenta trailing
                            1.0f to Color.Transparent
                        ),
                        start = Offset(0f, sweepCenterY - sweepHalfH),
                        end = Offset(w, sweepCenterY + sweepHalfH)
                    )

                    drawRect(brush = sheenBrush, blendMode = BlendMode.Screen)

                    // Expanding Concentric Shockwave Ring
                    val waveRadius = 25f + sheenP * (w * 0.65f)
                    val waveAlpha = max(0f, (1f - sheenP * sheenP) * 0.75f)
                    val waveColor = lerp(Color(0xFF00F3FF), Color(0xFFFF00FF), (sheenP - 0.35f).coerceIn(0f, 1f))
                    drawCircle(
                        color = waveColor.copy(alpha = waveAlpha),
                        radius = waveRadius,
                        center = Offset(w * 0.5f, h * 0.5f),
                        style = Stroke(width = 3.5f)
                    )

                    // Central Rotating Diamond Starburst Flare
                    val flareT = ((sheenP - 0.18f) / 0.62f).coerceIn(0f, 1f)
                    if (flareT in 0.01f..0.99f) {
                        val flareAlpha = if (flareT < 0.30f) flareT / 0.30f else 1.0f - (flareT - 0.30f) / 0.70f
                        val flareScale = if (flareT < 0.30f) 0.2f + (flareT / 0.30f) * 1.6f else 1.8f - (flareT - 0.30f) * 1.5f
                        val angle = flareT * 135f

                        drawIntoCanvas { canvas ->
                            canvas.save()
                            canvas.translate(w * 0.5f, h * 0.5f)
                            canvas.rotate(angle)

                            val armLen = 85f * flareScale
                            // White core beam
                            drawLine(
                                color = Color.White.copy(alpha = flareAlpha),
                                start = Offset(-armLen, 0f),
                                end = Offset(armLen, 0f),
                                strokeWidth = 4.5f
                            )
                            // Cyan beam
                            drawLine(
                                color = Color(0xFF00F3FF).copy(alpha = flareAlpha),
                                start = Offset(0f, -armLen),
                                end = Offset(0f, armLen),
                                strokeWidth = 4.5f
                            )
                            // Diagonal magenta rays
                            val diagLen = armLen * 0.65f
                            drawLine(
                                color = Color(0xFFFF00FF).copy(alpha = flareAlpha * 0.70f),
                                start = Offset(-diagLen, -diagLen),
                                end = Offset(diagLen, diagLen),
                                strokeWidth = 2.5f
                            )
                            drawLine(
                                color = Color(0xFFFF00FF).copy(alpha = flareAlpha * 0.70f),
                                start = Offset(-diagLen, diagLen),
                                end = Offset(diagLen, -diagLen),
                                strokeWidth = 2.5f
                            )

                            drawCircle(Color.White.copy(alpha = flareAlpha), radius = 18f * flareScale)
                            canvas.restore()
                        }
                    }

                    // 36 Atmospheric Dust Particles Settling Under Gravity
                    dustParticles.forEach { p ->
                        val pTime = ((elapsed - 2.65f - p.delay) / p.duration).coerceIn(0f, 1f)
                        if (pTime in 0.01f..0.99f) {
                            val curX = p.xFrac * w + p.driftX * pTime
                            val curY = p.yFrac * h + p.fallDist * (pTime * pTime)

                            val fade = when {
                                pTime < 0.15f -> pTime / 0.15f
                                pTime > 0.65f -> 1f - (pTime - 0.65f) / 0.35f
                                else -> 1f
                            }
                            val pAlpha = (p.maxAlpha * fade).coerceIn(0f, 1f)
                            val pSize = p.size * (1.15f - pTime * 0.55f)

                            drawCircle(
                                color = p.color.copy(alpha = pAlpha),
                                radius = pSize,
                                center = Offset(curX, curY)
                            )
                        }
                    }
                }
            }

            // Thermal Flash Whiteout Overlay (2.40s - 2.65s)
            if (isFlash) {
                drawRect(color = Color.White.copy(alpha = flashFrac * 0.60f))
            }
        }
    }
}
