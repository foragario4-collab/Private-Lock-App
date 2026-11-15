package at.agar.privatelock

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates direkt aus den GitHub-Releases:
 * sieht nach, ob es eine neuere Version gibt, lädt die APK und startet die Installation.
 */
object Updater {
    private const val OWNER = "foragario4-collab"
    private const val REPO = "Private-Lock-App"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    class Release(val version: String, val apkUrl: String)

    /** Zuletzt gefundene neuere Version (für die Karte auf der Startseite) */
    @Volatile
    var available: Release? = null

    @Volatile
    var busy = false
        private set

    fun localVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** "1.10" > "1.9" usw. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Beim Start: höchstens einmal am Tag automatisch nachsehen */
    fun autoCheck(ctx: Context, onFound: (Release) -> Unit) {
        if (!Prefs.on(ctx, Flag.UPDATE_AUTO)) return
        val prefs = Prefs.get(ctx)
        val last = prefs.getLong("upd_last", 0L)
        available?.let {
            if (isNewer(it.version, localVersion(ctx))) onFound(it)
            return
        }
        if (System.currentTimeMillis() - last < DAY_MS) return
        prefs.edit().putLong("upd_last", System.currentTimeMillis()).apply()
        checkNow(ctx) { rel, _ -> if (rel != null) onFound(rel) }
    }

    /** Fragt GitHub ab. Antwort im UI-Thread: (neuere Version oder null, Fehler ja/nein) */
    fun checkNow(ctx: Context, done: (Release?, Boolean) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var result: Release? = null
            var failed = false
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "PrivateLock")
                if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                // Tag z. B. "v1.8-12" -> Version "1.8"
                val version = json.optString("tag_name").removePrefix("v").substringBefore('-')
                var apk: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apk = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apk != null && version.isNotEmpty() && isNewer(version, localVersion(app))) {
                    result = Release(version, apk)
                }
            } catch (_: Exception) {
                failed = true
            }
            available = result
            main.post { done(result, failed) }
        }.start()
    }

    /** Herunterladen und Installation starten */
    fun install(a: Activity, frame: FrameLayout, rel: Release) {
        val t = L.of(a)
        if (busy) return
        // Android muss erlauben, dass Private Lock Apps installiert (einmalig)
        if (!a.packageManager.canRequestPackageInstalls()) {
            Ui.showBanner(frame, t.updatePermission, 4500L)
            try {
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))
                )
            } catch (_: Exception) {
            }
            return
        }
        busy = true
        Ui.showBanner(frame, t.updateDownloading, 6000L)
        val app = a.applicationContext
        Thread {
            try {
                val file = File(app.cacheDir, "update.apk")
                val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "PrivateLock")
                if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                c.disconnect()
                installFile(app, file)
            } catch (_: Exception) {
                a.runOnUiThread { Ui.showBanner(frame, t.updateFailed, 4000L) }
            } finally {
                busy = false
            }
        }.start()
    }

    private fun installFile(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { input ->
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val status = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(status.intentSender)
        }
    }
}

/** Antwort von Android auf die Installation: Bestätigungsfenster zeigen oder Fehler melden */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (_: Exception) {
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(context, L.of(context).updateFailed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmIntent(i: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        legacy(i)
    }

    @Suppress("DEPRECATION")
    private fun legacy(i: Intent): Intent? = i.getParcelableExtra(Intent.EXTRA_INTENT)
}
