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
    var bubbleInvulnTimer: Float = 0f

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
        bubbleInvulnTimer = 0f
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
}
