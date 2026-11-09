package at.agar.privatelock

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Kachel im Schnellmenü: Private Lock ein/aus */
class LockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !Prefs.active(this)
        if (turnOn && !Admin.isActive(this)) {
            openApp()
            return
        }
        Prefs.setActive(this, turnOn)
        if (turnOn) LockService.start(this) else LockService.stop(this)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Prefs.active(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Private Lock"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) L.of(this).tileOn else L.of(this).tileOff
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(i)
            }
        } catch (_: Exception) {
        }
    }
}
