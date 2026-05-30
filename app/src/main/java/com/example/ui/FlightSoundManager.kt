package com.example.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class FlightSoundManager {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isRunning = false
    private var thread: Thread? = null

    // Real-time parameters modified by the game loop
    @Volatile var throttleValue: Float = 0.0f
    @Volatile var dynamicPressureQ: Float = 0.0f // up to 25000 Pa
    @Volatile var rcsBursts: Float = 0.0f // 1.0 if thruster active, 0.0 otherwise
    @Volatile var isMuted: Boolean = false

    fun start() {
        if (isRunning) return
        isRunning = true

        val minBufferSize = AudioTrack.getMinBufferSize(
            22050,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e("SoundManager", "Invalid minimum buffer size calculated.")
            return
        }

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
                        .setSampleRate(22050)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("SoundManager", "Failed to initialize AudioTrack: ${e.message}")
            isRunning = false
            return
        }

        thread = Thread {
            val sampleRate = 22050
            val bufferSize = 1024
            val buffer = ShortArray(bufferSize)
            var phase = 0.0
            val random = java.util.Random()

            // Filter states
            var fLowPass = 0.0
            var fHighPass = 0.0

            while (isRunning) {
                if (isMuted) {
                    buffer.fill(0)
                    try {
                        audioTrack?.write(buffer, 0, buffer.size)
                        Thread.sleep(30)
                    } catch (e: Exception) {
                        // ignore interruption
                    }
                    continue
                }

                val targetThrottle = throttleValue
                val targetQ = dynamicPressureQ
                val rcsIntensity = rcsBursts

                // Volume variables
                val rocketVolume = targetThrottle.coerceIn(0.0f, 1.0f) * 0.65f
                val windVolume = (targetQ / 16000.0f).coerceIn(0.0f, 1.0f) * 0.35f
                val rcsVolume = rcsIntensity.coerceIn(0.0f, 1.0f) * 0.25f

                for (i in 0 until bufferSize) {
                    val rawWhiteNoise = random.nextDouble() * 2.0 - 1.0

                    // 1. Rocket engine generator: low-pass filtered white noise combined with dynamic sub-sine rumble
                    fLowPass = fLowPass * 0.88 + rawWhiteNoise * 0.12
                    val lowRoar = fLowPass

                    // Synthesized base rumble wave (45 Hz to 52 Hz depending on throttle)
                    val freq = 45.0 + targetThrottle * 7.0
                    phase += (2.0 * Math.PI * freq) / sampleRate
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                    val bassSine = Math.sin(phase)

                    val rocketSample = (lowRoar * 0.35 + bassSine * 0.65) * rocketVolume

                    // 2. Wind aerodynamics high-frequency engine: simple high-pass filtered white noise
                    fHighPass = fHighPass * 0.45 + rawWhiteNoise * 0.55
                    val highWind = rawWhiteNoise - fHighPass
                    val windSample = highWind * windVolume

                    // 3. RCS gaseous cold nitrogen jet burst: pure hissing noise
                    val rcsSample = rawWhiteNoise * rcsVolume

                    // Master combination
                    val combined = rocketSample + windSample + rcsSample
                    
                    // Out scalar clamp to prevent clipping distortion
                    val pcmInt = (combined * 14000.0).coerceIn(-32768.0, 32767.0).toInt()
                    buffer[i] = pcmInt.toShort()
                }

                try {
                    audioTrack?.write(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e("SoundManager", "Error writing audio buffer.")
                    break
                }
            }
        }.apply {
            name = "X15_AudioSynth_Thread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning = false
        thread?.apply {
            try {
                interrupt()
                join(500)
            } catch (e: Exception) {}
        }
        audioTrack?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {}
        }
        audioTrack = null
        thread = null
    }
}
