package dev.local.newsdigest

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
) {
    private val playPauseButton: PlayPauseImageView
    private val speedButton: TextView
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

        val prevSectionButton = iconImageView(context, "ic_prev_section", 18) { onPreviousSection() }
        val rewindButton = iconImageView(context, "ic_rewind", 22) { onRewind() }
        playPauseButton = PlayPauseImageView(context) { onPlayPause() }
        val forwardButton = iconImageView(context, "ic_forward", 22) { onForward() }
        val nextSectionButton = iconImageView(context, "ic_next_section", 18) { onNextSection() }
        val locateButton = iconImageView(context, "ic_locate", 20) { onLocate() }
        speedButton = iconButton("1x") { onSpeedClick(speedButton) }

        view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.surface)
            val padH = Theme.dp(context, 6)
            val padV = Theme.dp(context, 3)
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
            addView(prevSectionButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(rewindButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(playPauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(forwardButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextSectionButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(locateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun show() { view.visibility = View.VISIBLE }
    fun hide() { view.visibility = View.GONE }
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
        val sizePx = Theme.dp(context, 22)
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
