package com.arcade.bubbledefense.ui.render

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.loop.ViewportScaler
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance zero-allocation procedural Canvas renderer.
 *
 * Implements the rendering pipeline specified in `docs/ARCHITECTURE.md` §7 and
 * `docs/GAME_DESIGN.md` §6.
 *
 * Guarantees zero heap allocation per frame:
 * - All [Path], [Stroke], and [AndroidPaint] instances are pre-allocated and cached as fields.
 * - Multi-pass neon bloom is achieved via layered stroke passes:
 *   Pass 1 (Outer Glow): wide stroke, low alpha
 *   Pass 2 (Mid Core): medium stroke, accent color
 *   Pass 3 (Hot Core): thin stroke, white/high-contrast
 * - Bubble soft-body wobble uses a precomputed 64-entry trigonometric lookup table.
 * - Text rendering uses a cached, hardware-accelerated [AndroidPaint] with typeface flags.
 */
class ProceduralRenderer {

    companion object {
        private const val BUBBLE_VERTICES = 64

        // Neon Cyberpunk Palette
        val COLOR_BG = Color(0xFF07090E)
        val COLOR_CYAN = Color(0xFF00F0FF)
        val COLOR_MAGENTA = Color(0xFFFF0055)
        val COLOR_AMBER = Color(0xFFFFB800)
        val COLOR_RED = Color(0xFFFF3300)
        val COLOR_GREEN = Color(0xFF00FF99)
        val COLOR_WHITE = Color(0xFFFFFFFF)

        // Pre-allocated text strings
        private val TEXT_STRINGS = arrayOf(
            "PARRY!",       // 0: TEXT_PARRY
            "+5",           // 1: TEXT_PLUS_5
            "+10",          // 2: TEXT_PLUS_10
            "+50",          // 3: TEXT_PLUS_50
            "+100",         // 4: TEXT_PLUS_100
            "SPLIT!",       // 5: TEXT_SPLIT
            "CRUSH!",       // 6: TEXT_CRUSH
            "CLOSE CALL!"   // 7: TEXT_CLOSE_CALL
        )
    }

    // Precomputed Bubble Harmonic Lookup Tables
    private val cosTable = FloatArray(BUBBLE_VERTICES)
    private val sinTable = FloatArray(BUBBLE_VERTICES)

    init {
        val step = (2.0 * PI / BUBBLE_VERTICES).toFloat()
        for (i in 0 until BUBBLE_VERTICES) {
            val angle = i * step
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    // Reusable Paths for DrawScope
    private val bubblePath = Path()
    private val obstaclePath = Path()
    private val scratchPath = Path()

    // Reusable Pre-Allocated Strokes
    private val strokeGlowWide = Stroke(width = 16f, cap = StrokeCap.Round)
    private val strokeGlowMid = Stroke(width = 8f, cap = StrokeCap.Round)
    private val strokeCoreThin = Stroke(width = 3f, cap = StrokeCap.Round)
    private val strokeWhiteCore = Stroke(width = 1.5f, cap = StrokeCap.Round)

    private val strokeShieldGlow = Stroke(width = 22f, cap = StrokeCap.Round)
    private val strokeShieldMid = Stroke(width = 12f, cap = StrokeCap.Round)
    private val strokeShieldCore = Stroke(width = 4f, cap = StrokeCap.Round)

    private val strokeRing = Stroke(width = 2f)
    private val strokeThickRing = Stroke(width = 4f)

    // Reusable Native Paint for Floating Text
    private val textPaint = AndroidPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = AndroidPaint.Align.CENTER
    }

    /**
     * Renders the complete game state into the provided [drawScope].
     *
     * @param drawScope The Compose DrawScope to render into.
     * @param world The current mutable simulation state.
     * @param scaler Coordinate transformer between virtual world (1000x1800) and device screen.
     * @param alpha Temporal interpolation factor in `[0, 1]` for smooth inter-frame rendering.
     */
    fun draw(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler,
        alpha: Float
    ) {
        // 1. Draw Deep Void Background
        drawScope.drawRect(COLOR_BG)

        // 2. Draw Parallax Background Layers
        drawParallaxStarfield(drawScope, world, scaler)
        drawParallaxGrid(drawScope, world, scaler)

        // 3. Draw Active Particles (Behind shields and obstacles)
        drawParticles(drawScope, world, scaler)

        // 4. Draw Player Shield Segments with Multi-Pass Neon Glow
        drawShieldSegments(drawScope, world, scaler)

        // 5. Draw Defense Bubble with Soft-Body Harmonic Wobble & Ink Arc
        drawBubble(drawScope, world, scaler)

        // 6. Draw Falling Obstacles (Procedural Geometry)
        drawObstacles(drawScope, world, scaler)

        // 7. Draw Floating Combat Text
        drawFloatingTexts(drawScope, world, scaler)
    }

    // ---------------------------------------------------------------------------------------------
    // Parallax Starfield & Neon Grid
    // ---------------------------------------------------------------------------------------------

    private fun drawParallaxStarfield(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        // Far starfield scrolling slowly (0.15x altitude)
        val scrollY = (world.altitudeMeters * GameConfig.WU_PER_METER * 0.15f) % GameConfig.WORLD_HEIGHT
        val starCount = 48
        val scale = scaler.scale

        for (i in 0 until starCount) {
            // Deterministic pseudo-random star distribution
            val seedX = (i * 37 + 19) % GameConfig.WORLD_WIDTH.toInt()
            val seedY = (i * 73 + 41) % GameConfig.WORLD_HEIGHT.toInt()

            var wy = seedY + scrollY
            if (wy >= GameConfig.WORLD_HEIGHT) wy -= GameConfig.WORLD_HEIGHT

            val sx = scaler.worldToScreenX(seedX.toFloat())
            val sy = scaler.worldToScreenY(wy)
            val brightness = 0.2f + ((i % 5) * 0.15f)
            val radius = if (i % 7 == 0) 2.5f * scale else 1.5f * scale

            drawScope.drawCircle(
                color = COLOR_WHITE.copy(alpha = brightness),
                radius = radius,
                center = Offset(sx, sy)
            )
        }
    }

    private fun drawParallaxGrid(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        // Mid neon horizon grid (0.40x altitude)
        val spacingWu = 120f
        val scrollY = (world.altitudeMeters * GameConfig.WU_PER_METER * 0.40f) % spacingWu
        val scale = scaler.scale

        var wy = -spacingWu + scrollY
        while (wy < GameConfig.WORLD_HEIGHT + spacingWu) {
            val sy = scaler.worldToScreenY(wy)
            val leftSx = scaler.worldToScreenX(0f)
            val rightSx = scaler.worldToScreenX(GameConfig.WORLD_WIDTH)

            drawScope.drawLine(
                color = COLOR_CYAN.copy(alpha = 0.06f),
                start = Offset(leftSx, sy),
                end = Offset(rightSx, sy),
                strokeWidth = 1f * scale
            )
            wy += spacingWu
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Particles
    // ---------------------------------------------------------------------------------------------

    private fun drawParticles(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.particles.count
        val scale = scaler.scale

        for (i in 0 until count) {
            val px = scaler.worldToScreenX(world.particles.x[i])
            val py = scaler.worldToScreenY(world.particles.y[i])
            val lifeRatio = (1f - world.particles.life[i] / world.particles.maxLife[i]).coerceIn(0f, 1f)
            val argb = world.particles.color[i]
            val color = Color(argb).copy(alpha = lifeRatio)
            val pSize = world.particles.size[i] * scale * lifeRatio

            drawScope.drawCircle(
                color = color,
                radius = pSize.coerceAtLeast(1f),
                center = Offset(px, py)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shield Segments (Multi-Pass Neon Bloom & Integrity Degradation)
    // ---------------------------------------------------------------------------------------------

    private fun drawShieldSegments(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.shieldSegments.count
        val scale = scaler.scale

        for (i in 0 until count) {
            val sax = scaler.worldToScreenX(world.shieldSegments.ax[i])
            val say = scaler.worldToScreenY(world.shieldSegments.ay[i])
            val sbx = scaler.worldToScreenX(world.shieldSegments.bx[i])
            val sby = scaler.worldToScreenY(world.shieldSegments.by[i])

            val age = world.shieldSegments.age[i]
            val integrity = world.shieldSegments.integrity[i]
            val flash = world.shieldSegments.flashTimer[i]

            // Fade opacity near expiration
            val remainingLife = GameConfig.SHIELD_LIFETIME - age
            val fadeAlpha = if (remainingLife < GameConfig.SEGMENT_FADE_TIME) {
                (remainingLife / GameConfig.SEGMENT_FADE_TIME).coerceIn(0f, 1f)
            } else {
                1f
            }

            // Chromatic transition based on structural integrity: Cyan -> Magenta -> Orange
            val integrityRatio = (integrity / GameConfig.SEGMENT_INTEGRITY).coerceIn(0f, 1f)
            val baseColor = when {
                integrityRatio > 0.60f -> COLOR_CYAN
                integrityRatio > 0.30f -> COLOR_MAGENTA
                else -> COLOR_AMBER
            }

            val pStart = Offset(sax, say)
            val pEnd = Offset(sbx, sby)

            // Pass 1: Wide Outer Glow
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.14f * fadeAlpha),
                start = pStart,
                end = pEnd,
                strokeWidth = strokeShieldGlow.width * scale,
                cap = StrokeCap.Round
            )

            // Pass 2: Mid Neon Core
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.65f * fadeAlpha),
                start = pStart,
                end = pEnd,
                strokeWidth = strokeShieldMid.width * scale,
                cap = StrokeCap.Round
            )

            // Pass 3: Hot Core (Turns pure white when hit / flashing)
            val coreColor = if (flash > 0f) COLOR_WHITE else COLOR_WHITE.copy(alpha = 0.90f * fadeAlpha)
            drawScope.drawLine(
                color = coreColor,
                start = pStart,
                end = pEnd,
                strokeWidth = strokeShieldCore.width * scale,
                cap = StrokeCap.Round
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The Bubble (Soft-Body Harmonics, Proximity Warning & Diegetic Ink Arc)
    // ---------------------------------------------------------------------------------------------

    private fun drawBubble(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val sbx = scaler.worldToScreenX(world.bubbleX)
        val sby = scaler.worldToScreenY(world.bubbleY)
        val sbr = scaler.scaleDistance(world.bubbleRadius)
        val scale = scaler.scale
        val time = world.gameTime

        // 1. Proximity Warning Ring
        val proximity = world.closestObstacleDistance
        if (proximity < 320f) {
            val proxRatio = (1f - proximity / 320f).coerceIn(0f, 1f)
            val pulse = 0.5f + 0.5f * sin(time * 16f)
            val warnAlpha = (0.25f + 0.50f * pulse) * proxRatio
            val warnRadius = sbr + scaler.scaleDistance(proximity * 0.4f)

            drawScope.drawCircle(
                color = COLOR_MAGENTA.copy(alpha = warnAlpha),
                radius = warnRadius,
                center = Offset(sbx, sby),
                style = strokeRing
            )
        }

        // 2. Procedural Soft-Body Harmonic Wobble (64-vertex sine deformation)
        val a3 = world.bubbleWobbleAmplitudes[0]
        val a5 = world.bubbleWobbleAmplitudes[1]
        val a7 = world.bubbleWobbleAmplitudes[2]
        val phi3 = time * 4.2f
        val phi5 = time * 6.8f
        val phi7 = time * 9.5f

        bubblePath.rewind()
        for (i in 0 until BUBBLE_VERTICES) {
            val cosVal = cosTable[i]
            val sinVal = sinTable[i]

            // Harmonic radius deformation: r = R * (1 + sum(A_k * sin(k*theta + phi_k)))
            val theta = (i.toFloat() / BUBBLE_VERTICES) * (2f * PI.toFloat())
            val deformation = 1f +
                    a3 * sin(3f * theta + phi3) +
                    a5 * sin(5f * theta + phi5) +
                    a7 * sin(7f * theta + phi7)

            val r = sbr * deformation
            val vx = sbx + cosVal * r
            val vy = sby + sinVal * r

            if (i == 0) {
                bubblePath.moveTo(vx, vy)
            } else {
                bubblePath.lineTo(vx, vy)
            }
        }
        bubblePath.close()

        // Pass A: Translucent Inner Fill
        val bubbleColor = if (world.bubbleInvulnTimer > 0f) {
            val flash = 0.5f + 0.5f * sin(time * 30f)
            COLOR_WHITE.copy(alpha = 0.25f * flash)
        } else {
            COLOR_CYAN.copy(alpha = 0.08f)
        }
        drawScope.drawPath(bubblePath, color = bubbleColor, style = Fill)

        // Pass B: Outer Glow Ring
        drawScope.drawPath(
            bubblePath,
            color = COLOR_CYAN.copy(alpha = 0.20f),
            style = strokeGlowWide
        )

        // Pass C: Bright Neon Perimeter
        drawScope.drawPath(
            bubblePath,
            color = COLOR_CYAN,
            style = strokeCoreThin
        )

        // Pass D: Top-Left Specular Highlight
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.35f),
            radius = sbr * 0.18f,
            center = Offset(sbx - sbr * 0.40f, sby - sbr * 0.40f)
        )

        // 3. Diegetic Ink Arc (Around Bubble rim, 150 deg to 390 deg sweep)
        val inkRatio = (world.ink / GameConfig.INK_MAX).coerceIn(0f, 1f)
        val inkArcRadius = sbr + 14f * scale
        val arcTopLeft = Offset(sbx - inkArcRadius, sby - inkArcRadius)
        val arcSize = Size(inkArcRadius * 2f, inkArcRadius * 2f)
        val totalSweep = 240f
        val currentSweep = totalSweep * inkRatio
        val inkColor = if (inkRatio > 0.25f) COLOR_CYAN else COLOR_AMBER

        // Background track
        drawScope.drawArc(
            color = COLOR_CYAN.copy(alpha = 0.10f),
            startAngle = 150f,
            sweepAngle = totalSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = strokeThickRing
        )

        // Active ink arc
        if (currentSweep > 0f) {
            drawScope.drawArc(
                color = inkColor.copy(alpha = 0.85f),
                startAngle = 150f,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = strokeThickRing
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Obstacles (Procedural Geometry by Archetype)
    // ---------------------------------------------------------------------------------------------

    private fun drawObstacles(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.obstacles.count
        val scale = scaler.scale

        for (i in 0 until count) {
            val ox = scaler.worldToScreenX(world.obstacles.x[i])
            val oy = scaler.worldToScreenY(world.obstacles.y[i])
            val r = scaler.scaleDistance(world.obstacles.radius[i])
            val type = world.obstacles.type[i]
            val vx = world.obstacles.vx[i]
            val vy = world.obstacles.vy[i]
            val angle = world.obstacles.angle[i]
            val flags = world.obstacles.flags[i]
            val isParried = (flags and GameConfig.FLAG_PARRIED) != 0

            when (type) {
                GameConfig.TYPE_DRIFTER -> drawDrifter(drawScope, ox, oy, r, scale, isParried)
                GameConfig.TYPE_NEEDLE -> drawNeedle(drawScope, ox, oy, r, vx, vy, scale, isParried)
                GameConfig.TYPE_CRUSHER -> drawCrusher(drawScope, ox, oy, r, angle, scale, isParried)
                GameConfig.TYPE_SPLITTER -> drawSplitter(drawScope, ox, oy, r, angle, scale, isParried)
                GameConfig.TYPE_SHARD -> drawShard(drawScope, ox, oy, r, angle, scale, isParried)
            }
        }
    }

    private fun drawDrifter(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        scale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_MAGENTA
        val center = Offset(cx, cy)

        // Outer glow pass
        drawScope.drawCircle(baseColor.copy(alpha = 0.15f), radius = r + 6f * scale, center = center)
        // Main outline
        drawScope.drawCircle(baseColor, radius = r, center = center, style = strokeCoreThin)
        // Inner concentric accent
        drawScope.drawCircle(baseColor.copy(alpha = 0.40f), radius = r * 0.5f, center = center, style = strokeWhiteCore)
    }

    private fun drawNeedle(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        vx: Float,
        vy: Float,
        scale: Float,
        isParried: Boolean
    ) {
        // Needle points in direction of velocity
        val speedSq = vx * vx + vy * vy
        val heading = if (speedSq > 1f) atan2(vy, vx) else (PI.toFloat() * 0.5f)
        val length = r * 3.5f
        val halfBase = r * 1.2f

        val baseColor = if (isParried) COLOR_CYAN else COLOR_RED

        val tipX = cx + cos(heading) * length
        val tipY = cy + sin(heading) * length

        val backAngle = heading + PI.toFloat()
        val perpAngle = heading + (PI.toFloat() * 0.5f)

        val baseX = cx + cos(backAngle) * (r * 0.8f)
        val baseY = cy + sin(backAngle) * (r * 0.8f)

        val leftX = baseX + cos(perpAngle) * halfBase
        val leftY = baseY + sin(perpAngle) * halfBase
        val rightX = baseX - cos(perpAngle) * halfBase
        val rightY = baseY - sin(perpAngle) * halfBase

        obstaclePath.rewind()
        obstaclePath.moveTo(tipX, tipY)
        obstaclePath.lineTo(leftX, leftY)
        obstaclePath.lineTo(rightX, rightY)
        obstaclePath.close()

        // Glow pass
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.20f), style = strokeGlowMid)
        // Fill
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.40f), style = Fill)
        // Core outline
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)
    }

    private fun drawCrusher(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        scale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_RED
        val sides = 6
        val step = (2.0 * PI / sides).toFloat()

        obstaclePath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) obstaclePath.moveTo(px, py) else obstaclePath.lineTo(px, py)
        }
        obstaclePath.close()

        // Outer glow
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.15f), style = strokeGlowWide)
        // Hex outline
        drawScope.drawPath(obstaclePath, color = baseColor, style = strokeShieldMid)
        // Inner danger cross
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE.copy(alpha = 0.50f), style = strokeWhiteCore)
    }

    private fun drawSplitter(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        scale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_GREEN
        val sides = 4
        val step = (2.0 * PI / sides).toFloat()

        obstaclePath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) obstaclePath.moveTo(px, py) else obstaclePath.lineTo(px, py)
        }
        obstaclePath.close()

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.20f), style = strokeGlowMid)
        drawScope.drawPath(obstaclePath, color = baseColor, style = strokeCoreThin)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)
    }

    private fun drawShard(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        scale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_GREEN
        val sides = 3
        val step = (2.0 * PI / sides).toFloat()

        obstaclePath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) obstaclePath.moveTo(px, py) else obstaclePath.lineTo(px, py)
        }
        obstaclePath.close()

        drawScope.drawPath(obstaclePath, color = baseColor, style = strokeCoreThin)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)
    }

    // ---------------------------------------------------------------------------------------------
    // Floating Combat Text
    // ---------------------------------------------------------------------------------------------

    private fun drawFloatingTexts(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.floatingTexts.count
        if (count == 0) return

        val scale = scaler.scale
        val canvas = drawScope.drawContext.canvas.nativeCanvas

        for (i in 0 until count) {
            val sx = scaler.worldToScreenX(world.floatingTexts.x[i])
            val sy = scaler.worldToScreenY(world.floatingTexts.y[i])
            val lifeRatio = (1f - world.floatingTexts.life[i] / world.floatingTexts.maxLife[i]).coerceIn(0f, 1f)
            val textScale = world.floatingTexts.scale[i]
            val textType = world.floatingTexts.textId[i].toInt().coerceIn(0, TEXT_STRINGS.size - 1)
            val label = TEXT_STRINGS[textType]

            val textSize = 22f * scale * textScale
            textPaint.textSize = textSize
            textPaint.color = when (textType) {
                FloatingTextPool.TEXT_PARRY.toInt() -> 0xFF00F0FF.toInt() // Neon Cyan
                FloatingTextPool.TEXT_CRUSH.toInt() -> 0xFFFF3300.toInt() // Crimson
                FloatingTextPool.TEXT_SPLIT.toInt() -> 0xFF00FF99.toInt() // Neon Green
                else -> 0xFFFFB800.toInt()                                // Amber
            }
            textPaint.alpha = (lifeRatio * 255).toInt().coerceIn(0, 255)

            canvas.drawText(label, sx, sy, textPaint)
        }
    }
}
