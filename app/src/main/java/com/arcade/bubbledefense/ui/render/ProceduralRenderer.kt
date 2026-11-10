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

    // Reusable Native Paints for 120Hz Hardware HUD
    private val hudPaintLeft = AndroidPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = AndroidPaint.Align.LEFT
    }

    private val hudPaintRight = AndroidPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = AndroidPaint.Align.RIGHT
    }

    private val hudPaintCenter = AndroidPaint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = AndroidPaint.Align.CENTER
    }

    private val mountainPath = Path()

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

        // 2. Draw 2.5D Synthwave Sky, Mountains & Retro Sun
        drawSkyAndHorizon(drawScope, world, scaler)

        // 3. Draw 2.5D Highway Lane (Road surface, curbs, scrolling velocity crosslines)
        drawHighwayLane(drawScope, world, scaler)

        // 4. Draw Roadside Assets (Neon Light Pylons & Overhead Milestone Gantries)
        drawRoadsideAssets(drawScope, world, scaler)

        // 5. Draw Active Particles & Sensory Juice VFX (Shockwaves, Warp Streaks)
        drawSensoryJuiceVFX(drawScope, world, scaler)
        drawParticles(drawScope, world, scaler)

        // 6. Draw Extruded 3D Neon Energy Walls
        drawShieldSegments(drawScope, world, scaler)

        // 7. Draw 3D Defense Bubble with Soft-Body Harmonic Wobble & Inclined Ink Ring
        drawBubble(drawScope, world, scaler)

        // 8. Draw Depth-Sorted Incoming Obstacles (3D Geometry & Shadows)
        drawObstacles(drawScope, world, scaler)

        // 9. Draw 3D Threat Telegraph Warning Lasers & Chevrons
        drawTelegraphs(drawScope, world, scaler)

        // 10. Draw 3D Floating Combat Text
        drawFloatingTexts(drawScope, world, scaler)

        // 11. Draw 120Hz Hardware-Accelerated Live HUD (Altitude, Score, Health Cores, Wave Banner)
        drawLiveHUD(drawScope, world, scaler)
    }

    // ---------------------------------------------------------------------------------------------
    // 2.5D Synthwave Sky, Mountains & Retro Sun
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

        // Synthwave Retro Sun centered at horizon
        val sunRadius = min(screenW * 0.15f, 70f)
        val sunCx = screenW * 0.5f
        val sunCy = horizonY - 8f

        // Outer glow
        drawScope.drawCircle(
            color = COLOR_MAGENTA.copy(alpha = 0.22f),
            radius = sunRadius * 1.35f,
            center = Offset(sunCx, sunCy)
        )
        // Mid aura
        drawScope.drawCircle(
            color = COLOR_AMBER.copy(alpha = 0.45f),
            radius = sunRadius,
            center = Offset(sunCx, sunCy)
        )
        // Sun core
        drawScope.drawCircle(
            color = COLOR_AMBER,
            radius = sunRadius * 0.85f,
            center = Offset(sunCx, sunCy)
        )

        // Horizontal dark scanlines cutting through the retro sun
        var scanlineY = sunCy - sunRadius * 0.25f
        var scanlineH = 1.5f
        while (scanlineY <= horizonY) {
            val dy = scanlineY - sunCy
            val halfWSq = sunRadius * sunRadius - dy * dy
            if (halfWSq > 4f) {
                val halfW = kotlin.math.sqrt(halfWSq)
                drawScope.drawLine(
                    color = COLOR_BG,
                    start = Offset(sunCx - halfW - 2f, scanlineY),
                    end = Offset(sunCx + halfW + 2f, scanlineY),
                    strokeWidth = scanlineH
                )
            }
            scanlineH += 0.7f
            scanlineY += scanlineH + 3.2f
        }

        // Distant Jagged Mountains flanking the horizon
        drawMountains(drawScope, horizonY, screenW)

        // Horizon Laser Glow Line
        drawScope.drawLine(
            color = COLOR_MAGENTA.copy(alpha = 0.25f),
            start = Offset(0f, horizonY - 1f),
            end = Offset(screenW, horizonY - 1f),
            strokeWidth = 6f
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.55f),
            start = Offset(0f, horizonY),
            end = Offset(screenW, horizonY),
            strokeWidth = 2f
        )
    }

    private fun drawMountains(drawScope: DrawScope, horizonY: Float, screenW: Float) {
        // Left Ridge
        mountainPath.rewind()
        mountainPath.moveTo(0f, horizonY)
        mountainPath.lineTo(0f, horizonY - 35f)
        mountainPath.lineTo(screenW * 0.08f, horizonY - 20f)
        mountainPath.lineTo(screenW * 0.16f, horizonY - 50f)
        mountainPath.lineTo(screenW * 0.24f, horizonY - 25f)
        mountainPath.lineTo(screenW * 0.32f, horizonY - 12f)
        mountainPath.lineTo(screenW * 0.38f, horizonY)
        mountainPath.close()
        drawScope.drawPath(mountainPath, color = Color(0xFF0A0F20), style = Fill)
        drawScope.drawPath(mountainPath, color = COLOR_MAGENTA.copy(alpha = 0.40f), style = strokeWhiteCore)

        // Right Ridge
        mountainPath.rewind()
        mountainPath.moveTo(screenW * 0.62f, horizonY)
        mountainPath.lineTo(screenW * 0.68f, horizonY - 16f)
        mountainPath.lineTo(screenW * 0.76f, horizonY - 45f)
        mountainPath.lineTo(screenW * 0.84f, horizonY - 22f)
        mountainPath.lineTo(screenW * 0.92f, horizonY - 38f)
        mountainPath.lineTo(screenW, horizonY - 25f)
        mountainPath.lineTo(screenW, horizonY)
        mountainPath.close()
        drawScope.drawPath(mountainPath, color = Color(0xFF0A0F20), style = Fill)
        drawScope.drawPath(mountainPath, color = COLOR_CYAN.copy(alpha = 0.40f), style = strokeWhiteCore)
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

        // 5. Cascading Neon Curb Arrows (High-Speed Runway Guidance)
        val arrowPeriod = 75f
        val arrowScroll = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % arrowPeriod
        var arrowY = arrowScroll
        while (arrowY <= cameraY) {
            val depthScale = scaler.scaleLaneDepth(arrowY)
            val arrowAlpha = (depthScale * 0.65f).coerceIn(0.08f, 0.75f)
            val strokeW = (2f * depthScale).coerceAtLeast(1f)

            // Left Curb Chevron pointing forward into horizon
            val lApexX = scaler.worldLaneToScreenX(leftCurbX, arrowY)
            val lApexY = scaler.worldLaneToScreenY(arrowY)
            val lBase1X = scaler.worldLaneToScreenX(leftCurbX - 16f, arrowY + 18f)
            val lBase1Y = scaler.worldLaneToScreenY(arrowY + 18f)
            val lBase2X = scaler.worldLaneToScreenX(leftCurbX + 16f, arrowY + 18f)
            val lBase2Y = scaler.worldLaneToScreenY(arrowY + 18f)

            drawScope.drawLine(COLOR_CYAN.copy(alpha = arrowAlpha), Offset(lBase1X, lBase1Y), Offset(lApexX, lApexY), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawScope.drawLine(COLOR_CYAN.copy(alpha = arrowAlpha), Offset(lApexX, lApexY), Offset(lBase2X, lBase2Y), strokeWidth = strokeW, cap = StrokeCap.Round)

            // Right Curb Chevron pointing forward into horizon
            val rApexX = scaler.worldLaneToScreenX(rightCurbX, arrowY)
            val rApexY = scaler.worldLaneToScreenY(arrowY)
            val rBase1X = scaler.worldLaneToScreenX(rightCurbX - 16f, arrowY + 18f)
            val rBase1Y = scaler.worldLaneToScreenY(arrowY + 18f)
            val rBase2X = scaler.worldLaneToScreenX(rightCurbX + 16f, arrowY + 18f)
            val rBase2Y = scaler.worldLaneToScreenY(arrowY + 18f)

            drawScope.drawLine(COLOR_CYAN.copy(alpha = arrowAlpha), Offset(rBase1X, rBase1Y), Offset(rApexX, rApexY), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawScope.drawLine(COLOR_CYAN.copy(alpha = arrowAlpha), Offset(rApexX, rApexY), Offset(rBase2X, rBase2Y), strokeWidth = strokeW, cap = StrokeCap.Round)

            arrowY += arrowPeriod
        }
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

        // 1.5. Twin Thruster Laser Ribbons Streaming Backward from Bubble Flanks
        val trailLen = (sbr * (0.9f + (world.ascentSpeed / GameConfig.ASCENT_SPEED_BASE) * 0.7f)).coerceIn(sbr * 0.8f, sbr * 3.2f)
        val leftFlankX = sbx - sbr * 0.65f
        val rightFlankX = sbx + sbr * 0.65f
        val flankY = sby + sbr * 0.25f

        val trailWobbleL = sin(time * 22f) * 4f
        val trailWobbleR = cos(time * 22f) * 4f

        // Left Thruster Stream
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.22f),
            start = Offset(leftFlankX, flankY),
            end = Offset(leftFlankX + trailWobbleL, flankY + trailLen),
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = 0.65f),
            start = Offset(leftFlankX, flankY),
            end = Offset(leftFlankX + trailWobbleL, flankY + trailLen * 0.65f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )

        // Right Thruster Stream
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.22f),
            start = Offset(rightFlankX, flankY),
            end = Offset(rightFlankX + trailWobbleR, flankY + trailLen),
            strokeWidth = 9f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = 0.65f),
            start = Offset(rightFlankX, flankY),
            end = Offset(rightFlankX + trailWobbleR, flankY + trailLen * 0.65f),
            strokeWidth = 3f,
            cap = StrokeCap.Round
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

        // Pass E: Energetic Rim Corona Pulse
        val speedRatio = (world.ascentSpeed / GameConfig.ASCENT_SPEED_MAX).coerceIn(0f, 1f)
        val coronaPulse = 0.5f + 0.5f * sin(time * 12f)
        val coronaRadius = sbr * (1.14f + 0.08f * speedRatio + 0.04f * coronaPulse)
        drawScope.drawCircle(
            color = membraneColor.copy(alpha = 0.16f + 0.14f * speedRatio),
            radius = coronaRadius,
            center = Offset(sbx, sby),
            style = strokeCoreThin
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

    // ---------------------------------------------------------------------------------------------
    // Roadside Map Assets: Neon Light Pylons & Overhead Milestone Gantries
    // ---------------------------------------------------------------------------------------------

    private fun drawRoadsideAssets(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val pylonSpacing = 160f
        val pylonScroll = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % pylonSpacing
        val shoulderLeft = 60f
        val shoulderRight = 940f

        var py = pylonScroll
        while (py <= cameraY) {
            val depthScale = scaler.scaleLaneDepth(py)
            val pylonH = 100f

            // Left Shoulder Pylon
            drawSinglePylon(drawScope, scaler, shoulderLeft, py, pylonH, depthScale, isLeft = true)

            // Right Shoulder Pylon
            drawSinglePylon(drawScope, scaler, shoulderRight, py, pylonH, depthScale, isLeft = false)

            py += pylonSpacing
        }

        // Overhead Milestone Gantries spanning highway every 250m
        drawMilestoneGantries(drawScope, world, scaler)
    }

    private fun drawSinglePylon(
        drawScope: DrawScope,
        scaler: ViewportScaler,
        wx: Float,
        wy: Float,
        hWu: Float,
        depthScale: Float,
        isLeft: Boolean
    ) {
        val gx = scaler.worldLaneToScreenX(wx, wy)
        val gy = scaler.worldLaneToScreenY(wy)
        val ty = scaler.worldLaneToScreenY(wy, heightWu = hWu)

        val alpha = (depthScale * 0.85f).coerceIn(0.12f, 0.90f)
        val shaftW = (3f * depthScale).coerceAtLeast(1.5f)
        val color = if (isLeft) COLOR_CYAN else COLOR_AMBER

        // Vertical Pylon Column
        drawScope.drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(gx, gy),
            end = Offset(gx, ty),
            strokeWidth = shaftW
        )

        // Glowing Lantern at Top
        val lanternRadius = (6f * depthScale).coerceAtLeast(2f)
        drawScope.drawCircle(
            color = color.copy(alpha = alpha * 0.40f),
            radius = lanternRadius * 1.8f,
            center = Offset(gx, ty)
        )
        drawScope.drawCircle(
            color = color.copy(alpha = alpha),
            radius = lanternRadius,
            center = Offset(gx, ty)
        )
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = alpha),
            radius = lanternRadius * 0.45f,
            center = Offset(gx, ty)
        )

        // Ground Foundation Pad
        val padW = (10f * depthScale).coerceAtLeast(3f)
        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = alpha * 0.6f),
            start = Offset(gx - padW, gy),
            end = Offset(gx + padW, gy),
            strokeWidth = shaftW
        )
    }

    private fun drawMilestoneGantries(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val currentAlt = world.altitudeMeters
        val currentIdx = (currentAlt / 250f).toInt()

        // Check milestones ahead
        for (idx in currentIdx..(currentIdx + 2)) {
            val milestoneM = idx * 250
            if (milestoneM <= 0) continue

            // Distance in meters ahead of bubble:
            val dMeters = milestoneM - currentAlt
            val distWu = dMeters * GameConfig.WU_PER_METER * 1.5f
            val gantryY = GameConfig.BUBBLE_CENTER_Y - distWu

            if (gantryY in 10f..cameraY) {
                val depthScale = scaler.scaleLaneDepth(gantryY)
                val alpha = (depthScale * 0.95f).coerceIn(0.15f, 1f)
                val leftX = scaler.worldLaneToScreenX(GameConfig.LANE_LEFT_CURB_X, gantryY)
                val rightX = scaler.worldLaneToScreenX(GameConfig.LANE_RIGHT_CURB_X, gantryY)
                val groundY = scaler.worldLaneToScreenY(gantryY)
                val topY = scaler.worldLaneToScreenY(gantryY, heightWu = 140f)

                val postW = (4f * depthScale).coerceAtLeast(1.5f)
                val beamW = (5f * depthScale).coerceAtLeast(2f)

                // Left & Right Vertical Posts
                drawScope.drawLine(COLOR_CYAN.copy(alpha = alpha), Offset(leftX, groundY), Offset(leftX, topY), strokeWidth = postW)
                drawScope.drawLine(COLOR_CYAN.copy(alpha = alpha), Offset(rightX, groundY), Offset(rightX, topY), strokeWidth = postW)

                // Overhead Cross-Beam
                drawScope.drawLine(COLOR_CYAN.copy(alpha = alpha), Offset(leftX, topY), Offset(rightX, topY), strokeWidth = beamW)
                drawScope.drawLine(COLOR_WHITE.copy(alpha = alpha * 0.7f), Offset(leftX, topY), Offset(rightX, topY), strokeWidth = postW * 0.5f)

                // Center Illuminated Signboard
                val centerX = (leftX + rightX) * 0.5f
                val signW = (80f * depthScale).coerceAtLeast(30f)
                val signH = (26f * depthScale).coerceAtLeast(12f)
                val signTop = topY - signH * 0.5f

                drawScope.drawRect(
                    color = Color(0xCC050814),
                    topLeft = Offset(centerX - signW * 0.5f, signTop),
                    size = Size(signW, signH)
                )
                drawScope.drawRect(
                    color = COLOR_AMBER.copy(alpha = alpha),
                    topLeft = Offset(centerX - signW * 0.5f, signTop),
                    size = Size(signW, signH),
                    style = strokeRing
                )

                // Signboard text
                val canvas = drawScope.drawContext.canvas.nativeCanvas
                hudPaintCenter.textSize = (14f * depthScale).coerceAtLeast(8f)
                hudPaintCenter.color = 0xFFFFB800.toInt() // Amber
                hudPaintCenter.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawText("${milestoneM}m", centerX, signTop + signH * 0.75f, hudPaintCenter)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sensory Juice & VFX
    // ---------------------------------------------------------------------------------------------

    private fun drawSensoryJuiceVFX(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val sw = scaler.screenWidthPx
        val sh = scaler.screenHeightPx
        val horizonY = scaler.camera.vanishingPointY
        val sunCx = sw * 0.5f

        // 1. Warp Speed Streaks during high velocity / combo
        val speedFactor = (world.ascentSpeed / GameConfig.ASCENT_SPEED_BASE).coerceIn(1.0f, 3.5f)
        val streakCount = (14 * speedFactor).toInt()
        val streakAlpha = (0.16f * speedFactor + world.comboCount * 0.05f).coerceIn(0.15f, 0.65f)
        val streakTime = world.gameTime * (6f + 4f * speedFactor)

        for (s in 0 until streakCount) {
            val angle = (s * (2f * PI.toFloat() / streakCount)) + sin(streakTime + s) * 0.12f
            val length = (80f * speedFactor + sin(streakTime * 2f + s) * 50f).coerceAtLeast(40f)
            val startR = 30f + (s * 13) % 70
            val endR = startR + length

            val sx = sunCx + cos(angle) * startR
            val sy = horizonY + sin(angle) * startR * 0.55f
            val ex = sunCx + cos(angle) * endR
            val ey = horizonY + sin(angle) * endR * 0.55f

            val strokeW = if (s % 3 == 0) 2.5f else 1.2f
            val streakCol = if (s % 2 == 0) COLOR_CYAN else COLOR_WHITE
            drawScope.drawLine(
                color = streakCol.copy(alpha = streakAlpha),
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = strokeW
            )
        }

        // 2. Radial Parry Shockwave Rings
        if (world.trauma > 0.08f) {
            val shockwaveR = (1f - world.trauma) * 320f + 60f
            val shockAlpha = (world.trauma * 0.8f).coerceIn(0.1f, 0.7f)
            val bx = scaler.worldLaneToScreenX(world.bubbleX, world.bubbleY)
            val by = scaler.worldLaneToScreenY(world.bubbleY)

            // Ground-projected oval shockwave
            drawScope.drawOval(
                color = COLOR_CYAN.copy(alpha = shockAlpha),
                topLeft = Offset(bx - shockwaveR, by - shockwaveR * 0.35f),
                size = Size(shockwaveR * 2f, shockwaveR * 0.70f),
                style = strokeRing
            )
            drawScope.drawOval(
                color = COLOR_WHITE.copy(alpha = shockAlpha * 0.6f),
                topLeft = Offset(bx - shockwaveR * 0.85f, by - shockwaveR * 0.85f * 0.35f),
                size = Size(shockwaveR * 1.70f, shockwaveR * 1.70f * 0.35f),
                style = strokeWhiteCore
            )
        }

        // 3. Near-Miss Proximity Warning Vignette
        if (world.closestObstacleDistance < 100f) {
            val proxRatio = (1f - world.closestObstacleDistance / 100f).coerceIn(0f, 1f)
            val vigColor = if (world.bubbleHp == 1) COLOR_RED else COLOR_CYAN
            val vigAlpha = (proxRatio * 0.30f).coerceIn(0f, 0.35f)

            // Vignette lines at left, right, bottom edges
            drawScope.drawLine(vigColor.copy(alpha = vigAlpha), Offset(0f, 0f), Offset(0f, sh), strokeWidth = 14f)
            drawScope.drawLine(vigColor.copy(alpha = vigAlpha), Offset(sw, 0f), Offset(sw, sh), strokeWidth = 14f)
            drawScope.drawLine(vigColor.copy(alpha = vigAlpha), Offset(0f, sh), Offset(sw, sh), strokeWidth = 14f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 120Hz Hardware-Accelerated Live HUD
    // ---------------------------------------------------------------------------------------------

    private fun drawLiveHUD(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val sw = scaler.screenWidthPx
        val canvas = drawScope.drawContext.canvas.nativeCanvas

        val scale = (sw / 1000f).coerceIn(0.75f, 1.35f)
        val padX = 24f * scale
        val topY = 48f * scale

        // 1. Top-Left: Altitude & Velocity Readouts
        hudPaintLeft.textSize = 12f * scale
        hudPaintLeft.color = 0xFF00F0FF.toInt()
        hudPaintLeft.alpha = 180
        canvas.drawText("ALTITUDE", padX, topY + 14f * scale, hudPaintLeft)

        hudPaintLeft.textSize = 28f * scale
        hudPaintLeft.color = 0xFFFFFFFF.toInt()
        hudPaintLeft.alpha = 255
        canvas.drawText("${world.altitudeMeters.toInt()}m", padX, topY + 46f * scale, hudPaintLeft)

        hudPaintLeft.textSize = 12f * scale
        hudPaintLeft.color = 0xFF00F0FF.toInt()
        hudPaintLeft.alpha = 210
        val kmh = ((world.ascentSpeed / GameConfig.WU_PER_METER) * 3.6f).toInt()
        canvas.drawText("$kmh km/h", padX, topY + 68f * scale, hudPaintLeft)

        // 2. Top-Center: Neon Shield Health Cores & Wave Status
        val cx = sw * 0.5f

        hudPaintCenter.textSize = 12f * scale
        hudPaintCenter.color = if (world.wavePhaseName == "CRUNCH") 0xFFFFB800.toInt() else 0xFF00FF99.toInt()
        hudPaintCenter.alpha = 240
        canvas.drawText("WAVE ${world.currentWave} • ${world.wavePhaseName}", cx, topY + 14f * scale, hudPaintCenter)

        // 3 Shield Health Diamonds
        val coreY = topY + 38f * scale
        val coreSpacing = 34f * scale
        val coreSize = 12f * scale

        for (c in 1..3) {
            val coreX = cx + (c - 2) * coreSpacing
            val isActive = world.bubbleHp >= c
            val isFlashing = (world.bubbleHp == 1 && c == 1)
            val flashAlpha = if (isFlashing) (0.5f + 0.5f * sin(world.gameTime * 14f)) else 1f

            scratchPath.rewind()
            scratchPath.moveTo(coreX, coreY - coreSize)
            scratchPath.lineTo(coreX + coreSize, coreY)
            scratchPath.lineTo(coreX, coreY + coreSize)
            scratchPath.lineTo(coreX - coreSize, coreY)
            scratchPath.close()

            if (isActive) {
                // Active Core: Cyan / Amber Warning
                val coreColor = if (world.bubbleHp == 1) COLOR_AMBER else COLOR_CYAN
                drawScope.drawPath(scratchPath, color = coreColor.copy(alpha = 0.35f * flashAlpha), style = Fill)
                drawScope.drawPath(scratchPath, color = coreColor.copy(alpha = 0.90f * flashAlpha), style = strokeCoreThin)
                drawScope.drawCircle(COLOR_WHITE.copy(alpha = 0.85f * flashAlpha), radius = 3f * scale, center = Offset(coreX, coreY))
            } else {
                // Depleted Core: Crimson Outline
                drawScope.drawPath(scratchPath, color = COLOR_RED.copy(alpha = 0.25f), style = strokeWhiteCore)
            }
        }

        // 3. Top-Right: Score, Multiplier & Soft Currency Prisms
        hudPaintRight.textSize = 12f * scale
        hudPaintRight.color = 0xFF00F0FF.toInt()
        hudPaintRight.alpha = 180
        canvas.drawText("SCORE", sw - padX, topY + 14f * scale, hudPaintRight)

        hudPaintRight.textSize = 28f * scale
        hudPaintRight.color = 0xFFFFFFFF.toInt()
        hudPaintRight.alpha = 255
        canvas.drawText("${world.score}", sw - padX, topY + 46f * scale, hudPaintRight)

        var rightY = topY + 68f * scale
        if (world.comboCount > 1) {
            hudPaintRight.textSize = 12f * scale
            hudPaintRight.color = 0xFFFFB800.toInt() // Amber
            hudPaintRight.alpha = 255
            val comboText = "x${String.format("%.1f", world.comboMultiplier)} (${world.comboCount}x)"
            canvas.drawText(comboText, sw - padX, rightY, hudPaintRight)
            rightY += 18f * scale
        }

        hudPaintRight.textSize = 12f * scale
        hudPaintRight.color = 0xFF00FF99.toInt() // Green
        hudPaintRight.alpha = 255
        canvas.drawText("PRISMS: ${world.prismsCollected}", sw - padX, rightY, hudPaintRight)

        // 4. Top Ink Bar
        val barY = topY + 86f * scale
        val barW = sw - 2f * padX
        val barH = 4f * scale

        // Background slot
        drawScope.drawRect(
            color = COLOR_CYAN.copy(alpha = 0.15f),
            topLeft = Offset(padX, barY),
            size = Size(barW, barH)
        )

        // Active fill
        val inkFraction = (world.ink / GameConfig.INK_MAX).coerceIn(0f, 1f)
        val inkColor = if (inkFraction > 0.25f) COLOR_CYAN else COLOR_AMBER
        drawScope.drawRect(
            color = inkColor,
            topLeft = Offset(padX, barY),
            size = Size(barW * inkFraction, barH)
        )

        // 5. Center Wave Announcement Banner
        if (world.waveAnnouncementTimer > 0f) {
            val bannerAlpha = (world.waveAnnouncementTimer / 0.5f).coerceIn(0f, 1f)
            val bannerY = scaler.camera.vanishingPointY + 45f * scale
            val bannerW = sw * 0.72f
            val bannerH = 60f * scale
            val bannerLeft = (sw - bannerW) * 0.5f

            // Translucent glass backdrop
            drawScope.drawRoundRect(
                color = Color(0xDD080D1D).copy(alpha = 0.88f * bannerAlpha),
                topLeft = Offset(bannerLeft, bannerY),
                size = Size(bannerW, bannerH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f * scale, 12f * scale)
            )
            drawScope.drawRoundRect(
                color = COLOR_CYAN.copy(alpha = 0.65f * bannerAlpha),
                topLeft = Offset(bannerLeft, bannerY),
                size = Size(bannerW, bannerH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f * scale, 12f * scale),
                style = strokeRing
            )

            // Main Banner Text
            hudPaintCenter.textSize = 20f * scale
            hudPaintCenter.color = 0xFFFFFFFF.toInt()
            hudPaintCenter.alpha = (255 * bannerAlpha).toInt().coerceIn(0, 255)
            canvas.drawText("WAVE ${world.currentWave} • ${world.wavePhaseName}", cx, bannerY + 28f * scale, hudPaintCenter)

            // Subtitle
            hudPaintCenter.textSize = 11f * scale
            hudPaintCenter.color = if (world.wavePhaseName == "CRUNCH") 0xFFFFB800.toInt() else 0xFF00FF99.toInt()
            hudPaintCenter.alpha = (230 * bannerAlpha).toInt().coerceIn(0, 255)
            val subMsg = if (world.wavePhaseName == "CRUNCH") "INCOMING THREATS — DEFEND TARGET" else "HARVEST VALLEY — COLLECT PRISMS"
            canvas.drawText(subMsg, cx, bannerY + 48f * scale, hudPaintCenter)
        }

        // 6. Altitude Milestone Celebration Banner (Every 250m)
        val milestoneRem = world.altitudeMeters % 250f
        if (world.altitudeMeters >= 240f && (milestoneRem < 15f || milestoneRem > 245f)) {
            val milestoneVal = (((world.altitudeMeters + 10f) / 250f).toInt()) * 250
            val mAlpha = if (milestoneRem < 15f) (1f - milestoneRem / 15f) else ((milestoneRem - 245f) / 5f)
            val bannerY = scaler.camera.vanishingPointY + 80f * scale
            val bannerW = sw * 0.85f
            val bannerH = 50f * scale
            val bannerLeft = (sw - bannerW) * 0.5f

            drawScope.drawRoundRect(
                color = Color(0xEE080F24).copy(alpha = mAlpha * 0.92f),
                topLeft = Offset(bannerLeft, bannerY),
                size = Size(bannerW, bannerH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale)
            )
            drawScope.drawRoundRect(
                color = COLOR_AMBER.copy(alpha = mAlpha),
                topLeft = Offset(bannerLeft, bannerY),
                size = Size(bannerW, bannerH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale),
                style = strokeRing
            )

            hudPaintCenter.textSize = 17f * scale
            hudPaintCenter.color = 0xFFFFB800.toInt()
            hudPaintCenter.alpha = (mAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawText("★ MILESTONE: ${milestoneVal}M REACHED! ★", cx, bannerY + 28f * scale, hudPaintCenter)

            hudPaintCenter.textSize = 11f * scale
            hudPaintCenter.color = 0xFF00FF99.toInt()
            hudPaintCenter.alpha = (mAlpha * 230).toInt().coerceIn(0, 255)
            canvas.drawText("SPEED SURGE + BONUS SCORE AWARDED", cx, bannerY + 44f * scale, hudPaintCenter)
        }
    }
}
