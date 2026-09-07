package dev.local.newsdigest

import android.view.Menu
import android.view.MenuItem

/**
 * Shared "Speed" submenu for read-aloud playback rate, used identically
 * by DetailActivity and ArticleActivity - one place to add a speed option
 * or change the list, rather than keeping two menus in sync by hand.
 */
object SpeedMenu {
    private const val GROUP_ID = 100
    private val OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    fun addTo(menu: Menu?, currentSpeed: Float) {
        val sub = menu?.addSubMenu(0, GROUP_ID - 1, 2, "Speed") ?: return
        sub.setGroupCheckable(GROUP_ID, true, true)
        OPTIONS.forEachIndexed { index, speed ->
            val item = sub.add(GROUP_ID, GROUP_ID + index, index, formatSpeed(speed))
            item.isCheckable = true
            if (speed == currentSpeed) item.isChecked = true
        }
    }

    /** Returns true if this item belonged to the speed submenu (and was
     * handled), so callers can fall through to their own items otherwise. */
    fun handle(item: MenuItem, readAloud: ReadAloudController): Boolean {
        if (item.groupId != GROUP_ID) return false
        val index = item.itemId - GROUP_ID
        val speed = OPTIONS.getOrNull(index) ?: return false
        readAloud.setSpeed(speed)
        item.isChecked = true
        return true
    }

    private fun formatSpeed(speed: Float): String {
        val trimmed = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString()
        return "${trimmed}x"
    }
}
