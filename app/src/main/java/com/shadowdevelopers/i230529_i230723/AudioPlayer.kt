package com.shadowdevelopers.i230529_i230723

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast

/** Plays one voice message at a time. Tap a voice message to play, tap again (or another one) to stop. */
object AudioPlayer {
    private var player: MediaPlayer? = null
    /** The message that is playing now, or null. */
    var playingId: String? = null
        private set

    fun toggle(context: Context, messageId: String, ref: String, onChange: () -> Unit) {
        val wasPlaying = playingId == messageId
        stop()
        if (wasPlaying) {
            onChange()
            return
        }
        playingId = messageId
        onChange()
        MediaLoader.audioUri(context, ref) { uri ->
            if (playingId != messageId) return@audioUri // stopped or replaced while it loaded
            if (uri == null) {
                playingId = null
                Toast.makeText(context, "Could not load the voice message", Toast.LENGTH_SHORT).show()
                onChange()
                return@audioUri
            }
            try {
                player = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { AudioPlayer.stop(); onChange() }
                    setOnErrorListener { _, _, _ -> AudioPlayer.stop(); onChange(); true }
                    prepareAsync()
                }
            } catch (_: Exception) {
                AudioPlayer.stop()
                onChange()
            }
        }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        playingId = null
    }
}
