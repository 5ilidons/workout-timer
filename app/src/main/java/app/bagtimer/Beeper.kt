package app.bagtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import app.bagtimer.engine.Cue
import app.bagtimer.engine.Kind
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays synthesized beeps. Each cue is rendered once to PCM and kept in a static AudioTrack.
 * Beeps briefly lower other audio (music, a rugby stream) with transient may-duck focus.
 */
class Beeper(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = app.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
    private val main = Handler(Looper.getMainLooper())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    private val rate = 44_100

    private class Tone(val freq: Double, val startS: Double, val lenS: Double, val square: Boolean = true)

    private val tracks: Map<String, Pair<AudioTrack, Long>> = mapOf(
        "work" to build(listOf(Tone(988.0, 0.0, 0.22), Tone(988.0, 0.30, 0.35))),
        "rest" to build(listOf(Tone(440.0, 0.0, 0.80, square = false))),
        "bag" to build(listOf(Tone(1320.0, 0.0, 0.10), Tone(1320.0, 0.16, 0.10), Tone(1320.0, 0.32, 0.10))),
        "prep" to build(listOf(Tone(660.0, 0.0, 0.15))),
        "pip" to build(listOf(Tone(660.0, 0.0, 0.08))),
        "finish" to build(listOf(Tone(784.0, 0.0, 0.25), Tone(988.0, 0.30, 0.25), Tone(1175.0, 0.60, 0.60))),
    )

    private val releaseFocus = Runnable { audio.abandonAudioFocusRequest(focus) }

    private fun build(tones: List<Tone>): Pair<AudioTrack, Long> {
        val totalS = tones.maxOf { it.startS + it.lenS } + 0.05
        val n = (totalS * rate).toInt()
        val pcm = ShortArray(n)
        val amp = 0.55 * Short.MAX_VALUE
        for (t in tones) {
            val s0 = (t.startS * rate).toInt()
            val len = (t.lenS * rate).toInt()
            val ramp = (0.008 * rate).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                val phase = 2 * PI * t.freq * i / rate
                val wave = if (t.square) {
                    // Softened square wave: loud and cuts through noise, less harsh than a pure square.
                    val s = sin(phase)
                    (s + sin(3 * phase) / 3 + sin(5 * phase) / 5) * 0.9
                } else sin(phase)
                val env = min(1.0, min(i.toDouble() / ramp, (len - i).toDouble() / ramp))
                val v = pcm[idx] + (wave * env * amp).toInt()
                pcm[idx] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        track.write(pcm, 0, n)
        return track to (totalS * 1000).toLong()
    }

    fun play(cue: Cue) {
        val key = when (cue) {
            is Cue.Start -> when (cue.kind) {
                Kind.WORK -> "work"
                Kind.REST -> "rest"
                Kind.BAG -> "bag"
                Kind.PREP -> "prep"
            }
            Cue.Pip -> "pip"
            Cue.Finish -> "finish"
        }
        val (track, lenMs) = tracks.getValue(key)
        main.removeCallbacks(releaseFocus)
        audio.requestAudioFocus(focus)
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
        }
        main.postDelayed(releaseFocus, lenMs + 150)
        vibrate(cue)
    }

    private fun vibrate(cue: Cue) {
        val v = vibrator ?: return
        val pattern = when (cue) {
            is Cue.Start -> when (cue.kind) {
                Kind.WORK -> longArrayOf(0, 300, 100, 300)
                Kind.REST -> longArrayOf(0, 700)
                Kind.BAG -> longArrayOf(0, 120, 80, 120, 80, 120)
                Kind.PREP -> longArrayOf(0, 150)
            }
            Cue.Pip -> longArrayOf(0, 60)
            Cue.Finish -> longArrayOf(0, 400, 150, 400, 150, 800)
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        main.removeCallbacks(releaseFocus)
        audio.abandonAudioFocusRequest(focus)
        tracks.values.forEach { it.first.release() }
    }
}
