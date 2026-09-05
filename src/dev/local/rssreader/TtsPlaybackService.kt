package dev.local.rssreader

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** WebSocket client to tts-server + AudioTrack streaming playback +
 * MediaSession integration - not built yet. Declared now so the manifest
 * component resolves; nothing currently starts this service. */
class TtsPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
