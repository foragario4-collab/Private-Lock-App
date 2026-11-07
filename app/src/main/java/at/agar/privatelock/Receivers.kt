package at.agar.privatelock

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Nötig, damit die App das Handy sperren darf */
class AdminReceiver : DeviceAdminReceiver()

/** Nach Neustart oder Update den Schutz wieder starten */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.active(context) && Prefs.on(context, Flag.BOOT)) LockService.start(context)
    }
}
