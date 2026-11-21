package com.jaiden.steady

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily steps from the phone's built-in step counter.
 * The sensor counts steps since the phone last restarted, even while the app is closed,
 * so we just read it now and then (app open, hourly, just after midnight) and work out each day's total.
 */
object Steps {
    const val ACTION_SAMPLE = "com.jaiden.steady.STEPS"
    private const val PREFS = "steps"
    private const val CODE_MIDNIGHT = 777001
    private const val CODE_HOURLY = 777002
    private var live: SensorEventListener? = null

    private fun sm(c: Context) = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun available(c: Context): Boolean = sm(c).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun enabled(c: Context): Boolean = c.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", on).apply()
        if (on) schedule(c) else cancel(c)
    }

    private fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private fun hist(c: Context): JSONObject = try {
        JSONObject(c.getSharedPreferences(PREFS, 0).getString("hist", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun record(c: Context, counter: Long) {
        val p = c.getSharedPreferences(PREFS, 0)
        val td = today()
        val hist = hist(c)
        var day = p.getString("day", null)
        var base = p.getLong("base", counter)
        var accum = p.getLong("accum", 0L)
        val last = p.getLong("last", counter)
        if (day == null) {
            day = td
            base = counter
            accum = 0L
        } else {
            if (counter < last) {
                // Phone restarted, so the sensor started again from zero.
                accum += (last - base).coerceAtLeast(0L)
                base = 0L
            }
            if (day != td) {
                val prev = accum + (counter - base)
                if (prev > hist.optLong(day, 0L)) hist.put(day, prev)
                day = td
                base = counter
                accum = 0L
            }
        }
        hist.put(td, (accum + (counter - base)).coerceAtLeast(0L))
        if (hist.length() > 400) {
            val keys = hist.keys().asSequence().sorted().toList()
            keys.take(keys.size - 400).forEach { hist.remove(it) }
        }
        p.edit()
            .putString("day", day)
            .putLong("base", base)
            .putLong("accum", accum)
            .putLong("last", counter)
            .putString("hist", hist.toString())
            .apply()
    }

    fun statusJson(c: Context): JSONObject {
        val p = c.getSharedPreferences(PREFS, 0)
        val h = hist(c)
        val td = today()
        val todaySteps = if (p.getString("day", null) == td) h.optLong(td, 0L) else 0L
        return JSONObject()
            .put("available", available(c))
            .put("permission", hasPermission(c))
            .put("enabled", enabled(c))
            .put("today", todaySteps)
            .put("hist", h)
    }

    /** Read the sensor once, then call done. Gives up after 5 seconds. */
    fun sample(c: Context, done: (() -> Unit)?) {
        val app = c.applicationContext
        if (!enabled(app) || !hasPermission(app)) {
            done?.invoke()
            return
        }
        val m = sm(app)
        val s = m.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (s == null) {
            done?.invoke()
            return
        }
        val h = Handler(Looper.getMainLooper())
        var finished = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (finished) return
                finished = true
                m.unregisterListener(this)
                record(app, e.values[0].toLong())
                done?.invoke()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, h)
        h.postDelayed({
            if (!finished) {
                finished = true
                m.unregisterListener(l)
                done?.invoke()
            }
        }, 5000)
    }

    /** While the app is open, update as you walk. */
    fun startLive(c: Context, onUpdate: () -> Unit) {
        stopLive(c)
        val app = c.applicationContext
        if (!enabled(app) || !hasPermission(app)) return
        val m = sm(app)
        val s = m.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                record(app, e.values[0].toLong())
                onUpdate()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        live = l
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stopLive(c: Context) {
        val l = live ?: return
        sm(c.applicationContext).unregisterListener(l)
        live = null
    }

    private fun pi(c: Context, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, AlarmReceiver::class.java).setAction(ACTION_SAMPLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Check just after midnight (so each day's total is right) and roughly every hour. */
    fun schedule(c: Context) {
        val app = c.applicationContext
        if (!enabled(app)) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusSeconds(20).toInstant().toEpochMilli()
        val mp = pi(app, CODE_MIDNIGHT)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, mp)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, mp)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, mp)
        }
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HOUR,
            pi(app, CODE_HOURLY)
        )
    }

    fun cancel(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(app, CODE_MIDNIGHT))
        am.cancel(pi(app, CODE_HOURLY))
    }
}
