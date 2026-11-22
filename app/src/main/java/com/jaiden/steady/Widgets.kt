package com.jaiden.steady

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Home screen widgets: a Today agenda and a Month calendar, both read straight from data.json. */
object Widgets {
    const val ACTION_REFRESH = "com.jaiden.steady.WIDGET_REFRESH"
    const val ACTION_PREV = "com.jaiden.steady.WIDGET_PREV"
    const val ACTION_NEXT = "com.jaiden.steady.WIDGET_NEXT"
    const val ACTION_THIS = "com.jaiden.steady.WIDGET_THIS"
    const val ACTION_PICK = "com.jaiden.steady.WIDGET_PICK"
    const val ACTION_BACK = "com.jaiden.steady.WIDGET_BACK"

    /** The day picked on the month widget (shown on the Today widget). Resets to today each new day. */
    fun selDay(c: Context): LocalDate {
        val p = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        if (p.getString("selOn", "") != today.toString()) {
            p.edit().putString("selOn", today.toString()).remove("sel").apply()
            return today
        }
        return try { LocalDate.parse(p.getString("sel", "") ?: "") } catch (e: Exception) { today }
    }

    fun setSel(c: Context, d: LocalDate?) {
        val p = c.getSharedPreferences("widget", Context.MODE_PRIVATE).edit().putString("selOn", LocalDate.now().toString())
        if (d == null || d == LocalDate.now()) p.remove("sel") else p.putString("sel", d.toString())
        p.apply()
    }

    fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    val GREEN = Color.parseColor("#4E9A78")
    val LAV = Color.parseColor("#9C8FD0")
    val RED = Color.parseColor("#B97A86")
    val GREY = Color.parseColor("#D3DDD9")
    val SAGE = Color.parseColor("#5E8C83")
    val INK = Color.parseColor("#22302D")
    val MUTED = Color.parseColor("#6C7D78")
    val FAINT = Color.parseColor("#B5C2BE")

    data class Item(val taskId: String, val date: String, val slot: String, val name: String, val emoji: String, val done: Boolean, val tough: Boolean, val appt: Boolean = false)

    fun root(c: Context): JSONObject? = try {
        val raw = Store.load(c)
        if (raw.isBlank()) null else JSONObject(raw)
    } catch (e: Exception) { null }

    fun items(root: JSONObject, d: LocalDate): List<Item> {
        val done = Reminders.doneKeys(root)
        val out = ArrayList<Item>()
        val ds = d.toString()
        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            val sc = Reminders.occursOn(t, d) ?: continue
            val id = t.optString("id")
            for (slot in Reminders.strings(sc.optJSONArray("times"))) {
                out.add(Item(id, ds, slot, t.optString("name"), t.optString("emoji", "✨"), "$id|$ds|$slot" in done, t.optBoolean("tough", false)))
            }
        }
        val ap = root.optJSONArray("appointments") ?: JSONArray()
        for (i in 0 until ap.length()) {
            val a = ap.optJSONObject(i) ?: continue
            if (a.optString("date") != ds) continue
            out.add(Item("", ds, a.optString("time", ""), a.optString("title", "Appointment"), a.optString("emoji", "🩺").ifEmpty { "🩺" }, false, false, true))
        }
        out.sortWith(compareBy({ it.slot.ifEmpty { "99" } }, { it.name }))
        return out
    }

    /** Colour of the little bar under a day, same rules as the Calendar tab. 0 = no bar. */
    fun dayColour(root: JSONObject, d: LocalDate, today: LocalDate): Int {
        val its = items(root, d).filter { !it.appt }
        if (its.isEmpty()) return 0
        val dn = its.count { it.done }
        return when {
            d.isAfter(today) -> GREY
            dn == its.size -> GREEN
            d == today -> if (dn > 0) LAV else SAGE
            else -> if (dn > 0) LAV else RED
        }
    }

    fun hasAppt(root: JSONObject, d: LocalDate): Boolean {
        val ap = root.optJSONArray("appointments") ?: return false
        val ds = d.toString()
        for (i in 0 until ap.length()) if (ap.optJSONObject(i)?.optString("date") == ds) return true
        return false
    }

    fun fmtTime(s: String): String {
        val t = Reminders.parseTime(s) ?: return ""
        val h = t.hour % 12
        val hh = if (h == 0) 12 else h
        val ap = if (t.hour < 12) "am" else "pm"
        return if (t.minute == 0) "$hh$ap" else "$hh:${"%02d".format(t.minute)}$ap"
    }

    fun openApp(c: Context, code: Int, tab: String?, day: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) i.putExtra("openTab", tab)
        if (day != null) i.putExtra("openDay", day)
        i.data = Uri.parse("steady://open/" + (tab ?: "home") + "/" + code + "/" + (day ?: ""))
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun broadcast(c: Context, cls: Class<*>, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, cls).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Redraw every Steady widget. Cheap, so call it whenever the data changes. */
    fun updateAll(c0: Context) {
        val c = c0.applicationContext
        try {
            val m = AppWidgetManager.getInstance(c) ?: return
            val today = m.getAppWidgetIds(ComponentName(c, TodayWidget::class.java))
            if (today.isNotEmpty()) {
                TodayWidget.draw(c, m, today)
                m.notifyAppWidgetViewDataChanged(today, R.id.w_list)
            }
            val month = m.getAppWidgetIds(ComponentName(c, MonthWidget::class.java))
            if (month.isNotEmpty()) MonthWidget.draw(c, m, month)
            if (today.isNotEmpty() || month.isNotEmpty()) scheduleMidnight(c)
        } catch (e: Exception) {
            Log.e("Steady", "widget update failed", e)
        }
    }

    /** The date ticks over at midnight, so ask for a redraw just after. */
    fun scheduleMidnight(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000
        val pi = broadcast(c, TodayWidget::class.java, ACTION_REFRESH, 880001)
        am.set(AlarmManager.RTC, at, pi)
    }
}

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        draw(c, m, ids)
        m.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        Widgets.scheduleMidnight(c)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        when (intent.action) {
            Widgets.ACTION_REFRESH -> Widgets.updateAll(c)
            Widgets.ACTION_BACK -> { Widgets.setSel(c, null); Widgets.updateAll(c) }
        }
    }

    companion object {
        fun draw(c: Context, m: AppWidgetManager, ids: IntArray) {
            val root = Widgets.root(c)
            val today = LocalDate.now()
            val day = Widgets.selDay(c)
            val isToday = day == today
            val its = if (root != null) Widgets.items(root, day).filter { !it.appt } else emptyList()
            val dn = its.count { it.done }
            val countTxt = when {
                its.isEmpty() -> if (day.isBefore(today)) "Nothing planned" else "Rest day 🌿"
                day.isAfter(today) -> "${its.size} planned"
                dn == its.size -> "All done ✓"
                else -> "$dn of ${its.size} done"
            }
            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget_today)
                val lbl = Widgets.dayLabel(day, today)
                v.setTextViewText(R.id.w_title, if (isToday || lbl.contains(" ")) day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) else lbl)
                val dateTxt = "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
                v.setTextViewText(R.id.w_date, if (isToday) dateTxt else "$dateTxt · $countTxt")
                if (isToday) {
                    v.setTextViewText(R.id.w_count, countTxt)
                    v.setTextColor(R.id.w_count, if (its.isNotEmpty() && dn == its.size) Widgets.GREEN else Widgets.MUTED)
                    v.setOnClickPendingIntent(R.id.w_count, Widgets.openApp(c, 881500 + (id % 1000), "today"))
                } else {
                    v.setTextViewText(R.id.w_count, "↩ Today")
                    v.setTextColor(R.id.w_count, Widgets.SAGE)
                    v.setOnClickPendingIntent(R.id.w_count, Widgets.broadcast(c, TodayWidget::class.java, Widgets.ACTION_BACK, 886010))
                }
                val svc = Intent(c, TodayWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
                v.setRemoteAdapter(R.id.w_list, svc)
                v.setEmptyView(R.id.w_list, R.id.w_empty)
                v.setOnClickPendingIntent(R.id.w_head, Widgets.openApp(c, 881000 + (id % 1000), "today", if (isToday) null else day.toString()))
                v.setOnClickPendingIntent(R.id.w_empty, Widgets.openApp(c, 882000 + (id % 1000), "today", if (isToday) null else day.toString()))
                val tmpl = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                v.setPendingIntentTemplate(R.id.w_list, PendingIntent.getActivity(c, 883000 + (id % 1000), tmpl, flags))
                m.updateAppWidget(id, v)
            }
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    class Factory(private val c: Context) : RemoteViewsFactory {
        /** Either a section header (item == null) or a task row. */
        private var rows: List<Pair<String, Widgets.Item?>> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(p: Int) = p.toLong()
        override fun hasStableIds() = false
        override fun getCount() = rows.size

        override fun onDataSetChanged() {
            val root = Widgets.root(c)
            if (root == null) { rows = emptyList(); return }
            val today = LocalDate.now()
            val day = Widgets.selDay(c)
            val t = Widgets.items(root, day)
            val tm = Widgets.items(root, day.plusDays(1))
            val out = ArrayList<Pair<String, Widgets.Item?>>()
            // still to do first, then done
            t.filter { !it.done }.forEach { out.add("" to it) }
            t.filter { it.done }.forEach { out.add("" to it) }
            if (tm.isNotEmpty()) {
                out.add(Widgets.dayLabel(day.plusDays(1), today) to null)
                tm.forEach { out.add("" to it) }
            }
            rows = if (t.isEmpty() && tm.isEmpty()) emptyList() else out
        }

        override fun getViewAt(p: Int): RemoteViews {
            val (label, it) = rows.getOrNull(p) ?: return RemoteViews(c.packageName, R.layout.widget_head_row)
            if (it == null) {
                val h = RemoteViews(c.packageName, R.layout.widget_head_row)
                h.setTextViewText(R.id.wr_head, label.uppercase())
                h.setOnClickFillInIntent(R.id.wr_head, Intent().putExtra("openTab", "today"))
                return h
            }
            val v = RemoteViews(c.packageName, R.layout.widget_row)
            v.setTextViewText(R.id.wr_emoji, it.emoji)
            v.setTextViewText(R.id.wr_name, if (it.tough) "${it.name}  😬" else it.name)
            v.setTextViewText(R.id.wr_time, if (it.appt && it.slot.isEmpty()) "Appt" else Widgets.fmtTime(it.slot))
            v.setTextColor(R.id.wr_name, if (it.done) Widgets.FAINT else Widgets.INK)
            v.setViewVisibility(R.id.wr_tick, if (it.done) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.wr_time, if (it.done) View.GONE else View.VISIBLE)
            val fill = Intent()
            if (it.appt || it.taskId.isEmpty()) fill.putExtra("openTab", "today")
            else fill.putExtra("taskId", it.taskId).putExtra("date", it.date).putExtra("slot", it.slot)
            v.setOnClickFillInIntent(R.id.wr_root, fill)
            return v
        }
    }
}

class MonthWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        draw(c, m, ids)
        Widgets.scheduleMidnight(c)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        val p = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val off = p.getInt("monthOff", 0)
        when (intent.action) {
            Widgets.ACTION_PREV -> { p.edit().putInt("monthOff", (off - 1).coerceAtLeast(-24)).apply(); Widgets.updateAll(c) }
            Widgets.ACTION_NEXT -> { p.edit().putInt("monthOff", (off + 1).coerceAtMost(24)).apply(); Widgets.updateAll(c) }
            Widgets.ACTION_THIS -> { p.edit().putInt("monthOff", 0).apply(); Widgets.setSel(c, null); Widgets.updateAll(c) }
            Widgets.ACTION_PICK -> {
                val d = try { LocalDate.parse(intent.data?.lastPathSegment ?: "") } catch (e: Exception) { null }
                // tapping the picked day again goes back to today
                Widgets.setSel(c, if (d != null && d == Widgets.selDay(c)) null else d)
                Widgets.updateAll(c)
            }
            Widgets.ACTION_REFRESH -> Widgets.updateAll(c)
        }
    }

    companion object {
        private val CELL = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6, R.id.c7, R.id.c8, R.id.c9, R.id.c10, R.id.c11, R.id.c12, R.id.c13, R.id.c14, R.id.c15, R.id.c16, R.id.c17, R.id.c18, R.id.c19, R.id.c20, R.id.c21, R.id.c22, R.id.c23, R.id.c24, R.id.c25, R.id.c26, R.id.c27, R.id.c28, R.id.c29, R.id.c30, R.id.c31, R.id.c32, R.id.c33, R.id.c34, R.id.c35, R.id.c36, R.id.c37, R.id.c38, R.id.c39, R.id.c40, R.id.c41)
        private val NUM = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6, R.id.d7, R.id.d8, R.id.d9, R.id.d10, R.id.d11, R.id.d12, R.id.d13, R.id.d14, R.id.d15, R.id.d16, R.id.d17, R.id.d18, R.id.d19, R.id.d20, R.id.d21, R.id.d22, R.id.d23, R.id.d24, R.id.d25, R.id.d26, R.id.d27, R.id.d28, R.id.d29, R.id.d30, R.id.d31, R.id.d32, R.id.d33, R.id.d34, R.id.d35, R.id.d36, R.id.d37, R.id.d38, R.id.d39, R.id.d40, R.id.d41)
        private val BAR = intArrayOf(R.id.b0, R.id.b1, R.id.b2, R.id.b3, R.id.b4, R.id.b5, R.id.b6, R.id.b7, R.id.b8, R.id.b9, R.id.b10, R.id.b11, R.id.b12, R.id.b13, R.id.b14, R.id.b15, R.id.b16, R.id.b17, R.id.b18, R.id.b19, R.id.b20, R.id.b21, R.id.b22, R.id.b23, R.id.b24, R.id.b25, R.id.b26, R.id.b27, R.id.b28, R.id.b29, R.id.b30, R.id.b31, R.id.b32, R.id.b33, R.id.b34, R.id.b35, R.id.b36, R.id.b37, R.id.b38, R.id.b39, R.id.b40, R.id.b41)
        private val DOT = intArrayOf(R.id.a0, R.id.a1, R.id.a2, R.id.a3, R.id.a4, R.id.a5, R.id.a6, R.id.a7, R.id.a8, R.id.a9, R.id.a10, R.id.a11, R.id.a12, R.id.a13, R.id.a14, R.id.a15, R.id.a16, R.id.a17, R.id.a18, R.id.a19, R.id.a20, R.id.a21, R.id.a22, R.id.a23, R.id.a24, R.id.a25, R.id.a26, R.id.a27, R.id.a28, R.id.a29, R.id.a30, R.id.a31, R.id.a32, R.id.a33, R.id.a34, R.id.a35, R.id.a36, R.id.a37, R.id.a38, R.id.a39, R.id.a40, R.id.a41)

        fun draw(c: Context, m: AppWidgetManager, ids: IntArray) {
            val root = Widgets.root(c)
            val today = LocalDate.now()
            val prefs = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
            // snap back to this month at the start of each new day
            if (prefs.getString("monthDay", "") != today.toString()) prefs.edit().putInt("monthOff", 0).putString("monthDay", today.toString()).apply()
            val off = prefs.getInt("monthOff", 0)
            val sel = Widgets.selDay(c)
            val first = today.withDayOfMonth(1).plusMonths(off.toLong())
            val start = first.minusDays(((first.dayOfWeek.value + 6) % 7).toLong()) // Monday-start, like the app
            for (id in ids) {
                val v = RemoteViews(c.packageName, R.layout.widget_month)
                v.setTextViewText(R.id.wm_title, first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (first.year != today.year) " ${first.year}" else "")
                v.setViewVisibility(R.id.wm_this, if (off != 0 || sel != today) View.VISIBLE else View.GONE)
                for (i in 0 until 42) {
                    val d = start.plusDays(i.toLong())
                    val cell = CELL[i]
                    val num = NUM[i]
                    val bar = BAR[i]
                    val dot = DOT[i]
                    val row6 = i >= 35 && d.month != first.month
                    if (row6) { v.setViewVisibility(cell, View.INVISIBLE); continue }
                    v.setViewVisibility(cell, View.VISIBLE)
                    v.setTextViewText(num, d.dayOfMonth.toString())
                    val inMonth = d.month == first.month
                    v.setTextColor(num, if (d == today) Color.WHITE else if (inMonth) Widgets.INK else Widgets.FAINT)
                    v.setInt(num, "setBackgroundResource", if (d == today) R.drawable.w_today else if (d == sel) R.drawable.w_sel else 0)
                    val pick = Intent(c, MonthWidget::class.java).setAction(Widgets.ACTION_PICK).setData(Uri.parse("steady://day/$d"))
                    v.setOnClickPendingIntent(cell, PendingIntent.getBroadcast(c, 887000 + i, pick, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    val col = if (root != null && inMonth) Widgets.dayColour(root, d, today) else 0
                    if (col == 0) v.setViewVisibility(bar, View.INVISIBLE)
                    else { v.setViewVisibility(bar, View.VISIBLE); v.setInt(bar, "setColorFilter", col) }
                    v.setViewVisibility(dot, if (root != null && inMonth && Widgets.hasAppt(root, d)) View.VISIBLE else View.GONE)
                }
                v.setOnClickPendingIntent(R.id.wm_title, Widgets.openApp(c, 885000 + (id % 1000), "cal"))
                v.setOnClickPendingIntent(R.id.wm_prev, Widgets.broadcast(c, MonthWidget::class.java, Widgets.ACTION_PREV, 886001))
                v.setOnClickPendingIntent(R.id.wm_next, Widgets.broadcast(c, MonthWidget::class.java, Widgets.ACTION_NEXT, 886002))
                v.setOnClickPendingIntent(R.id.wm_this, Widgets.broadcast(c, MonthWidget::class.java, Widgets.ACTION_THIS, 886003))
                m.updateAppWidget(id, v)
            }
        }
    }
}
