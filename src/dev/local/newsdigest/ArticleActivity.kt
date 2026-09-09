package dev.local.newsdigest

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class ArticleActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var readAloud: ReadAloudController
    private lateinit var contentView: TextView
    private lateinit var synthBanner: SynthesizingBanner
    private lateinit var playerBar: PlayerControlBar
    private var readAloudMenuItem: MenuItem? = null
    private var isPlaying = false
    private var plainText: String = ""
    private var articleTitle: String = ""

    private fun dp(v: Int) = Theme.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readAloud = ReadAloudController(
            this,
            onStateChanged = { playing ->
                // Belt-and-suspenders repaint - see DetailActivity's
                // identical comment for why this wasn't actually the bug.
                readAloudMenuItem?.title = if (playing) "Stop" else "Read aloud"
                window.decorView.post { invalidateOptionsMenu() }
                if (playing) {
                    isPlaying = true
                    playerBar.show()
                    playerBar.setPlaying(true)
                    playerBar.setSpeed(readAloud.getSpeed())
                } else {
                    playerBar.hide()
                    // Restore the plain view whenever playback stops -
                    // whether the user stopped it or it finished on its
                    // own reaching the end (onStateChanged fires for both).
                    contentView.text = plainText
                }
            },
            onCaptionChanged = { caption -> if (readAloud.isActive()) contentView.text = caption },
            onGenerating = { generating, estimatedMs ->
                if (generating) synthBanner.start(estimatedMs) else synthBanner.stop()
            },
            onPlayingChanged = { playing ->
                isPlaying = playing
                playerBar.setPlaying(playing)
            },
        )
        readAloud.bind()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }
        playerBar = PlayerControlBar(
            this,
            onRewind = { readAloud.seekRelative(-15_000) },
            onPlayPause = { if (isPlaying) readAloud.pause() else readAloud.resume() },
            onForward = { readAloud.seekRelative(15_000) },
            onSpeedClick = { anchor ->
                SpeedPicker.show(this, anchor, readAloud.getSpeed()) { speed ->
                    readAloud.setSpeed(speed)
                    playerBar.setSpeed(speed)
                }
            },
        )
        outer.addView(playerBar.view)

        synthBanner = SynthesizingBanner(this)
        outer.addView(synthBanner.view) // above the ScrollView, not inside it - stays visible regardless of scroll position

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Theme.bg)
        }
        statusView = TextView(this).apply {
            text = "Loading article..."
            setTextColor(Theme.onSurfaceVariant)
        }
        root.addView(statusView)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) }
        outer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(outer)

        val link = intent.getStringExtra("link")
        if (link.isNullOrBlank()) {
            statusView.text = "No article link provided"
            return
        }
        load(link)
    }

    // Sticky in the top bar (survives scrolling) rather than a button
    // inside the scrolling content, per feedback.
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        readAloudMenuItem = menu?.add(0, 1, 0, if (readAloud.isActive()) "Stop" else "Read aloud")
        readAloudMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            toggleReadAloud()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load(link: String) {
        Thread {
            try {
                val path = "/feed/article?link=" + ApiClient.encodeQuery(link)
                val article = JSONObject(ApiClient.get(this@ArticleActivity, path))
                val title = article.optString("title")
                val contentHtml = article.optString("content_html")
                val extractedText = Html.fromHtml(contentHtml, Html.FROM_HTML_MODE_LEGACY).toString()

                runOnUiThread {
                    articleTitle = title
                    plainText = extractedText
                    root.removeAllViews()
                    root.addView(
                        TextView(this).apply {
                            text = title
                            textSize = 19f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Theme.onBackground)
                        },
                    )
                    contentView = TextView(this).apply {
                        text = extractedText
                        textSize = 15f
                        setTextColor(Theme.onBackground)
                        setLineSpacing(dp(4).toFloat(), 1f)
                        setPadding(0, dp(16), 0, 0)
                        // Needed for ReadAloudController's per-word seek
                        // spans to actually receive taps - this plain
                        // extracted text has no other links to worry about.
                        // LinkTapHandler rather than LinkMovementMethod:
                        // one ClickableSpan per word adds up fast on a
                        // full article, and LinkMovementMethod hit-tests
                        // all of them on every touch move, not just a tap
                        // (see that file's doc for the reasoning).
                        LinkTapHandler.attach(this)
                    }
                    root.addView(contentView)
                }
            } catch (e: Exception) {
                Log.e("NewsDigest", "article load failed", e)
                runOnUiThread { statusView.text = "Failed to load article: ${e.message}" }
            }
        }.start()
    }

    private fun toggleReadAloud() {
        if (readAloud.isActive()) {
            readAloud.stop()
            contentView.text = plainText
        } else {
            readAloud.start(articleTitle, plainText)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readAloud.unbind()
    }
}
