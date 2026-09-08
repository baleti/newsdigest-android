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
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.PlaybackParams
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
        /** startMs is this sentence's absolute position from the top of
         * the whole session - i.e. what seekTo() would need to jump back
         * to its first word - regardless of any seekOffsetMs used to
         * resume mid-sentence. Lets a caller build "tap a word to seek
         * there" without duplicating this service's own position math. */
        fun onSentenceStart(text: String, words: List<WordTiming>, startMs: Long) {}
        fun onWordHighlight(wordIndex: Int) {}
        fun onSentenceEnd() {}
        fun onQueueIdle() {}
        /** Fires whenever actually-playing state changes, from ANY
         * trigger - the in-app controls, the system notification's own
         * play/pause button, a seek, audio-focus loss/gain - so a caller
         * building play/pause UI stays correct regardless of which of
         * those caused the change, not just ones it initiated itself. */
        fun onPlayingChanged(playing: Boolean) {}
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

    // Bumped on every startSession(). Confirmed live: calling stopAll() on
    // one logical use of this shared service (e.g. stopping a chat-reply
    // read-aloud right before starting the main-content one) queues a
    // mainHandler.post{} for onQueueIdle - and since that post reads the
    // (mutable, shared) listener/active state only once it actually runs,
    // a startSession() that happens in the meantime races it: the new
    // session's onStateChanged(true) fires, then this stale post runs and
    // immediately fires onStateChanged(false), because from its own stale
    // point of view nothing is playing. The onQueueIdle call sites below
    // capture sessionGeneration at post-time and only actually deliver the
    // signal if nothing newer has started since.
    @Volatile private var sessionGeneration = 0

    private var playThread: Thread? = null
    @Volatile private var playing = false
    // True from startSession() until this session genuinely ends (idle
    // fires, or stopAll()) -- unlike `playing`, stays true across a pause.
    // Lets a controller that (re)binds after the launching Activity was
    // recreated (or a completely new one) tell "nothing to resume" apart
    // from "a session most likely still running in the background" without
    // needing its own separate tracking -- this service already outlives
    // any one Activity by design (foreground service + real notification),
    // so it's the only thing that reliably knows.
    @Volatile private var hasActiveSession = false
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
    private var currentTitle: String = "News Digest"
    @Volatile private var lastSentenceText: String = "Preparing..."

    /** All the places that change `playing` route through here instead of
     * assigning it directly, so HighlightListener.onPlayingChanged fires
     * exactly on real transitions - once per change, from whichever of
     * pause()/resume()/seekTo()/playLoop actually caused it. */
    private fun setPlaying(v: Boolean) {
        val changed = playing != v
        playing = v
        if (changed) mainHandler.post { listener?.onPlayingChanged(v) }
    }

    // Lets pause()/resume()/seekTo() report a sensible interpolated
    // position even though they're called asynchronously mid-sentence,
    // without needing a high-frequency position-reporting timer - Android
    // extrapolates a PlaybackState's position using its speed between
    // updates, same as any other media app.
    @Volatile private var posAnchorMs: Long = 0L
    @Volatile private var posAnchorAtNanos: Long = System.nanoTime()
    @Volatile private var posAnchorPlaying: Boolean = false

    // Playback speed (AudioTrack.setPlaybackParams does real time-
    // stretching, not just resampling, so pitch stays natural at any
    // speed). The word-highlighter's timer needs its own anchor here too
    // - changing speed mid-sentence would otherwise retroactively
    // reinterpret time already elapsed under the OLD speed, jumping the
    // highlighted word instead of smoothly changing pace from that point on.
    @Volatile private var playbackSpeed: Float = 1.0f
    @Volatile private var highlightAnchorMs: Long = 0L
    @Volatile private var highlightAnchorAtNanos: Long = System.nanoTime()

    // AudioTrack plays regardless of what else is making noise - it does
    // NOT request audio focus on its own, so without this, starting a
    // read-aloud session while music/a video was already playing left
    // both audible at once instead of the other one pausing (confirmed
    // live: this was exactly that bug, not a race in the playback code).
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false
    @Volatile private var pausedByFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopAll()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Pause rather than duck under it even for the "can duck"
                // case - this is spoken content, not background music, so
                // playing quietly under something else is still unlistenable.
                if (playing) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
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

    /** AUDIOFOCUS_GAIN_TRANSIENT: tells other media apps to pause (not
     * duck) while we're reading, and to resume once we abandon focus -
     * exactly "interrupt what's playing, then hand it back", which is
     * what starting a read-aloud session over existing playback should do. */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        focusRequest = request
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) Log.w(TAG, "audio focus request denied - playing anyway")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasAudioFocus = false
        pausedByFocusLoss = false
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
        sessionGeneration++ // invalidate any onQueueIdle already queued from a stop before this
        hasActiveSession = true
        requestAudioFocus()
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
        setPlaying(false)
        audioTrack?.pause()
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateNotification(lastSentenceText)
    }

    fun resume() {
        requestAudioFocus()
        setPositionAnchor(estimatedPositionMs(), true)
        setPlaying(true)
        audioTrack?.play()
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        updateNotification(lastSentenceText)
    }

    /** Current playback position estimate, in ms - what a seekTo() call
     * with this same value would resolve back to. Public so a caller can
     * build relative seek ("skip back/forward 15s") on top of seekTo(). */
    fun getPositionMs(): Long = estimatedPositionMs()

    /** True from startSession() until the session genuinely ends (queue
     * drains with nothing more coming, or stopAll()) -- stays true across
     * a pause, unlike isPlaying(). A controller that just (re)bound uses
     * this to tell whether there's a background session worth reflecting
     * in its own UI at all. */
    fun hasActiveSession(): Boolean = hasActiveSession

    /** Real current playing/paused state, independent of which controller
     * (if any) is currently bound -- same use as hasActiveSession(). */
    fun isPlaying(): Boolean = playing

    /** 1.0 = normal. Takes effect immediately, including mid-sentence. */
    fun setPlaybackSpeed(speed: Float) {
        // Rebase the highlight anchor using the OLD speed before swapping
        // it in, so time already elapsed stays interpreted the way it was
        // actually played, and only time from this instant on speeds up.
        val elapsedAtOldSpeed = highlightAnchorMs +
            ((System.nanoTime() - highlightAnchorAtNanos) / 1_000_000.0 * playbackSpeed).toLong()
        highlightAnchorMs = elapsedAtOldSpeed
        highlightAnchorAtNanos = System.nanoTime()
        playbackSpeed = speed
        try {
            audioTrack?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "setPlaybackSpeed failed: ${e.message}")
        }
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
        requestAudioFocus()
        audioTrack?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        setPlaying(true)
        setPositionAnchor(newPosMs, true)
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    /** For "skip ahead past what's been synthesized so far": positions
     * the session so the NEXT enqueueSentence() call becomes the very
     * next thing played, abandoning whatever was still mid-flight for
     * the gap being skipped - that gap is simply never synthesized, not
     * queued up behind the jump. Unlike seekTo(), this doesn't require
     * the target to already exist in allSentences - the caller is about
     * to stream fresh sentences that will land at exactly this index. */
    fun jumpToUpcoming() {
        val resumeMs: Long
        synchronized(lock) {
            playIndex = allSentences.size
            seekOffsetMs = 0L
            resumeMs = positionMsUpTo(allSentences.size)
        }
        seekGeneration++
        sessionEnded = false // a fresh stream is about to start - not done yet
        idleSignaled = false
        requestAudioFocus()
        audioTrack?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }
        setPlaying(true)
        setPositionAnchor(resumeMs, true)
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    fun stopAll() {
        stopRequested = true
        setPlaying(false)
        sessionEnded = true
        hasActiveSession = false
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
        abandonAudioFocus()
        // Matches the explicit startForegroundService() a start() now
        // makes (see ReadAloudController, DetailActivity.speakChatReply) --
        // ends this component's "started" lifecycle so it doesn't linger
        // indefinitely once nothing is bound either. Harmless if a client
        // is still bound: the service instance stays alive for that
        // binding regardless, this only clears the independent-of-binding
        // "started" flag.
        stopSelf()
        val myGen = sessionGeneration
        mainHandler.post { if (sessionGeneration == myGen) listener?.onQueueIdle() }
    }

    private fun ensurePlayThread() {
        if (playThread?.isAlive == true) return
        setPlaying(true)
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
                    hasActiveSession = false
                    abandonAudioFocus() // reading finished on its own - hand focus back
                    val myGen = sessionGeneration
                    mainHandler.post { if (sessionGeneration == myGen) listener?.onQueueIdle() }
                }
                Thread.sleep(100)
                continue
            }
            idleSignaled = false

            if (audioTrack == null || audioTrack?.sampleRate != current.sampleRate) {
                audioTrack?.release()
                audioTrack = buildAudioTrack(current.sampleRate)
                try {
                    audioTrack?.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f)
                } catch (e: Exception) {
                    Log.e(TAG, "applying playback speed to new AudioTrack failed: ${e.message}")
                }
            }
            val track = audioTrack ?: continue

            setPlaying(true)
            track.play()
            val sentenceStartMs = positionMsUpTo(index)
            setPositionAnchor(sentenceStartMs + startOffsetMs, true)
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            mainHandler.post {
                listener?.onSentenceStart(current.text, current.words, sentenceStartMs)
                updateNotification(current.text)
            }

            highlightAnchorMs = startOffsetMs
            highlightAnchorAtNanos = System.nanoTime()
            val highlighter = object : Runnable {
                var idx = 0
                override fun run() {
                    if (stopRequested || seekGeneration != myGeneration) return
                    // Reads playbackSpeed live (not captured at sentence
                    // start) so a mid-sentence speed change takes effect
                    // immediately - setPlaybackSpeed() rebases the anchor
                    // itself so this stays continuous across that change.
                    val elapsedMs = highlightAnchorMs +
                        ((System.nanoTime() - highlightAnchorAtNanos) / 1_000_000.0 * playbackSpeed).toLong()
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
                NotificationChannel(CHANNEL_ID, "News Digest playback", NotificationManager.IMPORTANCE_LOW),
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
