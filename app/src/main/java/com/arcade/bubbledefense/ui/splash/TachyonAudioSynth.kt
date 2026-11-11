package com.arcade.bubbledefense.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.random.Random

/**
 * Procedural in-memory Web Audio synthesizer matching the Tachyon Studio hero logo soundtrack.
 *
 * Generates and streams 44.1 kHz 16-bit PCM audio directly into an [AudioTrack] static buffer:
 * - 0.0s - 2.4s: Sawtooth laser pitch ramp (440Hz -> 880Hz -> 620Hz) + spark crackle
 * - 2.4s - 2.8s: Thermal snap pop (950Hz -> 120Hz triangle glide) + 2800Hz highpass thermal hiss
 * - 2.5s - 3.8s: Sub-bass surge (65Hz -> 140Hz -> 45Hz) + resonant noise whoosh + crystalline chime
 */
object TachyonAudioSynth {
    private const val SAMPLE_RATE = 44100
    private const val DURATION_SECONDS = 4.2

    suspend fun createAndPlaySplashAudio(): AudioTrack = withContext(Dispatchers.Default) {
        val totalSamples = (SAMPLE_RATE * DURATION_SECONDS).toInt()
        val pcmData = ShortArray(totalSamples)

        var cutPhase = 0.0
        var popPhase = 0.0
        var subPhase = 0.0
        var chimePhase = 0.0
        var filterState = 0.0
        var hissFilterState = 0.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var mixed = 0.0

            // 1. Stage 1: Laser Cut Sawtooth Pitch Sweep (0.0s - 2.45s)
            if (t < 2.45) {
                val cutFreq = if (t < 1.2) {
                    440.0 + (t / 1.2) * 440.0
                } else {
                    880.0 - ((t - 1.2) / 1.25) * 260.0
                }
                cutPhase += (2.0 * Math.PI * cutFreq) / SAMPLE_RATE
                val saw = 2.0 * ((cutPhase / (2.0 * Math.PI)) - floor((cutPhase / (2.0 * Math.PI)) + 0.5))
                val cutEnv = when {
                    t < 0.1 -> t / 0.1
                    t < 2.2 -> 1.0
                    else -> max(0.0, 1.0 - (t - 2.2) / 0.25)
                }
                val crackle = (Random.nextDouble() * 2.0 - 1.0)
                mixed += (saw * 0.16 + crackle * 0.02) * cutEnv
            }

            // 2. Stage 2: Thermal Flash Snap Pop & Sizzle (2.4s - 2.8s)
            if (t in 2.40..2.80) {
                val pt = t - 2.40
                val popFreq = max(100.0, 950.0 * exp(-pt / 0.045))
                popPhase += (2.0 * Math.PI * popFreq) / SAMPLE_RATE
                val popSaw = 2.0 * ((popPhase / (2.0 * Math.PI)) - floor((popPhase / (2.0 * Math.PI)) + 0.5))
                val popTri = 2.0 * abs(popSaw) - 1.0
                val popEnv = max(0.0, 1.0 - pt / 0.20) * 0.40

                val rawWhite = Random.nextDouble() * 2.0 - 1.0
                val hissAlpha = (2.0 * Math.PI * 2800.0) / (SAMPLE_RATE + 2.0 * Math.PI * 2800.0)
                hissFilterState += hissAlpha * (rawWhite - hissFilterState)
                val highPass = rawWhite - hissFilterState
                val hissEnv = max(0.0, 1.0 - pt / 0.38) * 0.14

                mixed += popTri * popEnv + highPass * hissEnv
            }

            // 3. Stage 3 & 4: Sub-Bass Surge, Resonant Whoosh & Crystal Sheen Chime (2.5s - 3.8s)
            if (t in 2.50..3.80) {
                val st = t - 2.50

                // Sub-bass surge (65Hz -> 140Hz -> 45Hz)
                val subFreq = if (st < 0.35) 65.0 + (st / 0.35) * 75.0 else max(35.0, 140.0 - ((st - 0.35) / 0.65) * 95.0)
                subPhase += (2.0 * Math.PI * subFreq) / SAMPLE_RATE
                val subEnv = if (st < 0.35) st / 0.35 else max(0.0, 1.0 - (st - 0.35) / 0.65)
                val subSample = sin(subPhase) * subEnv * 0.50

                // Filtered resonant noise whoosh (220Hz -> 1200Hz -> 180Hz)
                val rawNoise = Random.nextDouble() * 2.0 - 1.0
                val filterFreq = if (st < 0.35) 220.0 + (st / 0.35) * 980.0 else max(180.0, 1200.0 - ((st - 0.35) / 0.65) * 1020.0)
                val alpha = (2.0 * Math.PI * filterFreq) / (SAMPLE_RATE + 2.0 * Math.PI * filterFreq)
                filterState += alpha * (rawNoise - filterState)
                val noiseEnv = if (st < 0.35) st / 0.35 else max(0.0, 1.0 - (st - 0.35) / 0.60)
                val noiseSample = filterState * noiseEnv * 0.32

                // Crystalline flare chime (1650Hz -> 2400Hz -> 1200Hz)
                var chimeSample = 0.0
                if (st in 0.28..0.85) {
                    val ct = st - 0.28
                    val chimeFreq = if (ct < 0.15) 1650.0 + (ct / 0.15) * 750.0 else 2400.0 - ((ct - 0.15) / 0.42) * 1200.0
                    chimePhase += (2.0 * Math.PI * chimeFreq) / SAMPLE_RATE
                    val cSaw = 2.0 * ((chimePhase / (2.0 * Math.PI)) - floor((chimePhase / (2.0 * Math.PI)) + 0.5))
                    val cTri = 2.0 * abs(cSaw) - 1.0
                    val chimeEnv = if (ct < 0.10) ct / 0.10 else max(0.0, 1.0 - (ct - 0.10) / 0.47)
                    chimeSample = cTri * chimeEnv * 0.22
                }

                mixed += subSample + noiseSample + chimeSample
            }

            val clamped = mixed.coerceIn(-0.96, 0.96)
            pcmData[i] = (clamped * 32767.0).toInt().toShort()
        }

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
