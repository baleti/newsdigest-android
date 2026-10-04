package dev.local.newsdigest

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's own TextToSpeech engine, used only to bridge the gap before
 * the ai1 server's first sentence arrives (cold Chatterbox, GPU model
 * eviction, a slow network). Much lower quality than Kokoro/Chatterbox but
 * synthesizes a sentence in a few hundred ms with no network at all.
 *
 * Deliberately renders to PCM (synthesizeToFile, then strip the WAV
 * header) instead of speak()ing directly, so a local sentence goes through
 * the exact same TtsPlaybackService queue as a server one - seeking, speed,
 * word highlighting and the media notification all work unchanged.
 *
 * Created at ReadAloudController.bind() so the engine is already
 * initialised by the time the user taps "Read aloud" (init takes a few
 * hundred ms on its own).
 */
class LocalTts(context: Context) {
    class Audio(val pcm: ByteArray, val sampleRate: Int)

    private val cacheDir = context.cacheDir
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(Locale.US)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) Log.w(TAG, "local TTS has no usable en-US voice (setLanguage=$r)")
            } else {
                Log.w(TAG, "local TTS init failed: status=$status")
            }
        }
    }

    fun isReady(): Boolean = ready

    /** Blocks (call off the main thread) until the sentence is rendered or
     * `timeoutMs` passes. Null on any failure - the caller just carries on
     * without a local bridge. */
    fun synthesize(sentence: String, timeoutMs: Long = 4000): Audio? {
        val engine = tts ?: return null
        if (!ready) return null
        val id = UUID.randomUUID().toString()
        val file = File(cacheDir, "localtts-$id.wav")
        val latch = CountDownLatch(1)
        var ok = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) { ok = true; latch.countDown() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
        })
        return try {
            if (engine.synthesizeToFile(sentence, Bundle(), file, id) != TextToSpeech.SUCCESS) return null
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || !ok) return null
            parseWav(file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "local synth failed: ${e.message}")
            null
        } finally {
            file.delete()
        }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    /** RIFF/WAVE, 16-bit mono PCM only (what every stock engine writes);
     * anything else returns null rather than playing garbage. */
    private fun parseWav(b: ByteArray): Audio? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF") return null
        fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        var pos = 12
        var rate = 0; var channels = 0; var bits = 0
        while (pos + 8 <= b.size) {
            val name = String(b, pos, 4)
            val size = le32(pos + 4)
            if (name == "fmt ") {
                channels = le16(pos + 10); rate = le32(pos + 12); bits = le16(pos + 22)
            } else if (name == "data") {
                if (channels != 1 || bits != 16 || rate <= 0) return null
                val start = pos + 8
                // Some engines stream the file and leave the data size as 0/-1.
                val len = if (size <= 0 || start + size > b.size) b.size - start else size
                if (len <= 0) return null
                return Audio(b.copyOfRange(start, start + len), rate)
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    companion object { private const val TAG = "LocalTts" }
}
