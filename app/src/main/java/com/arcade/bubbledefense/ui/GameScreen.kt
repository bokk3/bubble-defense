package com.arcade.bubbledefense.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.arcade.bubbledefense.domain.economy.BubbleSkin
import com.arcade.bubbledefense.domain.economy.EconomyGateway
import com.arcade.bubbledefense.domain.economy.MockEconomyService
import com.arcade.bubbledefense.domain.economy.RewardPlacement
import com.arcade.bubbledefense.domain.economy.RewardResult
import com.arcade.bubbledefense.domain.economy.ShieldSkin
import com.arcade.bubbledefense.domain.systems.DirectorSystem
import com.arcade.bubbledefense.domain.systems.DroneSystem
import com.arcade.bubbledefense.domain.systems.JuiceSystem
import com.arcade.bubbledefense.domain.systems.PhysicsSystem
import com.arcade.bubbledefense.domain.systems.ShieldInputSystem
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.audio.PatternSequencer
import com.arcade.bubbledefense.engine.audio.VoidSynthEngine
import com.arcade.bubbledefense.engine.loop.FixedGameLoop
import com.arcade.bubbledefense.engine.loop.ViewportScaler
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import com.arcade.bubbledefense.ui.render.ProceduralRenderer
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Main game screen composable for Bubble Defense.
 *
 * Enforces Compose Phase Discipline (`kotlin-ux-architect`):
 * - Fast-changing simulation state is decoupled from Composition.
 * - Frame invalidation runs inside the Draw Phase driven by [FixedGameLoop.frameTick].
 * - Tabular figures (`tnum`) applied to all numerical readouts to eliminate layout jank.
 * - High-refresh gestures sampled sequentially via `historical` before `position`.
 * - Quarantined economy integration with 1-per-run Clutch Revive and 180s cooldown interstitials.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    initialSeed: Int = 1337,
    economyGateway: EconomyGateway = remember { MockEconomyService() }
) {
    // 1. Long-lived state instances (allocated once per screen lifecycle)
    val world = remember { GameWorld(initialSeed) }
    val scaler = remember { ViewportScaler() }
    val renderer = remember { ProceduralRenderer() }

    val inputSystem = remember { ShieldInputSystem(world) }
    val physicsSystem = remember { PhysicsSystem(world) }
    val directorSystem = remember { DirectorSystem(world, initialSeed) }
    val juiceSystem = remember { JuiceSystem(world) }

    // Procedural Software DSP Synthesizer & Algorithmic Sequencer
    val synth = remember { VoidSynthEngine() }
    val sequencer = remember { PatternSequencer(synth) }
    var isMuted by remember { mutableStateOf(false) }
    var lastParryCount by remember { mutableStateOf(0) }

    // Sideline Sentinel Drone System
    val droneSystem = remember { DroneSystem(world, synth) }

    // Sync drone upgrade level from economy
    world.droneLevel = economyGateway.droneUpgradeLevel

    // Lifecycle: start audio thread on enter, release hardware track on exit
    DisposableEffect(Unit) {
        synth.start()
        onDispose {
            synth.stop()
        }
    }

    // Sync procedural cosmetic skins
    renderer.activeShieldSkin = ShieldSkin.findById(economyGateway.equippedShieldSkinId)
    renderer.activeBubbleSkin = BubbleSkin.findById(economyGateway.equippedBubbleSkinId)

    var deathProcessed by remember { mutableStateOf(false) }
    var currentGameState by remember { mutableStateOf(world.state) }

    // Frame interpolation alpha
    var renderAlpha by remember { mutableStateOf(0f) }

    // Fixed game loop advancing systems at 120Hz physics / 480Hz narrowphase
    val gameLoop = remember {
        FixedGameLoop(
            world = world,
            fixedStepListener = { dt, _ ->
                if (world.state == GameState.PLAYING || world.state == GameState.READY) {
                    world.gameTime += dt
                    val targetBaseSpeed = min(
                        GameConfig.ASCENT_SPEED_MAX,
                        GameConfig.ASCENT_SPEED_BASE + GameConfig.ASCENT_ACCEL * world.gameTime
                    )
                    if (world.ascentSpeed < targetBaseSpeed) {
                        world.ascentSpeed = targetBaseSpeed
                    } else if (world.ascentSpeed > targetBaseSpeed) {
                        world.ascentSpeed = max(targetBaseSpeed, world.ascentSpeed - 80f * dt)
                    }

                    world.altitudeMeters += (world.ascentSpeed * dt) / GameConfig.WU_PER_METER
                    world.score = world.altitudeMeters.toInt() + world.bonusScore
                    if (world.score > world.highScore) {
                        world.highScore = world.score
                    }
                    // Step wave director
                    directorSystem.step(dt)
                }
                // Step input, physics, drone, and juice systems
                inputSystem.step(dt)
                physicsSystem.stepFrame(dt)
                droneSystem.step(dt)
                juiceSystem.step(dt)

                // Step procedural audio sequencer
                if (world.parryTriggerCount > lastParryCount) {
                    lastParryCount = world.parryTriggerCount
                    sequencer.onParry()
                }
                if (world.state == GameState.PLAYING || world.state == GameState.READY) {
                    sequencer.step(dt, world)
                }

                // Handle Game Over and Clutch Revive Fuse
                if (world.state == GameState.GAME_OVER) {
                    if (!deathProcessed) {
                        deathProcessed = true
                        if (!world.reviveUsedThisRun && economyGateway.isClutchReviveEligible(world)) {
                            world.reviveEligible = true
                            world.clutchReviveFuseTimer = GameConfig.CLUTCH_REVIVE_FUSE_SECONDS
                        } else {
                            world.reviveEligible = false
                            world.clutchReviveFuseTimer = 0f
                        }
                        economyGateway.recordRunCompleted(
                            runDurationSeconds = world.gameTime,
                            score = world.score,
                            parries = world.perfectParriesTotal,
                            prismsEarned = world.prismsCollected
                        )
                    }

                    if (world.clutchReviveFuseTimer > 0f) {
                        world.clutchReviveFuseTimer -= dt
                        if (world.clutchReviveFuseTimer <= 0f) {
                            world.reviveEligible = false
                        }
                    }
                } else if (world.state == GameState.PLAYING) {
                    deathProcessed = false
                }
            },
            frameListener = { _, alpha ->
                renderAlpha = alpha
            }
        )
    }

    // Monotonically increasing frame state to trigger draw pass without recomposing UI tree
    var drawTrigger by remember { mutableLongStateOf(0L) }
    var currentCharges by remember { mutableIntStateOf(world.storedPowerCharges) }
    var currentActivePower by remember { mutableIntStateOf(world.activePowerType) }

    // 2. Drive 120Hz Display Render Loop via withFrameNanos
    LaunchedEffect(Unit) {
        gameLoop.start()
        world.state = GameState.PLAYING
        currentGameState = GameState.PLAYING
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                gameLoop.onFrame(frameTimeNanos)
                drawTrigger = gameLoop.frameTick
                if (currentGameState != world.state) {
                    currentGameState = world.state
                }
                if (currentCharges != world.storedPowerCharges) {
                    currentCharges = world.storedPowerCharges
                }
                if (currentActivePower != world.activePowerType) {
                    currentActivePower = world.activePowerType
                }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ProceduralRenderer.COLOR_BG)
    ) {
        // 3. Procedural Canvas with Zero-Alloc DrawScope & Trauma Camera Shake
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = juiceSystem.shakeOffsetX
                    translationY = juiceSystem.shakeOffsetY
                    rotationZ = juiceSystem.shakeRotationRad * (180f / PI.toFloat())
                    val trauma = world.trauma
                    val zoom = 1f + trauma * 0.035f
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, GameConfig.CAMERA_HORIZON_RATIO_Y)
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
                                    // ACTION_DOWN - only accept touches safely below the horizon
                                    if (scaler.isDrawableScreenY(change.position.y)) {
                                        val wx = scaler.screenToWorldLaneX(change.position.x, change.position.y)
                                        val wy = scaler.screenToWorldLaneY(change.position.y)
                                        world.inputRing.push(wx, wy, uptimeNanos, pId, InputRingBuffer.ACTION_DOWN)
                                        change.consume()
                                    }
                                } else if (change.pressed && change.previousPressed) {
                                    // High-refresh historical pointer sampling
                                    val historicalSize = change.historical.size
                                    for (h in 0 until historicalSize) {
                                        val hist = change.historical[h]
                                        val hx = scaler.screenToWorldLaneX(hist.position.x, hist.position.y)
                                        val hy = scaler.screenToWorldLaneY(hist.position.y)
                                        world.inputRing.push(hx, hy, hist.uptimeMillis * 1_000_000L, pId, InputRingBuffer.ACTION_MOVE)
                                    }

                                    // ACTION_MOVE (current position)
                                    val wx = scaler.screenToWorldLaneX(change.position.x, change.position.y)
                                    val wy = scaler.screenToWorldLaneY(change.position.y)
                                    world.inputRing.push(wx, wy, uptimeNanos, pId, InputRingBuffer.ACTION_MOVE)
                                    change.consume()
                                } else if (!change.pressed && change.previousPressed) {
                                    // ACTION_UP
                                    val wx = scaler.screenToWorldLaneX(change.position.x, change.position.y)
                                    val wy = scaler.screenToWorldLaneY(change.position.y)
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
            renderer.draw(this, world, scaler, renderAlpha, droneSystem)
        }

        // 4. Procedural Audio Mute Toggle Button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 88.dp, end = 16.dp)
                .background(
                    color = if (isMuted) Color(0x33FF0055) else Color(0x2200F0FF),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable {
                    isMuted = !isMuted
                    synth.isMuted = isMuted
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isMuted) "🔇 MUTE" else "🔊 FUNK ON",
                color = if (isMuted) Color(0xFFFF0055) else ProceduralRenderer.COLOR_CYAN,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                style = tnumStyle
            )
        }

        // 5. Tactical Special Powers Hotbar Dock (Diegetic bottom command deck)
        if (currentGameState == GameState.PLAYING) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .background(Color(0xE6050C16), shape = RoundedCornerShape(14.dp))
                    .border(1.dp, ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Powers Charge Counter
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "POWERS",
                        color = ProceduralRenderer.COLOR_CYAN,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        style = tnumStyle
                    )
                    Text(
                        text = "$currentCharges/3",
                        color = if (currentCharges > 0) ProceduralRenderer.COLOR_WHITE else Color(0xFF607080),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = tnumStyle
                    )
                }

                SpecialPowerButton(
                    modifier = Modifier.weight(1f),
                    name = "CHRONO",
                    icon = "⏳",
                    color = ProceduralRenderer.COLOR_CYAN,
                    charges = currentCharges,
                    isActive = currentActivePower == GameConfig.POWER_CHRONO_WARP,
                    onClick = {
                        if (world.triggerPower(GameConfig.POWER_CHRONO_WARP)) {
                            currentCharges = world.storedPowerCharges
                            currentActivePower = world.activePowerType
                        }
                    }
                )
                SpecialPowerButton(
                    modifier = Modifier.weight(1f),
                    name = "EMP",
                    icon = "⚡",
                    color = ProceduralRenderer.COLOR_AMBER,
                    charges = currentCharges,
                    isActive = currentActivePower == GameConfig.POWER_SUPERNOVA_EMP,
                    onClick = {
                        if (world.triggerPower(GameConfig.POWER_SUPERNOVA_EMP)) {
                            currentCharges = world.storedPowerCharges
                            currentActivePower = world.activePowerType
                        }
                    }
                )
                SpecialPowerButton(
                    modifier = Modifier.weight(1f),
                    name = "LANCE",
                    icon = "🔱",
                    color = ProceduralRenderer.COLOR_MAGENTA,
                    charges = currentCharges,
                    isActive = currentActivePower == GameConfig.POWER_PLASMA_LANCE,
                    onClick = {
                        if (world.triggerPower(GameConfig.POWER_PLASMA_LANCE)) {
                            currentCharges = world.storedPowerCharges
                            currentActivePower = world.activePowerType
                        }
                    }
                )
            }
        }

        // 6. Interactive Game Over Modal with Clutch Revive & Sub-300ms Instant Retry
        if (currentGameState == GameState.GAME_OVER) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
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
                        text = "SCORE: ${world.score} | HIGH: ${world.highScore}",
                        color = ProceduralRenderer.COLOR_CYAN,
                        fontSize = 16.sp,
                        style = tnumStyle
                    )
                    Text(
                        text = "PRISMS COLLECTED: ${world.prismsCollected}",
                        color = ProceduralRenderer.COLOR_GREEN,
                        fontSize = 15.sp,
                        style = tnumStyle
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 1-per-run Clutch Revive / EMP Surge Prompt
                    if (world.reviveEligible && world.clutchReviveFuseTimer > 0f) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    economyGateway.showRewardedAd(RewardPlacement.ClutchRevive) { result ->
                                        if (result is RewardResult.Granted) {
                                            world.triggerClutchRevive()
                                            deathProcessed = false
                                            currentCharges = world.storedPowerCharges
                                            currentActivePower = world.activePowerType
                                            currentGameState = GameState.PLAYING
                                        }
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "⚡ CLUTCH REVIVE (EMP SURGE) [${String.format("%.1f", world.clutchReviveFuseTimer)}s]",
                                color = ProceduralRenderer.COLOR_CYAN,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                style = tnumStyle
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 2x Prism Doubler (when prisms collected >= 10)
                    if (world.prismsCollected >= 10) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = ProceduralRenderer.COLOR_GREEN.copy(alpha = 0.20f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    economyGateway.showRewardedAd(RewardPlacement.DoublePrismHarvest) { result ->
                                        if (result is RewardResult.Granted) {
                                            economyGateway.addPrisms(world.prismsCollected)
                                            world.prismsCollected *= 2
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "💎 2x PRISM BOUNTY (+${world.prismsCollected}) [AD]",
                                color = ProceduralRenderer.COLOR_GREEN,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = tnumStyle
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Tactical Drone Bay Upgrade Card
                    val nextDroneCost = economyGateway.getNextDroneUpgradeCost()
                    val totalPrisms = economyGateway.getPrismBalance()
                    val canAffordDrone = nextDroneCost > 0 && totalPrisms >= nextDroneCost
                    val droneTitle = when (world.droneLevel) {
                        0 -> "DEPLOY SENTINEL DRONE (30 💎)"
                        1 -> "UPGRADE DUAL SENTINELS (60 💎)"
                        2 -> "OVERCHARGE SENTINELS (100 💎)"
                        else -> "SENTINELS: MAX OVERCHARGE (LVL 3)"
                    }
                    val droneSubtext = when (world.droneLevel) {
                        0 -> "Auto-fires plasma beams at closest threats"
                        1 -> "Deploys 2nd sentinel on right shoulder rail"
                        2 -> "Rapid +30% fire rate and kinetic knockback"
                        else -> "Peak tactical automation achieved"
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (canAffordDrone) ProceduralRenderer.COLOR_CYAN.copy(alpha = 0.22f)
                                else Color(0x33101E33),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = canAffordDrone) {
                                if (economyGateway.upgradeDrone()) {
                                    world.droneLevel = economyGateway.droneUpgradeLevel
                                    droneSystem.reset()
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = droneTitle,
                                color = if (canAffordDrone) ProceduralRenderer.COLOR_CYAN
                                else if (nextDroneCost > 0) Color(0xFF6A85A0)
                                else ProceduralRenderer.COLOR_AMBER,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                style = tnumStyle
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = droneSubtext,
                                color = Color(0xFFA0B4C8),
                                fontSize = 10.sp,
                                style = tnumStyle
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Sub-300ms Instant Retry Button
                    Box(
                        modifier = Modifier
                            .background(
                                color = ProceduralRenderer.COLOR_AMBER.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                economyGateway.showInterstitialIfAllowed {
                                    world.reset()
                                    inputSystem.reset()
                                    directorSystem.reset()
                                    droneSystem.reset()
                                    sequencer.reset()
                                    lastParryCount = 0
                                    deathProcessed = false
                                    currentCharges = world.storedPowerCharges
                                    currentActivePower = world.activePowerType
                                    world.state = GameState.PLAYING
                                    currentGameState = GameState.PLAYING
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "TAP TO RETRY",
                            color = ProceduralRenderer.COLOR_AMBER,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            style = tnumStyle
                        )
                    }
                }
            }
        }
    }
}

/**
 * Diegetic tactical special power invocation button.
 * Zero-allocation Compose button with visual feedback on charges and active power state.
 */
@Composable
private fun SpecialPowerButton(
    modifier: Modifier = Modifier,
    name: String,
    icon: String,
    color: Color,
    charges: Int,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val canActivate = charges > 0 && !isActive
    val alpha = if (isActive) 1f else if (canActivate) 0.95f else 0.35f
    val borderColor = if (isActive) color else if (canActivate) color.copy(alpha = 0.6f) else Color(0x33405060)
    val bgColor = if (isActive) color.copy(alpha = 0.28f) else if (canActivate) color.copy(alpha = 0.12f) else Color(0x22101824)

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = canActivate) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$icon $name",
                color = color.copy(alpha = alpha),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Text(
                text = if (isActive) "ACTIVE" else if (canActivate) "READY" else "OFFLINE",
                color = if (isActive) color else if (canActivate) ProceduralRenderer.COLOR_WHITE else Color(0xFF607080),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}
