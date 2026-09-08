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
data class DigestEntry(val date: String, val topic: String, val markdown: String, val references: JSONArray)

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var entries: List<DigestEntry> = emptyList()
    // Only auto-open Settings once, on the very first launch - not on every
    // resume. Resuming after backing out of Settings without configuring
    // anything must NOT relaunch it, or Back becomes a trap that always
    // bounces straight back to an unconfigured Settings screen.
    private var autoOpenedSettings = false

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

    // A single action goes directly in the bar (SHOW_AS_ACTION_ALWAYS) -
    // no point routing one item through a "..." overflow menu.
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
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
            val digestsArray = body.getJSONArray("digests")
            for (i in 0 until digestsArray.length()) {
                val d = digestsArray.getJSONObject(i)
                out.add(DigestEntry(
                    date = date,
                    topic = d.optString("topic").ifBlank { "Today" },
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
            putExtra("topic", entry.topic)
            putExtra("markdown", entry.markdown)
            putExtra("references", entry.references.toString())
        }
        startActivity(intent)
    }

    private inner class EntryAdapter : BaseAdapter(), AdapterView.OnItemClickListener {
        init {
            listView.onItemClickListener = this
        }

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = entries[position]
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val padH = Theme.dp(this@MainActivity, 20)
                val padV = Theme.dp(this@MainActivity, 16)
                setPadding(padH, padV, padH, padV)
                background = Theme.rippleOn(Theme.roundedDrawable(Theme.bg, this@MainActivity, radiusDp = 0))
            }

            val badge = TextView(this@MainActivity).apply {
                text = "DIGEST"
                textSize = 10f
                setTextColor(Theme.onPrimary)
                background = Theme.roundedDrawable(Theme.primary, this@MainActivity, radiusDp = 4)
                setPadding(Theme.dp(this@MainActivity, 8), Theme.dp(this@MainActivity, 2), Theme.dp(this@MainActivity, 8), Theme.dp(this@MainActivity, 2))
            }
            val title = TextView(this@MainActivity).apply {
                text = entry.topic
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.onBackground)
                setPadding(0, Theme.dp(this@MainActivity, 6), 0, 0)
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

            container.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(title)
            container.addView(subtitle)
            container.addView(snippet)
            return container
        }

        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            openDetail(entries[position])
        }
    }
}
