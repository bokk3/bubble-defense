package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.engine.math.Vec2
import kotlin.math.*

/**
 * Zero-allocation microgravity float physics & gyroscopic response system.
 *
 * Implements 3D microgravity buoyancy, gyroscopic tilt reaction to touch gestures,
 * kinetic recoil spring-damping, directional RCS stabilization micro-burns, and
 * 3D orbiting escort satellites.
 *
 * Hot-path guarantee: 0 heap allocations per update.
 */
class SpaceFloatSystem {

    // Current float offsets relative to anchor (wu)
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set
    var elevationZ: Float = 0f
        private set

    // Gyroscopic tilt angles (radians)
    var pitch: Float = 0f
        private set
    var roll: Float = 0f
        private set

    // RCS micro-thruster exhaust intensities [0.0, 1.0]
    var rcsLeft: Float = 0f
        private set
    var rcsRight: Float = 0f
        private set
    var rcsBottom: Float = 0f
        private set

    // Kinetic recoil offset & velocity (damped spring)
    private var recoilX: Float = 0f
    private var recoilY: Float = 0f
    private var recoilVx: Float = 0f
    private var recoilVy: Float = 0f

    // Escort satellite angles (3 satellites)
    var satAngle0: Float = 0f
        private set
    var satAngle1: Float = 2.094f // 2*PI/3
        private set
    var satAngle2: Float = 4.188f // 4*PI/3
        private set

    private var simTime: Float = 0f

    fun reset() {
        offsetX = 0f
        offsetY = 0f
        elevationZ = 0f
        pitch = 0f
        roll = 0f
        rcsLeft = 0f
        rcsRight = 0f
        rcsBottom = 0f
        recoilX = 0f
        recoilY = 0f
        recoilVx = 0f
        recoilVy = 0f
        satAngle0 = 0f
        satAngle1 = 2.094f
        satAngle2 = 4.188f
        simTime = 0f
    }

    /**
     * Applies an impulse shock to the bubble's zero-g mounting (e.g. from parries or impacts).
     */
    fun applyRecoilImpulse(dirX: Float, dirY: Float, impulseMagnitude: Float) {
        val clampedMag = impulseMagnitude.coerceIn(0f, 350f)
        recoilVx += dirX * clampedMag
        recoilVy += dirY * clampedMag
    }

    /**
     * Updates zero-g float physics, recoil spring dynamics, and gyroscopic orientation.
     *
     * @param dt Fixed timestep in seconds.
     * @param inputActive True if player is currently drawing a shield.
     * @param swipeDx Raw pointer delta X in world units.
     * @param swipeDy Raw pointer delta Y in world units.
     */
    fun update(
        dt: Float,
        inputActive: Boolean,
        swipeDx: Float,
        swipeDy: Float
    ) {
        simTime += dt

        // 1. Multi-harmonic Lissajous zero-g micro-buoyancy
        val floatBaseX = 14f * sin(simTime * 1.35f) + 6f * cos(simTime * 2.10f)
        val floatBaseY = 10f * cos(simTime * 1.10f) + 4f * sin(simTime * 1.85f)
        elevationZ = 8f * sin(simTime * 0.95f)

        // 2. Damped harmonic spring for recoil recovery (zeta = 0.85, omega = 12 rad/s)
        val springK = 144f // omega^2
        val damping = 20.4f // 2 * zeta * omega
        val ax = -springK * recoilX - damping * recoilVx
        val ay = -springK * recoilY - damping * recoilVy

        recoilVx += ax * dt
        recoilVy += ay * dt
        recoilX += recoilVx * dt
        recoilY += recoilVy * dt

        offsetX = floatBaseX + recoilX
        offsetY = floatBaseY + recoilY

        // 3. Gyroscopic tilt towards swipe actions with spring return
        val targetRoll = if (inputActive) (swipeDx * 0.015f).coerceIn(-0.35f, 0.35f) else (recoilX * 0.005f).coerceIn(-0.25f, 0.25f)
        val targetPitch = if (inputActive) (swipeDy * 0.015f).coerceIn(-0.25f, 0.25f) else (recoilY * 0.005f).coerceIn(-0.20f, 0.20f)

        roll += (targetRoll - roll) * (dt * 10f).coerceAtMost(1f)
        pitch += (targetPitch - pitch) * (dt * 10f).coerceAtMost(1f)

        // 4. Directional RCS micro-thrusters firing to counteract velocity
        val rcsThreshold = 8f
        val driftVx = floatBaseX - offsetX
        rcsLeft = if (driftVx < -rcsThreshold || recoilVx > 20f) ((-driftVx) / 25f).coerceIn(0f, 1f) else (rcsLeft * 0.85f)
        rcsRight = if (driftVx > rcsThreshold || recoilVx < -20f) ((driftVx) / 25f).coerceIn(0f, 1f) else (rcsRight * 0.85f)
        rcsBottom = if (recoilVy > 20f) ((recoilVy) / 50f).coerceIn(0f, 1f) else (rcsBottom * 0.85f)

        // 5. Escort satellite orbital progression
        satAngle0 = (satAngle0 + dt * 1.8f) % (2f * PI.toFloat())
        satAngle1 = (satAngle1 + dt * 1.8f) % (2f * PI.toFloat())
        satAngle2 = (satAngle2 + dt * 1.8f) % (2f * PI.toFloat())
    }
}
