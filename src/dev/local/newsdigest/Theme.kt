package dev.local.newsdigest

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Small shared style kit so every screen looks like one app instead of a
 * pile of default system widgets - dark background, rounded surfaces,
 * ripple-backed buttons, a single amber/gold accent (matching the app
 * icon's palette). Ported from the structure of the sibling
 * claudeagents-android app's own Theme.kt, with this app's own colors.
 */
object Theme {
    const val bg = 0xFF17150F.toInt()
    const val surface = 0xFF211E17.toInt()
    const val surfaceContainer = 0xFF3A342A.toInt()
    const val onBackground = 0xFFEDE7DD.toInt()
    const val onSurfaceVariant = 0xFFCBBFA9.toInt()
    const val outline = 0xFF8F8368.toInt()
    const val outlineVariant = 0xFF3A342A.toInt()
    const val primary = 0xFFF2B632.toInt()
    const val onPrimary = 0xFF2E2000.toInt()
    const val error = 0xFFFFB4AB.toInt()
    const val errorContainer = 0xFF4A1410.toInt()
    const val muted = 0xFF8A8270.toInt()
    const val linkColor = 0xFF7FC4FF.toInt()

    const val roundingDp = 10
    const val spacingDp = 8

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(color: Int, context: Context, radiusDp: Int = roundingDp, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    fun rippleOn(base: GradientDrawable): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(outline and 0x66FFFFFF.toInt()), base, base)

    fun styleEditText(e: EditText, context: Context) {
        e.setTextColor(onBackground)
        e.setHintTextColor(muted)
        e.background = roundedDrawable(surface, context, strokeColor = outlineVariant)
        e.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        try {
            e.highlightColor = primary and 0x55FFFFFF.toInt()
        } catch (_: Throwable) {
        }
    }

    fun stylePrimaryButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(primary, context))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }

    fun styleGhostButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, strokeColor = outlineVariant))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }

    /** Small dropdown menu anchored to a view -- ported from
     * claudeagents-android's own Theme.showMenu (same structure, this
     * app's own colors), used for DetailActivity's long-press-on-article
     * "Copy to clipboard" menu (asked for explicitly 2026-09-20: "long
     * press on article text should show a popup like long press on
     * messages in claude agents"). Opens upward instead of down whenever
     * there isn't enough room below the anchor. */
    fun showMenu(context: Context, anchor: View, items: List<String>, onSelect: (String) -> Unit) {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.background = roundedDrawable(surface, context, radiusDp = 12, strokeColor = primary)

        val popup = PopupWindow(context)
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(context, 12).toFloat()

        for ((i, item) in items.withIndex()) {
            if (i > 0) {
                val divider = View(context)
                divider.setBackgroundColor(primary and 0x33FFFFFF.toInt())
                container.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)))
            }
            val row = TextView(context)
            row.text = item
            row.textSize = 14f
            row.setTypeface(null, Typeface.BOLD)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setTextColor(primary)
            row.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
            row.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, radiusDp = 0))
            row.isClickable = true
            row.setOnClickListener {
                popup.dismiss()
                onSelect(item)
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        popup.contentView = container
        popup.width = LinearLayout.LayoutParams.WRAP_CONTENT
        popup.height = LinearLayout.LayoutParams.WRAP_CONTENT

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = container.measuredHeight
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val visibleFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)

        val spaceBelow = visibleFrame.bottom - (anchorLoc[1] + anchor.height)
        val yOffset = if (popupHeight > spaceBelow) -(anchor.height + popupHeight) else 0
        popup.showAsDropDown(anchor, 0, yOffset)
    }
}
