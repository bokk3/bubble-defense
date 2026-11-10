package com.arcade.bubbledefense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.systems.PhysicsSystem
import com.arcade.bubbledefense.domain.systems.ShieldInputSystem
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.loop.FixedGameLoop
import com.arcade.bubbledefense.engine.loop.ViewportScaler
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import com.arcade.bubbledefense.ui.render.ProceduralRenderer
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Main game screen composable for Bubble Defense.
 *
 * Enforces Compose Phase Discipline (`kotlin-ux-architect`):
 * - Fast-changing simulation state is decoupled from Composition.
 * - Frame invalidation runs inside the Draw Phase driven by [FixedGameLoop.frameTick].
 * - Tabular figures (`tnum`) applied to all numerical readouts to eliminate layout jank.
 * - High-refresh gestures sampled sequentially via `historical` before `position`.
 */
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    initialSeed: Int = 1337
) {
    // 1. Long-lived state instances (allocated once per screen lifecycle)
    val world = remember { GameWorld(initialSeed) }
    val scaler = remember { ViewportScaler() }
    val renderer = remember { ProceduralRenderer() }

    val inputSystem = remember { ShieldInputSystem(world) }
    val physicsSystem = remember { PhysicsSystem(world) }

    // Frame interpolation alpha
    var renderAlpha by remember { mutableStateOf(0f) }

    // Fixed game loop advancing systems at 120Hz physics / 480Hz narrowphase
    val gameLoop = remember {
        FixedGameLoop(
            world = world,
            fixedStepListener = { dt, _ ->
                if (world.state == GameState.PLAYING || world.state == GameState.READY) {
                    world.gameTime += dt
                    world.altitudeMeters += (world.ascentSpeed * dt) / GameConfig.WU_PER_METER
                    world.score = world.altitudeMeters.toInt() + world.bonusScore
                    if (world.score > world.highScore) {
                        world.highScore = world.score
                    }
                }
                // Step input and physics systems
                inputSystem.step(dt)
                physicsSystem.stepFrame(dt)
            },
            frameListener = { _, alpha ->
                renderAlpha = alpha
            }
        )
    }

    // Monotonically increasing frame state to trigger draw pass without recomposing UI tree
    var drawTrigger by remember { mutableLongStateOf(0L) }

    // 2. Drive 120Hz Display Render Loop via withFrameNanos
    LaunchedEffect(Unit) {
        gameLoop.start()
        world.state = GameState.PLAYING
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                gameLoop.onFrame(frameTimeNanos)
                drawTrigger = gameLoop.frameTick
            }
        }
    }

    // Tabular numerals font style
    val tnumStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum"
        )
    }

    // Camera Trauma Shake calculation
    val trauma = world.trauma
    val shakeIntensity = trauma * trauma
    val time = world.gameTime
    val shakeOffsetX = if (shakeIntensity > 0f) {
        sin(time * GameConfig.SHAKE_NOISE_FREQUENCY_HZ * (2f * PI.toFloat())) * shakeIntensity * GameConfig.MAX_SHAKE_OFFSET_PX
    } else 0f
    val shakeOffsetY = if (shakeIntensity > 0f) {
        cos(time * GameConfig.SHAKE_NOISE_FREQUENCY_HZ * 1.3f * (2f * PI.toFloat())) * shakeIntensity * GameConfig.MAX_SHAKE_OFFSET_PX
    } else 0f
    val shakeRotation = if (shakeIntensity > 0f) {
        sin(time * GameConfig.SHAKE_NOISE_FREQUENCY_HZ * 0.7f * (2f * PI.toFloat())) * shakeIntensity * GameConfig.MAX_SHAKE_ROTATION_RAD
    } else 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ProceduralRenderer.COLOR_BG)
    ) {
        // 3. Procedural Canvas with Zero-Alloc DrawScope
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = shakeOffsetX
                    translationY = shakeOffsetY
                    rotationZ = shakeRotation * (180f / PI.toFloat())
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val uptimeNanos = System.nanoTime()

                            for (i in 0 until event.changes.size) {
                                val change = event.changes[i]
                                val pId = change.id.value.toInt()

                                if (change.pressed && !change.previousPressed) {
                                    // ACTION_DOWN
                                    val wx = scaler.screenToWorldX(change.position.x)
                                    val wy = scaler.screenToWorldY(change.position.y)
                                    world.inputRing.push(wx, wy, uptimeNanos, pId, InputRingBuffer.ACTION_DOWN)
                                    change.consume()
                                } else if (change.pressed && change.previousPressed) {
                                    // High-refresh historical pointer sampling
                                    val historicalSize = change.historical.size
                                    for (h in 0 until historicalSize) {
                                        val hist = change.historical[h]
                                        val hx = scaler.screenToWorldX(hist.position.x)
                                        val hy = scaler.screenToWorldY(hist.position.y)
                                        world.inputRing.push(hx, hy, hist.uptimeMillis * 1_000_000L, pId, InputRingBuffer.ACTION_MOVE)
                                    }

                                    // ACTION_MOVE (current position)
                                    val wx = scaler.screenToWorldX(change.position.x)
                                    val wy = scaler.screenToWorldY(change.position.y)
                                    world.inputRing.push(wx, wy, uptimeNanos, pId, InputRingBuffer.ACTION_MOVE)
                                    change.consume()
                                } else if (!change.pressed && change.previousPressed) {
                                    // ACTION_UP
                                    val wx = scaler.screenToWorldX(change.position.x)
                                    val wy = scaler.screenToWorldY(change.position.y)
                                    world.inputRing.push(wx, wy, uptimeNanos, pId, InputRingBuffer.ACTION_UP)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
        ) {
            // Read drawTrigger to register Draw-phase dependency
            @Suppress("UNUSED_VARIABLE")
            val tick = drawTrigger

            // Update viewport scaler dimensions if screen resized
            if (scaler.screenWidthPx != size.width || scaler.screenHeightPx != size.height) {
                scaler.update(size.width, size.height)
            }

            // Execute zero-allocation render pass
            renderer.draw(this, world, scaler, renderAlpha)
        }

        // 4. Diegetic HUD Overlay (Top Safe Area)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Score & Altitude
                Column {
                    Text(
                        text = "ALTITUDE",
                        color = ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        style = tnumStyle
                    )
                    Text(
                        text = "${world.altitudeMeters.toInt()}m",
                        color = ProceduralRenderer.COLOR_WHITE,
                        fontSize = 24.sp,
                        style = tnumStyle
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Score Readout
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SCORE",
                        color = ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        style = tnumStyle
                    )
                    Text(
                        text = "${world.score}",
                        color = ProceduralRenderer.COLOR_WHITE,
                        fontSize = 24.sp,
                        style = tnumStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Combo & Multiplier Banner
            if (world.comboCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "COMBO x${world.comboCount}",
                        color = ProceduralRenderer.COLOR_AMBER,
                        fontSize = 13.sp,
                        style = tnumStyle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${String.format("%.2f", world.comboMultiplier)}x)",
                        color = ProceduralRenderer.COLOR_CYAN,
                        fontSize = 12.sp,
                        style = tnumStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ink Energy Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.15f))
            ) {
                val inkFraction = (world.ink / GameConfig.INK_MAX).coerceIn(0f, 1f)
                val inkColor = if (inkFraction > 0.25f) ProceduralRenderer.COLOR_CYAN else ProceduralRenderer.COLOR_AMBER

                Box(
                    modifier = Modifier
                        .fillMaxWidth(inkFraction)
                        .height(4.dp)
                        .background(inkColor)
                )
            }
        }

        // 5. Game Over Instant Retry Overlay
        if (world.state == GameState.GAME_OVER) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (i in 0 until event.changes.size) {
                                    val change = event.changes[i]
                                    if (change.pressed && !change.previousPressed) {
                                        // Sub-300ms Instant Retry: In-place pool reset
                                        world.reset()
                                        inputSystem.reset()
                                        world.state = GameState.PLAYING
                                        change.consume()
                                        break
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "BUBBLE POPPED",
                        color = ProceduralRenderer.COLOR_MAGENTA,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        style = tnumStyle
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "FINAL ALTITUDE: ${world.altitudeMeters.toInt()}m",
                        color = ProceduralRenderer.COLOR_WHITE,
                        fontSize = 18.sp,
                        style = tnumStyle
                    )
                    Text(
                        text = "HIGH SCORE: ${world.highScore}",
                        color = ProceduralRenderer.COLOR_CYAN,
                        fontSize = 16.sp,
                        style = tnumStyle
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "TAP ANYWHERE TO RETRY",
                        color = ProceduralRenderer.COLOR_AMBER,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = tnumStyle
                    )
                }
            }
        }
    }
}
