package com.ffvpn.client.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.ffvpn.client.R
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sound the tunnel coming up makes, so a connection that finishes while
 * the phone is face-down is still noticed. Two kinds of source: the shipped
 * chime ([TONE_CLASSIC], the default) and shapes drawn in code — a blip, a
 * two-note chime, a rising three-note, a bell — so the picker has real
 * options without shipping files. Every one of them is built with
 * `USAGE_NOTIFICATION_EVENT`, which is what lets it sound even when the
 * ringer is muted.
 */
object ConnectSound {

    /** The shipped chime; the key [Prefs.connectTone] defaults to. */
    const val TONE_CLASSIC = "classic"

    /** Every choice the settings picker offers, in order. */
    val TONES = listOf(TONE_CLASSIC, "tick", "chime", "rise", "bell")

    /** The master switch applies to the tunnel's own cue. */
    fun ping(context: Context) {
        if (!Prefs.connectSound) return
        play(context, Prefs.connectTone)
    }

    /** The picker's tap: the chosen shape, heard even with the switch off. */
    fun preview(context: Context, tone: String) {
        play(context, tone)
    }

    private fun play(context: Context, tone: String) {
        if (tone == TONE_CLASSIC) {
            chime(context)
        } else {
            synth(context, tone)
        }
    }

    /** The original chime, from the shipped raw resource. */
    private fun chime(context: Context) {
        val player = MediaPlayer()
        runCatching {
            // Attributes must be set while the player is still idle: on a player
            // built by MediaPlayer.create() they are silently ignored, and the
            // chime then lands on a stream the device may have turned down.
            player.setAudioAttributes(notification())
            context.resources.openRawResourceFd(R.raw.connected).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { failed, what, extra ->
                failed.release()
                AppState.log("connect sound stopped: what=$what extra=$extra")
                true
            }
            player.prepare()
            player.start()
            AppState.log("connect chime started")
        }.onFailure {
            runCatching { player.release() }
            AppState.log("connect sound failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /**
     * One shape drawn in code: a sequence of (frequency, milliseconds) notes,
     * each a sine with a short attack and an exponential release, written into
     * one AudioTrack and let go when it has played. The envelope is what keeps
     * a hard frequency jump from clicking.
     */
    private fun synth(context: Context, tone: String) {
        val notes = when (tone) {
            "tick" -> listOf(988 to 70)
            "chime" -> listOf(880 to 120, 1174 to 150)
            "rise" -> listOf(659 to 95, 880 to 95, 1174 to 140)
            "bell" -> listOf(1319 to 480)
            else -> listOf(1046 to 110)
        }
        val rate = 44_100
        val totalMs = notes.sumOf { it.second }
        val totalSamples = rate * totalMs / 1000
        val pcm = ShortArray(totalSamples)
        var at = 0
        for ((freq, ms) in notes) {
            val len = rate * ms / 1000
            val attack = (rate * 4 / 1000).coerceAtMost(len)
            for (i in 0 until len) {
                val t = i.toDouble() / len
                val env = when {
                    i < attack -> i.toDouble() / attack
                    // The bell rings out; the rest settle back quickly.
                    tone == "bell" -> exp(-3.2 * t)
                    else -> exp(-2.2 * t)
                }
                val sample = sin(2.0 * Math.PI * freq * i / rate) * env * 0.55
                val idx = at + i
                if (idx < pcm.size) pcm[idx] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
            at += len
        }
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(notification())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bytes.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(bytes, 0, bytes.size)
            track.play()
            // A static track plays once and stays; let it finish, then let it go.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { track.stop() }
                runCatching { track.release() }
            }, totalMs + 400L)
            AppState.log("connect tone started: $tone")
        }.onFailure {
            AppState.log("connect tone failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun notification(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}
