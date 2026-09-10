package dev.local.newsdigest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

// Overview jump-links (see build_digest_json.py / generate-digest.sh)
// aren't real URLs - the part after this prefix is an exact, verbatim
// phrase to find by plain substring search in the main body and scroll/
// highlight, rather than something to hand to a browser.
private const val JUMP_LINK_PREFIX = "#jump:"

/**
 * Content view (digest or item) + read-aloud-with-highlighting + a sticky
 * chat box. Two independent bindings to TtsPlaybackService are used on
 * purpose: ReadAloudController owns one for the main content (with live
 * word-highlighting), and this activity owns a second, simpler one for
 * "read this reply aloud" buttons in the chat thread (no highlighting -
 * the reply's full text is already visible as it streamed in, so a
 * separate live-caption view would just duplicate it). Only one of the
 * two is ever actually driving playback at a time, since starting either
 * stops the other's queue first - Android allows multiple bind() calls
 * against the same running service instance, so this isn't two services.
 */
class DetailActivity : Activity() {

    private lateinit var contentContainer: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var overviewView: TextView
    private lateinit var overviewSpacer: View
    private lateinit var sectionIndicatorView: TextView
    // (bodyTextOffset, label) per overview jump-link, sorted by offset -
    // lets the scroll listener below say which section is currently at
    // the top of the viewport. Empty for anything without an overview
    // (a plain item, or a digest predating the overview format), which
    // just keeps sectionIndicatorView permanently hidden.
    private var sectionMarkers: List<Pair<Int, String>> = emptyList()
    private lateinit var contentView: TextView
    private lateinit var synthBanner: SynthesizingBanner
    private lateinit var playerBar: PlayerControlBar
    private lateinit var readAloudButton: Button
    private lateinit var resumeButton: Button
    private lateinit var resumePreviewView: TextView
    // Stable per-content id for ReadAloudPositionStore - a digest run's
    // own runId+topic when there is one (stays valid across app restarts,
    // unlike this screen's own instance), else the item's link (the one
    // stable identifier a plain feed item has). Set in loadDigest()/loadItem().
    private var readAloudPositionKey: String = ""
    // Ticks while actively playing so a killed app can still resume close
    // to where it left off, not just wherever the last explicit pause
    // happened to be (see updateReadAloudButtons's own doc).
    private val positionSaveTick = object : Runnable {
        override fun run() {
            if (readAloud.isActive()) {
                readAloud.currentReadingOffset()?.let {
                    ReadAloudPositionStore.set(this@DetailActivity, readAloudPositionKey, it)
                }
                mainHandler.postDelayed(this, 5_000)
            }
        }
    }
    private var isPlaying = false
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var scrollView: ScrollView
    private val mainHandler = Handler(Looper.getMainLooper())

    // The static (non-read-aloud) rendering of the main body - kept as its
    // own mutable builder, the same "own field, reassign a fresh copy on
    // every span change" pattern ReadAloudController uses for its live
    // caption, rather than trying to mutate whatever TextView.text hands
    // back (a plain TextView snapshots an assigned Spannable into an
    // immutable copy - see jumpToPhrase()). An overview link's highlight
    // is applied here, not to ReadAloudController's own copy - jumping
    // around while actively reading isn't supported, only in the static view.
    private var staticContentSpannable: SpannableStringBuilder? = null
    private var jumpHighlightSpan: BackgroundColorSpan? = null
    private var jumpHighlightRunnable: Runnable? = null

    private lateinit var readAloud: ReadAloudController
    private var chatTtsService: TtsPlaybackService? = null
    private var chatTtsBound = false

    private var agentChat: AgentChatClient? = null
    private var chatBusyRow: LinearLayout? = null

    private var sourceTitle: String = ""
    private var sourceLink: String = ""
    private var contentForChat: String = ""
    private var rawContent: String = ""
    // Raw markdown of the overview paragraph(s) shown above the body (see
    // overviewView) - kept separately from rawContent so toggleReadAloud()
    // can prepend it to what's actually read aloud (asked for explicitly
    // 2026-09-10: "that table of contents doesn't even get read... needs
    // to explain... at the beginning"). Blank for a plain item, or a
    // digest predating the overview format.
    private var rawOverview: String = ""
    private var isDigest = false
    private var digestRunId: String = ""
    private var digestTopic: String = ""

    private val chatConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            chatTtsService = (service as TtsPlaybackService.LocalBinder).service()
            chatTtsBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            chatTtsService = null
            chatTtsBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest's android:label="Item" was only ever a placeholder
        // action-bar title, and blanking it (the first fix, 2026-09-09)
        // still left an empty action-bar row taking up space - reported
        // live the same day ("its still there"). Hiding the action bar
        // outright removes the row entirely; "Read aloud" moves to
        // readAloudButton, a plain view in the scrolling layout instead
        // of an action-bar menu item (see buildUi()).
        actionBar?.hide()

        readAloud = ReadAloudController(
            this,
            onStateChanged = { playing ->
                readAloudButton.visibility = if (playing) View.GONE else View.VISIBLE
                resumeButton.visibility = if (playing) View.GONE else resumeButton.visibility
                if (playing) {
                    isPlaying = true
                    playerBar.show()
                    playerBar.setPlaying(true)
                    playerBar.setSpeed(readAloud.getSpeed())
                    // For a digest, the caption keeps its real reference-
                    // link ClickableSpans alongside the per-word seek spans
                    // this controller adds (see ReadAloudController.start's
                    // doc); for a plain item there are no real links to
                    // begin with (renderedText is the raw summary string,
                    // see toggleReadAloud). Either way this is the many-
                    // spans-in-a-ScrollView case LinkTapHandler exists for.
                    LinkTapHandler.attach(contentView)
                    mainHandler.postDelayed(positionSaveTick, 5_000)
                } else {
                    playerBar.hide()
                    // The readAloudButton that could trigger a manual stop
                    // was removed entirely (asked for explicitly 2026-09-09:
                    // "remove the stop button... it serves no purpose"), so
                    // this is only ever reached by genuinely finishing the
                    // whole text - safe to treat as "done, nothing left to
                    // resume" and clear any saved position outright.
                    mainHandler.removeCallbacks(positionSaveTick)
                    ReadAloudPositionStore.clear(this, readAloudPositionKey)
                    updateReadAloudButtons()
                    // Restore the rich static view whenever playback
                    // stops - whether the user stopped it or it finished
                    // on its own reaching the end (onStateChanged fires
                    // for both, so this one place covers it).
                    if (isDigest) {
                        contentView.text = renderStaticContent(rawContent)
                        LinkTapHandler.attach(contentView)
                    } else {
                        contentView.text = rawContent
                    }
                }
            },
            onCaptionChanged = { caption ->
                // caption is the SAME captionBuilder instance on every
                // call within one reading session now (see
                // ReadAloudController's doc) - reassigning contentView.text
                // to it again on every ~60ms highlight tick forced a full
                // TextView relayout of the whole (now overview+body-length)
                // article that many times a second, which is what was
                // actually behind two reported regressions: choppy
                // scrolling while playing, and word-tap-to-seek
                // (LinkTapHandler reading tv.layout at tap time) landing on
                // stale/mid-relayout geometry. Only a genuinely new object
                // (the very first call of a session) needs the real
                // assignment; every later call for the same object is just
                // a cheap repaint of the moved highlight span.
                if (readAloud.isActive()) {
                    if (contentView.text !== caption) {
                        contentView.text = caption
                    } else {
                        contentView.invalidate()
                    }
                }
            },
            onGenerating = { generating, estimatedMs ->
                if (generating) synthBanner.start(estimatedMs) else synthBanner.stop()
            },
            onPlayingChanged = { playing ->
                isPlaying = playing
                playerBar.setPlaying(playing)
                // Fires for a pause from ANY source (in-app, the system
                // notification's own button, swiping the notification -
                // see TtsPlaybackService's delete-intent doc, audio focus
                // loss), unlike playerBar's onPlayPause below which only
                // ever sees an in-app tap - confirmed live 2026-09-10 as
                // the reason "Resume" started over from the beginning
                // after pausing via the notification/swipe path: nothing
                // saved a position at all in that case, since the only
                // other save points (this activity's periodic tick, and
                // the in-app pause handler) either hadn't ticked yet or
                // never ran for a trigger that didn't come through them.
                if (!playing) {
                    readAloud.currentReadingOffset()?.let {
                        ReadAloudPositionStore.set(this, readAloudPositionKey, it)
                    }
                }
            },
        )
        readAloud.bind()
        bindService(Intent(this, TtsPlaybackService::class.java), chatConnection, Context.BIND_AUTO_CREATE)

        buildUi()

        when (intent.getStringExtra("type")) {
            "digest" -> loadDigest()
            "item" -> loadItem()
            else -> titleView.text = "Unknown entry"
        }

        // Set by MainActivity's "combine selected digests" action - the
        // whole point of that flow is to start reading immediately, not
        // land on a screen you still have to press play on. Delayed
        // slightly since bindService() above is asynchronous even for a
        // same-process service; toggleReadAloud() would silently no-op
        // if it fires before onServiceConnected has actually run.
        if (intent.getBooleanExtra("autoReadAloud", false)) {
            window.decorView.postDelayed({ if (!readAloud.isActive()) toggleReadAloud() }, 300)
        }
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }

        playerBar = PlayerControlBar(
            this,
            onPreviousSection = { readAloud.skipToPreviousSection() },
            onRewind = { readAloud.seekRelative(-15_000) },
            // Position-saving itself now lives in onPlayingChanged above,
            // which fires for a pause from any source, not just this one.
            onPlayPause = { if (isPlaying) readAloud.pause() else readAloud.resume() },
            onForward = { readAloud.seekRelative(15_000) },
            onNextSection = { readAloud.skipToNextSection() },
            onSpeedClick = { anchor ->
                SpeedPicker.show(this, anchor, readAloud.getSpeed()) { speed ->
                    readAloud.setSpeed(speed)
                    playerBar.setSpeed(speed)
                }
            },
            onLocate = { scrollToCurrentReading() },
            getPosition = { readAloud.getPositionMs() },
            getDuration = { readAloud.getDurationMs() },
            onSeek = { fraction -> readAloud.seekToFraction(fraction) },
        )
        outer.addView(playerBar.view)

        synthBanner = SynthesizingBanner(this)
        outer.addView(synthBanner.view) // above the ScrollView, not inside it - stays visible regardless of scroll position

        // Sticky "which section am I looking at" cue for a digest with an
        // overview - asked for live 2026-09-09 ("position in view what
        // section currently is scrolled to would be shown somewhere in
        // the UI"). Above the ScrollView like synthBanner, not inside it,
        // so it stays put regardless of scroll position. GONE by default;
        // loadDigest() only shows it once sectionMarkers is non-empty.
        sectionIndicatorView = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.onSurfaceVariant)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(Theme.surface)
        }
        outer.addView(sectionIndicatorView)

        scrollView = ScrollView(this).apply { setBackgroundColor(Theme.bg) }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scrollView.addView(contentContainer)
        outer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateSectionIndicator(scrollY) }

        titleView = TextView(this).apply {
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.onBackground)
        }
        subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, dp(6), 0, dp(16))
        }
        // Only shown for a digest whose generation run produced an
        // ===OVERVIEW=== block (see build_digest_json.py) - visibility is
        // set per-load in loadDigest(), GONE by default so an item view
        // (which never has one) or an older digest never leaves empty
        // space here.
        overviewView = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(Theme.onSurfaceVariant)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Theme.roundedDrawable(Theme.surface, this@DetailActivity)
            LinkTapHandler.attach(this)
            setLinkTextColor(Theme.linkColor)
        }
        contentView = TextView(this).apply {
            textSize = 15f
            setTextColor(Theme.onBackground)
            setLineSpacing(dp(4).toFloat(), 1f)
            LinkTapHandler.attach(this)
            setLinkTextColor(Theme.linkColor)
        }
        // Only shown to START a read -- once playing, playerBar (rewind/
        // play-pause/forward/prev/next/speed) plus the system
        // notification's own Stop action already cover control, so this
        // just disappears (onStateChanged above) instead of turning into
        // a redundant second "Stop" (asked for explicitly 2026-09-09:
        // "remove the stop button... it serves no purpose"). Used to be
        // an action-bar menu item instead of a real view - moved here
        // once the action bar itself was hidden (see onCreate's comment).
        readAloudButton = Button(this).apply {
            text = "Read aloud"
            setTextColor(Theme.onBackground)
            Theme.styleGhostButton(this, this@DetailActivity)
            // Always starts over from the top -- label switches to "Start
            // again" (see updateReadAloudButtons) once a resumable saved
            // position exists, so this one action always means the same
            // thing regardless of which label it's currently showing.
            setOnClickListener {
                ReadAloudPositionStore.clear(this@DetailActivity, readAloudPositionKey)
                toggleReadAloud(resumeOffset = null)
            }
        }
        // Shown only alongside readAloudButton, only when a saved position
        // exists for this exact article/digest (asked for explicitly
        // 2026-09-10: "save last position... instead of one read aloud
        // button... a read aloud section with two buttons: start again
        // and resume"). See updateReadAloudButtons/readAloudPositionKey.
        resumeButton = Button(this).apply {
            text = "Resume"
            setTextColor(Theme.onPrimary)
            Theme.stylePrimaryButton(this, this@DetailActivity)
            visibility = View.GONE
            setOnClickListener {
                val offset = ReadAloudPositionStore.get(this@DetailActivity, readAloudPositionKey)
                toggleReadAloud(resumeOffset = offset)
            }
        }
        val readAloudButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        readAloudButtonRow.addView(readAloudButton)
        val resumeButtonParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        resumeButtonParams.marginStart = dp(10)
        readAloudButtonRow.addView(resumeButton, resumeButtonParams)

        // Shows what Resume will actually pick up from - asked for
        // explicitly 2026-09-10 ("it would be good if resume showed
        // where it was, where it will start from"). Text set in
        // updateReadAloudButtons.
        resumePreviewView = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.ITALIC)
            setTextColor(Theme.onSurfaceVariant)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val readAloudSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        readAloudSection.addView(readAloudButtonRow)
        val resumePreviewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        resumePreviewParams.topMargin = dp(6)
        readAloudSection.addView(resumePreviewView, resumePreviewParams)

        contentContainer.addView(titleView)
        contentContainer.addView(subtitleView)
        contentContainer.addView(readAloudSection)
        contentContainer.addView(overviewView)
        overviewSpacer = spacer(16).also { it.visibility = View.GONE }
        contentContainer.addView(overviewSpacer)
        contentContainer.addView(contentView)
        contentContainer.addView(spacer(28))
        chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentContainer.addView(chatContainer)
        contentContainer.addView(spacer(80)) // keep last chat bubble clear of the sticky bar

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val topBorder = android.graphics.drawable.GradientDrawable()
            topBorder.setColor(Theme.surface)
            elevation = dp(8).toFloat()
        }
        inputField = EditText(this).apply {
            hint = "Ask a question..."
            Theme.styleEditText(this, this@DetailActivity)
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setTextColor(Theme.onPrimary)
            Theme.stylePrimaryButton(this, this@DetailActivity)
            setOnClickListener { onSend() }
        }
        inputRow.addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(spacerHorizontal(8))
        inputRow.addView(sendButton)
        outer.addView(inputRow)

        setContentView(outer)
    }

    private fun spacer(dpVal: Int) = TextView(this).apply { setPadding(0, dp(dpVal), 0, 0) }
    private fun spacerHorizontal(dpVal: Int) = TextView(this).apply { setPadding(dp(dpVal), 0, 0, 0) }

    private fun loadDigest() {
        val date = intent.getStringExtra("date") ?: ""
        val runId = intent.getStringExtra("runId") ?: ""
        val topic = intent.getStringExtra("topic") ?: "Today"
        val overview = intent.getStringExtra("overview") ?: ""
        val markdown = intent.getStringExtra("markdown") ?: ""
        isDigest = true
        digestRunId = runId
        digestTopic = topic
        titleView.text = topic
        subtitleView.text = date
        rawContent = markdown
        rawOverview = overview
        contentForChat = "Today's \"$topic\" digest ($date):\n\n$markdown"
        sourceTitle = "$topic ($date)"
        sourceLink = ""
        contentView.text = renderStaticContent(markdown)

        if (overview.isBlank()) {
            overviewView.visibility = View.GONE
            overviewSpacer.visibility = View.GONE
            sectionMarkers = emptyList()
            sectionIndicatorView.visibility = View.GONE
        } else {
            overviewView.text = MarkdownRenderer.render(overview) { url -> handleContentLink(url) }
            overviewView.visibility = View.VISIBLE
            overviewSpacer.visibility = View.VISIBLE
            sectionMarkers = parseSectionMarkers(overview)
            sectionIndicatorView.visibility = if (sectionMarkers.isEmpty()) View.GONE else View.VISIBLE
            if (sectionMarkers.isNotEmpty()) sectionIndicatorView.text = sectionMarkers.first().second
        }

        // Was this article already chatted about (this session, an earlier
        // one, even another device)? The conversation is persisted
        // server-side against this exact digest run + topic - resume it
        // rather than starting a new one every time the article is opened.
        if (runId.isNotBlank()) {
            ensureAgentChat().resume(runId, topic)
        }

        readAloudPositionKey = "digest:${if (runId.isNotBlank()) runId else date}:$topic"
        updateReadAloudButtons()
    }

    private fun loadItem() {
        val title = intent.getStringExtra("title") ?: ""
        val summary = intent.getStringExtra("summary") ?: ""
        val feedTitle = intent.getStringExtra("feedTitle") ?: ""
        val link = intent.getStringExtra("link") ?: ""
        isDigest = false
        titleView.text = title
        subtitleView.text = feedTitle
        rawContent = summary
        contentForChat = "Article \"$title\" from $feedTitle:\n\n$summary"
        sourceTitle = title
        sourceLink = link
        contentView.text = summary

        // link is the one genuinely stable identifier a plain feed item
        // has (title/feedTitle can repeat, e.g. wire-service reprints) -
        // falls back to title+feedTitle only for the rare item with no link.
        readAloudPositionKey = "item:" + link.ifBlank { "$title|$feedTitle" }
        updateReadAloudButtons()

        val openArticleButton = Button(this).apply {
            text = "Open full article"
            setTextColor(Theme.onBackground)
            Theme.styleGhostButton(this, this@DetailActivity)
        }
        openArticleButton.setOnClickListener { openArticle(link) }
        val idx = contentContainer.indexOfChild(contentView)
        contentContainer.addView(spacer(16), idx + 1)
        contentContainer.addView(openArticleButton, idx + 2)
    }

    /** Opens `link` in the system browser. Used to launch an in-app
     * ArticleActivity that fetched and re-rendered the article server-side
     * - too fragile against paywalls/JS-rendered pages in practice
     * (reported live 2026-09-09: reference links "don't do anything"),
     * and the real source page is what the reader actually wants anyway.
     * Only http/https is ever handed to ACTION_VIEW - link text comes
     * from the digest generator's own output (an LLM-authored field, not
     * literally user input, but still untrusted content this app didn't
     * write) and a scheme like "intent:" or "javascript:" could otherwise
     * be used to target an unintended component or run script in whatever
     * handles it. */
    private fun openArticle(link: String) {
        if (link.isBlank()) return
        val uri = try { Uri.parse(link) } catch (_: Exception) { null } ?: return
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            Toast.makeText(this, "Not a web link", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Renders markdown for the static (non-read-aloud) main body and
     * keeps the resulting builder as staticContentSpannable so a later
     * overview jump-link can mutate its spans - see that field's own doc. */
    private fun renderStaticContent(markdown: String): SpannableStringBuilder {
        val rendered = MarkdownRenderer.render(markdown) { url -> handleContentLink(url) }
        staticContentSpannable = rendered
        return rendered
    }

    /** Every link inside content this activity renders - the main body and
     * the overview alike - goes through here: a real link opens the
     * article, a "#jump:" one (only ever produced by an overview, but
     * handled the same regardless of source) scrolls/highlights within
     * the main body instead. */
    private fun handleContentLink(url: String) {
        if (url.startsWith(JUMP_LINK_PREFIX)) {
            jumpToPhrase(url.removePrefix(JUMP_LINK_PREFIX))
        } else {
            openArticle(url)
        }
    }

    /** Every `[label](#jump:phrase)` in the overview, resolved to where
     * `phrase` actually falls in the rendered body's plain text (same
     * lookup jumpToPhrase() does), sorted by that position - the ordering
     * the overview lists them in isn't trusted since a generator run
     * could in principle emit them out of body order. A phrase that
     * doesn't match verbatim is dropped rather than breaking the whole
     * list, same "best effort" spirit as jumpToPhrase() itself. */
    private fun parseSectionMarkers(overview: String): List<Pair<Int, String>> {
        val bodyText = staticContentSpannable?.toString() ?: return emptyList()
        val re = Regex("""\[([^\]]+)]\(${Regex.escape(JUMP_LINK_PREFIX)}([^)]+)\)""")
        return re.findAll(overview)
            .mapNotNull { m ->
                val label = m.groupValues[1]
                val phrase = m.groupValues[2]
                val idx = bodyText.indexOf(phrase)
                if (idx < 0) null else idx to label
            }
            .sortedBy { it.first }
            .toList()
    }

    /** Called on every scroll of the main body - finds whichever section
     * marker's body position is at or just above the top of the current
     * viewport and shows its label, so the indicator always names the
     * section the reader is currently inside rather than the one they're
     * approaching. Same y-to-line math as jumpToPhrase() uses, in reverse. */
    private fun updateSectionIndicator(scrollY: Int) {
        if (sectionMarkers.isEmpty()) return
        val layout = contentView.layout ?: return
        val y = (scrollY - contentView.top).coerceIn(0, layout.height)
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, 0f)
        val current = sectionMarkers.lastOrNull { it.first <= offset } ?: sectionMarkers.first()
        if (sectionIndicatorView.text != current.second) sectionIndicatorView.text = current.second
    }

    /** Finds `phrase` as an exact substring of the main body's current
     * plain text, scrolls it into view, and briefly highlights it. Best
     * effort, same spirit as ReadAloudController's own sentence search -
     * a phrase that doesn't match verbatim (the generator paraphrased
     * instead of quoting, or the body was edited) just silently does
     * nothing rather than erroring. Only acts on the static view -
     * jumping around mid-read-aloud isn't supported. */
    private fun jumpToPhrase(phrase: String) {
        val builder = staticContentSpannable ?: return
        val text = builder.toString()
        val idx = text.indexOf(phrase)
        if (idx < 0) return
        val end = (idx + phrase.length).coerceAtMost(text.length)

        // Clear any still-pending highlight from a previous jump first, so
        // two quick taps don't leave two live removal timers fighting over
        // the same builder.
        jumpHighlightSpan?.let { builder.removeSpan(it) }
        jumpHighlightRunnable?.let { mainHandler.removeCallbacks(it) }

        val span = BackgroundColorSpan(0x552196F3.toInt())
        builder.setSpan(span, idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        jumpHighlightSpan = span
        contentView.text = SpannableStringBuilder(builder)

        contentView.post {
            val layout = contentView.layout ?: return@post
            val line = layout.getLineForOffset(idx)
            val y = contentView.top + layout.getLineTop(line)
            scrollView.smoothScrollTo(0, (y - dp(24)).coerceAtLeast(0))
        }

        val runnable = Runnable {
            builder.removeSpan(span)
            contentView.text = SpannableStringBuilder(builder)
            jumpHighlightSpan = null
            jumpHighlightRunnable = null
        }
        jumpHighlightRunnable = runnable
        mainHandler.postDelayed(runnable, 2500)
    }

    /** Scrolls to wherever read-aloud is currently at - asked for
     * explicitly 2026-09-10: on a long article or digest overview it's
     * easy to lose track of the live position after scrolling away from
     * it. contentView is showing the live caption while playback is
     * active (see onCaptionChanged), so its offsets line up directly
     * with readAloud.currentReadingOffset() - same line/y math as
     * jumpToPhrase(), just driven by a known offset instead of a text
     * search. */
    private fun scrollToCurrentReading() {
        val offset = readAloud.currentReadingOffset() ?: return
        val layout = contentView.layout ?: return
        val clamped = offset.coerceIn(0, contentView.text.length)
        val line = layout.getLineForOffset(clamped)
        val y = contentView.top + layout.getLineTop(line)
        scrollView.smoothScrollTo(0, (y - dp(24)).coerceAtLeast(0))
    }

    private fun toggleReadAloud(resumeOffset: Int? = null) {
        if (readAloud.isActive()) {
            readAloud.stop()
            // restore the rich static view once playback stops
            if (isDigest) {
                contentView.text = renderStaticContent(rawContent)
                LinkTapHandler.attach(contentView)
            } else {
                contentView.text = rawContent
            }
        } else {
            chatTtsService?.stopAll()
            // Pass the RENDERED text, not raw markdown - see
            // ReadAloudController.start()'s doc: this keeps the caption's
            // plain-text form identical to what the server actually
            // strips down to and times, so highlighting/tap-to-seek stay
            // in sync for sentences containing links, bold, etc. instead
            // of silently failing to match (confirmed live before this fix).
            //
            // The overview is prepended here (asked for explicitly
            // 2026-09-10) so playback opens with the same spoken summary
            // the overview paragraph gives a sighted reader, instead of
            // jumping straight into the full narrative with no framing -
            // it was never part of rawContent (loadDigest keeps it
            // separate, see rawOverview's own doc), so without this it
            // never reached the TTS stream at all.
            val renderedText = if (isDigest) {
                val combined = if (rawOverview.isNotBlank()) "$rawOverview\n\n$rawContent" else rawContent
                MarkdownRenderer.render(combined) { url ->
                    // A "#jump:" link scrolls/highlights the STATIC view
                    // (see staticContentSpannable's doc) - contentView is
                    // showing the live read-aloud caption right now, not
                    // that view, so jumping is meaningless mid-playback.
                    // Silently ignored rather than routed to openArticle(),
                    // which would just show a confusing "Not a web link"
                    // toast for what looks like a normal tap on a link.
                    if (!url.startsWith(JUMP_LINK_PREFIX)) openArticle(url)
                }
            } else {
                rawContent
            }
            readAloud.start(sourceTitle, renderedText, startOffset = resumeOffset ?: 0)
        }
    }

    // Toggles between the plain "Read aloud" state and the "Start again" /
    // "Resume" pair (see readAloudButton/resumeButton's own doc) based on
    // whatever's currently saved for readAloudPositionKey. Called whenever
    // that could have changed: content just loaded, or a read just ended.
    private fun updateReadAloudButtons() {
        val saved = ReadAloudPositionStore.get(this, readAloudPositionKey)
        if (saved != null) {
            readAloudButton.text = "Start again"
            resumeButton.visibility = View.VISIBLE
            resumePreviewView.text = "Resumes at: “${resumePreviewSnippet(saved)}…”"
            resumePreviewView.visibility = View.VISIBLE
        } else {
            readAloudButton.text = "Read aloud"
            resumeButton.visibility = View.GONE
            resumePreviewView.visibility = View.GONE
        }
    }

    // Same combined-and-rendered text toggleReadAloud() builds for actual
    // playback, minus the real link spans - only the plain characters
    // matter here, for indexing into it at a saved char offset.
    private fun readAloudPlainText(): String = if (isDigest) {
        val combined = if (rawOverview.isNotBlank()) "$rawOverview\n\n$rawContent" else rawContent
        MarkdownRenderer.render(combined) {}.toString()
    } else {
        rawContent
    }

    private fun resumePreviewSnippet(offset: Int): String {
        val text = readAloudPlainText()
        val clamped = offset.coerceIn(0, text.length)
        return text.substring(clamped).trimStart().take(90)
    }

    // ------------------------------------------------------------ chat

    private fun addChatBubble(role: String, text: String) {
        val isUser = role == "You"
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Theme.roundedDrawable(if (isUser) Theme.surfaceContainer else Theme.surface, this@DetailActivity)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(wrapper)
        row.setPadding(0, dp(6), 0, dp(6))

        wrapper.addView(
            TextView(this).apply {
                this.text = role
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isUser) Theme.primary else Theme.linkColor)
            },
        )
        wrapper.addView(
            TextView(this).apply {
                // Was raw `this.text = text` -- an assistant reply's own
                // markdown (bold, links, code, headers) showed as literal
                // "**"/backtick characters, never rendered at all (unlike
                // the main article view a few lines up, which already
                // goes through MarkdownRenderer). User's own typed
                // question is left as plain text -- nothing to render,
                // and it's never markdown in practice.
                this.text = if (isUser) text else MarkdownRenderer.render(text) { url -> openArticle(url) }
                textSize = 14f
                setTextColor(Theme.onBackground)
                setPadding(0, dp(4), 0, 0)
                if (!isUser) LinkTapHandler.attach(this)
            },
        )
        if (!isUser) {
            val readBtn = Button(this).apply {
                this.text = "Read aloud"
                setTextColor(Theme.onBackground)
                Theme.styleGhostButton(this, this@DetailActivity)
                setOnClickListener { speakChatReply(text) }
            }
            val readBtnRow = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
            readBtnRow.addView(readBtn)
            wrapper.addView(readBtnRow)
        }
        chatContainer.addView(row)
    }

    private fun addToolStatusLine(text: String) {
        chatContainer.addView(
            TextView(this).apply {
                this.text = text
                textSize = 11f
                setTextColor(Theme.onSurfaceVariant)
                setPadding(dp(14), dp(2), dp(14), dp(2))
            },
        )
    }

    private fun showChatBusy(busy: Boolean) {
        chatBusyRow?.let { chatContainer.removeView(it) }
        chatBusyRow = null
        if (busy) {
            val row = LinearLayout(this).apply {
                setPadding(dp(14), dp(6), dp(14), dp(10))
                addView(
                    TextView(this@DetailActivity).apply {
                        text = "Claude is working…"
                        textSize = 12f
                        setTextColor(Theme.onSurfaceVariant)
                    },
                )
            }
            chatContainer.addView(row)
            chatBusyRow = row
        }
        scrollToBottom()
    }

    /** One AgentChatClient per Activity instance, created lazily so
     * loadDigest()'s resume() call and the first onSend() share the same
     * live conversation/poll loop instead of each spawning their own. */
    private fun ensureAgentChat(): AgentChatClient {
        agentChat?.let { return it }
        val client = AgentChatClient(
            this,
            onSessionReady = { _, resumed -> if (!resumed) Log.i("NewsDigest", "chat: spawned new session") },
            onMessages = { messages ->
                for (m in messages) {
                    when {
                        // The raw output of a Bash/Read/etc call being fed
                        // back to Claude - can be many KB of file content
                        // (confirmed live: an assistant reply that
                        // consulted memory files dumped whole file bodies
                        // as separate "Assistant" bubbles here before this
                        // filter existed). Not conversational content, so
                        // it doesn't belong in this thread at all - matches
                        // claudeagents-android's own ChatActivity treatment.
                        m.role == "tool_result" -> {}
                        m.role == "user" -> addChatBubble("You", displayUserText(m.text))
                        // "→ Bash: ..." / "→ Read(...)" - a tool call
                        // summary, not prose - a one-line muted status
                        // row rather than a full bubble with its own
                        // "Read aloud" button.
                        m.text.startsWith("→ ") -> addToolStatusLine(m.text)
                        else -> addChatBubble("Assistant", m.text)
                    }
                }
                scrollToBottom()
            },
            onBusyChanged = { busy -> showChatBusy(busy) },
            onError = { message -> Toast.makeText(this, "Chat error: $message", Toast.LENGTH_LONG).show() },
        )
        agentChat = client
        return client
    }

    /** The very first message of a conversation carries the full article/
     * digest text ahead of the actual question - Claude needs that for
     * context, but the article is already right there on screen, so
     * showing it a second time in the chat thread (every time the
     * conversation is resumed, not just when it was sent) is pure noise.
     * Show only the "Question: ..." tail this activity itself appended in
     * onSend() below; a follow-up message has no such marker and passes
     * through unchanged.
     *
     * Matches on "Question: " alone, not "\n\nQuestion: " - confirmed live
     * that the newlines onSend() puts before it don't survive the trip
     * through tmux's paste handling into the transcript (a long/multi-line
     * send arrives back with every newline stripped, sentences run
     * straight together with no separator at all), so a leading-newline
     * marker never matched and the recap kept showing on resume. */
    private fun displayUserText(text: String): String {
        val marker = "Question: "
        val idx = text.lastIndexOf(marker)
        return if (idx >= 0) text.substring(idx + marker.length) else text
    }

    private fun onSend() {
        val question = inputField.text.toString().trim()
        if (question.isEmpty()) return
        inputField.text.clear()

        addChatBubble("You", question)
        scrollToBottom()

        val client = ensureAgentChat()
        if (client.sessionId == null) {
            val prompt = buildString {
                append(contentForChat)
                if (sourceLink.isNotBlank()) append("\n\nSource link: $sourceLink")
                append("\n\nQuestion: $question")
            }
            client.start(digestRunId, digestTopic, prompt)
        } else {
            client.send(question)
        }
    }

    /** Reuses /tts/stream directly (the same protocol ReadAloudController
     * drives for the main article) rather than the old per-delta synth
     * that used to ride along inside /agent/chat's WebSocket - chat
     * replies now arrive as one finished block of text from the poll loop
     * rather than token-by-token, so there is nothing left to synthesize
     * incrementally as it streams. */
    private fun speakChatReply(text: String) {
        if (!chatTtsBound) return
        val svc = chatTtsService ?: return
        readAloud.stop()
        svc.stopAll()
        svc.setListener(object : TtsPlaybackService.HighlightListener {})
        // See ReadAloudController.start()'s comment -- a chat-reply read
        // shares the same TtsPlaybackService instance, and needs the same
        // explicit start to survive leaving this screen (this is in fact
        // the exact "close the activity/window with the conversation"
        // case the fix was asked for).
        startForegroundService(Intent(this, TtsPlaybackService::class.java))
        svc.startSession(sourceTitle)
        Thread {
            val ws = WebSocketClient(Settings.getHost(this), Settings.getTtsPort(this), "/tts/stream", mapOf("X-Peer-Agent" to "1"))
            ws.connect(
                object : WebSocketClient.Listener {
                    private var pendingMeta: JSONObject? = null

                    override fun onOpen() {
                        ws.sendText(
                            JSONObject().apply {
                                put("text", text)
                                put("engine", Settings.getTtsEngine(this@DetailActivity))
                            }.toString(),
                        )
                    }

                    override fun onText(msg: String) {
                        val obj = JSONObject(msg)
                        when (obj.optString("type")) {
                            "sentence" -> pendingMeta = obj
                            "done" -> {
                                svc.endSession()
                                ws.close()
                            }
                            "error" -> {
                                runOnUiThread { Toast.makeText(this@DetailActivity, "Read aloud failed: ${obj.optString("message")}", Toast.LENGTH_LONG).show() }
                                ws.close()
                            }
                        }
                    }

                    override fun onBinary(data: ByteArray) {
                        val meta = pendingMeta ?: return
                        val words = mutableListOf<WordTiming>()
                        meta.optJSONArray("words")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val w = arr.getJSONObject(i)
                                words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                            }
                        }
                        svc.enqueueSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
                    }

                    override fun onFailure(error: Throwable) {}
                },
            )
        }.apply { isDaemon = true; name = "ChatReplyTts"; start() }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Without this, positionSaveTick kept running against this
        // instance's own (now going away) ReadAloudController even after
        // the screen closed - confirmed live 2026-09-10 as part of a
        // reported "Resume started from the beginning" bug: reopening the
        // same article rebinds a NEW controller as the service's live
        // listener, which freezes this old one's currentReadingOffset()
        // at whatever it last saw, and this leaked tick was still there to
        // periodically write that frozen, increasingly-stale offset back
        // over whatever the new screen was correctly saving. One last
        // save here too, on the position this controller actually knows
        // right now, so closing the screen mid-read doesn't lose up to
        // the last 5s of progress the periodic tick hadn't caught yet.
        mainHandler.removeCallbacks(positionSaveTick)
        if (readAloud.isActive()) {
            readAloud.currentReadingOffset()?.let {
                ReadAloudPositionStore.set(this, readAloudPositionKey, it)
            }
        }
        readAloud.unbind()
        agentChat?.close()
        if (chatTtsBound) {
            try { unbindService(chatConnection) } catch (_: Exception) {}
        }
    }
}
