package com.arcade.bubbledefense.engine.audio

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld

/**
 * Strudel-inspired algorithmic pattern sequencer driving funky cosmic void grooves.
 *
 * Implements the 3-Tier Dopamine Clock & Sensory Matrix (`kinetic-flow-architect`):
 * - 16th-note clock scheduler with micro-swing (funky syncopated pocket).
 * - Syncopated slap bassline in C minor / Dorian mode with octave pops.
 * - Dynamic arrangement: Stripped-down eerie space exploration in HARVEST phase vs high-octane driving funk in CRUNCH phase.
 * - Adaptive tempo scaling linked to player velocity (106 BPM at base speed -> 136 BPM at 950 wu/s).
 * - Relativistic parry Shepard modulation upon perfect parries.
 * - Zero heap allocations during hot-loop `step(dt)` updates.
 */
class PatternSequencer(
    val synth: VoidSynthEngine
) {
    companion object {
        const val TOTAL_STEPS = 32 // 2 bars of 16th notes (4/4 time)

        // Musical note frequencies (C minor / Dorian mode)
        const val NOTE_C2 = 65.41f
        const val NOTE_EB2 = 77.78f
        const val NOTE_F2 = 87.31f
        const val NOTE_G2 = 97.99f
        const val NOTE_BB2 = 116.54f
        const val NOTE_C3 = 130.81f
        const val NOTE_EB3 = 155.56f
        const val NOTE_F3 = 174.61f
        const val NOTE_G3 = 196.00f
        const val NOTE_BB3 = 233.08f
        const val NOTE_C4 = 261.63f
        const val NOTE_D4 = 293.66f
        const val NOTE_EB4 = 311.13f
        const val NOTE_G4 = 392.00f
        const val NOTE_BB4 = 466.16f
        const val NOTE_C5 = 523.25f

        // Pre-allocated static pattern tables (32 steps)
        // Bass Note Frequencies (0f = rest)
        val BASS_NOTES = floatArrayOf(
            NOTE_C2,  0f,       0f,       NOTE_C3,  // 0..3 (C2 slap, C3 pop)
            0f,       0f,       NOTE_EB2, 0f,       // 4..7 (Eb2 slap)
            NOTE_F2,  0f,       NOTE_G2,  0f,       // 8..11 (F2, G2 slap)
            NOTE_BB2, 0f,       NOTE_C3,  0f,       // 12..15 (Bb2 slap, C3 pop)
            NOTE_C2,  0f,       0f,       NOTE_C3,  // 16..19 (C2 slap, C3 pop)
            0f,       0f,       NOTE_F2,  0f,       // 20..23 (F2 slap)
            NOTE_G2,  0f,       0f,       NOTE_BB2, // 24..27 (G2 slap, Bb2 slap)
            NOTE_C3,  0f,       NOTE_EB3, 0f        // 28..31 (C3 pop, Eb3 pop)
        )

        // Bass Pop Flags (true = bright octave slap pop)
        val BASS_POPS = booleanArrayOf(
            false, false, false, true,
            false, false, false, false,
            false, false, false, false,
            false, false, true,  false,
            false, false, false, true,
            false, false, false, false,
            false, false, false, false,
            true,  false, true,  false
        )

        // Kick Drum Hits (1 = full kick, 2 = ghost kick, 0 = rest)
        val KICK_PATTERN = intArrayOf(
            1, 0, 0, 0,  0, 0, 0, 0,  // Beat 1
            1, 0, 2, 0,  0, 0, 0, 0,  // Beat 2 (ghost on 10)
            1, 0, 0, 0,  0, 0, 2, 0,  // Beat 3 (ghost on 22)
            1, 0, 0, 0,  2, 0, 0, 0   // Beat 4 (ghost on 28)
        )

        // Snare Drum Hits (1 = snappy snare backbeat on beats 2 and 4)
        val SNARE_PATTERN = intArrayOf(
            0, 0, 0, 0,  1, 0, 0, 0,  // Beat 2
            0, 0, 0, 0,  1, 0, 0, 0,  // Beat 4
            0, 0, 0, 0,  1, 0, 0, 0,  // Beat 6
            0, 0, 0, 0,  1, 0, 0, 0   // Beat 8
        )

        // Cosmic Arpeggio Plucks
        val ARP_NOTES = floatArrayOf(
            0f,       NOTE_C4,  0f,       0f,       // 0..3
            0f,       NOTE_EB4, 0f,       0f,       // 4..7
            0f,       NOTE_G4,  0f,       0f,       // 8..11
            0f,       NOTE_BB4, 0f,       0f,       // 12..15
            0f,       NOTE_C5,  0f,       0f,       // 16..19
            0f,       NOTE_BB4, 0f,       0f,       // 20..23
            0f,       NOTE_G4,  0f,       0f,       // 24..27
            0f,       NOTE_EB4, 0f,       0f        // 28..31
        )
    }

    // Sequencer Clock State
    var currentStep: Int = 0
        private set

    var currentBpm: Float = 108.0f
        private set

    private var stepTimer: Float = 0f
    private var swingOffset: Float = 0f
    private var isPlaying: Boolean = true

    /**
     * Advances the sequencer clock by [dt] seconds, reading game telemetry from [world].
     * Zero heap allocations.
     */
    fun step(dt: Float, world: GameWorld) {
        if (!isPlaying) return

        // 1. Adaptive Tempo Scaling: 106 BPM at base speed (420 wu/s) -> 136 BPM at max (950 wu/s)
        val speedRange = GameConfig.ASCENT_SPEED_MAX - GameConfig.ASCENT_SPEED_BASE
        val speedRatio = if (speedRange > 0f) {
            ((world.ascentSpeed - GameConfig.ASCENT_SPEED_BASE) / speedRange).coerceIn(0f, 1f)
        } else 0f
        currentBpm = 106.0f + 30.0f * speedRatio

        // 16th-note step duration in seconds
        val baseStepDuration = 60.0f / (currentBpm * 4.0f)

        // 14% Micro-swing for funk pocket (odd steps trigger slightly delayed)
        val isOddStep = (currentStep % 2 == 1)
        val currentStepDuration = if (isOddStep) {
            baseStepDuration * 1.14f
        } else {
            baseStepDuration * 0.86f
        }

        stepTimer += dt
        if (stepTimer >= currentStepDuration) {
            stepTimer -= currentStepDuration
            triggerStep(currentStep, world)
            currentStep = (currentStep + 1) % TOTAL_STEPS
        }
    }

    /**
     * Executes note triggers for [step] based on game phase and dynamic intensity.
     */
    private fun triggerStep(step: Int, world: GameWorld) {
        val isCrunch = (world.wavePhaseName == "CRUNCH")
        val velocityScale = if (isCrunch) 1.0f else 0.70f

        // 1. Kick Drum
        val kickHit = KICK_PATTERN[step]
        if (kickHit == 1) {
            synth.triggerKick(velocity = 0.95f * velocityScale)
        } else if (kickHit == 2 && isCrunch) {
            // Ghost kick in crunch mode for maximum funk drive
            synth.triggerKick(velocity = 0.55f)
        }

        // 2. Snare Drum
        if (SNARE_PATTERN[step] == 1) {
            synth.triggerSnare(velocity = 0.90f * velocityScale)
        }

        // 3. Hi-Hats (Continuous 16th notes with groove dynamics)
        val isAccented = (step % 4 == 2)
        val isOpenHat = (step == 14 || step == 30)
        val hatVel = if (isOpenHat) 0.85f else if (isAccented) 0.65f else 0.40f
        synth.triggerHiHat(velocity = hatVel * velocityScale, isOpen = isOpenHat)

        // 4. Funky Slap Bass
        val bassFreq = BASS_NOTES[step]
        if (bassFreq > 0f) {
            val isPop = BASS_POPS[step]
            val bassVel = if (isPop) 0.95f else 0.85f
            synth.triggerBass(freqHz = bassFreq, velocity = bassVel * velocityScale, isPop = isPop)
        }

        // 5. Crystalline Arp Plucks
        val arpFreq = ARP_NOTES[step]
        if (arpFreq > 0f) {
            synth.triggerPluck(freqHz = arpFreq, velocity = if (isCrunch) 0.75f else 0.55f)
        }

        // 6. Eerie Background Void Pad (Updates on measure boundaries)
        if (step == 0) {
            // Bar 1: Cm9 chord root (C3)
            synth.setPadChord(NOTE_C3, targetEnv = if (isCrunch) 0.25f else 0.45f)
        } else if (step == 16) {
            // Bar 2: Fm9 / Bb chord root (F3)
            synth.setPadChord(NOTE_F3, targetEnv = if (isCrunch) 0.25f else 0.45f)
        }
    }

    /** Triggers on-the-fly Shepard laser glissando when a successful parry occurs. */
    fun onParry() {
        synth.triggerParryGlissando()
    }

    /** Resets the sequencer clock to step 0. */
    fun reset() {
        currentStep = 0
        stepTimer = 0f
    }
}
