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
import com.arcade.bubbledefense.domain.economy.BubbleSkin
import com.arcade.bubbledefense.domain.economy.ShieldSkin
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.loop.ViewportScaler
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance zero-allocation procedural 2.5D Canvas renderer.
 *
 * Implements the third-person perspective highway lane rendering pipeline specified in
 * `plan_2_5d_perspective_lane.md` and `docs/GAME_DESIGN.md`.
 *
 * Visual Features:
 * - 2.5D Synthwave highway with converging neon curbs, forward-scrolling floor crosslines
 *   with 1/Z perspective compression driven by altitude speed, and dashed centerline dividers.
 * - Extruded 3D neon energy shield barricades with height displacement, translucent barrier fill,
 *   hot-core laser crests, vertical end pylons, and directional swipe parry chevrons.
 * - 3D spherical bubble target with ground drop-shadow, multi-harmonic soft-body wobble,
 *   directional specular highlight, and an inclined diegetic ink gauge ring.
 * - In-place zero-allocation depth sorting for incoming obstacles (far to near).
 * - 3D procedural obstacles: shaded Drifter spheres, supersonic Needle arrowheads with rear jet trails,
 *   extruded Crusher hexagonal monoliths, and rotating Splitter octahedrons.
 * - 3D threat telegraph laser guides, depth-scaled particles, and floating combat text.
 *
 * Hot-path guarantee: 0 heap allocations per frame.
 */
class ProceduralRenderer {

    companion object {
        private const val BUBBLE_VERTICES = 64

        // Neon Cyberpunk Palette
        val COLOR_BG = Color(0xFF06080E)
        val COLOR_LANE_BG = Color(0xFF090D18)
        val COLOR_CYAN = Color(0xFF00F0FF)
        val COLOR_MAGENTA = Color(0xFFFF0055)
        val COLOR_AMBER = Color(0xFFFFB800)
        val COLOR_RED = Color(0xFFFF3300)
        val COLOR_GREEN = Color(0xFF00FF99)
        val COLOR_WHITE = Color(0xFFFFFFFF)
        val COLOR_SHADOW = Color(0x66000000)

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

    // Pre-allocated Depth Sorting Buffer (Primitive array, 0 heap allocations)
    private val sortIndices = IntArray(128)

    // Reusable Paths for DrawScope
    private val bubblePath = Path()
    private val obstaclePath = Path()
    private val scratchPath = Path()
    private val highwayPath = Path()

    // Reusable Pre-Allocated Strokes
    private val strokeGlowWide = Stroke(width = 16f, cap = StrokeCap.Round)
    private val strokeGlowMid = Stroke(width = 8f, cap = StrokeCap.Round)
    private val strokeCoreThin = Stroke(width = 3f, cap = StrokeCap.Round)
    private val strokeWhiteCore = Stroke(width = 1.5f, cap = StrokeCap.Round)

    private val strokeShieldGlow = Stroke(width = 20f, cap = StrokeCap.Round)
    private val strokeShieldMid = Stroke(width = 10f, cap = StrokeCap.Round)
    private val strokeShieldCore = Stroke(width = 3.5f, cap = StrokeCap.Round)

    private val strokeRing = Stroke(width = 2f)
    private val strokeThickRing = Stroke(width = 4f)

    // Reusable Native Paint for Floating Text
    private val textPaint = AndroidPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = AndroidPaint.Align.CENTER
    }

    /** Active equipped procedural shield cosmetic skin. */
    var activeShieldSkin: ShieldSkin = ShieldSkin.CYBER_CYAN

    /** Active equipped procedural bubble cosmetic skin. */
    var activeBubbleSkin: BubbleSkin = BubbleSkin.CLASSIC_NEON

    /**
     * Renders the complete 2.5D game state into the provided [drawScope].
     */
    fun draw(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler,
        alpha: Float
    ) {
        // 1. Draw Deep Cyberpunk Void Background
        drawScope.drawRect(COLOR_BG)

        // 2. Draw 2.5D Synthwave Sky & Horizon
        drawSkyAndHorizon(drawScope, world, scaler)

        // 3. Draw 2.5D Highway Lane (Road surface, curbs, scrolling velocity crosslines)
        drawHighwayLane(drawScope, world, scaler)

        // 4. Draw Active Particles (Depth-projected)
        drawParticles(drawScope, world, scaler)

        // 5. Draw Extruded 3D Neon Energy Walls
        drawShieldSegments(drawScope, world, scaler)

        // 6. Draw 3D Defense Bubble with Soft-Body Harmonic Wobble & Inclined Ink Ring
        drawBubble(drawScope, world, scaler)

        // 7. Draw Depth-Sorted Incoming Obstacles (3D Geometry & Shadows)
        drawObstacles(drawScope, world, scaler)

        // 8. Draw 3D Threat Telegraph Warning Lasers & Chevrons
        drawTelegraphs(drawScope, world, scaler)

        // 9. Draw 3D Floating Combat Text
        drawFloatingTexts(drawScope, world, scaler)
    }

    // ---------------------------------------------------------------------------------------------
    // 2.5D Synthwave Sky & Horizon
    // ---------------------------------------------------------------------------------------------

    private fun drawSkyAndHorizon(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val horizonY = scaler.camera.vanishingPointY
        val screenW = scaler.screenWidthPx

        // Far Starfield in upper sky (y <= horizonY)
        val starCount = 36
        val scrollX = (world.altitudeMeters * 2f) % screenW

        for (i in 0 until starCount) {
            val seedX = ((i * 47 + 23) % screenW.toInt()).toFloat()
            val seedY = ((i * 29 + 17) % horizonY.toInt()).toFloat()
            val sx = (seedX + scrollX) % screenW
            val brightness = 0.2f + ((i % 5) * 0.15f)
            val radius = if (i % 6 == 0) 2.0f else 1.2f

            drawScope.drawCircle(
                color = COLOR_WHITE.copy(alpha = brightness),
                radius = radius,
                center = Offset(sx, seedY)
            )
        }

        // Horizon Laser Glow Line
        drawScope.drawLine(
            color = COLOR_MAGENTA.copy(alpha = 0.18f),
            start = Offset(0f, horizonY - 1f),
            end = Offset(screenW, horizonY - 1f),
            strokeWidth = 6f
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.40f),
            start = Offset(0f, horizonY),
            end = Offset(screenW, horizonY),
            strokeWidth = 2f
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2.5D Highway Lane Grid & Converging Curbs
    // ---------------------------------------------------------------------------------------------

    private fun drawHighwayLane(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val leftCurbX = GameConfig.LANE_LEFT_CURB_X
        val rightCurbX = GameConfig.LANE_RIGHT_CURB_X

        // 1. Road Surface Trapezoid (Ground plane between curbs)
        val topY = 0f
        val botY = cameraY

        val tlx = scaler.worldLaneToScreenX(leftCurbX, topY)
        val tly = scaler.worldLaneToScreenY(topY)
        val trx = scaler.worldLaneToScreenX(rightCurbX, topY)
        val try_ = scaler.worldLaneToScreenY(topY)

        val blx = scaler.worldLaneToScreenX(leftCurbX, botY)
        val bly = scaler.worldLaneToScreenY(botY)
        val brx = scaler.worldLaneToScreenX(rightCurbX, botY)
        val bry = scaler.worldLaneToScreenY(botY)

        highwayPath.rewind()
        highwayPath.moveTo(tlx, tly)
        highwayPath.lineTo(trx, try_)
        highwayPath.lineTo(brx, bry)
        highwayPath.lineTo(blx, bly)
        highwayPath.close()

        drawScope.drawPath(highwayPath, color = COLOR_LANE_BG, style = Fill)

        // 2. Forward-Scrolling Velocity Crosslines (1/Z perspective compression)
        val crosslineSpacing = GameConfig.FLOOR_CROSSLINE_SPACING_WU
        val scrollOffset = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % crosslineSpacing

        var lineY = scrollOffset
        while (lineY <= cameraY) {
            val clx = scaler.worldLaneToScreenX(leftCurbX, lineY)
            val crx = scaler.worldLaneToScreenX(rightCurbX, lineY)
            val cly = scaler.worldLaneToScreenY(lineY)
            val depthScale = scaler.scaleLaneDepth(lineY)

            val lineAlpha = (depthScale * 0.25f).coerceIn(0.04f, 0.35f)
            val strokeW = (2.5f * depthScale).coerceAtLeast(1f)

            drawScope.drawLine(
                color = COLOR_CYAN.copy(alpha = lineAlpha),
                start = Offset(clx, cly),
                end = Offset(crx, cly),
                strokeWidth = strokeW
            )
            lineY += crosslineSpacing
        }

        // 3. Center Dashed Line Dividers (Streaming down highway centerline)
        val dashPeriod = 80f
        val dashScroll = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % dashPeriod

        var dashY = dashScroll
        while (dashY <= cameraY) {
            val dashEnd = min(cameraY, dashY + 40f)
            val p1x = scaler.worldLaneToScreenX(GameConfig.BUBBLE_CENTER_X, dashY)
            val p1y = scaler.worldLaneToScreenY(dashY)
            val p2x = scaler.worldLaneToScreenX(GameConfig.BUBBLE_CENTER_X, dashEnd)
            val p2y = scaler.worldLaneToScreenY(dashEnd)
            val depthScale = scaler.scaleLaneDepth(dashY)

            val dashAlpha = (depthScale * 0.45f).coerceIn(0.08f, 0.55f)
            val strokeW = (3f * depthScale).coerceAtLeast(1.5f)

            drawScope.drawLine(
                color = COLOR_AMBER.copy(alpha = dashAlpha),
                start = Offset(p1x, p1y),
                end = Offset(p2x, p2y),
                strokeWidth = strokeW
            )
            dashY += dashPeriod
        }

        // 4. Left and Right Converging Neon Curbs (Multi-pass glow)
        // Left Curb
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.20f),
            start = Offset(tlx, tly),
            end = Offset(blx, bly),
            strokeWidth = 12f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.85f),
            start = Offset(tlx, tly),
            end = Offset(blx, bly),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE,
            start = Offset(tlx, tly),
            end = Offset(blx, bly),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )

        // Right Curb
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.20f),
            start = Offset(trx, try_),
            end = Offset(brx, bry),
            strokeWidth = 12f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.85f),
            start = Offset(trx, try_),
            end = Offset(brx, bry),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE,
            start = Offset(trx, try_),
            end = Offset(brx, bry),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Depth-Projected Particles
    // ---------------------------------------------------------------------------------------------

    private fun drawParticles(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.particles.count

        for (i in 0 until count) {
            val wy = world.particles.y[i]
            val px = scaler.worldLaneToScreenX(world.particles.x[i], wy)
            val py = scaler.worldLaneToScreenY(wy)
            val depthScale = scaler.scaleLaneDepth(wy)

            val lifeRatio = (1f - world.particles.life[i] / world.particles.maxLife[i]).coerceIn(0f, 1f)
            val argb = world.particles.color[i]
            val color = Color(argb).copy(alpha = lifeRatio)
            val pSize = (world.particles.size[i] * depthScale * lifeRatio).coerceAtLeast(1f)

            drawScope.drawCircle(
                color = color,
                radius = pSize,
                center = Offset(px, py)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Extruded 3D Neon Energy Shields
    // ---------------------------------------------------------------------------------------------

    private fun drawShieldSegments(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.shieldSegments.count
        val wallHeight = GameConfig.SHIELD_EXTRUSION_HEIGHT_WU

        for (i in 0 until count) {
            val sax = world.shieldSegments.ax[i]
            val say = world.shieldSegments.ay[i]
            val sbx = world.shieldSegments.bx[i]
            val sby = world.shieldSegments.by[i]

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

            // Chromatic transition based on structural integrity
            val integrityRatio = (integrity / GameConfig.SEGMENT_INTEGRITY).coerceIn(0f, 1f)
            val baseColor = when {
                integrityRatio > 0.60f -> Color(activeShieldSkin.primaryColorArgb)
                integrityRatio > 0.30f -> COLOR_MAGENTA
                else -> COLOR_AMBER
            }

            // Mid-depth perspective scale
            val midY = (say + sby) * 0.5f
            val depthScale = scaler.scaleLaneDepth(midY)

            // Ground projected points
            val gax = scaler.worldLaneToScreenX(sax, say)
            val gay = scaler.worldLaneToScreenY(say)
            val gbx = scaler.worldLaneToScreenX(sbx, sby)
            val gby = scaler.worldLaneToScreenY(sby)

            // Top elevated points (extruding upward along 3D height)
            val tax = scaler.worldLaneToScreenX(sax, say)
            val tay = scaler.worldLaneToScreenY(say, wallHeight)
            val tbx = scaler.worldLaneToScreenX(sbx, sby)
            val tby = scaler.worldLaneToScreenY(sby, wallHeight)

            // 1. Translucent Extruded Energy Barrier Quad Fill
            scratchPath.rewind()
            scratchPath.moveTo(gax, gay)
            scratchPath.lineTo(gbx, gby)
            scratchPath.lineTo(tbx, tby)
            scratchPath.lineTo(tax, tay)
            scratchPath.close()

            drawScope.drawPath(
                scratchPath,
                color = baseColor.copy(alpha = 0.22f * fadeAlpha),
                style = Fill
            )

            // 2. Ground Laser Foundation Line
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.35f * fadeAlpha),
                start = Offset(gax, gay),
                end = Offset(gbx, gby),
                strokeWidth = (2f * depthScale).coerceAtLeast(1f)
            )

            // 3. Vertical End Energy Pylons
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.50f * fadeAlpha),
                start = Offset(gax, gay),
                end = Offset(tax, tay),
                strokeWidth = (2.5f * depthScale).coerceAtLeast(1f)
            )
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.50f * fadeAlpha),
                start = Offset(gbx, gby),
                end = Offset(tbx, tby),
                strokeWidth = (2.5f * depthScale).coerceAtLeast(1f)
            )

            // 4. Top Laser Crest (Multi-Pass Neon Bloom)
            val topStart = Offset(tax, tay)
            val topEnd = Offset(tbx, tby)

            // Pass A: Wide Bloom Glow
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.16f * fadeAlpha),
                start = topStart,
                end = topEnd,
                strokeWidth = (strokeShieldGlow.width * depthScale).coerceAtLeast(4f),
                cap = StrokeCap.Round
            )
            // Pass B: Mid Neon Core
            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.70f * fadeAlpha),
                start = topStart,
                end = topEnd,
                strokeWidth = (strokeShieldMid.width * depthScale).coerceAtLeast(2f),
                cap = StrokeCap.Round
            )
            // Pass C: Hot Core (White when hit)
            val coreColor = if (flash > 0f) COLOR_WHITE else COLOR_WHITE.copy(alpha = 0.90f * fadeAlpha)
            drawScope.drawLine(
                color = coreColor,
                start = topStart,
                end = topEnd,
                strokeWidth = (strokeShieldCore.width * depthScale).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )

            // 5. Active Parry Boost Directional Chevron
            if (age <= GameConfig.PARRY_WINDOW) {
                val pvx = world.shieldSegments.swipeVx[i]
                val pvy = world.shieldSegments.swipeVy[i]
                val speedSq = pvx * pvx + pvy * pvy

                if (speedSq > 2500f) {
                    val mx = (tax + tbx) * 0.5f
                    val my = (tay + tby) * 0.5f
                    val heading = atan2(pvy, pvx)
                    val arrowLen = (18f * depthScale).coerceAtLeast(8f)

                    val tipX = mx + cos(heading) * arrowLen
                    val tipY = my + sin(heading) * arrowLen
                    val leftWingX = mx + cos(heading + 2.5f) * (arrowLen * 0.6f)
                    val leftWingY = my + sin(heading + 2.5f) * (arrowLen * 0.6f)
                    val rightWingX = mx + cos(heading - 2.5f) * (arrowLen * 0.6f)
                    val rightWingY = my + sin(heading - 2.5f) * (arrowLen * 0.6f)

                    scratchPath.rewind()
                    scratchPath.moveTo(leftWingX, leftWingY)
                    scratchPath.lineTo(tipX, tipY)
                    scratchPath.lineTo(rightWingX, rightWingY)

                    drawScope.drawPath(
                        scratchPath,
                        color = COLOR_WHITE.copy(alpha = (1f - age / GameConfig.PARRY_WINDOW)),
                        style = strokeCoreThin
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3D Defense Bubble Target
    // ---------------------------------------------------------------------------------------------

    private fun drawBubble(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val sbx = scaler.worldLaneToScreenX(world.bubbleX, world.bubbleY)
        val sby = scaler.worldLaneToScreenY(world.bubbleY)
        val sbr = scaler.scaleLaneDistance(world.bubbleRadius, world.bubbleY)
        val time = world.gameTime

        // 1. Ground Drop-Shadow on Highway Surface
        drawScope.drawOval(
            color = COLOR_SHADOW,
            topLeft = Offset(sbx - sbr * 0.90f, sby + sbr * 0.40f),
            size = Size(sbr * 1.8f, sbr * 0.45f)
        )

        // 2. Proximity Warning Ring
        val proximity = world.closestObstacleDistance
        if (proximity < 320f) {
            val proxRatio = (1f - proximity / 320f).coerceIn(0f, 1f)
            val pulse = 0.5f + 0.5f * sin(time * 16f)
            val warnAlpha = (0.25f + 0.50f * pulse) * proxRatio
            val warnRadius = sbr + scaler.scaleLaneDistance(proximity * 0.4f, world.bubbleY)

            drawScope.drawOval(
                color = COLOR_MAGENTA.copy(alpha = warnAlpha),
                topLeft = Offset(sbx - warnRadius, sby - warnRadius * 0.65f),
                size = Size(warnRadius * 2f, warnRadius * 1.30f),
                style = strokeRing
            )
        }

        // 3. Procedural 3D Soft-Body Harmonic Wobble (64 vertices)
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

            val theta = (i.toFloat() / BUBBLE_VERTICES) * (2f * PI.toFloat())
            val deformation = 1f +
                    a3 * sin(3f * theta + phi3) +
                    a5 * sin(5f * theta + phi5) +
                    a7 * sin(7f * theta + phi7)

            val r = sbr * deformation
            val vx = sbx + cosVal * r
            // Foreshorten vertical axis slightly for 3D sphere elevation
            val vy = sby + sinVal * r

            if (i == 0) bubblePath.moveTo(vx, vy) else bubblePath.lineTo(vx, vy)
        }
        bubblePath.close()

        val membraneColor = Color(activeBubbleSkin.membraneColorArgb)
        val auraColor = Color(activeBubbleSkin.auraColorArgb)

        val bubbleColor = if (world.bubbleInvulnTimer > 0f) {
            val flash = 0.5f + 0.5f * sin(time * 30f)
            COLOR_WHITE.copy(alpha = 0.25f * flash)
        } else {
            auraColor
        }

        // Pass A: Translucent Inner Fill
        drawScope.drawPath(bubblePath, color = bubbleColor, style = Fill)

        // Pass B: Outer Glow Ring
        drawScope.drawPath(
            bubblePath,
            color = membraneColor.copy(alpha = 0.22f),
            style = strokeGlowWide
        )

        // Pass C: Bright Neon Perimeter
        drawScope.drawPath(
            bubblePath,
            color = membraneColor,
            style = strokeCoreThin
        )

        // Pass D: 3D Directional Specular Highlight (Simulating top-left sun)
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.40f),
            radius = sbr * 0.20f,
            center = Offset(sbx - sbr * 0.35f, sby - sbr * 0.35f)
        )

        // 4. Inclined Diegetic Ink Ring (Tilted forward in perspective)
        val inkRatio = (world.ink / GameConfig.INK_MAX).coerceIn(0f, 1f)
        val ringRadiusX = sbr + 14f
        val ringRadiusY = ringRadiusX * 0.60f
        val arcTopLeft = Offset(sbx - ringRadiusX, sby - ringRadiusY)
        val arcSize = Size(ringRadiusX * 2f, ringRadiusY * 2f)

        val totalSweep = 240f
        val currentSweep = totalSweep * inkRatio
        val inkColor = if (inkRatio > 0.25f) COLOR_CYAN else COLOR_AMBER

        // Background track
        drawScope.drawArc(
            color = COLOR_CYAN.copy(alpha = 0.12f),
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
    // Depth-Sorted Procedural Obstacles (Far to Near)
    // ---------------------------------------------------------------------------------------------

    private fun drawObstacles(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.obstacles.count
        if (count == 0) return

        // 1. In-Place Insertion Sort of active indices by world Y ascending (far to near)
        for (i in 0 until count) sortIndices[i] = i
        for (i in 1 until count) {
            val key = sortIndices[i]
            val keyY = world.obstacles.y[key]
            var j = i - 1
            while (j >= 0 && world.obstacles.y[sortIndices[j]] > keyY) {
                sortIndices[j + 1] = sortIndices[j]
                j--
            }
            sortIndices[j + 1] = key
        }

        // 2. Render each obstacle in sorted order
        for (k in 0 until count) {
            val i = sortIndices[k]
            val wy = world.obstacles.y[i]
            val wx = world.obstacles.x[i]

            val ox = scaler.worldLaneToScreenX(wx, wy)
            val oy = scaler.worldLaneToScreenY(wy)
            val r = scaler.scaleLaneDistance(world.obstacles.radius[i], wy)
            val depthScale = scaler.scaleLaneDepth(wy)

            val type = world.obstacles.type[i]
            val vx = world.obstacles.vx[i]
            val vy = world.obstacles.vy[i]
            val angle = world.obstacles.angle[i]
            val flags = world.obstacles.flags[i]
            val isParried = (flags and GameConfig.FLAG_PARRIED) != 0

            // Ground Drop-Shadow on Highway
            drawScope.drawOval(
                color = COLOR_SHADOW.copy(alpha = (0.35f * depthScale).coerceIn(0.10f, 0.45f)),
                topLeft = Offset(ox - r * 0.85f, oy + r * 0.40f),
                size = Size(r * 1.7f, r * 0.45f)
            )

            when (type) {
                GameConfig.TYPE_DRIFTER -> drawDrifter3D(drawScope, ox, oy, r, depthScale, isParried)
                GameConfig.TYPE_NEEDLE -> drawNeedle3D(drawScope, ox, oy, r, vx, vy, depthScale, isParried)
                GameConfig.TYPE_CRUSHER -> drawCrusher3D(drawScope, ox, oy, r, angle, depthScale, isParried)
                GameConfig.TYPE_SPLITTER -> drawSplitter3D(drawScope, ox, oy, r, angle, depthScale, isParried)
                GameConfig.TYPE_SHARD -> drawShard3D(drawScope, ox, oy, r, angle, depthScale, isParried)
            }
        }
    }

    private fun drawDrifter3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        depthScale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_MAGENTA
        val center = Offset(cx, cy)

        // Outer glow
        drawScope.drawCircle(baseColor.copy(alpha = 0.18f), radius = r + 6f * depthScale, center = center)
        // Main body fill
        drawScope.drawCircle(baseColor.copy(alpha = 0.35f), radius = r, center = center, style = Fill)
        // Perimeter
        drawScope.drawCircle(baseColor, radius = r, center = center, style = strokeCoreThin)
        // 3D Specular Highlight
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.50f),
            radius = r * 0.25f,
            center = Offset(cx - r * 0.35f, cy - r * 0.35f)
        )
    }

    private fun drawNeedle3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        vx: Float,
        vy: Float,
        depthScale: Float,
        isParried: Boolean
    ) {
        val speedSq = vx * vx + vy * vy
        val heading = if (speedSq > 1f) atan2(vy, vx) else (PI.toFloat() * 0.5f)
        val length = r * 3.8f
        val halfBase = r * 1.3f
        val baseColor = if (isParried) COLOR_CYAN else COLOR_RED

        // Rear glowing jet trail extending toward horizon
        val tailX = cx - cos(heading) * (length * 1.5f)
        val tailY = cy - sin(heading) * (length * 1.5f)
        drawScope.drawLine(
            color = baseColor.copy(alpha = 0.35f),
            start = Offset(cx, cy),
            end = Offset(tailX, tailY),
            strokeWidth = (2.5f * depthScale).coerceAtLeast(1f)
        )

        // 3D Supersonic Arrowhead
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.25f), style = strokeGlowMid)
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.55f), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)
    }

    private fun drawCrusher3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_RED
        val sides = 6
        val step = (2.0 * PI / sides).toFloat()
        val extrudeH = 20f * depthScale

        // Ground Hexagon
        obstaclePath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) obstaclePath.moveTo(px, py) else obstaclePath.lineTo(px, py)
        }
        obstaclePath.close()

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.20f), style = Fill)

        // Top Elevated Hexagon
        scratchPath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = (cy - extrudeH) + sin(a) * r
            if (i == 0) scratchPath.moveTo(px, py) else scratchPath.lineTo(px, py)
        }
        scratchPath.close()

        // Vertical corner connecting edges
        for (i in 0 until sides) {
            val a = angle + i * step
            val gpx = cx + cos(a) * r
            val gpy = cy + sin(a) * r
            val tpx = cx + cos(a) * r
            val tpy = (cy - extrudeH) + sin(a) * r
            drawScope.drawLine(baseColor.copy(alpha = 0.40f), Offset(gpx, gpy), Offset(tpx, tpy), strokeWidth = 1.5f)
        }

        // Top Hex Face Outline & Fill
        drawScope.drawPath(scratchPath, color = baseColor.copy(alpha = 0.50f), style = Fill)
        drawScope.drawPath(scratchPath, color = baseColor, style = strokeShieldMid)
        drawScope.drawPath(scratchPath, color = COLOR_WHITE.copy(alpha = 0.70f), style = strokeWhiteCore)
    }

    private fun drawSplitter3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        isParried: Boolean
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_GREEN
        val sides = 4
        val step = (2.0 * PI / sides).toFloat()

        // Rotating 3D Diamond Octahedron
        obstaclePath.rewind()
        for (i in 0 until sides) {
            val a = angle + i * step
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) obstaclePath.moveTo(px, py) else obstaclePath.lineTo(px, py)
        }
        obstaclePath.close()

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.25f), style = strokeGlowMid)
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.45f), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)

        // Internal crystal facets
        val topVertex = Offset(cx, cy - r * 0.7f)
        val botVertex = Offset(cx, cy + r * 0.7f)
        drawScope.drawLine(COLOR_WHITE.copy(alpha = 0.50f), topVertex, botVertex, strokeWidth = 1.5f)
    }

    private fun drawShard3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.50f), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE, style = strokeWhiteCore)
    }

    // ---------------------------------------------------------------------------------------------
    // 3D Threat Telegraph Warning Indicators
    // ---------------------------------------------------------------------------------------------

    private fun drawTelegraphs(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.telegraphCount
        if (count == 0) return

        val horizonY = scaler.camera.vanishingPointY

        for (i in 0 until count) {
            val tx = world.telegraphX[i]
            val timer = world.telegraphTimer[i]
            val type = world.telegraphType[i]
            val lifeRatio = (timer / GameConfig.THREAT_TELEGRAPH_TIME).coerceIn(0f, 1f)

            // Rapid 24Hz flashing pulse
            val pulse = 0.5f + 0.5f * sin((GameConfig.THREAT_TELEGRAPH_TIME - timer) * 40f)
            val alpha = (0.4f + 0.6f * pulse) * (0.3f + 0.7f * lifeRatio)

            val color = when (type) {
                GameConfig.TYPE_NEEDLE -> COLOR_RED
                GameConfig.TYPE_SPLITTER -> COLOR_GREEN
                else -> COLOR_AMBER
            }

            // Screen X at horizon spawn point
            val sx = scaler.worldLaneToScreenX(tx, 0f)

            // 3D Guide laser extending down the lane from horizon
            val bottomLaserY = scaler.worldLaneToScreenY(600f)
            val bottomLaserX = scaler.worldLaneToScreenX(tx, 600f)

            drawScope.drawLine(
                color = color.copy(alpha = alpha * 0.35f),
                start = Offset(sx, horizonY),
                end = Offset(bottomLaserX, bottomLaserY),
                strokeWidth = 2f
            )

            // Hovering Threat Chevron at horizon
            val chevronSize = 14f + 4f * pulse
            scratchPath.rewind()
            scratchPath.moveTo(sx - chevronSize, horizonY - 4f)
            scratchPath.lineTo(sx, horizonY + chevronSize)
            scratchPath.lineTo(sx + chevronSize, horizonY - 4f)

            drawScope.drawPath(
                scratchPath,
                color = color.copy(alpha = alpha * 0.50f),
                style = strokeGlowMid
            )
            drawScope.drawPath(
                scratchPath,
                color = COLOR_WHITE.copy(alpha = alpha),
                style = strokeCoreThin
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3D Floating Combat Text
    // ---------------------------------------------------------------------------------------------

    private fun drawFloatingTexts(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val count = world.floatingTexts.count
        if (count == 0) return

        val canvas = drawScope.drawContext.canvas.nativeCanvas

        for (i in 0 until count) {
            val wy = world.floatingTexts.y[i]
            val sx = scaler.worldLaneToScreenX(world.floatingTexts.x[i], wy)
            val sy = scaler.worldLaneToScreenY(wy, heightWu = 40f)
            val depthScale = scaler.scaleLaneDepth(wy)

            val lifeRatio = (1f - world.floatingTexts.life[i] / world.floatingTexts.maxLife[i]).coerceIn(0f, 1f)
            val textScale = world.floatingTexts.scale[i]
            val textType = world.floatingTexts.textId[i].toInt().coerceIn(0, TEXT_STRINGS.size - 1)
            val label = TEXT_STRINGS[textType]

            val textSize = (20f * depthScale * textScale).coerceAtLeast(10f)
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
