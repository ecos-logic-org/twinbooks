package org.ecos.logic.twinbooks.tts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the lock screen / notification media player shows while the reader speaks.
 * [active] = a reading session is open (playing or paused); false removes the player.
 */
data class PlaybackInfo(
    val active: Boolean = false,
    val isPlaying: Boolean = false,
    val bookTitle: String = "",
    val sentence: String = "",
    val cover: Bitmap? = null
)

enum class PlaybackCommand { PLAY, PAUSE, TOGGLE, STOP }

/**
 * Link between the reader (ReaderViewModel, which owns the TTS) and [TtsPlaybackService]
 * (which owns the MediaSession shown on the lock screen). The view model publishes its
 * state here; the service turns lock screen / notification buttons into [commands].
 */
@Singleton
class PlaybackBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _info = MutableStateFlow(PlaybackInfo())
    val info: StateFlow<PlaybackInfo> = _info.asStateFlow()

    private val _commands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()

    fun publish(info: PlaybackInfo) {
        val wasActive = _info.value.active
        _info.value = info
        // Started when the user presses play (app in the foreground, as Android requires);
        // afterwards it stays up while paused, so reading can be resumed from the lock screen
        if (info.active && !wasActive) {
            ContextCompat.startForegroundService(context, Intent(context, TtsPlaybackService::class.java))
        }
    }

    fun send(command: PlaybackCommand) {
        _commands.tryEmit(command)
    }
}
