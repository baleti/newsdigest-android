package dev.local.newsdigest

import android.content.Context

/**
 * Backend host/port and TTS engine choice, set once on first launch rather
 * than baked into the source - this app has no legitimate way to know
 * your network layout at build time. Everything (feed, TTS, and agent
 * chat) is served by the one process in server/server.py, on the one
 * port, so there's only one host/port pair to configure - no separate
 * token, since that server's security is the WireGuard tunnel + a fixed
 * non-secret header (see server.py's docstring), not a credential.
 *
 * Each field saves itself the moment it changes (see SettingsActivity) -
 * there's no separate Save button/step, and no "configured" flag to fall
 * out of sync with the fields it's supposed to describe: a non-blank host
 * is by definition all "configured" means.
 */
object Settings {
    private const val PREFS = "newsdigest_prefs"

    const val DEFAULT_TTS_PORT = 8792
    const val DEFAULT_ENGINE = "kokoro"
    const val DEFAULT_ACCOUNT = "claude2"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean = getHost(context).isNotBlank()

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString("host", host).apply()
    }

    fun setTtsPort(context: Context, port: Int) {
        prefs(context).edit().putInt("tts_port", port).apply()
    }

    fun setTtsEngine(context: Context, engine: String) {
        prefs(context).edit().putString("engine", engine).apply()
    }

    fun setAccount(context: Context, account: String) {
        prefs(context).edit().putString("account", account).apply()
    }

    fun getHost(context: Context): String = prefs(context).getString("host", "") ?: ""

    fun getTtsPort(context: Context): Int = prefs(context).getInt("tts_port", DEFAULT_TTS_PORT)

    fun getTtsEngine(context: Context): String = prefs(context).getString("engine", DEFAULT_ENGINE) ?: DEFAULT_ENGINE

    fun getAccount(context: Context): String = prefs(context).getString("account", DEFAULT_ACCOUNT) ?: DEFAULT_ACCOUNT
}
