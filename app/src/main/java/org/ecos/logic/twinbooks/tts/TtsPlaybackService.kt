package org.ecos.logic.twinbooks.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ecos.logic.twinbooks.MainActivity
import org.ecos.logic.twinbooks.R
import javax.inject.Inject

/**
 * Foreground "media playback" service while the reader speaks:
 *  - lock screen / notification media player: app, book, cover, first words of the
 *    sentence being read, play/pause and stop
 *  - keeps TTS audio legal in the background (Android 17 background audio hardening)
 * State comes from [PlaybackBridge]; button presses go back through it to the reader.
 */
@AndroidEntryPoint
class TtsPlaybackService : Service() {

    @Inject lateinit var bridge: PlaybackBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "TwinBooks").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = bridge.send(PlaybackCommand.PLAY)
                override fun onPause() = bridge.send(PlaybackCommand.PAUSE)
                override fun onStop() = bridge.send(PlaybackCommand.STOP)
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        // Must be foreground within a few seconds of startForegroundService()
        startInForeground(bridge.info.value)
        scope.launch {
            bridge.info.collect { info ->
                if (!info.active) {
                    stopSelf()
                } else {
                    update(info)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> bridge.send(PlaybackCommand.TOGGLE)
            ACTION_STOP -> bridge.send(PlaybackCommand.STOP)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // The reader closed the app from the recents list: stop reading
    override fun onTaskRemoved(rootIntent: Intent?) {
        bridge.send(PlaybackCommand.STOP)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun update(info: PlaybackInfo) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, sentenceTitle(info))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .apply { info.cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (info.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (info.isPlaying) 1f else 0f
                )
                .build()
        )
        startInForeground(info)
    }

    private fun startInForeground(info: PlaybackInfo) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(info),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun buildNotification(info: PlaybackInfo) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_book)
            .setContentTitle(sentenceTitle(info))
            .setContentText(info.bookTitle)
            .setLargeIcon(info.cover)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(info.isPlaying)
            .addAction(
                if (info.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (info.isPlaying) "Pausa" else "Reproducir",
                serviceIntent(ACTION_TOGGLE)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", serviceIntent(ACTION_STOP))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()

    /** First words of the sentence being read, or the book title before the first one. */
    private fun sentenceTitle(info: PlaybackInfo): String {
        val words = info.sentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return info.bookTitle.ifBlank { getString(R.string.app_name) }
        return if (words.size <= SENTENCE_WORDS) words.joinToString(" ")
        else words.take(SENTENCE_WORDS).joinToString(" ") + "…"
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, TtsPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Control de la lectura (TTS) en la pantalla de bloqueo"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001
        private const val SENTENCE_WORDS = 8
        private const val ACTION_TOGGLE = "org.ecos.logic.twinbooks.tts.TOGGLE"
        private const val ACTION_STOP = "org.ecos.logic.twinbooks.tts.STOP"
    }
}
