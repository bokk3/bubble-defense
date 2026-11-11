package com.arcade.bubbledefense.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.random.Random

/**
 * Procedural in-memory audio synthesizer for the 2-second Core Lore Intro sequence.
 *
 * Generates 44.1 kHz 16-bit PCM streamed via [AudioTrack.MODE_STATIC]:
 * - 0.0s - 0.7s: Eerie cosmic void drone (48Hz sine with 3Hz tremolo) + spatial static hiss.
 * - 0.7s - 1.4s: Organic Genesis awakening: harmonic crystal triad (528Hz + 660Hz + 792Hz) + high-pass shimmer.
 * - 1.4s - 2.1s: Supersonic thruster ignition boom (120Hz -> 38Hz sub-bass dive) + accelerating rocket whoosh.
 *
 * Hot-path & memory safety: Generated entirely in memory in one pass with zero runtime disk I/O.
 */
object CoreLoreAudioSynth {
    const val SAMPLE_RATE = 44100
    const val DURATION_SECONDS = 2.1

    fun generateLorePcm(
        sampleRate: Int = SAMPLE_RATE,
        durationSeconds: Double = DURATION_SECONDS
    ): ShortArray {
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val pcmData = ShortArray(totalSamples)

        var dronePhase = 0.0
        var triadPhase1 = 0.0
        var triadPhase2 = 0.0
        var triadPhase3 = 0.0
        var boomPhase = 0.0
        var noiseFilter = 0.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var mixed = 0.0

            // Phase 1: Cosmic Void Drone & Deep Space Static (0.0s - 0.85s)
            if (t < 0.85) {
                val droneFreq = 48.0
                dronePhase += (2.0 * Math.PI * droneFreq) / sampleRate
                val tremolo = 0.85 + 0.15 * sin(2.0 * Math.PI * 3.0 * t)
                val droneEnv = when {
                    t < 0.15 -> t / 0.15
                    t < 0.65 -> 1.0
                    else -> max(0.0, 1.0 - (t - 0.65) / 0.20)
                }
                val drone = sin(dronePhase) * tremolo * 0.35 * droneEnv

                // Cold spatial static
                val staticNoise = (Random.nextDouble() * 2.0 - 1.0) * 0.035 * droneEnv
                mixed += drone + staticNoise
            }

            // Phase 2: Harmonic Crystal Triad (0.65s - 1.50s)
            if (t in 0.65..1.50) {
                val pt = t - 0.65
                triadPhase1 += (2.0 * Math.PI * 528.0) / sampleRate
                triadPhase2 += (2.0 * Math.PI * 660.0) / sampleRate
                triadPhase3 += (2.0 * Math.PI * 792.0) / sampleRate

                val triadEnv = when {
                    pt < 0.20 -> pt / 0.20
                    pt < 0.55 -> 1.0
                    else -> max(0.0, 1.0 - (pt - 0.55) / 0.30)
                }
                val triadWave = (sin(triadPhase1) * 0.16 + sin(triadPhase2) * 0.12 + sin(triadPhase3) * 0.10) * triadEnv
                mixed += triadWave
            }

            // Phase 3: Supersonic Thruster Ignition Boom & Accelerating Whoosh (1.35s - 2.10s)
            if (t >= 1.35) {
                val bt = t - 1.35
                // Pitch drop: 120Hz down to 38Hz
                val boomFreq = max(38.0, 120.0 * exp(-bt / 0.22))
                boomPhase += (2.0 * Math.PI * boomFreq) / sampleRate
                val boomEnv = max(0.0, 1.0 - bt / 0.70)
                val boom = sin(boomPhase) * boomEnv * 0.55

                // Accelerating thruster noise whoosh (filtered low-pass sweeping upward)
                val rawNoise = Random.nextDouble() * 2.0 - 1.0
                val filterFreq = min(4200.0, 280.0 + bt * 3800.0)
                val alpha = (2.0 * Math.PI * filterFreq) / (sampleRate + 2.0 * Math.PI * filterFreq)
                noiseFilter += alpha * (rawNoise - noiseFilter)
                val whooshEnv = if (bt < 0.18) bt / 0.18 else max(0.0, 1.0 - (bt - 0.18) / 0.57)
                val whoosh = noiseFilter * whooshEnv * 0.28

                mixed += boom + whoosh
            }

            val clamped = mixed.coerceIn(-1.0, 1.0)
            pcmData[i] = (clamped * 32767.0).toInt().toShort()
        }
        return pcmData
    }

    suspend fun createAndPlayLoreAudio(): AudioTrack = withContext(Dispatchers.Default) {
        val pcmData = generateLorePcm(SAMPLE_RATE, DURATION_SECONDS)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcmData.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        track.play()
        track
    }
}
