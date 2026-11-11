package com.arcade.bubbledefense.domain.world

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import com.arcade.bubbledefense.engine.pool.ObstaclePool
import com.arcade.bubbledefense.engine.pool.ParticlePool
import com.arcade.bubbledefense.engine.pool.ShieldSegmentPool

/**
 * Game state enumeration.
 */
enum class GameState {
    READY,
    PLAYING,
    PAUSED_FOR_EXTERNAL_OVERLAY,
    GAME_OVER
}

/**
 * Mutable, zero-allocation simulation state container for Bubble Defense.
 *
 * Holds all pre-allocated Structure-of-Arrays entity pools and scalar gameplay state.
 * All memory is allocated once during construction (~54 KB total, see `docs/ARCHITECTURE.md` §4).
 * The [reset] method reinitializes game state in-place in `<0.1ms`, powering the sub-300ms
 * instant retry loop mandated by `kinetic-flow-architect`.
 */
class GameWorld(initialSeed: Int = 1337) {

    // ---------------------------------------------------------------------------------------------
    // Pre-Allocated Entity Pools
    // ---------------------------------------------------------------------------------------------

    val obstacles = ObstaclePool(GameConfig.OBSTACLE_POOL_CAPACITY)
    val shieldSegments = ShieldSegmentPool(GameConfig.SHIELD_SEGMENT_POOL_CAPACITY)
    val particles = ParticlePool(GameConfig.PARTICLE_POOL_CAPACITY)
    val floatingTexts = FloatingTextPool(GameConfig.FLOATING_TEXT_POOL_CAPACITY)
    val inputRing = InputRingBuffer(GameConfig.INPUT_RING_CAPACITY)

    // ---------------------------------------------------------------------------------------------
    // Simulation Clock & State
    // ---------------------------------------------------------------------------------------------

    var state: GameState = GameState.READY
    var gameTime: Float = 0f
    var fixedStepCount: Long = 0L

    // ---------------------------------------------------------------------------------------------
    // The Bubble (Defense Target)
    // ---------------------------------------------------------------------------------------------

    var bubbleX: Float = GameConfig.BUBBLE_CENTER_X
    var bubbleY: Float = GameConfig.BUBBLE_CENTER_Y
    var bubbleRadius: Float = GameConfig.BUBBLE_RADIUS
    var bubbleHp: Int = GameConfig.BUBBLE_MAX_HP
    var overshieldHp: Int = 0
    var bubbleInvulnTimer: Float = 0f
    var timeSinceLastDamage: Float = 0f

    /** Soft-body procedural harmonic wobble amplitudes for k in {3, 5, 7}. */
    val bubbleWobbleAmplitudes = FloatArray(3) { 0f }

    /** Proximity distance (wu) to the closest falling obstacle for the warning ring. */
    var closestObstacleDistance: Float = Float.MAX_VALUE

    // ---------------------------------------------------------------------------------------------
    // Ink Economy
    // ---------------------------------------------------------------------------------------------

    var ink: Float = GameConfig.INK_MAX
    var inkCooldownTimer: Float = 0f
    var activePointerCount: Int = 0

    // ---------------------------------------------------------------------------------------------
    // Altitude, Progression & Score
    // ---------------------------------------------------------------------------------------------

    var altitudeMeters: Float = 0f
    var ascentSpeed: Float = GameConfig.ASCENT_SPEED_BASE
    var score: Int = 0
    var bonusScore: Int = 0

    var comboCount: Int = 0
    var comboMultiplier: Float = 1.0f
    var comboTimer: Float = 0f

    var prismsCollected: Int = 0
    var perfectParriesTotal: Int = 0
    var parryTriggerCount: Int = 0

    var highScore: Int = 0
    var bestAltitudeMeters: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Juice & Sensory Dynamics (`kinetic-flow-architect`)
    // ---------------------------------------------------------------------------------------------

    /** Screen-shake trauma in `[0f, 1f]`; shake = trauma * trauma. */
    var trauma: Float = 0f

    /** Hit-stop real-time freeze duration remaining (s). */
    var hitStopTimer: Float = 0f

    /** Time-dilation multiplier applied to fixed physics updates (e.g. 0.25x in close call). */
    var timeScale: Float = 1.0f

    /** Duration remaining for dynamic slow-motion (s). */
    var slowMoTimer: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Wave Director & Sawtooth Pacing
    // ---------------------------------------------------------------------------------------------

    companion object {
        const val MAX_TELEGRAPHS: Int = 8
    }

    var directorThreatBudget: Float = 0f
    var directorWaveTimer: Float = 0f
    var directorSpawnCooldown: Float = 0f
    var currentWave: Int = 1
    var wavePhaseName: String = "CRUNCH"
    var waveAnnouncementTimer: Float = 2.0f
    var rngSeed: Int = initialSeed

    val telegraphX = FloatArray(MAX_TELEGRAPHS)
    val telegraphTimer = FloatArray(MAX_TELEGRAPHS)
    val telegraphType = ByteArray(MAX_TELEGRAPHS)
    var telegraphCount: Int = 0

    // ---------------------------------------------------------------------------------------------
    // Flow Economy & Relief Valves (`arcade-economy-architect`)
    // ---------------------------------------------------------------------------------------------

    var reviveUsedThisRun: Boolean = false
    var reviveEligible: Boolean = false
    var clutchReviveFuseTimer: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Sideline Sentinel Drones (Tactical Unlockable Defense)
    // ---------------------------------------------------------------------------------------------
    var droneLevel: Int = 0
    var droneDisruptedTimer: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Solar Flare & Cataclysm Dynamics
    // ---------------------------------------------------------------------------------------------
    var solarFlareState: Int = 0 // 0 = IDLE, 1 = WARNING, 2 = STORM
    var solarFlareTimer: Float = 0f
    var solarFlareIntensity: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // 3 Stored Special Powers
    // ---------------------------------------------------------------------------------------------
    var storedPowerCharges: Int = 1
    var activePowerType: Int = GameConfig.POWER_NONE
    var activePowerTimer: Float = 0f
    var parriesSinceLastCharge: Int = 0
    var empShockwaveRadius: Float = 0f
    var empShockwaveTimer: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Roguelike Tactical Protocols & Milestone Warp State
    // ---------------------------------------------------------------------------------------------
    var activeProtocolsMask: Int = 0
    var lastMilestoneWarp: Int = 0
    var warpDraftActive: Boolean = false
    val warpDraftChoices = IntArray(3)
    var emergencyCrystalTimer: Float = 0f
    var prismBatteryProgress: Int = 0
    var lastComboMilestoneRolled: Int = 0

    // ---------------------------------------------------------------------------------------------
    // Phase 15: The Hypernova Ultimate ("ULT") & In-Run Drone Scramble State
    // ---------------------------------------------------------------------------------------------
    var ultCharge: Float = 0f
    var ultActiveTimer: Float = 0f
    var ultStage: Int = 0 // 0 = INACTIVE, 1 = IMPLOSION, 2 = HYPER_BEAM, 3 = OVERDRIVE
    var droneOverdriveTimer: Float = 0f
    var droneOverdriveCooldown: Float = 0f
    var droneArrivalAnimTimer: Float = 0f
    var freeDroneDeploymentAvailable: Boolean = true

    init {
        reset(initialSeed)
    }

    /**
     * Resets simulation state in-place to initial conditions.
     * Guarantees 0ms GC allocations and completes within `<0.1ms`.
     */
    fun reset(seed: Int = rngSeed) {
        state = GameState.READY
        gameTime = 0f
        fixedStepCount = 0L

        // Clear pools
        obstacles.clear()
        shieldSegments.clear()
        particles.clear()
        floatingTexts.clear()
        inputRing.clear()

        // Reset Bubble
        bubbleX = GameConfig.BUBBLE_CENTER_X
        bubbleY = GameConfig.BUBBLE_CENTER_Y
        bubbleRadius = GameConfig.BUBBLE_RADIUS
        bubbleHp = GameConfig.BUBBLE_MAX_HP
        overshieldHp = 0
        bubbleInvulnTimer = 0f
        timeSinceLastDamage = 0f
        bubbleWobbleAmplitudes[0] = 0.020f
        bubbleWobbleAmplitudes[1] = 0.012f
        bubbleWobbleAmplitudes[2] = 0.006f
        closestObstacleDistance = Float.MAX_VALUE

        // Reset Ink
        ink = GameConfig.INK_MAX
        inkCooldownTimer = 0f
        activePointerCount = 0

        // Reset Progression
        altitudeMeters = 0f
        ascentSpeed = GameConfig.ASCENT_SPEED_BASE
        score = 0
        bonusScore = 0
        comboCount = 0
        comboMultiplier = 1.0f
        comboTimer = 0f
        prismsCollected = 0
        perfectParriesTotal = 0
        parryTriggerCount = 0

        // Reset Cataclysm & Powers
        droneDisruptedTimer = 0f
        solarFlareState = 0
        solarFlareTimer = 0f
        solarFlareIntensity = 0f
        storedPowerCharges = 1
        activePowerType = GameConfig.POWER_NONE
        activePowerTimer = 0f
        parriesSinceLastCharge = 0
        empShockwaveRadius = 0f
        empShockwaveTimer = 0f

        // Reset Protocols & Warps
        activeProtocolsMask = 0
        lastMilestoneWarp = 0
        warpDraftActive = false
        warpDraftChoices[0] = 0
        warpDraftChoices[1] = 0
        warpDraftChoices[2] = 0
        emergencyCrystalTimer = 0f
        prismBatteryProgress = 0
        lastComboMilestoneRolled = 0

        // Reset Ultimate & In-Run Drones
        ultCharge = 0f
        ultActiveTimer = 0f
        ultStage = 0
        droneOverdriveTimer = 0f
        droneOverdriveCooldown = 0f
        droneArrivalAnimTimer = 0f
        freeDroneDeploymentAvailable = true

        // Reset Juice
        trauma = 0f
        hitStopTimer = 0f
        timeScale = 1.0f
        slowMoTimer = 0f

        // Reset Director
        directorThreatBudget = 1.5f // Start with enough budget for immediate initial threat
        directorWaveTimer = 0f
        directorSpawnCooldown = 0.15f // Brief instant before first threat emerges from horizon
        currentWave = 1
        wavePhaseName = "CRUNCH"
        waveAnnouncementTimer = 2.0f
        rngSeed = if (seed != 0) seed else 1337
        telegraphCount = 0

        // Reset Economy
        reviveUsedThisRun = false
        reviveEligible = false
        clutchReviveFuseTimer = 0f
    }

    /** Triggers the 1-per-run Clutch Revive / EMP surge shockwave. */
    fun triggerClutchRevive() {
        if (reviveUsedThisRun) return
        reviveUsedThisRun = true
        reviveEligible = false
        clutchReviveFuseTimer = 0f

        // Restore Bubble HP and grant invulnerability
        bubbleHp = 1
        bubbleInvulnTimer = GameConfig.CLUTCH_REVIVE_INVULN_SECONDS
        ink = GameConfig.INK_MAX

        // EMP Surge: clear all active obstacles with shatter particles
        for (i in 0 until obstacles.count) {
            val ox = obstacles.x[i]
            val oy = obstacles.y[i]
            // Spawn electric particles
            for (p in 0 until 6) {
                particles.spawn(
                    ox, oy,
                    (p - 3) * 60f, -120f,
                    0.35f, 3f, 0f, 0xFF00F0FF.toInt()
                )
            }
        }
        obstacles.clear()

        // Slow-motion ramp back to 1.0x
        timeScale = 0.1f
        slowMoTimer = GameConfig.CLUTCH_REVIVE_RAMP_DURATION
        state = GameState.PLAYING
    }

    /** Adds trauma clamped to `[0f, 1f]`. */
    fun addTrauma(amount: Float) {
        val next = trauma + amount
        trauma = if (next > 1f) 1f else if (next < 0f) 0f else next
    }

    /** Triggers a real-time hit-stop freeze frame. */
    fun triggerHitStop(durationSeconds: Float) {
        if (durationSeconds > hitStopTimer) {
            hitStopTimer = durationSeconds
        }
    }

    /** Increments score and checks for new high-score record. */
    fun addScore(bonus: Int) {
        bonusScore += bonus
        score = altitudeMeters.toInt() + bonusScore
        if (score > highScore) {
            highScore = score
        }
    }

    /** Triggers one of the 3 stored special powers if charges are available. */
    fun triggerPower(powerType: Int): Boolean {
        if (storedPowerCharges <= 0 || activePowerType != GameConfig.POWER_NONE) return false
        storedPowerCharges--
        activePowerType = powerType
        when (powerType) {
            GameConfig.POWER_CHRONO_WARP -> {
                activePowerTimer = GameConfig.POWER_CHRONO_DURATION
                timeScale = GameConfig.POWER_CHRONO_TIMESCALE
                floatingTexts.spawn(bubbleX, bubbleY - 80f, -80f, 0.4f, 1.4f, FloatingTextPool.TEXT_CLOSE_CALL)
            }
            GameConfig.POWER_SUPERNOVA_EMP -> {
                activePowerTimer = 0.6f
                empShockwaveTimer = 0.6f
                empShockwaveRadius = 0f
                trauma = 0.95f
                // Instant radial impulse on all active obstacles
                for (i in 0 until obstacles.count) {
                    val oy = obstacles.y[i]
                    if (oy in 100f..1500f) {
                        val oType = obstacles.type[i]
                        if (oType == GameConfig.TYPE_NEEDLE || oType == GameConfig.TYPE_SHARD || obstacles.radius[i] <= 18f) {
                            obstacles.removeAt(i)
                            addScore(100)
                        } else {
                            obstacles.vy[i] = -GameConfig.POWER_EMP_BLAST_IMPULSE
                            obstacles.radius[i] = (obstacles.radius[i] * 0.75f).coerceAtLeast(14f)
                        }
                    }
                }
            }
            GameConfig.POWER_PLASMA_LANCE -> {
                activePowerTimer = GameConfig.POWER_LANCE_DURATION
                // Purge solar flare radiation if active
                if (solarFlareState == 1 || solarFlareState == 2) {
                    droneDisruptedTimer = 0f
                }
            }
        }
        return true
    }

    /** Increments parry counter towards next stored special power charge. */
    fun addParryPowerProgress() {
        parriesSinceLastCharge++
        if (parriesSinceLastCharge >= GameConfig.PARRIES_PER_POWER_CHARGE) {
            parriesSinceLastCharge = 0
            if (storedPowerCharges < GameConfig.MAX_STORED_POWERS) {
                storedPowerCharges++
                floatingTexts.spawn(bubbleX, bubbleY - 90f, -90f, 0.4f, 1.3f, FloatingTextPool.TEXT_BOUNTY)
            }
        }
    }

    /** Returns true if the given [protocolFlag] is currently active. */
    fun hasProtocol(protocolFlag: Int): Boolean = (activeProtocolsMask and protocolFlag) != 0

    /**
     * Installs a selected tactical protocol, applying immediate benefits
     * and resuming simulation from Warp Chamber.
     */
    fun installProtocol(protocolFlag: Int) {
        activeProtocolsMask = activeProtocolsMask or protocolFlag
        when (protocolFlag) {
            GameConfig.PROTOCOL_REPAIR_NANITES -> {
                if (bubbleHp < GameConfig.BUBBLE_MAX_HP) {
                    bubbleHp++
                } else {
                    overshieldHp = GameConfig.MAX_OVERSHIELD_HP
                }
            }
            GameConfig.PROTOCOL_DEEP_INK_WELL -> {
                ink = GameConfig.PROTOCOL_DEEP_INK_MAX
            }
        }
        warpDraftActive = false
        timeScale = 1.0f
        floatingTexts.spawn(bubbleX, bubbleY - 80f, -80f, 0.4f, 1.4f, FloatingTextPool.TEXT_BOUNTY)
    }

    /** Increments prism battery towards special power charges if PROTOCOL_PRISM_BATTERY is active. */
    fun addPrismHarvestProgress() {
        if (!hasProtocol(GameConfig.PROTOCOL_PRISM_BATTERY)) return
        prismBatteryProgress++
        if (prismBatteryProgress >= GameConfig.PROTOCOL_PRISM_BATTERY_THRESHOLD) {
            prismBatteryProgress = 0
            if (storedPowerCharges < GameConfig.MAX_STORED_POWERS) {
                storedPowerCharges++
                floatingTexts.spawn(bubbleX, bubbleY - 90f, -90f, 0.4f, 1.3f, FloatingTextPool.TEXT_BOUNTY)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 15: The Hypernova Ultimate ("ULT") & In-Run Drone Scramble Logic
    // ---------------------------------------------------------------------------------------------

    /** Accumulates Ultimate charge up to 100%. Spawns alert when fully charged. */
    fun addUltCharge(amount: Float) {
        if (ultActiveTimer > 0f) return // Do not charge while ult is actively firing
        val prev = ultCharge
        ultCharge = (ultCharge + amount).coerceIn(0f, GameConfig.ULT_MAX_CHARGE)
        if (prev < GameConfig.ULT_MAX_CHARGE && ultCharge >= GameConfig.ULT_MAX_CHARGE) {
            floatingTexts.spawn(bubbleX, bubbleY - 100f, -90f, 0.5f, 1.5f, FloatingTextPool.TEXT_ULT_READY)
            addTrauma(0.18f)
        }
    }

    /** Triggers the multi-stage Hypernova Ultimate. */
    fun triggerUltimate(): Boolean {
        if (ultCharge < GameConfig.ULT_MAX_CHARGE || ultActiveTimer > 0f) return false
        ultCharge = 0f
        ultActiveTimer = GameConfig.ULT_DURATION
        ultStage = 1 // Stage 1: Gravitational Implosion
        triggerHitStop(0.040f) // 40ms cinematic hit-stop
        addTrauma(0.85f)
        floatingTexts.spawn(bubbleX, bubbleY - 110f, -100f, 0.6f, 1.6f, FloatingTextPool.TEXT_HYPERNOVA)
        return true
    }

    /** Checks whether the player has enough resources/cooldown to scramble a drone or overdrive. */
    fun canScrambleDrone(): Boolean {
        return if (droneLevel < 2) {
            freeDroneDeploymentAvailable || prismsCollected >= GameConfig.DRONE_SCRAMBLE_PRISM_COST
        } else {
            droneOverdriveCooldown <= 0f && droneOverdriveTimer <= 0f
        }
    }

    /**
     * Tactically scrambles a Sentinel Drone mid-run:
     * - Level 0 -> 1: Scrambles Left Sentinel
     * - Level 1 -> 2: Scrambles Right Sentinel
     * - Level 2+: Triggers Overdrive Barrage
     */
    fun scrambleDrone(): Boolean {
        if (droneLevel < 2) {
            if (!freeDroneDeploymentAvailable) {
                if (prismsCollected < GameConfig.DRONE_SCRAMBLE_PRISM_COST) return false
                prismsCollected -= GameConfig.DRONE_SCRAMBLE_PRISM_COST
            } else {
                freeDroneDeploymentAvailable = false
            }
            droneLevel++
            droneArrivalAnimTimer = GameConfig.DRONE_ARRIVAL_DURATION
            floatingTexts.spawn(
                if (droneLevel == 1) 75f else 925f,
                800f, -90f, 0.4f, 1.4f, FloatingTextPool.TEXT_BOUNTY
            )
            addTrauma(0.20f)
            return true
        } else {
            return triggerDroneOverdrive()
        }
    }

    /** Triggers 5.0s of hyper-speed 3x rapid drone fire with amber plasma tracers. */
    fun triggerDroneOverdrive(): Boolean {
        if (droneOverdriveCooldown > 0f || droneOverdriveTimer > 0f) return false
        droneOverdriveTimer = GameConfig.DRONE_OVERDRIVE_DURATION
        droneOverdriveCooldown = GameConfig.DRONE_OVERDRIVE_COOLDOWN
        floatingTexts.spawn(bubbleX, bubbleY - 90f, -90f, 0.4f, 1.4f, FloatingTextPool.TEXT_OVERDRIVE)
        addTrauma(0.30f)
        return true
    }

    /**
     * Seamlessly rolls and installs a random uninstalled tactical protocol.
     * Guaranteed zero-pause and zero time-dilation interruption.
     */
    fun rollAndInstallProtocol(seedVal: Int = rngSeed): Int {
        var uninstalledCount = 0
        for (i in 0 until GameConfig.TOTAL_PROTOCOLS) {
            val flag = 1 shl i
            if (!hasProtocol(flag)) {
                uninstalledCount++
            }
        }
        if (uninstalledCount == 0) return 0 // All protocols already installed!

        var pickIndex = (kotlin.math.abs(seedVal) % uninstalledCount)
        var targetFlag = 0
        for (i in 0 until GameConfig.TOTAL_PROTOCOLS) {
            val flag = 1 shl i
            if (!hasProtocol(flag)) {
                if (pickIndex == 0) {
                    targetFlag = flag
                    break
                }
                pickIndex--
            }
        }
        if (targetFlag != 0) {
            installProtocol(targetFlag)
            floatingTexts.spawn(bubbleX, bubbleY - 80f, -90f, 0.5f, 1.4f, FloatingTextPool.TEXT_PROTOCOL_UNLOCKED)
        }
        return targetFlag
    }
}
