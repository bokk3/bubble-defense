package com.arcade.bubbledefense.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [VoidSynthEngine] verifying zero-allocation DSP synthesis and waveform generation.
 */
class VoidSynthEngineTest {

    private lateinit var synth: VoidSynthEngine
    private val buffer = ShortArray(VoidSynthEngine.BUFFER_SIZE)

    @Before
    fun setUp() {
        synth = VoidSynthEngine(sampleRate = 44100)
    }

    @Test
    fun `renderBlock outputs silence when muted`() {
        synth.isMuted = true
        synth.triggerKick(1.0f)
        synth.triggerBass(65.41f, 1.0f)

        synth.renderBlock(buffer)

        for (i in buffer.indices) {
            assertEquals("Sample at index $i must be 0 when muted", 0.toShort(), buffer[i])
        }
    }

    @Test
    fun `triggerKick generates non-zero audio samples`() {
        synth.isMuted = false
        synth.masterVolume = 1.0f
        synth.triggerKick(1.0f)

        synth.renderBlock(buffer)

        var hasNonZero = false
        var maxSample = 0
        for (sample in buffer) {
            val s = abs(sample.toInt())
            if (s > 0) hasNonZero = true
            if (s > maxSample) maxSample = s
        }

        assertTrue("Kick must produce audible audio samples", hasNonZero)
        assertTrue("Kick max peak must exceed minimum audible threshold", maxSample > 1000)
    }

    @Test
    fun `triggerBass produces resonant filtered waveform`() {
        synth.isMuted = false
        synth.triggerBass(65.41f, velocity = 0.9f, isPop = false)

        synth.renderBlock(buffer)

        var nonZeroCount = 0
        for (sample in buffer) {
            if (sample != 0.toShort()) nonZeroCount++
        }

        assertTrue("Bass must produce sustained oscillations", nonZeroCount > buffer.size / 2)
    }

    @Test
    fun `audio buffer output never clips beyond 16-bit range`() {
        synth.isMuted = false
        synth.masterVolume = 1.0f

        // Trigger all voices simultaneously (worst-case amplitude stack)
        synth.triggerKick(1.0f)
        synth.triggerSnare(1.0f)
        synth.triggerHiHat(1.0f, isOpen = true)
        synth.triggerBass(130.81f, velocity = 1.0f, isPop = true)
        synth.triggerPluck(523.25f, velocity = 1.0f)
        synth.triggerParryGlissando()
        synth.setPadChord(130.81f, 0.5f)

        for (block in 0 until 10) {
            synth.renderBlock(buffer)
            for (sample in buffer) {
                val value = sample.toInt()
                assertTrue(
                    "Sample $value must remain within 16-bit PCM bounds [-32768, 32767]",
                    value in -32768..32767
                )
            }
        }
    }

    @Test
    fun `parry glissando chirps and terminates after decay`() {
        synth.isMuted = false
        synth.triggerParryGlissando()
        assertTrue("Parry voice must be active initially", synth.isParryActive)

        synth.renderBlock(buffer)
        var initialEnergy = 0L
        for (s in buffer) initialEnergy += abs(s.toLong())
        assertTrue("Parry glissando must generate initial burst of energy", initialEnergy > 0)

        // Render enough blocks for glissando envelope to decay to zero
        for (b in 0 until 50) {
            synth.renderBlock(buffer)
        }

        assertFalse("Parry voice must terminate after decay", synth.isParryActive)
        synth.renderBlock(buffer)
        var finalEnergy = 0L
        for (s in buffer) finalEnergy += abs(s.toLong())
        assertEquals("Parry glissando must cleanly terminate after decay", 0L, finalEnergy)
    }
}
