package dev.local.newsdigest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

data class WordTiming(val word: String, val startMs: Int, val endMs: Int)

private data class QueuedSentence(
    val text: String,
    val words: List<WordTiming>,
    val pcm: ByteArray,
    val sampleRate: Int,
) {
    /** Duration of this sentence's audio, in ms - 16-bit mono PCM. */
    val durationMs: Long get() = (pcm.size / 2).toLong() * 1000 / sampleRate
}

/**
 * Real-time TTS playback: an AudioTrack in streaming mode fed sentence by
 * sentence, a real Android media session - play/pause/stop/seek, visible
 * in the notification shade and on the lock screen like any other player
 * - and a word-highlight callback driven by a wall-clock timer against
 * each sentence's own estimated word timings (not AudioTrack's hardware
 * frame position - simpler to get right, and the timings are already
 * estimates, so a little wall-clock drift doesn't cost real accuracy).
 *
 * All synthesized sentences for the current session stay in memory
 * (allSentences) instead of being discarded once played, so seeking can
 * actually replay real PCM rather than fake a scrub bar. Seeking past the
 * end of what's been synthesized so far just clamps to it - this is a
 * live stream, not a finished file, so there's genuinely nothing there
 * yet; the reported duration grows as more of it arrives.
 *
 * Deliberately source-agnostic: callers enqueue whatever sentence audio
 * they have, whether that's from /tts/stream (reading the digest or an
 * article) or /agent/chat (reading a streamed agent reply aloud sentence
 * by sentence as it arrives) - this service has no idea which, and doesn't
 * need to.
 */
class TtsPlaybackService : Service() {

    interface HighlightListener {
        fun onSentenceStart(text: String, words: List<WordTiming>) {}
        fun onWordHighlight(wordIndex: Int) {}
        fun onSentenceEnd() {}
        fun onQueueIdle() {}
    }

    inner class LocalBinder : Binder() {
        fun service(): TtsPlaybackService = this@TtsPlaybackService
    }

    companion object {
        private const val TAG = "TtsPlaybackService"
        const val CHANNEL_ID = "tts_playback"
        const val NOTIF_ID = 2
        private const val HIGHLIGHT_TICK_MS = 60L
        private const val ACTION_PLAY = "dev.local.newsdigest.action.PLAY"
        private const val ACTION_PAUSE = "dev.local.newsdigest.action.PAUSE"
        private const val ACTION_STOP = "dev.local.newsdigest.action.STOP"
    }

    private val binder = LocalBinder()
    @Volatile private var listener: HighlightListener? = null

    // All sentences for the current session, in order - retained (never
    // drained) so seeking can jump to any point already synthesized.
    private val lock = Any()
    private val allSentences = mutableListOf<QueuedSentence>()
    private var playIndex = 0 // guarded by lock: index playLoop is on/about to (re)start
    private var seekOffsetMs = 0L // guarded by lock: ms into allSentences[playIndex] to start from
    @Volatile private var seekGeneration = 0 // bumped on every seek/stop; an in-flight write loop checks this to abandon itself early

    private var playThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var stopRequested = false
    @Volatile private var idleSignaled = true
    // Distinguishes "nothing left to play right now because the network is
    // still generating the next sentence" from "nothing left because there
    // is nothing more coming". Without this, a gap between sentences during
    // normal streaming would fire onQueueIdle mid-read - the read-aloud/
    // chat "Stop" button would silently flip back to "Read aloud" while
    // audio was still playing. endSession() is the producer's explicit
    // signal that no more enqueueSentence() calls are coming.
    @Volatile private var sessionEnded = true
    private var audioTrack: AudioTrack? = null
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentTitle: String = "RSS Reader"
    @Volatile private var lastSentenceText: String = "Preparing..."

    // Lets pause()/resume()/seekTo() report a sensible interpolated
    // position even though they're called asynchronously mid-sentence,
    // without needing a high-frequency position-reporting timer - Android
    // extrapolates a PlaybackState's position using its speed between
    // updates, same as any other media app.
    @Volatile private var posAnchorMs: Long = 0L
    @Volatile private var posAnchorAtNanos: Long = System.nanoTime()
    @Volatile private var posAnchorPlaying: Boolean = false

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "NewsDigestTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopAll() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    fun setListener(l: HighlightListener?) {
        listener = l
    }

    fun startSession(title: String) {
        currentTitle = title
        stopRequested = false
        idleSignaled = false
        sessionEnded = false
        synchronized(lock) {
            allSentences.clear()
            playIndex = 0
            seekOffsetMs = 0L
        }
        setPositionAnchor(0L, false)
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 0L)
                .build(),
        )
        try {
            startForeground(NOTIF_ID, buildNotification("Preparing..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
        ensurePlayThread()
    }

    fun enqueueSentence(text: String, words: List<WordTiming>, pcm: ByteArray, sampleRate: Int) {
        idleSignaled = false
        val totalMs: Long
        synchronized(lock) {
            allSentences.add(QueuedSentence(text, words, pcm, sampleRate))
            totalMs = allSentences.sumOf { it.durationMs }
        }
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, totalMs)
                .build(),
        )
    }

    /** Call once the producer knows no further enqueueSentence() calls are
     * coming for the current session (e.g. the server's "done" message, or
     * an agent chat turn finishing) - see the sessionEnded comment above. */
    fun endSession() {
        sessionEnded = true
    }

    fun pause() {
        setPositionAnchor(estimatedPositionMs(), false)
        playing = false
        audioTrack?.pause()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateNotification(lastSentenceText)
    }

    fun resume() {
        setPositionAnchor(estimatedPositionMs(), true)
        playing = true
        audioTrack?.play()
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        updateNotification(lastSentenceText)
    }

    /** Jumps to an absolute position (ms) across all sentences synthesized
     * so far for this session. Clamps into range - can't seek into audio
     * that hasn't streamed in yet, or before the start. */
    fun seekTo(targetMs: Long) {
        var newPosMs = 0L
        synchronized(lock) {
            if (allSentences.isEmpty()) return
            var remaining = targetMs.coerceAtLeast(0)
            var idx = 0
            while (idx < allSentences.size - 1 && remaining >= allSentences[idx].durationMs) {
                remaining -= allSentences[idx].durationMs
                idx++
            }
            remaining = remaining.coerceAtMost(allSentences[idx].durationMs)
            playIndex = idx
            seekOffsetMs = remaining
            for (i in 0 until idx) newPosMs += allSentences[i].durationMs
            newPosMs += remaining
        }
        seekGeneration++ // an in-flight write loop sees this and abandons itself; playLoop re-reads playIndex/seekOffsetMs
        audioTrack?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        playing = true
        setPositionAnchor(newPosMs, true)
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    fun stopAll() {
        stopRequested = true
        playing = false
        sessionEnded = true
        seekGeneration++
        synchronized(lock) {
            allSentences.clear()
            playIndex = 0
            seekOffsetMs = 0L
        }
        setPositionAnchor(0L, false)
        audioTrack?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {}
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        mainHandler.post { listener?.onQueueIdle() }
    }

    private fun ensurePlayThread() {
        if (playThread?.isAlive == true) return
        playing = true
        playThread = Thread {
            try {
                playLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "playLoop crashed", e)
            }
        }.apply { isDaemon = true; name = "TtsPlayback"; start() }
    }

    private fun playLoop() {
        while (!stopRequested) {
            val myGeneration = seekGeneration
            var index: Int
            var startOffsetMs: Long
            var sentence: QueuedSentence?
            synchronized(lock) {
                index = playIndex
                sentence = allSentences.getOrNull(index)
                startOffsetMs = seekOffsetMs
                seekOffsetMs = 0L // consumed - only applies to the sentence we're about to (re)start
            }
            val current = sentence
            if (current == null) {
                // Only a real end-of-session with nothing left counts as
                // idle - nothing to play *right now* just means the
                // network hasn't delivered the next sentence yet, which is
                // expected and fine (see sessionEnded's doc comment).
                if (sessionEnded && !idleSignaled) {
                    idleSignaled = true
                    mainHandler.post { listener?.onQueueIdle() }
                }
                Thread.sleep(100)
                continue
            }
            idleSignaled = false

            if (audioTrack == null || audioTrack?.sampleRate != current.sampleRate) {
                audioTrack?.release()
                audioTrack = buildAudioTrack(current.sampleRate)
            }
            val track = audioTrack ?: continue

            playing = true
            track.play()
            setPositionAnchor(positionMsUpTo(index) + startOffsetMs, true)
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            mainHandler.post {
                listener?.onSentenceStart(current.text, current.words)
                updateNotification(current.text)
            }

            val startNanos = System.nanoTime() - startOffsetMs * 1_000_000
            val highlighter = object : Runnable {
                var idx = 0
                override fun run() {
                    if (stopRequested || seekGeneration != myGeneration) return
                    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                    while (idx < current.words.size && elapsedMs >= current.words[idx].endMs) idx++
                    if (idx < current.words.size) {
                        listener?.onWordHighlight(idx)
                        mainHandler.postDelayed(this, HIGHLIGHT_TICK_MS)
                    }
                }
            }
            mainHandler.post(highlighter)

            // write() blocks roughly at real playback speed once its
            // internal buffer fills, which paces this loop naturally -
            // good enough given the word timings it's driving are already
            // estimates, not a forced alignment (see docs/design.md).
            val bytesPerMs = current.sampleRate * 2 / 1000
            var offset = ((startOffsetMs * bytesPerMs).toInt()) and 1.inv() // even byte boundary for 16-bit samples
            while (offset < current.pcm.size && !stopRequested && seekGeneration == myGeneration) {
                if (!playing) {
                    Thread.sleep(50)
                    continue
                }
                val chunk = minOf(4096, current.pcm.size - offset)
                val written = track.write(current.pcm, offset, chunk)
                if (written < 0) break
                offset += written
            }
            mainHandler.post { listener?.onSentenceEnd() }

            // Only advance naturally if nothing seeked elsewhere meanwhile
            // - a seek already repointed playIndex/seekOffsetMs itself.
            if (seekGeneration == myGeneration) {
                synchronized(lock) { if (playIndex == index) playIndex = index + 1 }
            }
        }
    }

    /** Sum of durations of all sentences before `index` - i.e. the
     * position at which sentence `index` begins, ignoring any intra-
     * sentence offset. */
    private fun positionMsUpTo(index: Int): Long {
        synchronized(lock) {
            var total = 0L
            for (i in 0 until index) total += allSentences.getOrNull(i)?.durationMs ?: 0L
            return total
        }
    }

    private fun setPositionAnchor(ms: Long, playingNow: Boolean) {
        posAnchorMs = ms
        posAnchorAtNanos = System.nanoTime()
        posAnchorPlaying = playingNow
    }

    private fun estimatedPositionMs(): Long =
        if (posAnchorPlaying) posAnchorMs + (System.nanoTime() - posAnchorAtNanos) / 1_000_000 else posAnchorMs

    private fun buildAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun updatePlaybackState(state: Int, positionMs: Long = estimatedPositionMs()) {
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(state, positionMs, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build(),
        )
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RSS Reader playback", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val playPauseAction = if (playing) {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Pause",
                actionPendingIntent(ACTION_PAUSE),
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Play",
                actionPendingIntent(ACTION_PLAY),
            ).build()
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            actionPendingIntent(ACTION_STOP),
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(text: String) {
        lastSentenceText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopAll()
        mediaSession?.release()
        audioTrack?.release()
        super.onDestroy()
    }
}
