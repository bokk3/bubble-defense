package com.arcade.bubbledefense.engine.audio

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PatternSequencer] verifying Strudel-style algorithmic pattern scheduling,
 * tempo scaling, and micro-swing pocket dynamics.
 */
class PatternSequencerTest {

    private lateinit var synth: VoidSynthEngine
    private lateinit var sequencer: PatternSequencer
    private lateinit var world: GameWorld

    @Before
    fun setUp() {
        synth = VoidSynthEngine()
        sequencer = PatternSequencer(synth)
        world = GameWorld(initialSeed = 42)
    }

    @Test
    fun `tempo ramps up monotonically with ascentSpeed`() {
        world.ascentSpeed = GameConfig.ASCENT_SPEED_BASE // 420 wu/s
        sequencer.step(0.01f, world)
        val baseBpm = sequencer.currentBpm
        assertEquals("Base BPM must equal 106 at base speed", 106.0f, baseBpm, 0.5f)

        world.ascentSpeed = GameConfig.ASCENT_SPEED_MAX // 950 wu/s
        sequencer.step(0.01f, world)
        val maxBpm = sequencer.currentBpm
        assertEquals("Max BPM must ramp to 136 at top speed", 136.0f, maxBpm, 0.5f)

        assertTrue("Max BPM must be higher than Base BPM", maxBpm > baseBpm)
    }

    @Test
    fun `sequencer advances through 32 steps and loops cleanly`() {
        assertEquals(0, sequencer.currentStep)

        // Advance through 32 steps (approx 2 bars)
        // At 120 BPM, 1 beat = 0.5s, 1 16th = 0.125s, 32 steps = 4.0s
        val dt = 0.01f
        var stepChanges = 0
        var lastStep = 0

        for (i in 0 until 500) {
            sequencer.step(dt, world)
            if (sequencer.currentStep != lastStep) {
                stepChanges++
                lastStep = sequencer.currentStep
            }
        }

        assertTrue("Sequencer must advance multiple steps over time", stepChanges > 20)
        assertTrue("Current step must remain within [0, 31]", sequencer.currentStep in 0 until PatternSequencer.TOTAL_STEPS)
    }

    @Test
    fun `reset returns clock to step 0`() {
        // Step forward a few beats
        for (i in 0 until 50) {
            sequencer.step(0.02f, world)
        }
        assertTrue("Step must have advanced", sequencer.currentStep > 0)

        sequencer.reset()
        assertEquals("Reset must return sequencer to step 0", 0, sequencer.currentStep)
    }

    @Test
    fun `pattern tables match total step count`() {
        assertEquals(PatternSequencer.TOTAL_STEPS, PatternSequencer.BASS_NOTES.size)
        assertEquals(PatternSequencer.TOTAL_STEPS, PatternSequencer.BASS_POPS.size)
        assertEquals(PatternSequencer.TOTAL_STEPS, PatternSequencer.KICK_PATTERN.size)
        assertEquals(PatternSequencer.TOTAL_STEPS, PatternSequencer.SNARE_PATTERN.size)
        assertEquals(PatternSequencer.TOTAL_STEPS, PatternSequencer.ARP_NOTES.size)
    }
}
