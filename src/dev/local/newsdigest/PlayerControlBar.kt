package dev.local.newsdigest

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Sticky media-style control row shown for the whole time a read-aloud
 * session is active - rewind/play-pause/forward plus a speed picker,
 * replacing the old single "Stop" action's all-or-nothing control.
 * Speed lives here now, as its own icon, rather than behind the action
 * bar's "..." overflow. Placed above the scrolling content by the
 * caller (like SynthesizingBanner) so it stays visible regardless of
 * scroll position.
 *
 * Icons are res/drawable PNGs (ic_prev_section/ic_rewind/ic_play/
 * ic_pause/ic_forward/ic_next_section/ic_locate), generated via codex
 * (asked for explicitly 2026-09-10: "they look bad, dont have
 * backgrounds just simple distinctive shapes for each - use codex") --
 * this build has no gradle/dependency resolver and so never generates an
 * R.java (see build.sh's own doc), so they're looked up by name at
 * runtime via Resources.getIdentifier rather than a compile-time R.id
 * reference; tinted to the theme's onBackground color at load time
 * (rather than baked into the PNG) so they always match regardless of
 * future theme changes. Replaces an earlier hand-drawn-Path version of
 * this row (SectionSkipIcon/SeekIcon/PlayPauseIcon/LocateIcon) that went
 * through several rounds of manual size/shape tuning and still read as
 * "clunky... nothing recognizable".
 */
class PlayerControlBar(
    context: Context,
    onPreviousSection: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextSection: () -> Unit,
    onSpeedClick: (anchor: View) -> Unit,
    // Scrolls the content view to wherever read-aloud is currently at --
    // asked for explicitly 2026-09-10: on a long article/overview it's
    // easy to lose track of the live position while scrolling around.
    // Kept in sync with claude-agents-android's copy of this file.
    onLocate: () -> Unit = {},
    // Scrubber - asked for explicitly 2026-09-10 ("scrubback... with
    // total length at the right end and current position... possible to
    // change position on that line to seek"). Polled on a short internal
    // tick (only while shown) rather than pushed, so this stays a self-
    // contained UI component; onSeek fires once the user releases a
    // drag, with the dragged fraction (0f..1f) through the whole text -
    // ReadAloudController.seekToFraction is the intended receiver.
    private val getPosition: () -> Long = { 0L },
    private val getDuration: () -> Long = { 0L },
    private val onSeek: (Float) -> Unit = {},
) {
    private val playPauseButton: PlayPauseImageView
    private val speedButton: TextView
    private val seekBar: SeekBar
    private val positionLabel: TextView
    private val durationLabel: TextView
    private var userIsDragging = false
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!userIsDragging) {
                val pos = getPosition()
                val dur = getDuration()
                if (dur > 0) seekBar.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
                positionLabel.text = formatMs(pos)
                durationLabel.text = formatMs(dur)
            }
            tickHandler.postDelayed(this, 500)
        }
    }
    val view: LinearLayout

    init {
        // Shorter row -- reported live 2026-09-09: "they dont need to be
        // this high". A real 44dp touch target comes from minimumHeight,
        // independent of how small the glyph itself is drawn -- same
        // "small icon, big touch target" split every button in this row
        // uses (iconImageView below, and this text-only one for speed).
        fun iconButton(label: String, size: Float = 20f, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = size
            isSingleLine = true
            setTextColor(Theme.onBackground)
            gravity = Gravity.CENTER
            minHeight = Theme.dp(context, 44)
            val padH = Theme.dp(context, 8)
            val padV = Theme.dp(context, 4)
            setPadding(padH, padV, padH, padV)
            background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, context, radiusDp = 16))
            setOnClickListener { onClick() }
        }

        // Sizes bumped up a second time -- reported live 2026-09-10, after
        // the codex-icon swap-in, still "too small" at the original
        // 14-22dp range this row used with the earlier hand-drawn icons.
        val prevSectionButton = iconImageView(context, "ic_prev_section", 32) { onPreviousSection() }
        val rewindButton = iconImageView(context, "ic_rewind", 38) { onRewind() }
        playPauseButton = PlayPauseImageView(context) { onPlayPause() }
        val forwardButton = iconImageView(context, "ic_forward", 38) { onForward() }
        val nextSectionButton = iconImageView(context, "ic_next_section", 32) { onNextSection() }
        val locateButton = iconImageView(context, "ic_locate", 22) { onLocate() }
        speedButton = iconButton("1x") { onSpeedClick(speedButton) }

        positionLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(Theme.muted)
            text = "0:00"
        }
        durationLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(Theme.muted)
            text = "0:00"
        }
        seekBar = SeekBar(context).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionLabel.text = formatMs((progress.toLong() * getDuration()) / 1000)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    userIsDragging = true
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    userIsDragging = false
                    onSeek(sb.progress / 1000f)
                }
            })
        }
        val scrubberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = Theme.dp(context, 10)
            setPadding(padH, 0, padH, 0)
            addView(positionLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val seekParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            seekParams.marginStart = Theme.dp(context, 6)
            seekParams.marginEnd = Theme.dp(context, 6)
            addView(seekBar, seekParams)
            addView(durationLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = Theme.dp(context, 6)
            val padV = Theme.dp(context, 3)
            setPadding(padH, padV, padH, padV)
            addView(prevSectionButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(rewindButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(playPauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(forwardButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextSectionButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(locateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.surface)
            visibility = View.GONE
            addView(scrubberRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(iconRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    fun show() {
        view.visibility = View.VISIBLE
        tickHandler.removeCallbacks(tick)
        tickHandler.post(tick)
    }
    fun hide() {
        view.visibility = View.GONE
        tickHandler.removeCallbacks(tick)
    }
    fun setPlaying(playing: Boolean) { playPauseButton.setPlaying(playing) }
    fun setSpeed(speed: Float) { speedButton.text = SpeedPicker.formatSpeed(speed) }
}

/** Looks up a res/drawable PNG by name (no R.java in this build -- see
 * PlayerControlBar's own class doc) and wraps it as a small, tinted,
 * tappable icon inside a full 44dp touch target. Shared by every button
 * in this row except play/pause (PlayPauseImageView below), which needs
 * to swap between two drawables as playback state changes. */
private fun iconImageView(context: Context, drawableName: String, sizeDp: Int, onClick: () -> Unit): ImageView {
    val iv = ImageView(context)
    setIconDrawable(iv, context, drawableName)
    iv.scaleType = ImageView.ScaleType.FIT_CENTER
    iv.adjustViewBounds = true
    val sizePx = Theme.dp(context, sizeDp)
    iv.maxWidth = sizePx
    iv.maxHeight = sizePx
    iv.minimumHeight = Theme.dp(context, 44)
    iv.isClickable = true
    iv.background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, context, radiusDp = 16))
    iv.setOnClickListener { onClick() }
    return iv
}

private fun setIconDrawable(iv: ImageView, context: Context, drawableName: String) {
    val id = context.resources.getIdentifier(drawableName, "drawable", context.packageName)
    if (id != 0) {
        iv.setImageDrawable(context.getDrawable(id))
        // The PNG itself is a plain black silhouette (see the codex
        // generation prompt) -- tinted here at load time to the theme's
        // real foreground color instead of being baked into the asset,
        // so it always matches even if Theme.kt's palette ever changes.
        iv.setColorFilter(Theme.onBackground, PorterDuff.Mode.SRC_IN)
    }
}

private class PlayPauseImageView(context: Context, onClick: () -> Unit) : ImageView(context) {
    private var playing = false

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
        val sizePx = Theme.dp(context, 34)
        maxWidth = sizePx
        maxHeight = sizePx
        minimumHeight = Theme.dp(context, 44)
        isClickable = true
        background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, context, radiusDp = 16))
        setOnClickListener { onClick() }
        setIconDrawable(this, context, "ic_play")
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        setIconDrawable(this, context, if (playing) "ic_pause" else "ic_play")
    }
}
