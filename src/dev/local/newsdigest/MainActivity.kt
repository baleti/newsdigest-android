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

sealed class FeedEntry {
    data class Digest(val date: String, val markdown: String, val references: JSONArray) : FeedEntry()
    data class Item(
        val id: String,
        val title: String,
        val summary: String,
        val link: String,
        val feedTitle: String,
        val published: String,
    ) : FeedEntry()
}

class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private var entries: List<FeedEntry> = emptyList()
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Settings")
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
                val result = fetchEntries()
                runOnUiThread {
                    entries = result
                    if (result.isEmpty()) {
                        statusView.visibility = View.VISIBLE
                        statusView.text = "No feed data yet"
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

    private fun fetchEntries(): List<FeedEntry> {
        val out = mutableListOf<FeedEntry>()

        try {
            val digestJson = JSONObject(ApiClient.get(this, "/feed/digest"))
            out.add(FeedEntry.Digest(
                date = digestJson.getString("date"),
                markdown = digestJson.getString("markdown"),
                references = digestJson.optJSONArray("references") ?: JSONArray(),
            ))
        } catch (e: ApiClient.ApiException) {
            if (e.code != 404) throw e // 404 just means no digest generated yet - fine
        }

        val itemsJson = JSONObject(ApiClient.get(this, "/feed/items?limit=50"))
        val itemsArray = itemsJson.getJSONArray("items")
        for (i in 0 until itemsArray.length()) {
            val it = itemsArray.getJSONObject(i)
            out.add(FeedEntry.Item(
                id = it.optString("id"),
                title = it.optString("title"),
                summary = it.optString("summary"),
                link = it.optString("link"),
                feedTitle = it.optString("feed_title"),
                published = it.optString("published"),
            ))
        }
        return out
    }

    private fun openDetail(entry: FeedEntry) {
        val intent = Intent(this, DetailActivity::class.java)
        when (entry) {
            is FeedEntry.Digest -> {
                intent.putExtra("type", "digest")
                intent.putExtra("date", entry.date)
                intent.putExtra("markdown", entry.markdown)
                intent.putExtra("references", entry.references.toString())
            }
            is FeedEntry.Item -> {
                intent.putExtra("type", "item")
                intent.putExtra("title", entry.title)
                intent.putExtra("summary", entry.summary)
                intent.putExtra("link", entry.link)
                intent.putExtra("feedTitle", entry.feedTitle)
            }
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
                textSize = 10f
                setTextColor(Theme.onPrimary)
                setPadding(Theme.dp(this@MainActivity, 8), Theme.dp(this@MainActivity, 2), Theme.dp(this@MainActivity, 8), Theme.dp(this@MainActivity, 2))
            }
            val title = TextView(this@MainActivity).apply {
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.onBackground)
                setPadding(0, Theme.dp(this@MainActivity, 6), 0, 0)
            }
            val subtitle = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Theme.muted)
                setPadding(0, Theme.dp(this@MainActivity, 4), 0, Theme.dp(this@MainActivity, 6))
            }
            val snippet = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Theme.onSurfaceVariant)
                maxLines = 2
            }

            when (entry) {
                is FeedEntry.Digest -> {
                    badge.text = "DIGEST"
                    badge.background = Theme.roundedDrawable(Theme.primary, this@MainActivity, radiusDp = 4)
                    title.text = "Today's digest"
                    subtitle.text = entry.date
                    snippet.text = entry.markdown.take(140).replace("\n", " ")
                }
                is FeedEntry.Item -> {
                    badge.text = "ARTICLE"
                    badge.background = Theme.roundedDrawable(Theme.surfaceContainer, this@MainActivity, radiusDp = 4)
                    badge.setTextColor(Theme.onSurfaceVariant)
                    title.text = entry.title
                    subtitle.text = "${entry.feedTitle} · ${entry.published.take(10)}"
                    snippet.text = entry.summary
                }
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
