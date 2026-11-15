package at.agar.privatelock

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.text.Collator

/** Liste aller Apps: welche sollen geschützt werden? */
class AppPickerActivity : Activity() {

    private class AppInfo(val pkg: String, val label: String, val icon: Drawable?)
    private class Row(val app: AppInfo, val view: View)

    private lateinit var t: Strings
    private lateinit var frame: FrameLayout
    private lateinit var list: LinearLayout
    private val rows = ArrayList<Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var warned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = L.of(this)
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root

        root.addView(Ui.backButton(this) { finish() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(Glass.label(this, t.chooseApps, 32f, Glass.TEXT, bold = true).apply {
            letterSpacing = -0.02f
        }, Ui.lp(dp(14)))
        root.addView(Glass.label(this, t.chooseAppsSub, 14f), Ui.lp(dp(4)))

        val search = Glass.input(this, "🔍  " + t.searchApps, true)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) = filter(e?.toString() ?: "")
        })
        root.addView(search, Ui.lp(dp(18)))

        list = Ui.card(this, 2)
        root.addView(list, Ui.lp(dp(12)))
        list.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
        ))

        // Apps im Hintergrund laden (Icons brauchen etwas Zeit)
        Thread {
            val apps = loadApps()
            handler.post { if (!isFinishing) show(apps) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        AppLock.gateSelf(this)
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<AppInfo>()
        try {
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(i, 0)
            for (r in found) {
                val pkg = r.activityInfo?.packageName ?: continue
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = try { r.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                val icon = try { r.loadIcon(pm) } catch (_: Exception) { null }
                out.add(AppInfo(pkg, label, icon))
            }
        } catch (_: Exception) {
        }
        val c = Collator.getInstance()
        val sel = AppLock.selected(this)
        // Geschützte Apps zuerst, dann alphabetisch
        return out.sortedWith(Comparator { a, b ->
            val sa = a.pkg in sel
            val sb = b.pkg in sel
            if (sa != sb) (if (sa) -1 else 1) else c.compare(a.label, b.label)
        })
    }

    private fun show(apps: List<AppInfo>) {
        list.removeAllViews()
        rows.clear()
        apps.forEachIndexed { idx, app ->
            if (idx > 0) list.addView(Ui.separator(this))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            row.addView(Glass.label(this, app.label, 16.5f, Glass.TEXT).apply { maxLines = 2 },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = GlassSwitch(this)
            sw.setOn(AppLock.isSelected(this, app.pkg))
            sw.onChange = { on -> toggle(app.pkg, on) }
            row.addView(sw, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) })
            row.isClickable = true
            row.setOnClickListener { sw.setOn(!sw.isChecked, animate = true, fromUser = true) }
            list.addView(row)
            rows.add(Row(app, row))
        }
    }

    private fun toggle(pkg: String, on: Boolean) {
        Feedback.vibrate(this, 15)
        AppLock.setSelected(this, pkg, on)
        if (on && !warned) {
            warned = true
            if (!AppLock.canAuthenticate(this)) Ui.showBanner(frame, t.noSecureLock, 4000L)
            else if (!AppLockService.isEnabled(this)) Ui.showBanner(frame, t.accSub, 3500L)
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        val rowViews = java.util.IdentityHashMap<View, AppInfo>()
        for (r in rows) rowViews[r.view] = r.app
        // 1. Zeilen ein-/ausblenden
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            val app = rowViews[v] ?: continue
            val vis = query.isEmpty() || app.label.lowercase().contains(query) || app.pkg.contains(query)
            v.visibility = if (vis) View.VISIBLE else View.GONE
        }
        // 2. Trennlinie nur vor einer sichtbaren Zeile, wenn davor schon eine sichtbar war
        var seenVisible = false
        var pendingSep: View? = null
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (rowViews.containsKey(v)) {
                if (v.visibility == View.VISIBLE) {
                    pendingSep?.visibility = if (seenVisible) View.VISIBLE else View.GONE
                    pendingSep = null
                    seenVisible = true
                }
            } else {
                pendingSep?.visibility = View.GONE
                v.visibility = View.GONE
                pendingSep = v
            }
        }
        pendingSep?.visibility = View.GONE
    }
}
