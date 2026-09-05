package dev.local.rssreader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
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

private const val TAG = "RssReader"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusView = TextView(this).apply {
            text = "Loading..."
            setPadding(40, 40, 40, 40)
        }
        root.addView(statusView)

        listView = ListView(this)
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.isConfigured(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        statusView.text = "Loading..."
        Thread {
            try {
                val result = fetchEntries()
                runOnUiThread {
                    entries = result
                    statusView.text = if (result.isEmpty()) "No feed data yet" else ""
                    listView.adapter = EntryAdapter()
                }
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                runOnUiThread {
                    statusView.text = "Failed to load: ${e.message}"
                    Toast.makeText(this, "Load failed - check WireGuard connection", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetchEntries(): List<FeedEntry> {
        val out = mutableListOf<FeedEntry>()

        try {
            val digestJson = JSONObject(ApiClient.ttsGet(this, "/feed/digest"))
            out.add(FeedEntry.Digest(
                date = digestJson.getString("date"),
                markdown = digestJson.getString("markdown"),
                references = digestJson.optJSONArray("references") ?: JSONArray(),
            ))
        } catch (e: ApiClient.ApiException) {
            if (e.code != 404) throw e // 404 just means no digest generated yet - fine
        }

        val itemsJson = JSONObject(ApiClient.ttsGet(this, "/feed/items?limit=50"))
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
                setPadding(40, 30, 40, 30)
            }

            val title = TextView(this@MainActivity).apply {
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }
            val subtitle = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 6, 0, 6)
            }
            val snippet = TextView(this@MainActivity).apply {
                textSize = 14f
                maxLines = 2
            }

            when (entry) {
                is FeedEntry.Digest -> {
                    title.text = "Today's digest"
                    subtitle.text = entry.date
                    snippet.text = entry.markdown.take(140).replace("\n", " ")
                }
                is FeedEntry.Item -> {
                    title.text = entry.title
                    subtitle.text = "${entry.feedTitle} · ${entry.published.take(10)}"
                    snippet.text = entry.summary
                }
            }

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
