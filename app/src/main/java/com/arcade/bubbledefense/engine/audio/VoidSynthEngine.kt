package com.arcade.bubbledefense.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance, zero-allocation procedural software DSP synthesizer engine for Bubble Defense.
 *
 * Architecture & Constraints (`01-zero-alloc-hot-loop`, `03-compose-and-threading`):
 * - Streams 16-bit PCM mono directly to [AudioTrack] in [AudioTrack.MODE_STREAM].
 * - Runs on an independent background daemon thread [AudioThread] at [Thread.MAX_PRIORITY].
 * - Completely decoupled from the 120Hz Compose UI frame rate and physics loop.
 * - Zero heap allocations inside the audio rendering hot loop (44,100 samples/sec).
 * - Algorithmic voices:
 *   1. Funky Slap Bass: Sawtooth + 25% Pulse with dynamic 2-pole resonant SVF and soft-clip saturation.
 *   2. Punchy Kick: Fast exponential pitch sweep (160Hz -> 42Hz) with transient click.
 *   3. Snappy Snare: Pseudo-random white noise through bandpass SVF + 190Hz body tone.
 *   4. Crisp Hi-Hats: High-pass noise burst (>7.5kHz) with 16th-note swing accents.
 *   5. Eerie Space Void Pad: Dual detuned sine chorus (0.08Hz LFO) for empty space immersion.
 *   6. Crystalline Arp Pluck: Resonant triangle wave with exponential decay.
 *   7. Parry Laser Glissando: Relativistic frequency chirps on successful deflections.
 */
class VoidSynthEngine(
    val sampleRate: Int = 44100
) {
    companion object {
        const val BUFFER_SIZE = 512 // ~11.6ms latency buffer at 44.1kHz
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val INV_SAMPLE_RATE = 1.0f / 44100f
    }

    // Master volume and mute state (thread-safe volatiles)
    @Volatile var isMuted: Boolean = false
    @Volatile var masterVolume: Float = 0.85f

    // ---------------------------------------------------------------------------------------------
    // Voice 1: Funky Slap Bass (Saw + Pulse + Resonant SVF Low-pass + Overdrive)
    // ---------------------------------------------------------------------------------------------
    private var bassPhase: Float = 0f
    private var bassPhaseInc: Float = 0f
    private var bassEnv: Float = 0f
    private var bassEnvDecay: Float = 0.9994f
    private var bassCutoff: Float = 400f
    private var bassResonance: Float = 2.4f
    private var bassFiltLp: Float = 0f
    private var bassFiltBp: Float = 0f
    private var bassActive: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Voice 2: Punchy Kick Drum (Pitch-swept Sine + Click)
    // ---------------------------------------------------------------------------------------------
    private var kickPhase: Float = 0f
    private var kickFreq: Float = 0f
    private var kickEnv: Float = 0f
    private var kickActive: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Voice 3: Snappy Snare Drum (Bandpass Noise + Body Tone)
    // ---------------------------------------------------------------------------------------------
    private var snareBodyPhase: Float = 0f
    private var snareBodyPhaseInc: Float = 0f
    private var snareNoiseEnv: Float = 0f
    private var snareBodyEnv: Float = 0f
    private var snareFiltLp: Float = 0f
    private var snareFiltBp: Float = 0f
    private var snareActive: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Voice 4: Crisp Hi-Hat (Highpass Noise)
    // ---------------------------------------------------------------------------------------------
    private var hatEnv: Float = 0f
    private var hatEnvDecay: Float = 0.9975f
    private var hatFiltHp: Float = 0f
    private var hatFiltLp: Float = 0f
    private var hatActive: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Voice 5: Eerie Space Void Pad (Dual Detuned Sines + LFO)
    // ---------------------------------------------------------------------------------------------
    private var padPhase1: Float = 0f
    private var padPhase2: Float = 0f
    private var padPhaseInc1: Float = 0f
    private var padPhaseInc2: Float = 0f
    private var padTargetFreq: Float = 130.81f // C3
    private var padCurrentFreq: Float = 130.81f
    private var padLfoPhase: Float = 0f
    private var padEnv: Float = 0f
    private var padTargetEnv: Float = 0f

    // ---------------------------------------------------------------------------------------------
    // Voice 6: Crystalline Arp Pluck (Resonant Triangle)
    // ---------------------------------------------------------------------------------------------
    private var pluckPhase: Float = 0f
    private var pluckPhaseInc: Float = 0f
    private var pluckEnv: Float = 0f
    private var pluckActive: Boolean = false

    // ---------------------------------------------------------------------------------------------
    // Voice 7: Parry Glissando Laser SFX
    // ---------------------------------------------------------------------------------------------
    private var parryPhase: Float = 0f
    private var parryFreq: Float = 0f
    private var parryEnv: Float = 0f
    private var parryActive: Boolean = false

    // Diagnostics / Testing Accessors
    val isParryActive: Boolean get() = parryActive
    val isBassActive: Boolean get() = bassActive
    val isKickActive: Boolean get() = kickActive
    val isSnareActive: Boolean get() = snareActive
    val isHatActive: Boolean get() = hatActive
    val isPluckActive: Boolean get() = pluckActive

    // ---------------------------------------------------------------------------------------------
    // Synthesis Buffers & Audio Daemon Thread
    // ---------------------------------------------------------------------------------------------
    private val pcmBuffer = ShortArray(BUFFER_SIZE)
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var isRunning: Boolean = false

    // Fast XorShift32 PRNG for noise generation (zero heap allocations)
    private var rngState: Int = 13374299

    private fun nextNoise(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return (x and 0xFFFF).toFloat() / 32768.0f - 1.0f
    }

    /**
     * Initializes and starts the audio daemon thread.
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val targetBufSize = max(minBufSize, BUFFER_SIZE * 4)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(targetBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (_: Exception) {
            // Audio initialization failed (e.g. running in headless unit test or restricted device)
            audioTrack = null
        }

        audioThread = Thread({
            audioLoop()
        }, "VoidSynth-AudioDaemon").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /**
     * Stops the audio engine and releases native audio hardware tracks.
     */
    @Synchronized
    fun stop() {
        isRunning = false
        audioThread?.interrupt()
        audioThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    // ---------------------------------------------------------------------------------------------
    // Note Trigger Interfaces (Called by Sequencer on Audio Thread or Game Loop)
    // ---------------------------------------------------------------------------------------------

    /** Triggers a funky slap bass note with envelope filter pop. */
    fun triggerBass(freqHz: Float, velocity: Float = 1.0f, isPop: Boolean = false) {
        bassPhaseInc = (freqHz * TWO_PI) * INV_SAMPLE_RATE
        bassEnv = velocity.coerceIn(0.1f, 1.0f)
        bassEnvDecay = if (isPop) 0.9991f else 0.99955f
        bassCutoff = if (isPop) 2600f else 1400f
        bassResonance = if (isPop) 3.2f else 2.2f
        bassActive = true
    }

    /** Triggers a punchy pitch-drop kick drum. */
    fun triggerKick(velocity: Float = 1.0f) {
        kickFreq = 160f
        kickEnv = velocity.coerceIn(0.1f, 1.0f)
        kickActive = true
    }

    /** Triggers a crisp snare/clap burst. */
    fun triggerSnare(velocity: Float = 1.0f) {
        snareBodyPhaseInc = (195f * TWO_PI) * INV_SAMPLE_RATE
        snareNoiseEnv = velocity.coerceIn(0.1f, 1.0f)
        snareBodyEnv = velocity * 0.7f
        snareActive = true
    }

    /** Triggers a closed or open 16th hi-hat. */
    fun triggerHiHat(velocity: Float = 1.0f, isOpen: Boolean = false) {
        hatEnv = velocity.coerceIn(0.1f, 1.0f)
        hatEnvDecay = if (isOpen) 0.9986f else 0.9962f
        hatActive = true
    }

    /** Modulates the ethereal cosmic void background pad note. */
    fun setPadChord(freqHz: Float, targetEnv: Float = 0.35f) {
        padTargetFreq = freqHz
        padTargetEnv = targetEnv
    }

    /** Triggers a crystalline arp pluck note. */
    fun triggerPluck(freqHz: Float, velocity: Float = 0.8f) {
        pluckPhaseInc = (freqHz * TWO_PI) * INV_SAMPLE_RATE
        pluckEnv = velocity.coerceIn(0.1f, 1.0f)
        pluckActive = true
    }

    /** Triggers a high-energy parry laser glissando / Shepard chirp. */
    fun triggerParryGlissando() {
        parryFreq = 480f
        parryEnv = 1.0f
        parryActive = true
    }

    // ---------------------------------------------------------------------------------------------
    // Core Audio Loop & Block Synthesis (Zero Allocations)
    // ---------------------------------------------------------------------------------------------

    private fun audioLoop() {
        while (isRunning) {
            renderBlock(pcmBuffer)
            val track = audioTrack
            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.write(pcmBuffer, 0, BUFFER_SIZE)
            } else {
                // If no audio track (test or headless), sleep to throttle daemon loop
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * Renders [BUFFER_SIZE] samples into [outputBuffer].
     * Pure zero-allocation DSP routine: all primitives, zero object creation.
     */
    fun renderBlock(outputBuffer: ShortArray) {
        val muted = isMuted
        val master = masterVolume

        // Pad frequency smoothing towards target
        val padTargetInc1 = (padTargetFreq * TWO_PI) * INV_SAMPLE_RATE
        val padTargetInc2 = ((padTargetFreq * 1.0065f) * TWO_PI) * INV_SAMPLE_RATE
        val padLfoInc = (0.08f * TWO_PI) * INV_SAMPLE_RATE

        for (i in 0 until BUFFER_SIZE) {
            if (muted || master <= 0f) {
                outputBuffer[i] = 0
                continue
            }

            var mix = 0.0f

            // 1. Funky Slap Bass
            if (bassActive) {
                // Sawtooth + Pulse mix
                val saw = (bassPhase / PI.toFloat()) - 1.0f
                val pulse = if (bassPhase < PI.toFloat() * 0.5f) 0.6f else -0.6f
                val oscMix = saw * 0.65f + pulse * 0.35f

                // Chamberlin State Variable Filter (2-pole resonant low-pass)
                val dynamicCutoff = (bassCutoff * bassEnv).coerceIn(80f, 6000f)
                val f = 2.0f * sin(PI.toFloat() * dynamicCutoff * INV_SAMPLE_RATE)
                val qInv = 1.0f / bassResonance

                bassFiltLp += f * bassFiltBp
                val hp = oscMix - bassFiltLp - qInv * bassFiltBp
                bassFiltBp += f * hp

                // Soft-clipping saturation: y = x - x^3 / 3
                val filtered = bassFiltLp * bassEnv
                val saturated = (filtered * 1.5f).coerceIn(-1.5f, 1.5f)
                val bassSample = saturated - (saturated * saturated * saturated) * 0.15f

                mix += bassSample * 0.85f

                bassPhase += bassPhaseInc
                if (bassPhase >= TWO_PI) bassPhase -= TWO_PI
                bassEnv *= bassEnvDecay
                if (bassEnv < 0.005f) {
                    bassActive = false
                    bassEnv = 0f
                }
            }

            // 2. Punchy Kick
            if (kickActive) {
                val kickSample = sin(kickPhase) * kickEnv
                // 2ms transient click
                val click = if (kickEnv > 0.85f) nextNoise() * 0.2f else 0f
                mix += (kickSample + click) * 0.90f

                kickPhase += (kickFreq * TWO_PI) * INV_SAMPLE_RATE
                if (kickPhase >= TWO_PI) kickPhase -= TWO_PI

                // Exponential pitch drop towards 42Hz
                kickFreq = 42f + (kickFreq - 42f) * 0.9982f
                kickEnv *= 0.9991f
                if (kickEnv < 0.005f) {
                    kickActive = false
                    kickEnv = 0f
                }
            }

            // 3. Snappy Snare
            if (snareActive) {
                // Noise through bandpass filter (~2000Hz)
                val noise = nextNoise()
                val f = 0.28f // ~2000Hz SVF tuning
                snareFiltLp += f * snareFiltBp
                val hp = noise - snareFiltLp - 0.5f * snareFiltBp
                snareFiltBp += f * hp

                val body = sin(snareBodyPhase) * snareBodyEnv
                mix += (snareFiltBp * snareNoiseEnv * 0.75f + body * 0.35f)

                snareBodyPhase += snareBodyPhaseInc
                if (snareBodyPhase >= TWO_PI) snareBodyPhase -= TWO_PI

                snareNoiseEnv *= 0.9984f
                snareBodyEnv *= 0.9988f
                if (snareNoiseEnv < 0.005f && snareBodyEnv < 0.005f) {
                    snareActive = false
                }
            }

            // 4. Crisp Hi-Hat
            if (hatActive) {
                val noise = nextNoise()
                // Simple 1-pole highpass filter: y[n] = x[n] - x_lp
                hatFiltLp += 0.55f * (noise - hatFiltLp)
                val hp = noise - hatFiltLp
                mix += hp * hatEnv * 0.40f

                hatEnv *= hatEnvDecay
                if (hatEnv < 0.005f) {
                    hatActive = false
                }
            }

            // 5. Eerie Space Void Pad (Continuous)
            padPhaseInc1 += (padTargetInc1 - padPhaseInc1) * 0.001f
            padPhaseInc2 += (padTargetInc2 - padPhaseInc2) * 0.001f
            padEnv += (padTargetEnv - padEnv) * 0.0005f

            val lfo = 0.75f + 0.25f * sin(padLfoPhase)
            val s1 = sin(padPhase1)
            val s2 = sin(padPhase2)
            val padSample = ((s1 + s2) * 0.5f) * (padEnv * lfo)
            mix += padSample * 0.45f

            padPhase1 += padPhaseInc1
            if (padPhase1 >= TWO_PI) padPhase1 -= TWO_PI
            padPhase2 += padPhaseInc2
            if (padPhase2 >= TWO_PI) padPhase2 -= TWO_PI
            padLfoPhase += padLfoInc
            if (padLfoPhase >= TWO_PI) padLfoPhase -= TWO_PI

            // 6. Crystalline Arp Pluck
            if (pluckActive) {
                // Triangle wave: 2 / PI * asin(sin(x)) or fast fold
                val normPhase = (pluckPhase / PI.toFloat()) - 1.0f
                val tri = 2.0f * (if (normPhase < 0f) -normPhase else normPhase) - 1.0f
                mix += tri * pluckEnv * 0.35f

                pluckPhase += pluckPhaseInc
                if (pluckPhase >= TWO_PI) pluckPhase -= TWO_PI
                pluckEnv *= 0.9992f
                if (pluckEnv < 0.005f) {
                    pluckActive = false
                }
            }

            // 7. Parry Laser Glissando
            if (parryActive) {
                val parrySample = sin(parryPhase) * parryEnv
                mix += parrySample * 0.55f

                parryPhase += (parryFreq * TWO_PI) * INV_SAMPLE_RATE
                if (parryPhase >= TWO_PI) parryPhase -= TWO_PI
                // Sweeps upward like a relativistic warp beam
                parryFreq += 4.5f
                parryEnv *= 0.9986f
                if (parryEnv < 0.005f || parryFreq > 2400f) {
                    parryActive = false
                }
            }

            // Master volume & soft limiter
            val finalSample = (mix * master).coerceIn(-1.0f, 1.0f)
            outputBuffer[i] = (finalSample * 32767.0f).toInt().toShort()
        }
    }
}
