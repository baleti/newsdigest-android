package dev.local.newsdigest

import android.content.Context

/**
 * Remembers the last read-aloud character offset per article/digest, so
 * reopening one can offer "Resume" instead of always starting over --
 * asked for explicitly 2026-09-10. Keyed by a stable per-content id (see
 * DetailActivity.readAloudPositionKey), not the transient session this
 * screen instance happens to run in.
 */
object ReadAloudPositionStore {
    private const val PREFS = "newsdigest_readaloud_positions"

    fun get(context: Context, key: String): Int? {
        if (key.isEmpty()) return null
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, -1)
        return if (v < 0) null else v
    }

    fun set(context: Context, key: String, offset: Int) {
        if (key.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, offset).apply()
    }

    // Cleared once a read finishes on its own (nothing left to resume) or
    // a fresh "Start again" is picked -- an offset only means anything
    // relative to a genuinely unfinished read.
    fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }
}
