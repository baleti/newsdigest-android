package dev.local.newsdigest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NewsDigest"

/** One topic-clustered digest for the day - see server/build_digest_json.py.
 * How many of these exist and what they're about is entirely up to that
 * day's generation run, never fixed. This is the ONLY thing the main
 * screen lists - individual source items are deliberately not shown here
 * (there's a separate app for reading raw articles); a digest's own
 * markdown links are still how you reach one. */
data class DigestEntry(
    val date: String,
    val runId: String,
    val topic: String,
    val category: String,
    val markdown: String,
    val references: JSONArray,
)

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var entries: List<DigestEntry> = emptyList()
    // Only auto-open Settings once, on the very first launch - not on every
    // resume. Resuming after backing out of Settings without configuring
    // anything must NOT relaunch it, or Back becomes a trap that always
    // bounces straight back to an unconfigured Settings screen.
    private var autoOpenedSettings = false

    // Long-press a digest to start selecting others, then "Read aloud" in
    // the top bar (shown only in this mode) combines whichever are
    // selected into one on-the-fly reading session - nothing is saved
    // server-side, it's just a convenience for reading several back to
    // back without opening each one.
    private var selectionMode = false
    private val selected = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }

        statusView = TextView(this).apply {
            text = "Loading..."
            textSize = 14f
            setTextColor(Theme.onSurfaceVariant)
            setPadding(Theme.dp(this@MainActivity, 20), Theme.dp(this@MainActivity, 20), Theme.dp(this@MainActivity, 20), Theme.dp(this@MainActivity, 20))
        }
        root.addView(statusView)

        listView = ListView(this).apply {
            divider = ColorDrawable(Theme.outlineVariant)
            dividerHeight = 1
            setBackgroundColor(Theme.bg)
            setSelector(android.R.color.transparent)
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (!Settings.isConfigured(this)) {
            autoOpenedSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.isConfigured(this)) {
            if (!autoOpenedSettings) {
                autoOpenedSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
                return
            }
            statusView.visibility = View.VISIBLE
            statusView.text = "Not configured yet - use the menu (top right) to open settings."
            listView.adapter = null
            return
        }
        if (entries.isEmpty()) load()
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    // A single action goes directly in the bar (SHOW_AS_ACTION_ALWAYS) -
    // no point routing one item through a "..." overflow menu. Swaps to
    // "Read aloud" while selecting digests to combine.
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (selectionMode) {
            val label = if (selected.size == 1) "Read aloud" else "Read aloud (${selected.size})"
            menu?.add(0, 2, 0, label)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        } else {
            menu?.add(0, 1, 0, "Settings")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            2 -> {
                combineAndReadAloud()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        refreshMenuAndList()
    }

    private fun refreshMenuAndList() {
        window.decorView.post { invalidateOptionsMenu() } // see DetailActivity's identical note on why this is deferred
        (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    /** Concatenates the selected digests' markdown into one ad-hoc digest
     * and opens it with reading already started - nothing is persisted,
     * this is purely a "read several back to back" convenience. */
    private fun combineAndReadAloud() {
        val chosen = selected.sorted().map { entries[it] }
        if (chosen.isEmpty()) return

        val combinedTopic = if (chosen.size <= 3) {
            chosen.joinToString(" + ") { it.topic }
        } else {
            "Combined digest (${chosen.size} topics)"
        }
        val combinedMarkdown = if (chosen.size == 1) {
            chosen[0].markdown
        } else {
            chosen.joinToString("\n\n") { "**${it.topic}**\n\n${it.markdown}" }
        }
        val combinedRefs = JSONArray()
        for (e in chosen) for (i in 0 until e.references.length()) combinedRefs.put(e.references.get(i))

        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("type", "digest")
            putExtra("date", chosen[0].date)
            // A combined entry's synthetic topic string never matches a
            // real entry in the digest JSON, so server-side chat
            // persistence (feed.set_chat_session) quietly no-ops for it -
            // the conversation still works for this session, it just won't
            // survive reopening the article later. Acceptable: combining
            // digests is a "read aloud now" flow, not really an "article
            // I'll come back to chat about" one.
            putExtra("runId", chosen[0].runId)
            putExtra("topic", combinedTopic)
            putExtra("markdown", combinedMarkdown)
            putExtra("references", combinedRefs.toString())
            putExtra("autoReadAloud", true)
        }
        exitSelectionMode()
        startActivity(intent)
    }

    private fun load() {
        statusView.visibility = View.VISIBLE
        statusView.text = "Loading..."
        Thread {
            try {
                val result = fetchDigests()
                runOnUiThread {
                    entries = result
                    if (result.isEmpty()) {
                        statusView.visibility = View.VISIBLE
                        statusView.text = "No digest yet - it's generated on a schedule, check back later"
                    } else {
                        statusView.visibility = View.GONE // no dead space above a populated list
                    }
                    listView.adapter = EntryAdapter()
                }
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                runOnUiThread {
                    statusView.visibility = View.VISIBLE
                    statusView.text = "Failed to load: ${e.message}"
                    Toast.makeText(this, "Load failed - check WireGuard connection", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetchDigests(): List<DigestEntry> {
        val out = mutableListOf<DigestEntry>()
        try {
            val body = JSONObject(ApiClient.get(this, "/feed/digests"))
            val date = body.getString("date")
            // Absent on a digest generated before this field existed (no
            // regeneration has happened since) - chat just won't be able to
            // resume/persist for those stale entries until the next run.
            val runId = body.optString("run_id", "")
            val digestsArray = body.getJSONArray("digests")
            for (i in 0 until digestsArray.length()) {
                val d = digestsArray.getJSONObject(i)
                out.add(DigestEntry(
                    date = date,
                    runId = runId,
                    topic = d.optString("topic").ifBlank { "Today" },
                    category = d.optString("category"),
                    markdown = d.getString("markdown"),
                    references = d.optJSONArray("references") ?: JSONArray(),
                ))
            }
        } catch (e: ApiClient.ApiException) {
            if (e.code != 404) throw e // 404 just means no digest generated yet - fine
        }
        return out
    }

    private fun openDetail(entry: DigestEntry) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("type", "digest")
            putExtra("date", entry.date)
            putExtra("runId", entry.runId)
            putExtra("topic", entry.topic)
            putExtra("markdown", entry.markdown)
            putExtra("references", entry.references.toString())
        }
        startActivity(intent)
    }

    private inner class EntryAdapter : BaseAdapter(), AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {
        init {
            listView.onItemClickListener = this
            listView.onItemLongClickListener = this
        }

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = entries[position]
            val isSelected = position in selected
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val padH = Theme.dp(this@MainActivity, 20)
                val padV = Theme.dp(this@MainActivity, 16)
                setPadding(padH, padV, padH, padV)
                // ~20% opacity primary tint over the row when selected -
                // composites against the list's own opaque background
                // behind it, so it reads as a tinted card, not garish.
                val bg = if (isSelected) (0x33000000 or (Theme.primary and 0x00FFFFFF)) else Theme.bg
                background = Theme.rippleOn(Theme.roundedDrawable(bg, this@MainActivity, radiusDp = 0))
            }

            // Category/source-count badges removed per feedback - with the
            // generator now producing one long-form digest instead of
            // several topic-clustered pieces, a per-entry category tag no
            // longer says anything useful, and the list is short enough
            // that a source count added noise rather than signal.
            val title = TextView(this@MainActivity).apply {
                text = (if (isSelected) "✓ " else "") + entry.topic
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSelected) Theme.primary else Theme.onBackground)
            }
            val subtitle = TextView(this@MainActivity).apply {
                text = entry.date
                textSize = 12f
                setTextColor(Theme.muted)
                setPadding(0, Theme.dp(this@MainActivity, 4), 0, Theme.dp(this@MainActivity, 6))
            }
            val snippet = TextView(this@MainActivity).apply {
                text = entry.markdown.take(140).replace("\n", " ")
                textSize = 14f
                setTextColor(Theme.onSurfaceVariant)
                maxLines = 2
            }

            container.addView(title)
            container.addView(subtitle)
            container.addView(snippet)
            return container
        }

        private fun toggleSelection(position: Int) {
            if (!selected.add(position)) selected.remove(position)
            if (selected.isEmpty()) selectionMode = false
            refreshMenuAndList()
        }

        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (selectionMode) toggleSelection(position) else openDetail(entries[position])
        }

        override fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean {
            if (!selectionMode) {
                selectionMode = true
                selected.add(position)
                refreshMenuAndList()
            } else {
                toggleSelection(position)
            }
            return true
        }
    }
}
