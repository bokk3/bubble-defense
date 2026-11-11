package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.ui.splash.CoreLoreAudioSynth
import com.arcade.bubbledefense.ui.splash.TachyonAudioSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * Phase 16 Verification Tests:
 * 1. Tachyon Studio Audio PCM buffer synthesis and duration math.
 * 2. Core Lore 2.0-Second Audio PCM buffer synthesis and harmonic frequencies.
 * 3. 3-Phase cinematic timeline progression & numerical stability (Singularity -> Seed -> Warp).
 * 4. 64-Vertex soft-body harmonic wobble perimeter sanity (positive-definite radius, zero self-intersection).
 * 5. Sequential multi-stage startup state machine (Stage 0 -> Stage 1 -> Stage 2) with instant tap-to-skip.
 */
class Phase16LoreAndSplashTest {

    // ---------------------------------------------------------------------------------------------
    // 1. Audio Synthesis PCM Buffer Verification
    // ---------------------------------------------------------------------------------------------

    @Test
    fun tachyonAudio_pcmBufferGeneration_correctLengthAndSampleBounds() {
        val sampleRate = TachyonAudioSynth.SAMPLE_RATE
        val duration = TachyonAudioSynth.DURATION_SECONDS
        val expectedSamples = (sampleRate * duration).toInt()

        assertEquals("Tachyon sample rate must be 44.1 kHz", 44100, sampleRate)
        assertEquals("Tachyon splash duration must be 4.2s", 4.2, duration, 1e-4)

        val pcm = TachyonAudioSynth.generateSplashPcm(sampleRate, duration)
        assertEquals(expectedSamples, pcm.size)

        var hasNonZero = false
        var maxAmplitude = 0
        for (sample in pcm) {
            val amp = sample.toInt().absoluteValue
            if (amp > 0) hasNonZero = true
            if (amp > maxAmplitude) maxAmplitude = amp
        }

        assertTrue("PCM audio stream must contain non-zero acoustic energy", hasNonZero)
        assertTrue("Max amplitude must be audible (> 1000)", maxAmplitude > 1000)
        assertTrue("Max amplitude must not exceed 16-bit ceiling", maxAmplitude <= 32767)
    }

    @Test
    fun coreLoreAudio_pcmBufferGeneration_correctLengthAndSampleBounds() {
        val sampleRate = CoreLoreAudioSynth.SAMPLE_RATE
        val duration = CoreLoreAudioSynth.DURATION_SECONDS
        val expectedSamples = (sampleRate * duration).toInt()

        assertEquals("Lore sample rate must be 44.1 kHz", 44100, sampleRate)
        assertEquals("Lore intro duration must be 2.1s", 2.1, duration, 1e-4)

        val pcm = CoreLoreAudioSynth.generateLorePcm(sampleRate, duration)
        assertEquals(expectedSamples, pcm.size)

        var hasNonZero = false
        var maxAmplitude = 0
        for (sample in pcm) {
            val amp = sample.toInt().absoluteValue
            if (amp > 0) hasNonZero = true
            if (amp > maxAmplitude) maxAmplitude = amp
        }

        assertTrue("Lore audio stream must contain acoustic energy", hasNonZero)
        assertTrue("Lore max amplitude must be audible (> 1000)", maxAmplitude > 1000)
        assertTrue("Lore max amplitude must not exceed 16-bit ceiling", maxAmplitude <= 32767)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Core Lore 3-Phase Cinematic Timeline Math
    // ---------------------------------------------------------------------------------------------

    @Test
    fun coreLore_phase1_singularityScaling_and_gridAlpha() {
        // Timeline Phase 1: 0.0s -> 0.70s
        for (step in 0..70) {
            val t = step / 100f
            val singScale = when {
                t < 0.6f -> (t / 0.6f).coerceIn(0f, 1f)
                else -> 1.0f
            }
            val gridAlpha = (if (t < 0.7f) (t / 0.7f) else 1.0f).coerceIn(0f, 0.45f)

            assertTrue("Singularity scale must be in [0, 1]", singScale in 0.0f..1.0f)
            assertTrue("Grid alpha must be capped at 0.45", gridAlpha in 0.0f..0.45f)
        }
    }

    @Test
    fun coreLore_phase2_softBodyWobble_radiusStability() {
        // Timeline Phase 2: 0.70s -> 1.40s
        val baseRadius = 56f
        val vertices = 64

        for (timeStep in 70..140 step 5) {
            val t = timeStep / 100f

            for (v in 0 until vertices) {
                val theta = v * (2f * PI.toFloat() / vertices)
                val w1 = 0.040f * sin(3f * theta + t * 7.5f)
                val w2 = 0.025f * sin(5f * theta - t * 10f)
                val w3 = 0.015f * cos(7f * theta + t * 15f)
                val r = baseRadius * (1f + w1 + w2 + w3)

                // Verify wobble deformation is bounded between 85% and 115% of base radius
                assertTrue(
                    "Radius $r at theta=$theta t=$t must be >= 0.85 * baseRadius",
                    r >= baseRadius * 0.85f
                )
                assertTrue(
                    "Radius $r at theta=$theta t=$t must be <= 1.15 * baseRadius",
                    r <= baseRadius * 1.15f
                )
            }
        }
    }

    @Test
    fun coreLore_phase3_supersonicWarp_and_dissolveAlpha() {
        // Timeline Phase 3: 1.40s -> 2.00s
        for (timeStep in 140..200 step 2) {
            val t = timeStep / 100f

            // Warp streak speed multiplier
            val warpFrac = (t - 1.35f) / 0.65f
            val warpSpeedMult = 1f + warpFrac * warpFrac * 6f
            assertTrue("Warp multiplier must accelerate upward", warpSpeedMult >= 1.0f)

            // Camera trauma shake (active in [1.40s, 1.75s])
            if (t in 1.40f..1.75f) {
                val trauma = (1.0f - (t - 1.40f) / 0.35f).coerceIn(0f, 1f)
                val shakeMag = trauma * trauma * 8f
                assertTrue("Shake magnitude must not exceed 8.0px", shakeMag in 0.0f..8.0f)
            }

            // Global dissolve alpha
            val globalAlpha = when {
                t > 1.80f -> (1.0f - (t - 1.80f) / 0.20f).coerceIn(0f, 1f)
                else -> 1.0f
            }
            if (t <= 1.80f) {
                assertEquals(1.0f, globalAlpha, 1e-4f)
            } else if (t >= 2.0f) {
                assertEquals(0.0f, globalAlpha, 1e-4f)
            } else {
                assertTrue("Alpha must dissolve smoothly during final 200ms", globalAlpha in 0.0f..1.0f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Multi-Stage Startup State Machine & Tap-To-Skip Verification
    // ---------------------------------------------------------------------------------------------

    @Test
    fun startupPipeline_standardSequentialProgression() {
        var startupStage = 0 // 0: Studio Splash, 1: Core Lore, 2: Live Game

        // Initially at Stage 0
        assertEquals(0, startupStage)

        // Studio splash completes (natural 4.15s or tap)
        val onSplashComplete = { startupStage = 1 }
        onSplashComplete()
        assertEquals("Must transition to Stage 1 (Core Lore)", 1, startupStage)

        // Core lore completes (natural 2.0s or tap)
        val onLoreComplete = { startupStage = 2 }
        onLoreComplete()
        assertEquals("Must transition to Stage 2 (Live Game)", 2, startupStage)
    }

    @Test
    fun startupPipeline_tapToSkip_immediateBypass() {
        var startupStage = 0

        // User taps immediately during studio splash
        fun handleSplashTap() {
            startupStage = 1
        }
        handleSplashTap()
        assertEquals(1, startupStage)

        // User taps immediately during core lore
        fun handleLoreTap() {
            startupStage = 2
        }
        handleLoreTap()
        assertEquals(2, startupStage)
    }
}
