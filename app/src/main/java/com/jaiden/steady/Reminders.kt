package com.jaiden.steady

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Works out every reminder from the saved data and sets Android alarms for them.
 * Nothing is remembered between runs except snoozes, so it can be re-run any time
 * (app opened, data saved, alarm fired, phone rebooted).
 */
object Reminders {
    const val CHANNEL = "reminders"
    const val ACTION_FIRE = "com.jaiden.steady.FIRE"
    const val ACTION_SNOOZE = "com.jaiden.steady.SNOOZE"
    private const val SNOOZE_MIN = 15L

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Steady reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Gentle reminders for your Steady tasks"
            ch.enableVibration(true)
            ch.enableLights(true)
            ch.lightColor = Color.rgb(94, 140, 131)
            nm.createNotificationChannel(ch)
        }
    }

    private fun firePi(c: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(am: AlarmManager, at: Long, p: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun exactAllowed(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = c.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    internal fun ints(a: JSONArray?): Set<Int> {
        val s = HashSet<Int>()
        if (a != null) for (i in 0 until a.length()) s.add(a.optInt(i, -1))
        return s
    }

    internal fun strings(a: JSONArray?): List<String> {
        val l = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
        return l
    }

    internal fun parseTime(s: String): LocalTime? = try {
        val p = s.split(":")
        LocalTime.of(p[0].trim().toInt(), p[1].trim().toInt())
    } catch (e: Exception) {
        null
    }

    internal fun doneKeys(root: JSONObject): Set<String> {
        val done = HashSet<String>()
        val comps = root.optJSONArray("completions") ?: JSONArray()
        for (i in 0 until comps.length()) {
            val o = comps.optJSONObject(i) ?: continue
            done.add(o.optString("taskId") + "|" + o.optString("date") + "|" + o.optString("slot"))
        }
        return done
    }

    /**
     * Which schedule applies on a day: the task itself, an older version kept in "past"
     * (so editing a task doesn't rewrite its history), or null. Same as schedAt() in index.html.
     */
    internal fun schedAt(t: JSONObject, ds: String): JSONObject? {
        val end = t.optString("end", "")
        if (end.isNotEmpty() && ds >= end) return null
        val past = t.optJSONArray("past")
        if (past != null) for (i in 0 until past.length()) {
            val v = past.optJSONObject(i) ?: continue
            if (ds < v.optString("to", "")) return if (v.optBoolean("off", false)) null else v
        }
        return if (t.optBoolean("active", true)) t else null
    }

    /** Is the task on that day, using whichever schedule applied then. */
    internal fun occursOn(t: JSONObject, d: LocalDate): JSONObject? {
        val s = schedAt(t, d.toString()) ?: return null
        return if (occurs(s, d, ints(s.optJSONArray("days")))) s else null
    }

    /** Next step that isn't ticked off yet (steps are ticked by their text, like in index.html). */
    internal fun nextStep(t: JSONObject): String {
        val steps = t.optJSONArray("steps") ?: return ""
        val done = t.optJSONObject("stepDone")
        for (i in 0 until steps.length()) {
            val st = steps.optString(i, "")
            if (st.isNotBlank() && (done == null || !done.optBoolean(st, false))) return st
        }
        return ""
    }

    /** Same rules as occursS() in index.html. */
    internal fun occurs(t: JSONObject, d: LocalDate, days: Set<Int>): Boolean {
        val ds = d.toString()
        val start = t.optString("start", "")
        if (start.isNotEmpty() && ds < start) return false
        val until = t.optString("until", "")
        if (until.isNotEmpty() && ds > until) return false
        val sd = try {
            LocalDate.parse(start)
        } catch (e: Exception) {
            d
        }
        val w = d.dayOfWeek.value % 7
        return when (t.optString("repeat", "weekly").ifEmpty { "weekly" }) {
            "none" -> d == sd
            "daily" -> true
            "weekly" -> w in days
            "fortnightly" -> {
                if (w !in days) {
                    false
                } else {
                    val ws0 = sd.minusDays(((sd.dayOfWeek.value + 6) % 7).toLong())
                    val ws1 = d.minusDays(((d.dayOfWeek.value + 6) % 7).toLong())
                    Math.floorMod(ChronoUnit.DAYS.between(ws0, ws1) / 7, 2L) == 0L
                }
            }
            "monthly" -> d.dayOfMonth == minOf(sd.dayOfMonth, d.lengthOfMonth())
            "everyN" -> {
                val n = t.optInt("every", 2).coerceAtLeast(1)
                val diff = ChronoUnit.DAYS.between(sd, d)
                diff >= 0 && diff % n == 0L
            }
            else -> false
        }
    }

    fun rescheduleAll(c: Context) {
        try {
            doReschedule(c.applicationContext)
        } catch (e: Exception) {
            Log.e("Steady", "reschedule failed", e)
        }
    }

    @Synchronized
    private fun doReschedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val prefs = c.getSharedPreferences("alarms", Context.MODE_PRIVATE)

        // Cancel everything we set last time.
        for (code in prefs.getStringSet("codes", emptySet()) ?: emptySet()) {
            val code2 = code.toIntOrNull() ?: continue
            val p = firePi(c, code2, Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE))
            am.cancel(p)
            p.cancel()
        }
        val codes = HashSet<String>()

        val raw = Store.load(c)
        if (raw.isBlank()) {
            prefs.edit().putStringSet("codes", codes).apply()
            return
        }
        val root = JSONObject(raw)
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val nagMin = settings.optInt("nagMinutes", 30).coerceAtLeast(5).toLong()
        val nagMax = settings.optInt("nagMax", 4).coerceIn(0, 10)
        val done = doneKeys(root)
        val snoozes = c.getSharedPreferences("snooze", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)

        val tasks = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            if (!t.optBoolean("active", true)) continue
            if (t.optString("end", "").isNotEmpty()) continue
            val id = t.optString("id")
            // today's or yesterday's times can still be the old ones if the task was just changed
            val slots = LinkedHashSet<String>(strings(t.optJSONArray("times")))
            for (off in -1..0) schedAt(t, today.plusDays(off.toLong()).toString())?.let { slots.addAll(strings(it.optJSONArray("times"))) }
            for (slot in slots) {
                val lt = parseTime(slot) ?: continue
                for (off in -1..45) {
                    val d = today.plusDays(off.toLong())
                    val ds = d.toString()
                    val sc = occursOn(t, d) ?: continue
                    if (slot !in strings(sc.optJSONArray("times"))) continue
                    val key = "$id|$ds|$slot"
                    val base = d.atTime(lt).atZone(zone).toInstant().toEpochMilli()
                    if (key in done) {
                        if (base > now) break
                        continue
                    }
                    var fireAt = -1L
                    var idx = 0
                    val snooze = snoozes.getLong(key, 0L)
                    if (snooze > now) {
                        fireAt = snooze
                        idx = -1
                    } else {
                        for (k in 0..nagMax) {
                            val tt = base + k * nagMin * 60_000L
                            if (tt > now) {
                                fireAt = tt
                                idx = k
                                break
                            }
                        }
                    }
                    if (fireAt > 0) {
                        val code = key.hashCode()
                        val intent = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
                            .putExtra("kind", "task").putExtra("key", key)
                            .putExtra("taskId", id).putExtra("date", ds).putExtra("slot", slot)
                            .putExtra("nag", idx)
                        setAlarm(am, fireAt, firePi(c, code, intent))
                        codes.add(code.toString())
                    }
                    if (base > now) break // next upcoming one is set; the rest get set later
                }
            }
        }

        // Appointments: evening before at 6pm, and on the day (1 hour before, or 8am).
        val aps = root.optJSONArray("appointments") ?: JSONArray()
        for (i in 0 until aps.length()) {
            val a = aps.optJSONObject(i) ?: continue
            val date = try {
                LocalDate.parse(a.optString("date"))
            } catch (e: Exception) {
                continue
            }
            if (date.isBefore(today)) continue
            val id = a.optString("id")
            val lt = parseTime(a.optString("time", ""))
            val whens = listOf(
                "eve" to date.minusDays(1).atTime(18, 0),
                "day" to (if (lt != null) date.atTime(lt).minusHours(1) else date.atTime(8, 0))
            )
            for ((w, ldt) in whens) {
                val ms = ldt.atZone(zone).toInstant().toEpochMilli()
                if (ms <= now) continue
                val code = "ap|$id|$w".hashCode()
                val intent = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE)
                    .putExtra("kind", "appt").putExtra("apptId", id).putExtra("which", w)
                setAlarm(am, ms, firePi(c, code, intent))
                codes.add(code.toString())
            }
        }

        prefs.edit().putStringSet("codes", codes).apply()

        // Tidy old snoozes
        val ed = snoozes.edit()
        for ((k, v) in snoozes.all) if (v is Long && v < now - 86_400_000L) ed.remove(k)
        ed.apply()
    }

    fun onFire(c: Context, i: Intent) {
        try {
            val raw = Store.load(c)
            if (raw.isNotBlank()) {
                val root = JSONObject(raw)
                when (i.getStringExtra("kind")) {
                    "task" -> fireTask(c, root, i)
                    "appt" -> fireAppt(c, root, i)
                }
            }
        } catch (e: Exception) {
            Log.e("Steady", "fire failed", e)
        }
        rescheduleAll(c)
    }

    private fun findById(arr: JSONArray?, id: String?): JSONObject? {
        if (arr == null || id == null) return null
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            if (o.optString("id") == id) return o
        }
        return null
    }

    private fun fireTask(c: Context, root: JSONObject, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        val taskId = i.getStringExtra("taskId") ?: return
        val date = i.getStringExtra("date") ?: return
        val slot = i.getStringExtra("slot") ?: return
        val nag = i.getIntExtra("nag", 0)
        val t = findById(root.optJSONArray("tasks"), taskId) ?: return
        if (!t.optBoolean("active", true) || t.optString("end", "").isNotEmpty()) return
        if (key in doneKeys(root)) return

        val nagMax = (root.optJSONObject("settings") ?: JSONObject()).optInt("nagMax", 4)
        val name = t.optString("name", "Health task")
        val emoji = t.optString("emoji", "")
        val notes = t.optString("notes", "")
        val tough = t.optBoolean("tough", false)
        val firstStep = nextStep(t)
        val text = if (tough) {
            when {
                nag == -1 -> "Snooze is over. Just the first step, then decide."
                nag == 0 -> "😬 Tough one. Just do 10 minutes, then you can stop." + (if (firstStep.isNotBlank()) " Next step: $firstStep" else "")
                nag >= nagMax -> "Last nudge. Even 5 minutes on it counts."
                else -> listOf(
                    "Start the 10 minute timer. You're allowed to stop after.",
                    "The dread is usually bigger than the job.",
                    "Just open it up and do the first step",
                    "Done badly beats not done"
                )[(nag - 1) % 4]
            }
        } else when {
            nag == -1 -> "Breather's over. Whenever you're ready."
            nag == 0 -> if (notes.isNotBlank()) "Time for this one · $notes" else "Time for this one. Small steps count."
            nag >= nagMax -> "Last gentle nudge for this one today. Go easy on yourself."
            else -> listOf(
                "Gentle nudge: this one's still waiting",
                "Just a few minutes. You'll feel better for it",
                "No pressure, but future you will thank you",
                "Small steps still count"
            )[(nag - 1) % 4]
        }
        val notifId = key.hashCode()
        val open = Intent(c, MainActivity::class.java)
            .putExtra("taskId", taskId).putExtra("date", date).putExtra("slot", slot)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(c, notifId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = Intent(c, AlarmReceiver::class.java).setAction(ACTION_SNOOZE).putExtra("key", key)
        val snoozePi = PendingIntent.getBroadcast(c, notifId, snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Color.rgb(94, 140, 131))
            .setContentTitle("$emoji $name".trim())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "Snooze $SNOOZE_MIN min", snoozePi)
            .build()
        notify(c, notifId, n)
    }

    private fun fireAppt(c: Context, root: JSONObject, i: Intent) {
        val a = findById(root.optJSONArray("appointments"), i.getStringExtra("apptId")) ?: return
        val which = i.getStringExtra("which") ?: "day"
        val title = a.optString("title", "Appointment")
        val emoji = a.optString("emoji", "🩺")
        val time = a.optString("time", "")
        val timeTxt = parseTime(time)?.let { t ->
            val h = if (t.hour % 12 == 0) 12 else t.hour % 12
            val m = if (t.minute == 0) "" else ":" + t.minute.toString().padStart(2, '0')
            " at $h$m" + (if (t.hour < 12) "am" else "pm")
        } ?: ""
        val text = (if (which == "eve") "Tomorrow$timeTxt" else "Today$timeTxt") +
            (a.optString("notes", "").let { if (it.isNotBlank()) " · $it" else "" })
        val id = ("ap|" + a.optString("id")).hashCode()
        val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Color.rgb(94, 140, 131))
            .setContentTitle("$emoji $title".trim())
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        notify(c, id, n)
    }

    fun onSnooze(c: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        c.getSharedPreferences("snooze", Context.MODE_PRIVATE).edit()
            .putLong(key, System.currentTimeMillis() + SNOOZE_MIN * 60_000L).apply()
        c.getSystemService(NotificationManager::class.java)?.cancel(key.hashCode())
        rescheduleAll(c)
    }

    fun showTest(c: Context) {
        ensureChannel(c)
        val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Color.rgb(94, 140, 131))
            .setContentTitle("🌿 Reminders are working")
            .setContentText("This is what your gentle nudges will look like.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        notify(c, 999, n)
    }

    private fun notify(c: Context, id: Int, n: android.app.Notification) {
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: SecurityException) {
            Log.w("Steady", "no notification permission")
        }
    }

    @Suppress("unused")
    private fun at(d: LocalDateTime, zone: ZoneId) = d.atZone(zone).toInstant().toEpochMilli()
}
