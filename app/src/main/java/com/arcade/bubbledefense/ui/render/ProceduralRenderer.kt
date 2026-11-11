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
import com.arcade.bubbledefense.domain.systems.DroneSystem
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

        // Eerie Deep Space Void Palette
        val COLOR_BG = Color(0xFF020306)              // Pure abyssal void
        val COLOR_LANE_BG = Color(0xFF03050C)         // Deep gravitational slipstream
        val COLOR_CYAN = Color(0xFF00F0FF)            // Fragile bioluminescence / laser core
        val COLOR_PHANTOM_CYAN = Color(0xFF00A2B3)    // Subdued cold phantom light
        val COLOR_MAGENTA = Color(0xFFFF0055)         // Threat highlight
        val COLOR_VIOLET = Color(0xFF7000FF)          // Cold cosmic void ultraviolet
        val COLOR_DARK_VIOLET = Color(0xFF240046)     // Deep accretion dust
        val COLOR_AMBER = Color(0xFFFFB800)           // Dying star glow / warning
        val COLOR_RED = Color(0xFFFF2200)             // Low health danger
        val COLOR_GREEN = Color(0xFF00FF99)           // Soft currency prism
        val COLOR_WHITE = Color(0xFFFFFFFF)           // Specular highlight / star core
        val COLOR_OBSIDIAN = Color(0xFF080C14)        // Ancient monolithic matter
        val COLOR_SHADOW = Color(0x99000000)          // Deep void occlusion shadow

        // Pre-allocated text strings
        private val TEXT_STRINGS = arrayOf(
            "PARRY!",       // 0: TEXT_PARRY
            "+5",           // 1: TEXT_PLUS_5
            "+10",          // 2: TEXT_PLUS_10
            "+50",          // 3: TEXT_PLUS_50
            "+100",         // 4: TEXT_PLUS_100
            "SPLIT!",       // 5: TEXT_SPLIT
            "CRUSH!",       // 6: TEXT_CRUSH
            "CLOSE CALL!",  // 7: TEXT_CLOSE_CALL
            "+5 PRISMS!"    // 8: TEXT_BOUNTY
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
    private val nebulaPath = Path()
    private val monolithPath = Path()
    private val dronePath = Path()

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
        alpha: Float,
        droneSystem: DroneSystem? = null
    ) {
        // 1. Draw Deep Cyberpunk Void Background
        drawScope.drawRect(COLOR_BG)

        // 2. Draw Deep Space Cosmic Void, Nebulae & Event Horizon Singularity
        drawSkyAndHorizon(drawScope, world, scaler)

        // 3. Draw Gravitational Void Slipstream (Translucent corridor, spacetime filaments, spectral containment)
        drawVoidSlipstream(drawScope, world, scaler)

        // 4. Draw Silent Void Relics (Monoliths & Suspended Spatial Warp Rings)
        drawRoadsideAssets(drawScope, world, scaler)

        // 5. Draw Active Particles & Sensory Juice VFX (Shockwaves, Warp Streaks, Space Dust, Abyssal Vignette)
        drawSensoryJuiceVFX(drawScope, world, scaler)
        drawParticles(drawScope, world, scaler)

        // 6. Draw Extruded 3D Neon Energy Walls
        drawShieldSegments(drawScope, world, scaler)

        // 7. Draw 3D Defense Bubble with Bioluminescent Local Light Oasis, Wobble & Ink Ring
        drawBubble(drawScope, world, scaler)

        // 8. Draw Depth-Sorted Incoming Obstacles with Atmospheric Void Fog
        drawObstacles(drawScope, world, scaler)

        // 8b. Draw Sideline Sentinel Drones & Interceptor Beams
        if (droneSystem != null && world.droneLevel > 0) {
            drawDrones(drawScope, world, scaler, droneSystem)
        }

        // 8c. Draw Stored Special Powers Active VFX (Chrono Warp, Supernova EMP, Plasma Lance)
        drawSpecialPowersVFX(drawScope, world, scaler)

        // 9. Draw 3D Threat Telegraph Warning Lasers & Chevrons
        drawTelegraphs(drawScope, world, scaler)

        // 10. Draw 3D Floating Combat Text
        drawFloatingTexts(drawScope, world, scaler)

        // 11. Draw 120Hz Hardware-Accelerated Live HUD (Altitude, Score, Health Cores, Wave Banner)
        drawLiveHUD(drawScope, world, scaler)
    }

    // ---------------------------------------------------------------------------------------------
    // Deep Space Cosmic Void, Nebulae & Event Horizon Singularity
    // ---------------------------------------------------------------------------------------------

    private fun drawSkyAndHorizon(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val horizonY = scaler.camera.vanishingPointY
        val screenW = scaler.screenWidthPx
        val screenH = scaler.screenHeightPx
        val time = world.gameTime

        // 1. Distant Organic Cosmic Nebulae (Floating in deep space void)
        drawDeepSpaceNebulae(drawScope, horizonY, screenW, time)

        // 2. Multi-Layer Deep Space Starfield (Cold, Twinkling)
        val starCount = 48
        val scrollX = (world.altitudeMeters * 0.8f) % screenW

        for (i in 0 until starCount) {
            val seedX = ((i * 53 + 17) % screenW.toInt()).toFloat()
            // Distribute stars both above horizon and in peripheral abyss
            val seedY = ((i * 37 + 11) % (screenH * 0.65f).toInt()).toFloat()
            val sx = (seedX + scrollX) % screenW

            // Cold harmonic twinkle
            val twinkle = 0.5f + 0.5f * sin(time * 2.4f + i * 1.6f)
            val brightness = (0.15f + ((i % 5) * 0.12f) + twinkle * 0.28f).coerceIn(0.10f, 0.90f)
            val radius = if (i % 8 == 0) 1.8f else if (i % 3 == 0) 1.2f else 0.8f

            val starColor = when (i % 4) {
                0 -> COLOR_WHITE
                1 -> COLOR_CYAN
                2 -> COLOR_VIOLET
                else -> COLOR_WHITE
            }

            drawScope.drawCircle(
                color = starColor.copy(alpha = brightness),
                radius = radius,
                center = Offset(sx, seedY)
            )

            // Micro diffraction spike for prominent stars
            if (i % 12 == 0 && brightness > 0.6f) {
                val spikeLen = 5f * brightness
                drawScope.drawLine(
                    color = starColor.copy(alpha = brightness * 0.45f),
                    start = Offset(sx - spikeLen, seedY),
                    end = Offset(sx + spikeLen, seedY),
                    strokeWidth = 1f
                )
                drawScope.drawLine(
                    color = starColor.copy(alpha = brightness * 0.45f),
                    start = Offset(sx, seedY - spikeLen),
                    end = Offset(sx, seedY + spikeLen),
                    strokeWidth = 1f
                )
            }
        }

        // 3. The Event Horizon Singularity (The Eye of the Void)
        val singularityRadius = min(screenW * 0.12f, 52f)
        val sunCx = screenW * 0.5f
        val sunCy = horizonY - 4f

        // Cosmic harmonic breathing (0.15 Hz - slow, terrifying pulse of the abyss)
        val breathe = 0.5f + 0.5f * sin(time * 0.94f)

        // Relativistic Polar Jet (Hawking Radiation beam erupting straight into upper deep space)
        drawScope.drawLine(
            color = COLOR_VIOLET.copy(alpha = 0.20f + 0.08f * breathe),
            start = Offset(sunCx, sunCy - singularityRadius),
            end = Offset(sunCx, 0f),
            strokeWidth = 6f
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.40f + 0.15f * breathe),
            start = Offset(sunCx, sunCy - singularityRadius),
            end = Offset(sunCx, horizonY * 0.35f),
            strokeWidth = 1.5f
        )

        // Gravitational Lensing Outer Halo (Einstein Ring)
        val haloRadius = singularityRadius * (1.60f + 0.10f * breathe)
        drawScope.drawCircle(
            color = COLOR_VIOLET.copy(alpha = 0.16f + 0.08f * breathe),
            radius = haloRadius,
            center = Offset(sunCx, sunCy)
        )
        drawScope.drawCircle(
            color = COLOR_CYAN.copy(alpha = 0.30f),
            radius = singularityRadius * 1.12f,
            center = Offset(sunCx, sunCy),
            style = strokeCoreThin
        )

        // Swirling Cosmic Accretion Disk (Perspective Ellipse orbiting the black hole)
        val diskW = singularityRadius * 2.8f
        val diskH = singularityRadius * 0.75f

        drawScope.drawOval(
            color = COLOR_DARK_VIOLET.copy(alpha = 0.35f),
            topLeft = Offset(sunCx - diskW * 0.5f, sunCy - diskH * 0.5f),
            size = Size(diskW, diskH),
            style = strokeGlowWide
        )
        drawScope.drawOval(
            color = COLOR_VIOLET.copy(alpha = 0.50f),
            topLeft = Offset(sunCx - diskW * 0.45f, sunCy - diskH * 0.45f),
            size = Size(diskW * 0.9f, diskH * 0.9f),
            style = strokeRing
        )
        drawScope.drawOval(
            color = COLOR_CYAN.copy(alpha = 0.35f),
            topLeft = Offset(sunCx - diskW * 0.35f, sunCy - diskH * 0.35f),
            size = Size(diskW * 0.7f, diskH * 0.7f),
            style = strokeWhiteCore
        )

        // Pitch-Black Event Horizon Core (Total light absorption)
        drawScope.drawCircle(
            color = Color(0xFF000000),
            radius = singularityRadius,
            center = Offset(sunCx, sunCy)
        )

        // Event Horizon Perimeter Glint
        drawScope.drawCircle(
            color = COLOR_VIOLET.copy(alpha = 0.70f),
            radius = singularityRadius,
            center = Offset(sunCx, sunCy),
            style = strokeWhiteCore
        )

        // Solar Flare Coronal Mass Ejection Prominence Arches
        if (world.solarFlareState > 0 || world.solarFlareIntensity > 0f) {
            val flareIntensity = if (world.solarFlareState == 1) world.solarFlareIntensity else 0.85f
            val flareColor = Color(0xFFFF4400)

            // Expanding coronal blast shockwave
            val blastR = singularityRadius * (1.6f + 2.0f * (1f - (time * 1.5f % 1f)))
            drawScope.drawCircle(
                color = flareColor.copy(alpha = 0.35f * flareIntensity),
                radius = blastR,
                center = Offset(sunCx, sunCy),
                style = strokeGlowWide
            )

            // Prominence solar flare loops
            val loopCount = 6
            for (lp in 0 until loopCount) {
                val ang = (lp * (2.0 * PI / loopCount)).toFloat() + time * 0.8f
                val loopDist = singularityRadius * (1.6f + 0.6f * sin(time * 6f + lp))
                val lx = sunCx + cos(ang) * loopDist
                val ly = sunCy + sin(ang) * (loopDist * 0.6f)
                drawScope.drawCircle(
                    color = COLOR_AMBER.copy(alpha = 0.60f * flareIntensity),
                    radius = 8f + lp * 2f,
                    center = Offset(lx, ly),
                    style = strokeRing
                )
            }
        }

        // Horizon Void Boundary Laser Line
        drawScope.drawLine(
            color = COLOR_VIOLET.copy(alpha = 0.30f),
            start = Offset(0f, horizonY),
            end = Offset(screenW, horizonY),
            strokeWidth = 3f
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.55f),
            start = Offset(0f, horizonY),
            end = Offset(screenW, horizonY),
            strokeWidth = 1f
        )
    }

    private fun drawDeepSpaceNebulae(
        drawScope: DrawScope,
        horizonY: Float,
        screenW: Float,
        time: Float
    ) {
        // Left Cosmic Dust Nebula
        val nebDrift = sin(time * 0.15f) * 15f
        nebulaPath.rewind()
        nebulaPath.moveTo(0f, horizonY)
        nebulaPath.lineTo(0f, horizonY - 90f + nebDrift)
        nebulaPath.cubicTo(
            screenW * 0.12f, horizonY - 140f + nebDrift,
            screenW * 0.28f, horizonY - 60f - nebDrift,
            screenW * 0.42f, horizonY
        )
        nebulaPath.close()
        drawScope.drawPath(nebulaPath, color = Color(0x331E0538), style = Fill)

        // Right Cosmic Dust Nebula
        nebulaPath.rewind()
        nebulaPath.moveTo(screenW * 0.58f, horizonY)
        nebulaPath.cubicTo(
            screenW * 0.70f, horizonY - 110f - nebDrift,
            screenW * 0.88f, horizonY - 150f + nebDrift,
            screenW, horizonY - 70f
        )
        nebulaPath.lineTo(screenW, horizonY)
        nebulaPath.close()
        drawScope.drawPath(nebulaPath, color = Color(0x280D1F38), style = Fill)
    }

    // ---------------------------------------------------------------------------------------------
    // Gravitational Void Slipstream & Spectral Containment Filaments
    // ---------------------------------------------------------------------------------------------

    private fun drawVoidSlipstream(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val leftCurbX = GameConfig.LANE_LEFT_CURB_X
        val rightCurbX = GameConfig.LANE_RIGHT_CURB_X
        val time = world.gameTime

        // 1. Slipstream Surface (Translucent dark corridor over the abyss)
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

        // 2. Spacetime Coordinate Filaments (Faint horizontal crosslines)
        val crosslineSpacing = GameConfig.FLOOR_CROSSLINE_SPACING_WU
        val scrollOffset = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % crosslineSpacing

        var lineY = scrollOffset
        while (lineY <= cameraY) {
            val clx = scaler.worldLaneToScreenX(leftCurbX, lineY)
            val crx = scaler.worldLaneToScreenX(rightCurbX, lineY)
            val cly = scaler.worldLaneToScreenY(lineY)
            val depthScale = scaler.scaleLaneDepth(lineY)

            val lineAlpha = (depthScale * 0.16f).coerceIn(0.02f, 0.20f)
            val strokeW = (1.5f * depthScale).coerceAtLeast(0.8f)

            drawScope.drawLine(
                color = COLOR_VIOLET.copy(alpha = lineAlpha),
                start = Offset(clx, cly),
                end = Offset(crx, cly),
                strokeWidth = strokeW
            )
            lineY += crosslineSpacing
        }

        // 3. Center Quantum Conduit (Ethereal guide laser humming down center)
        val centerPulse = 0.12f + 0.08f * sin(time * 6f)
        val p1x = scaler.worldLaneToScreenX(GameConfig.BUBBLE_CENTER_X, 0f)
        val p1y = scaler.worldLaneToScreenY(0f)
        val p2x = scaler.worldLaneToScreenX(GameConfig.BUBBLE_CENTER_X, cameraY)
        val p2y = scaler.worldLaneToScreenY(cameraY)

        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = centerPulse * 0.5f),
            start = Offset(p1x, p1y),
            end = Offset(p2x, p2y),
            strokeWidth = 6f
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = centerPulse),
            start = Offset(p1x, p1y),
            end = Offset(p2x, p2y),
            strokeWidth = 1.2f
        )

        // 4. Spectral Containment Filaments (Left & Right Corridor Boundaries)
        val vibL = sin(time * 18f) * 1.2f
        val vibR = cos(time * 18f) * 1.2f

        // Left Spectral Filament (Violet wide glow + Cyan core)
        drawScope.drawLine(
            color = COLOR_VIOLET.copy(alpha = 0.25f),
            start = Offset(tlx, tly),
            end = Offset(blx + vibL, bly),
            strokeWidth = 10f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.80f),
            start = Offset(tlx, tly),
            end = Offset(blx + vibL, bly),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = 0.90f),
            start = Offset(tlx, tly),
            end = Offset(blx + vibL, bly),
            strokeWidth = 1f,
            cap = StrokeCap.Round
        )

        // Right Spectral Filament
        drawScope.drawLine(
            color = COLOR_VIOLET.copy(alpha = 0.25f),
            start = Offset(trx, try_),
            end = Offset(brx + vibR, bry),
            strokeWidth = 10f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.80f),
            start = Offset(trx, try_),
            end = Offset(brx + vibR, bry),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = 0.90f),
            start = Offset(trx, try_),
            end = Offset(brx + vibR, bry),
            strokeWidth = 1f,
            cap = StrokeCap.Round
        )

        // 5. Quantum Containment Nodes (Pulsing Synapses along boundaries)
        val nodePeriod = 90f
        val nodeScroll = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % nodePeriod
        var nodeY = nodeScroll
        while (nodeY <= cameraY) {
            val depthScale = scaler.scaleLaneDepth(nodeY)
            val nodeAlpha = (depthScale * 0.70f).coerceIn(0.10f, 0.85f)
            val nodeR = (3f * depthScale).coerceAtLeast(1.2f)

            val nlx = scaler.worldLaneToScreenX(leftCurbX, nodeY)
            val nly = scaler.worldLaneToScreenY(nodeY)
            val nrx = scaler.worldLaneToScreenX(rightCurbX, nodeY)
            val nry = scaler.worldLaneToScreenY(nodeY)

            // Left Node
            drawScope.drawCircle(COLOR_CYAN.copy(alpha = nodeAlpha * 0.4f), radius = nodeR * 2.2f, center = Offset(nlx, nly))
            drawScope.drawCircle(COLOR_WHITE.copy(alpha = nodeAlpha), radius = nodeR, center = Offset(nlx, nly))

            // Right Node
            drawScope.drawCircle(COLOR_CYAN.copy(alpha = nodeAlpha * 0.4f), radius = nodeR * 2.2f, center = Offset(nrx, nry))
            drawScope.drawCircle(COLOR_WHITE.copy(alpha = nodeAlpha), radius = nodeR, center = Offset(nrx, nry))

            nodeY += nodePeriod
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

            // Kinetic Impact Ripple Deformation (sine wave along segment normal upon impact)
            val ripTimer = world.shieldSegments.rippleTimer[i]
            val ripIntensity = world.shieldSegments.rippleIntensity[i]
            val snx = world.shieldSegments.nx[i]
            val sny = world.shieldSegments.ny[i]

            val ripOffset = if (ripTimer > 0f) {
                val rNorm = (ripTimer / 0.28f).coerceIn(0f, 1f)
                sin(ripTimer * 58f) * ripIntensity * rNorm
            } else 0f

            val rOffX = snx * ripOffset * depthScale
            val rOffY = sny * ripOffset * depthScale

            // Ground projected points
            val gax = scaler.worldLaneToScreenX(sax, say)
            val gay = scaler.worldLaneToScreenY(say)
            val gbx = scaler.worldLaneToScreenX(sbx, sby)
            val gby = scaler.worldLaneToScreenY(sby)

            // Top elevated points (extruding upward along 3D height with kinetic ripple)
            val tax = scaler.worldLaneToScreenX(sax, say) + rOffX
            val tay = scaler.worldLaneToScreenY(say, wallHeight) + rOffY
            val tbx = scaler.worldLaneToScreenX(sbx, sby) + rOffX
            val tby = scaler.worldLaneToScreenY(sby, wallHeight) + rOffY

            // Ground the Shields: Corridor Rail Clamping & Dielectric Sparks
            if (sax <= 135f || sax >= 865f) {
                drawRailClampNode(drawScope, gax, gay, depthScale, baseColor, fadeAlpha, isLeft = (sax <= 135f), time = world.gameTime)
            }
            if (sbx <= 135f || sbx >= 865f) {
                drawRailClampNode(drawScope, gbx, gby, depthScale, baseColor, fadeAlpha, isLeft = (sbx <= 135f), time = world.gameTime)
            }

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

    private fun drawRailClampNode(
        drawScope: DrawScope,
        px: Float,
        py: Float,
        depthScale: Float,
        color: Color,
        alpha: Float,
        isLeft: Boolean,
        time: Float
    ) {
        val nodeR = (5.5f * depthScale).coerceAtLeast(2.5f)
        val center = Offset(px, py)

        // Pulsing clamping halo
        val pulse = 0.7f + 0.3f * sin(time * 16f)
        drawScope.drawCircle(color.copy(alpha = 0.30f * alpha * pulse), radius = nodeR * 2.2f, center = center)
        drawScope.drawCircle(COLOR_CYAN.copy(alpha = 0.85f * alpha), radius = nodeR, center = center)
        drawScope.drawCircle(COLOR_WHITE.copy(alpha = 0.95f * alpha), radius = nodeR * 0.45f, center = center)

        // Dielectric grounding micro-sparks jumping outward to containment filament
        val sparkDirX = if (isLeft) -1f else 1f
        val sparkLen = 14f * depthScale
        val sparkJitter1 = sin(time * 42f) * 6f * depthScale
        val sparkJitter2 = cos(time * 36f) * 6f * depthScale

        drawScope.drawLine(
            color = COLOR_WHITE.copy(alpha = 0.80f * alpha),
            start = center,
            end = Offset(px + sparkDirX * sparkLen, py + sparkJitter1),
            strokeWidth = (1.5f * depthScale).coerceAtLeast(1f)
        )
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = 0.60f * alpha),
            start = center,
            end = Offset(px + sparkDirX * sparkLen * 0.7f, py + sparkJitter2),
            strokeWidth = (1.0f * depthScale).coerceAtLeast(0.8f)
        )
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

        // 0. Bioluminescent Local Light Oasis (Illuminating the nearby void)
        val lightR = sbr * 2.5f
        drawScope.drawOval(
            color = COLOR_CYAN.copy(alpha = 0.09f),
            topLeft = Offset(sbx - lightR, sby - lightR * 0.40f),
            size = Size(lightR * 2f, lightR * 0.80f)
        )

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
            color = COLOR_CYAN.copy(alpha = 0.16f),
            startAngle = 150f,
            sweepAngle = totalSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = strokeThickRing
        )

        // Active ink arc (Multi-pass vibrant fuel gauge)
        if (currentSweep > 0f) {
            // Pass A: Wide glow
            drawScope.drawArc(
                color = inkColor.copy(alpha = 0.35f),
                startAngle = 150f,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = strokeGlowMid
            )
            // Pass B: Solid body
            drawScope.drawArc(
                color = inkColor.copy(alpha = 0.95f),
                startAngle = 150f,
                sweepAngle = currentSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = strokeThickRing
            )
            // Pass C: Hot tip cursor
            val tipAngleRad = (150f + currentSweep) * (PI.toFloat() / 180f)
            val tipX = sbx + ringRadiusX * cos(tipAngleRad)
            val tipY = sby + ringRadiusY * sin(tipAngleRad)
            drawScope.drawCircle(
                color = COLOR_WHITE,
                radius = 3.5f,
                center = Offset(tipX, tipY)
            )
        }

        // 5. Overshield Aegis Forcefield (Translucent Emerald-Gold Hex Shell)
        if (world.overshieldHp > 0) {
            val overshieldR = sbr + 14f * scaler.scaleLaneDepth(world.bubbleY)
            val aegisPulse = 0.85f + 0.15f * sin(time * 6f)
            val aegisColor = Color(0xFF00FF99)
            drawScope.drawCircle(
                color = aegisColor.copy(alpha = 0.20f * aegisPulse),
                radius = overshieldR,
                center = Offset(sbx, sby)
            )
            drawScope.drawCircle(
                color = aegisColor.copy(alpha = 0.85f * aegisPulse),
                radius = overshieldR,
                center = Offset(sbx, sby),
                style = strokeCoreThin
            )
            // 4 Orbiting Aegis Shield Nodes
            for (node in 0 until 4) {
                val nodeAng = time * 2.5f + (node * PI.toFloat() * 0.5f)
                val nx = sbx + cos(nodeAng) * overshieldR
                val ny = sby + sin(nodeAng) * (overshieldR * 0.65f)
                drawScope.drawCircle(COLOR_WHITE, radius = 3.5f, center = Offset(nx, ny))
                drawScope.drawCircle(COLOR_AMBER, radius = 6f, center = Offset(nx, ny), style = strokeRing)
            }
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
            // Atmospheric Void Fog: distant threats emerging from the horizon are spectral silhouettes
            val voidFogAlpha = ((wy - 20f) / 100f).coerceIn(0.15f, 1f)

            val type = world.obstacles.type[i]
            val vx = world.obstacles.vx[i]
            val vy = world.obstacles.vy[i]
            val angle = world.obstacles.angle[i]
            val flags = world.obstacles.flags[i]
            val isParried = (flags and GameConfig.FLAG_PARRIED) != 0

            // Ground Drop-Shadow on Highway
            drawScope.drawOval(
                color = COLOR_SHADOW.copy(alpha = (0.35f * depthScale * voidFogAlpha).coerceIn(0.08f, 0.45f)),
                topLeft = Offset(ox - r * 0.85f, oy + r * 0.40f),
                size = Size(r * 1.7f, r * 0.45f)
            )

            when (type) {
                GameConfig.TYPE_DRIFTER -> drawDrifter3D(drawScope, ox, oy, r, depthScale, isParried, voidFogAlpha)
                GameConfig.TYPE_NEEDLE -> drawNeedle3D(drawScope, ox, oy, r, vx, vy, depthScale, isParried, voidFogAlpha)
                GameConfig.TYPE_CRUSHER -> drawCrusher3D(drawScope, ox, oy, r, angle, depthScale, isParried, voidFogAlpha)
                GameConfig.TYPE_SPLITTER -> drawSplitter3D(drawScope, ox, oy, r, angle, depthScale, isParried, voidFogAlpha)
                GameConfig.TYPE_SHARD -> drawShard3D(drawScope, ox, oy, r, angle, depthScale, isParried, voidFogAlpha)
                GameConfig.TYPE_PRISM_CARRIER -> drawPrismCarrier3D(drawScope, ox, oy, r, angle, depthScale, voidFogAlpha, world.gameTime)
                GameConfig.TYPE_ARMOR_CRYSTAL -> drawArmorCrystal3D(drawScope, ox, oy, r, depthScale, voidFogAlpha, world.gameTime, wy)
                GameConfig.TYPE_METEORITE -> drawMeteorite3D(drawScope, ox, oy, r, vx, vy, depthScale, isParried, voidFogAlpha, world.gameTime)
            }
        }
    }

    private fun drawDrifter3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        depthScale: Float,
        isParried: Boolean,
        voidFogAlpha: Float
    ) {
        val baseColor = if (isParried) COLOR_CYAN else COLOR_MAGENTA
        val center = Offset(cx, cy)

        // Outer glow
        drawScope.drawCircle(baseColor.copy(alpha = 0.18f * voidFogAlpha), radius = r + 6f * depthScale, center = center)
        // Main body fill (Dark obsidian void core with colored aura)
        drawScope.drawCircle(baseColor.copy(alpha = 0.35f * voidFogAlpha), radius = r, center = center, style = Fill)
        // Perimeter
        drawScope.drawCircle(baseColor.copy(alpha = voidFogAlpha), radius = r, center = center, style = strokeCoreThin)
        // 3D Specular Highlight
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.50f * voidFogAlpha),
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
        isParried: Boolean,
        voidFogAlpha: Float
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
            color = baseColor.copy(alpha = 0.35f * voidFogAlpha),
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.25f * voidFogAlpha), style = strokeGlowMid)
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.55f * voidFogAlpha), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE.copy(alpha = voidFogAlpha), style = strokeWhiteCore)
    }

    private fun drawCrusher3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        isParried: Boolean,
        voidFogAlpha: Float
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.20f * voidFogAlpha), style = Fill)

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
            drawScope.drawLine(baseColor.copy(alpha = 0.40f * voidFogAlpha), Offset(gpx, gpy), Offset(tpx, tpy), strokeWidth = 1.5f)
        }

        // Top Hex Face Outline & Fill
        drawScope.drawPath(scratchPath, color = baseColor.copy(alpha = 0.50f * voidFogAlpha), style = Fill)
        drawScope.drawPath(scratchPath, color = baseColor.copy(alpha = voidFogAlpha), style = strokeShieldMid)
        drawScope.drawPath(scratchPath, color = COLOR_WHITE.copy(alpha = 0.70f * voidFogAlpha), style = strokeWhiteCore)
    }

    private fun drawSplitter3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        isParried: Boolean,
        voidFogAlpha: Float
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.25f * voidFogAlpha), style = strokeGlowMid)
        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.45f * voidFogAlpha), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE.copy(alpha = voidFogAlpha), style = strokeWhiteCore)

        // Internal crystal facets
        val topVertex = Offset(cx, cy - r * 0.7f)
        val botVertex = Offset(cx, cy + r * 0.7f)
        drawScope.drawLine(COLOR_WHITE.copy(alpha = 0.50f * voidFogAlpha), topVertex, botVertex, strokeWidth = 1.5f)
    }

    private fun drawShard3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        isParried: Boolean,
        voidFogAlpha: Float
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

        drawScope.drawPath(obstaclePath, color = baseColor.copy(alpha = 0.50f * voidFogAlpha), style = Fill)
        drawScope.drawPath(obstaclePath, color = COLOR_WHITE.copy(alpha = voidFogAlpha), style = strokeWhiteCore)
    }

    private fun drawPrismCarrier3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        depthScale: Float,
        voidFogAlpha: Float,
        time: Float
    ) {
        val center = Offset(cx, cy)
        val emeraldColor = Color(0xFF00FF99) // Soft currency prism emerald
        val cyanColor = Color(0xFF00F0FF)

        // 1. Outer Soft Emerald Glow Aura
        val pulse = 0.5f + 0.5f * sin(time * 8f)
        drawScope.drawCircle(
            color = emeraldColor.copy(alpha = (0.24f + 0.12f * pulse) * voidFogAlpha),
            radius = r + 12f * depthScale,
            center = center
        )

        // 2. Orbiting Celestial Quantum Ring (Tilted ellipse rotating around the gem)
        val ringW = r * 2.2f
        val ringH = r * 0.85f
        drawScope.drawOval(
            color = cyanColor.copy(alpha = 0.65f * voidFogAlpha),
            topLeft = Offset(cx - ringW * 0.5f, cy - ringH * 0.5f),
            size = Size(ringW, ringH),
            style = strokeCoreThin
        )

        // 3. Rotating Emerald Diamond Core (4 Diamond Facets)
        val rot = angle + time * 2.5f
        val cosR = cos(rot)
        val sinR = sin(rot)
        val dR = r * 1.25f
        val topX = cx - sinR * dR
        val topY = cy + cosR * dR
        val botX = cx + sinR * dR
        val botY = cy - cosR * dR
        val rightX = cx + cosR * (dR * 0.70f)
        val rightY = cy + sinR * (dR * 0.70f)
        val leftX = cx - cosR * (dR * 0.70f)
        val leftY = cy - sinR * (dR * 0.70f)

        obstaclePath.rewind()
        obstaclePath.moveTo(topX, topY)
        obstaclePath.lineTo(rightX, rightY)
        obstaclePath.lineTo(botX, botY)
        obstaclePath.lineTo(leftX, leftY)
        obstaclePath.close()

        // Facet fill
        drawScope.drawPath(obstaclePath, color = emeraldColor.copy(alpha = 0.45f * voidFogAlpha), style = Fill)
        drawScope.drawPath(obstaclePath, color = emeraldColor.copy(alpha = 0.95f * voidFogAlpha), style = strokeShieldMid)

        // 4. Radiant White/Emerald Inner Crystal Core
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.90f * voidFogAlpha),
            radius = r * 0.35f,
            center = center
        )
        drawScope.drawCircle(
            color = emeraldColor.copy(alpha = 0.60f * voidFogAlpha),
            radius = r * 0.60f,
            center = center,
            style = strokeCoreThin
        )
    }

    private fun drawArmorCrystal3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        depthScale: Float,
        voidFogAlpha: Float,
        time: Float,
        wy: Float
    ) {
        val center = Offset(cx, cy)
        val emeraldColor = Color(0xFF00FF99)

        // 1. Contracting Timing Resonance Ring
        val cycle = (time * 1.6f) % 1.0f
        val ringR = r * (1.0f + (1f - cycle) * 1.5f)
        val ringAlpha = (cycle * voidFogAlpha * 0.85f).coerceIn(0.1f, 0.9f)
        val inTapZone = wy in GameConfig.ARMOR_CRYSTAL_MIN_TAP_Y..GameConfig.ARMOR_CRYSTAL_MAX_TAP_Y
        val ringColor = if (inTapZone) emeraldColor else COLOR_AMBER

        drawScope.drawCircle(
            color = ringColor.copy(alpha = ringAlpha),
            radius = ringR,
            center = center,
            style = strokeCoreThin
        )

        // 2. Nano-Repair Octahedron Body (Rotates with time)
        val rot = time * 3.0f
        val cosR = cos(rot)
        val sinR = sin(rot)
        scratchPath.rewind()
        scratchPath.moveTo(cx + (-r * 0.7f * cosR), cy + (-r * 0.7f * sinR))
        scratchPath.lineTo(cx + (r * cosR - r * sinR), cy + (r * sinR + r * cosR))
        scratchPath.lineTo(cx + (r * 0.7f * cosR), cy + (r * 0.7f * sinR))
        scratchPath.lineTo(cx + (-r * cosR + r * sinR), cy + (-r * sinR - r * cosR))
        scratchPath.close()

        // Outer glow
        drawScope.drawCircle(
            color = emeraldColor.copy(alpha = 0.25f * voidFogAlpha),
            radius = r + 8f * depthScale,
            center = center
        )
        // Body fill
        drawScope.drawPath(scratchPath, color = Color(0xFF062A1E).copy(alpha = 0.85f * voidFogAlpha), style = Fill)
        drawScope.drawPath(scratchPath, color = emeraldColor.copy(alpha = voidFogAlpha), style = strokeCoreThin)

        // 3. Central pulsating core
        val pulse = 0.75f + 0.25f * sin(time * 8f)
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.95f * voidFogAlpha),
            radius = r * 0.35f * pulse,
            center = center
        )

        // 4. "TAP" prompt text if inside optimal tap zone
        if (inTapZone && depthScale > 0.35f) {
            hudPaintCenter.textSize = (11f * depthScale * 20f).coerceIn(10f, 22f)
            hudPaintCenter.color = 0xFF00FF99.toInt()
            hudPaintCenter.alpha = (230 * voidFogAlpha).toInt()
            drawScope.drawContext.canvas.nativeCanvas.drawText("TAP!", cx, cy - r - 8f * depthScale, hudPaintCenter)
        }
    }

    private fun drawMeteorite3D(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        r: Float,
        vx: Float,
        vy: Float,
        depthScale: Float,
        isParried: Boolean,
        voidFogAlpha: Float,
        time: Float
    ) {
        val center = Offset(cx, cy)
        val speedSq = vx * vx + vy * vy
        val heading = if (speedSq > 1f) atan2(vy, vx) else (PI.toFloat() * 0.5f)

        // 1. Fiery Burning Plasma Tail
        val tailLen = r * 3.2f
        val tailX = cx - cos(heading) * tailLen
        val tailY = cy - sin(heading) * tailLen
        val flameColor = if (isParried) COLOR_CYAN else Color(0xFFFF5500)
        drawScope.drawLine(
            color = flameColor.copy(alpha = 0.45f * voidFogAlpha),
            start = center,
            end = Offset(tailX, tailY),
            strokeWidth = 12f * depthScale
        )
        drawScope.drawLine(
            color = COLOR_AMBER.copy(alpha = 0.80f * voidFogAlpha),
            start = center,
            end = Offset(cx - cos(heading) * (tailLen * 0.6f), cy - sin(heading) * (tailLen * 0.6f)),
            strokeWidth = 5f * depthScale
        )

        // 2. Jagged Rocky Bolide Body
        val segments = 7
        scratchPath.rewind()
        for (s in 0 until segments) {
            val ang = s * (2.0 * PI / segments).toFloat() + time * 1.5f
            val radVar = r * (0.85f + 0.30f * sin(ang * 3f + s))
            val px = cx + cos(ang) * radVar
            val py = cy + sin(ang) * radVar
            if (s == 0) scratchPath.moveTo(px, py) else scratchPath.lineTo(px, py)
        }
        scratchPath.close()

        // Outer heat aura
        drawScope.drawCircle(
            color = flameColor.copy(alpha = 0.30f * voidFogAlpha),
            radius = r + 6f * depthScale,
            center = center
        )
        // Dark molten crust
        drawScope.drawPath(scratchPath, color = Color(0xFF1A0A04).copy(alpha = 0.90f * voidFogAlpha), style = Fill)
        drawScope.drawPath(scratchPath, color = flameColor.copy(alpha = 0.95f * voidFogAlpha), style = strokeCoreThin)

        // Glowing molten fissures
        drawScope.drawCircle(
            color = COLOR_AMBER.copy(alpha = 0.85f * voidFogAlpha),
            radius = r * 0.40f,
            center = center
        )
        drawScope.drawCircle(
            color = COLOR_WHITE.copy(alpha = 0.95f * voidFogAlpha),
            radius = r * 0.18f,
            center = center
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Sideline Sentinel Drones & Interceptor Beams
    // ---------------------------------------------------------------------------------------------

    private fun drawDrones(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler,
        droneSystem: DroneSystem
    ) {
        val activeCount = if (world.droneLevel >= 2) 2 else 1
        val time = world.gameTime

        for (d in 0 until activeCount) {
            val wx = droneSystem.droneX[d]
            val wy = droneSystem.droneY[d]

            val dsx = scaler.worldLaneToScreenX(wx, wy)
            val dsy = scaler.worldLaneToScreenY(wx, wy)
            val depthScale = scaler.scaleLaneDepth(wy)

            // 1. Faint Targeting Laser Sight Line (when acquiring target and not firing beam)
            if (droneSystem.hasTarget[d] && droneSystem.beamTimer[d] <= 0f) {
                val tx = droneSystem.beamTargetX[d]
                val ty = droneSystem.beamTargetY[d]
                val tsx = scaler.worldLaneToScreenX(tx, ty)
                val tsy = scaler.worldLaneToScreenY(tx, ty)

                // Laser guide sight line
                drawScope.drawLine(
                    color = COLOR_CYAN.copy(alpha = 0.22f),
                    start = Offset(dsx, dsy),
                    end = Offset(tsx, tsy),
                    strokeWidth = 1.2f * depthScale
                )

                // Subtle reticle brackets on target
                val reticleR = 12f * depthScale
                drawScope.drawCircle(
                    color = COLOR_CYAN.copy(alpha = 0.30f),
                    radius = reticleR,
                    center = Offset(tsx, tsy),
                    style = strokeWhiteCore
                )
            }

            // 2. Active Interceptor Plasma Beam (during fire flash)
            if (droneSystem.beamTimer[d] > 0f) {
                val beamRatio = (droneSystem.beamTimer[d] / GameConfig.DRONE_BEAM_DURATION).coerceIn(0f, 1f)
                val tx = droneSystem.beamTargetX[d]
                val ty = droneSystem.beamTargetY[d]
                val tsx = scaler.worldLaneToScreenX(tx, ty)
                val tsy = scaler.worldLaneToScreenY(tx, ty)

                val start = Offset(dsx, dsy)
                val end = Offset(tsx, tsy)

                val beamColor = if (world.droneLevel >= 3) COLOR_AMBER else COLOR_CYAN

                // Multi-pass neon beam
                // Wide outer glow
                drawScope.drawLine(
                    color = beamColor.copy(alpha = 0.28f * beamRatio),
                    start = start,
                    end = end,
                    strokeWidth = 14f * depthScale
                )
                // Mid laser core
                drawScope.drawLine(
                    color = beamColor.copy(alpha = 0.85f * beamRatio),
                    start = start,
                    end = end,
                    strokeWidth = 5f * depthScale
                )
                // Hot white center line
                drawScope.drawLine(
                    color = COLOR_WHITE.copy(alpha = beamRatio),
                    start = start,
                    end = end,
                    strokeWidth = 2f * depthScale
                )

                // Plasma impact flare at target
                drawScope.drawCircle(
                    color = beamColor.copy(alpha = 0.70f * beamRatio),
                    radius = 20f * depthScale,
                    center = end
                )
                drawScope.drawCircle(
                    color = COLOR_WHITE.copy(alpha = 0.95f * beamRatio),
                    radius = 8f * depthScale,
                    center = end
                )
            }

            // 3. Drone Shoulder Rail Tether (Visual grounding to corridor rail)
            val railWx = if (d == 0) 135f else 865f
            val rsx = scaler.worldLaneToScreenX(railWx, wy)
            val rsy = scaler.worldLaneToScreenY(railWx, wy)
            drawScope.drawLine(
                color = COLOR_CYAN.copy(alpha = 0.25f),
                start = Offset(rsx, rsy),
                end = Offset(dsx, dsy),
                strokeWidth = 1.5f * depthScale
            )

            // 4. Ground Drop-Shadow on Void Slipstream
            val shadowW = 28f * depthScale
            val shadowH = 9f * depthScale
            drawScope.drawOval(
                color = COLOR_SHADOW,
                topLeft = Offset(dsx - shadowW * 0.5f, dsy + 12f * depthScale),
                size = Size(shadowW, shadowH)
            )

            // 5. Thruster Plasma Jet (Down-facing with high-frequency sine flicker)
            val flicker = 0.75f + 0.25f * sin(time * 35f + d * 3f)
            val jetLen = 14f * depthScale * flicker
            val jetColor = if (world.droneLevel >= 3) COLOR_AMBER else COLOR_CYAN
            drawScope.drawLine(
                color = jetColor.copy(alpha = 0.60f * flicker),
                start = Offset(dsx, dsy + 10f * depthScale),
                end = Offset(dsx, dsy + 10f * depthScale + jetLen),
                strokeWidth = 3.5f * depthScale
            )
            drawScope.drawLine(
                color = COLOR_WHITE.copy(alpha = 0.85f * flicker),
                start = Offset(dsx, dsy + 10f * depthScale),
                end = Offset(dsx, dsy + 10f * depthScale + jetLen * 0.6f),
                strokeWidth = 1.5f * depthScale
            )

            // 6. Drone Chassis (Diamond Stealth Chassis with Specular Laser Trim)
            val rot = droneSystem.droneAngle[d]
            val cosR = cos(rot)
            val sinR = sin(rot)

            val halfW = 12f * depthScale
            val halfH = 16f * depthScale

            // Diamond vertices relative to drone center
            val p0x = 0f
            val p0y = -halfH
            val p1x = halfW
            val p1y = 0f
            val p2x = 0f
            val p2y = halfH
            val p3x = -halfW
            val p3y = 0f

            dronePath.reset()
            dronePath.moveTo(dsx + (p0x * cosR - p0y * sinR), dsy + (p0x * sinR + p0y * cosR))
            dronePath.lineTo(dsx + (p1x * cosR - p1y * sinR), dsy + (p1x * sinR + p1y * cosR))
            dronePath.lineTo(dsx + (p2x * cosR - p2y * sinR), dsy + (p2x * sinR + p2y * cosR))
            dronePath.lineTo(dsx + (p3x * cosR - p3y * sinR), dsy + (p3x * sinR + p3y * cosR))
            dronePath.close()

            // Obsidian armor body
            drawScope.drawPath(dronePath, color = Color(0xFF09111E), style = Fill)
            // Accent laser rim
            val rimColor = if (world.droneLevel >= 3) COLOR_AMBER else COLOR_CYAN
            drawScope.drawPath(dronePath, color = rimColor.copy(alpha = 0.90f), style = strokeWhiteCore)

            // 7. Rotating Plasma Core
            val coreR = 4f * depthScale
            val corePulse = 0.8f + 0.2f * sin(time * 8f + d * 2f)
            drawScope.drawCircle(
                color = rimColor.copy(alpha = 0.40f * corePulse),
                radius = coreR * 2.2f,
                center = Offset(dsx, dsy)
            )
            drawScope.drawCircle(
                color = COLOR_WHITE.copy(alpha = 0.95f),
                radius = coreR,
                center = Offset(dsx, dsy)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stored Special Powers Active VFX (Chrono Warp, Supernova EMP, Plasma Lance)
    // ---------------------------------------------------------------------------------------------

    private fun drawSpecialPowersVFX(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val time = world.gameTime

        // 1. Chrono Warp Time Dilation Screen Border Glint
        if (world.activePowerType == GameConfig.POWER_CHRONO_WARP) {
            val pulse = 0.5f + 0.5f * sin(time * 10f)
            val sw = scaler.screenWidthPx
            val sh = scaler.screenHeightPx
            drawScope.drawRect(
                color = COLOR_CYAN.copy(alpha = 0.08f * pulse),
                topLeft = Offset.Zero,
                size = Size(sw, sh)
            )
            drawScope.drawRect(
                color = COLOR_CYAN.copy(alpha = 0.35f * pulse),
                topLeft = Offset.Zero,
                size = Size(sw, sh),
                style = strokeThickRing
            )
        }

        // 2. Supernova EMP Shockwave Ring
        if (world.empShockwaveTimer > 0f) {
            val sx = scaler.worldLaneToScreenX(world.bubbleX, world.bubbleY)
            val sy = scaler.worldLaneToScreenY(world.bubbleY)
            val r = scaler.scaleLaneDistance(world.empShockwaveRadius, world.bubbleY)
            val alpha = (world.empShockwaveTimer / 0.6f).coerceIn(0f, 1f)
            drawScope.drawCircle(
                color = COLOR_CYAN.copy(alpha = 0.25f * alpha),
                radius = r,
                center = Offset(sx, sy),
                style = strokeGlowWide
            )
            drawScope.drawCircle(
                color = COLOR_WHITE.copy(alpha = 0.90f * alpha),
                radius = r,
                center = Offset(sx, sy),
                style = strokeThickRing
            )
        }

        // 3. Plasma Lance Overdrive (Twin Orbital Death Beams)
        if (world.activePowerType == GameConfig.POWER_PLASMA_LANCE) {
            val ratio = (world.activePowerTimer / GameConfig.POWER_LANCE_DURATION).coerceIn(0f, 1f)
            val lanceXs = floatArrayOf(450f, 550f)
            val horizonY = scaler.camera.vanishingPointY
            val bottomY = scaler.screenHeightPx

            for (lx in lanceXs) {
                val topSx = scaler.worldLaneToScreenX(lx, 30f)
                val botSx = scaler.worldLaneToScreenX(lx, 1600f)

                // Wide plasma core
                drawScope.drawLine(
                    color = COLOR_AMBER.copy(alpha = 0.35f * ratio),
                    start = Offset(topSx, horizonY),
                    end = Offset(botSx, bottomY),
                    strokeWidth = 24f
                )
                // Mid laser
                drawScope.drawLine(
                    color = Color(0xFFFF4400).copy(alpha = 0.85f * ratio),
                    start = Offset(topSx, horizonY),
                    end = Offset(botSx, bottomY),
                    strokeWidth = 10f
                )
                // Hot white laser center
                drawScope.drawLine(
                    color = COLOR_WHITE.copy(alpha = 0.95f * ratio),
                    start = Offset(topSx, horizonY),
                    end = Offset(botSx, bottomY),
                    strokeWidth = 3f
                )
            }
        }
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
                FloatingTextPool.TEXT_BOUNTY.toInt() -> 0xFF00FF99.toInt() // Radiant Emerald Bounty
                else -> 0xFFFFB800.toInt()                                // Amber
            }
            textPaint.alpha = (lifeRatio * 255).toInt().coerceIn(0, 255)

            canvas.drawText(label, sx, sy, textPaint)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Silent Void Monoliths & Spatial Warp Rings
    // ---------------------------------------------------------------------------------------------

    private fun drawRoadsideAssets(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val monolithSpacing = 220f
        val monolithScroll = (world.altitudeMeters * GameConfig.WU_PER_METER * 1.5f) % monolithSpacing
        val shoulderLeft = 60f
        val shoulderRight = 940f
        val time = world.gameTime

        var my = monolithScroll
        while (my <= cameraY) {
            val depthScale = scaler.scaleLaneDepth(my)

            // Left Silent Monolith
            drawSingleMonolith(drawScope, scaler, shoulderLeft, my, depthScale, isLeft = true, time)

            // Right Silent Monolith
            drawSingleMonolith(drawScope, scaler, shoulderRight, my, depthScale, isLeft = false, time)

            my += monolithSpacing
        }

        // Suspended Spatial Warp Rings spanning corridor every 250m
        drawSpatialWarpRings(drawScope, world, scaler)
    }

    private fun drawSingleMonolith(
        drawScope: DrawScope,
        scaler: ViewportScaler,
        wx: Float,
        wy: Float,
        depthScale: Float,
        isLeft: Boolean,
        time: Float
    ) {
        // Zero-gravity vertical floating bob
        val floatOffset = sin(time * 1.6f + wy * 0.03f) * 14f * depthScale
        val gx = scaler.worldLaneToScreenX(wx, wy)
        val gy = scaler.worldLaneToScreenY(wy) + floatOffset

        val alpha = (depthScale * 0.85f).coerceIn(0.12f, 0.95f)
        val w = 18f * depthScale
        val h = 90f * depthScale

        // Slender Monolith Obelisk Shape
        monolithPath.rewind()
        monolithPath.moveTo(gx, gy - h)             // Apex
        monolithPath.lineTo(gx + w * 0.5f, gy - h * 0.82f)
        monolithPath.lineTo(gx + w * 0.4f, gy)      // Base right
        monolithPath.lineTo(gx - w * 0.4f, gy)      // Base left
        monolithPath.lineTo(gx - w * 0.5f, gy - h * 0.82f)
        monolithPath.close()

        // Obsidian Fill
        drawScope.drawPath(monolithPath, color = COLOR_OBSIDIAN.copy(alpha = alpha), style = Fill)
        // Cold Edge Contour
        val contourColor = if (isLeft) COLOR_VIOLET else COLOR_PHANTOM_CYAN
        drawScope.drawPath(monolithPath, color = contourColor.copy(alpha = alpha * 0.60f), style = strokeWhiteCore)

        // Glowing Core Energy Fissure (Running down spine)
        val fissureW = (1.5f * depthScale).coerceAtLeast(1f)
        drawScope.drawLine(
            color = COLOR_CYAN.copy(alpha = alpha * 0.85f),
            start = Offset(gx, gy - h * 0.75f),
            end = Offset(gx, gy - h * 0.15f),
            strokeWidth = fissureW
        )
    }

    private fun drawSpatialWarpRings(
        drawScope: DrawScope,
        world: GameWorld,
        scaler: ViewportScaler
    ) {
        val cameraY = scaler.camera.cameraY
        val currentAlt = world.altitudeMeters
        val currentIdx = (currentAlt / 250f).toInt()

        for (idx in currentIdx..(currentIdx + 2)) {
            val milestoneM = idx * 250
            if (milestoneM <= 0) continue

            val dMeters = milestoneM - currentAlt
            val distWu = dMeters * GameConfig.WU_PER_METER * 1.5f
            val ringY = GameConfig.BUBBLE_CENTER_Y - distWu

            if (ringY in 10f..cameraY) {
                val depthScale = scaler.scaleLaneDepth(ringY)
                val alpha = (depthScale * 0.95f).coerceIn(0.15f, 1f)
                val cx = scaler.worldLaneToScreenX(GameConfig.BUBBLE_CENTER_X, ringY)
                val cy = scaler.worldLaneToScreenY(ringY, heightWu = 65f)

                val ringW = (320f * depthScale).coerceAtLeast(60f)
                val ringH = (110f * depthScale).coerceAtLeast(24f)

                // Ethereal Outer Spatial Distortion Ring
                drawScope.drawOval(
                    color = COLOR_VIOLET.copy(alpha = alpha * 0.35f),
                    topLeft = Offset(cx - ringW * 0.5f, cy - ringH * 0.5f),
                    size = Size(ringW, ringH),
                    style = strokeShieldMid
                )
                // Hot Energy Core Ring
                drawScope.drawOval(
                    color = COLOR_CYAN.copy(alpha = alpha * 0.75f),
                    topLeft = Offset(cx - ringW * 0.5f, cy - ringH * 0.5f),
                    size = Size(ringW, ringH),
                    style = strokeRing
                )

                // Holographic Floating Milestone Text
                val canvas = drawScope.drawContext.canvas.nativeCanvas
                hudPaintCenter.textSize = (16f * depthScale).coerceAtLeast(9f)
                hudPaintCenter.color = 0xFF00F0FF.toInt()
                hudPaintCenter.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawText("${milestoneM}m", cx, cy + ringH * 0.35f, hudPaintCenter)
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

        // 4. Cosmic Dust Motes (Floating space dust caught in zero-g)
        val moteCount = 24
        val bx = scaler.worldLaneToScreenX(world.bubbleX, world.bubbleY)
        val by = scaler.worldLaneToScreenY(world.bubbleY)

        for (m in 0 until moteCount) {
            val seedX = ((m * 67 + 31) % sw.toInt()).toFloat()
            val seedY = ((m * 43 + 19) % sh.toInt()).toFloat()
            val mx = (seedX + sin(world.gameTime * 0.5f + m) * 30f + sw) % sw
            val my = (seedY + world.altitudeMeters * 4f + m * 25f + sh) % sh

            // Distance to bubble center
            val dx = mx - bx
            val dy = my - by
            val dToBubbleSq = dx * dx + dy * dy

            // When close to bubble, dust mote illuminates intensely
            val isLit = dToBubbleSq < (300f * 300f)
            val moteAlpha = if (isLit) 0.60f else 0.14f
            val moteSize = if (isLit) 2.2f else 1.0f
            val moteCol = if (isLit) COLOR_CYAN else COLOR_WHITE

            drawScope.drawCircle(
                color = moteCol.copy(alpha = moteAlpha),
                radius = moteSize,
                center = Offset(mx, my)
            )
        }

        // 5. Abyssal Void Vignette (The Encroaching Nothingness)
        val isLowHp = world.bubbleHp == 1
        val lowHpPulse = if (isLowHp) 0.5f + 0.5f * sin(world.gameTime * 8f) else 0f
        val baseVigAlpha = if (isLowHp) (0.50f + 0.25f * lowHpPulse) else 0.40f

        // Heavy dark perimeter borders fading into blackness
        val edgeThick = 28f + world.trauma * 20f
        val vigColor = if (isLowHp) COLOR_RED else Color.Black
        drawScope.drawLine(vigColor.copy(alpha = baseVigAlpha), Offset(0f, 0f), Offset(0f, sh), strokeWidth = edgeThick)
        drawScope.drawLine(vigColor.copy(alpha = baseVigAlpha), Offset(sw, 0f), Offset(sw, sh), strokeWidth = edgeThick)
        drawScope.drawLine(vigColor.copy(alpha = baseVigAlpha), Offset(0f, 0f), Offset(sw, 0f), strokeWidth = edgeThick * 1.5f)
        drawScope.drawLine(vigColor.copy(alpha = baseVigAlpha), Offset(0f, sh), Offset(sw, sh), strokeWidth = edgeThick * 1.5f)
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

        // Overshield Aegis Diamond Indicator
        if (world.overshieldHp > 0) {
            val overshieldY = coreY - 18f * scale
            scratchPath.rewind()
            scratchPath.moveTo(cx, overshieldY - 7f * scale)
            scratchPath.lineTo(cx + 7f * scale, overshieldY)
            scratchPath.lineTo(cx, overshieldY + 7f * scale)
            scratchPath.lineTo(cx - 7f * scale, overshieldY)
            scratchPath.close()
            drawScope.drawPath(scratchPath, color = Color(0xFF00FF99).copy(alpha = 0.90f), style = Fill)
            drawScope.drawPath(scratchPath, color = COLOR_WHITE, style = strokeWhiteCore)
        }

        // Sentinel Drone Status Badge
        if (world.droneLevel > 0) {
            hudPaintCenter.textSize = 10f * scale
            if (world.droneDisruptedTimer > 0f) {
                hudPaintCenter.color = 0xFFFF2200.toInt()
                hudPaintCenter.alpha = 255
                val rebootSec = String.format("%.1f", world.droneDisruptedTimer)
                canvas.drawText("⚡ SENTINELS EMP REBOOTING [${rebootSec}s] ⚡", cx, topY + 58f * scale, hudPaintCenter)
            } else {
                hudPaintCenter.color = if (world.droneLevel >= 3) 0xFFFFB800.toInt() else 0xFF00F0FF.toInt()
                hudPaintCenter.alpha = 200
                val droneStatus = when (world.droneLevel) {
                    1 -> "SENTINEL 1 ACTIVE"
                    2 -> "DUAL SENTINELS ACTIVE"
                    else -> "SENTINELS OVERCHARGED"
                }
                canvas.drawText("✦ $droneStatus ✦", cx, topY + 58f * scale, hudPaintCenter)
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

        // Stored Special Powers Readout
        hudPaintRight.textSize = 11f * scale
        hudPaintRight.color = 0xFF00F0FF.toInt()
        hudPaintRight.alpha = 220
        canvas.drawText("POWERS: ${world.storedPowerCharges}/3", sw - padX, rightY, hudPaintRight)
        rightY += 18f * scale

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
            val flareWarning = world.solarFlareState == 1
            hudPaintCenter.textSize = if (flareWarning) 16f * scale else 20f * scale
            hudPaintCenter.color = if (flareWarning) 0xFFFF4400.toInt() else 0xFFFFFFFF.toInt()
            hudPaintCenter.alpha = (255 * bannerAlpha).toInt().coerceIn(0, 255)
            val bannerTitle = if (flareWarning) {
                "⚠ SOLAR FLARE DETECTED [${String.format("%.1f", world.solarFlareTimer)}s] ⚠"
            } else {
                "WAVE ${world.currentWave} • ${world.wavePhaseName}"
            }
            canvas.drawText(bannerTitle, cx, bannerY + 28f * scale, hudPaintCenter)

            // Subtitle
            hudPaintCenter.textSize = 10f * scale
            hudPaintCenter.color = if (flareWarning) 0xFFFFB800.toInt() else if (world.wavePhaseName == "CRUNCH") 0xFFFFB800.toInt() else 0xFF00FF99.toInt()
            hudPaintCenter.alpha = (230 * bannerAlpha).toInt().coerceIn(0, 255)
            val subMsg = if (flareWarning) {
                "CORONAL EMP • PERKS DISRUPTED • METEORITES INCOMING"
            } else if (world.wavePhaseName == "CRUNCH") {
                "INCOMING THREATS — DEFEND TARGET"
            } else {
                "HARVEST VALLEY — COLLECT PRISMS"
            }
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
