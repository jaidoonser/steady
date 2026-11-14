package com.jaiden.steady

import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import java.io.File

/** The bits of Android the app screen (index.html) can call, as window.Android.* */
class Bridge(private val a: MainActivity) {

    @JavascriptInterface
    fun loadData(): String = Store.load(a)

    @JavascriptInterface
    fun saveData(json: String) = Store.save(a, json)

    @JavascriptInterface
    fun reschedule() = Reminders.rescheduleAll(a.applicationContext)

    @JavascriptInterface
    fun takePhoto(req: String) {
        a.runOnUiThread { a.startCamera(req) }
    }

    @JavascriptInterface
    fun loadPhoto(id: String): String {
        val name = "$id.jpg"
        if (!Store.SAFE_PHOTO.matches(name)) return ""
        val f = File(Store.photoDir(a), name)
        if (!f.exists()) return ""
        return "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun deletePhoto(id: String) {
        val name = "$id.jpg"
        if (Store.SAFE_PHOTO.matches(name)) File(Store.photoDir(a), name).delete()
    }

    @JavascriptInterface
    fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(a).areNotificationsEnabled()

    @JavascriptInterface
    fun requestNotifications() {
        a.runOnUiThread { a.askNotifications() }
    }

    @JavascriptInterface
    fun exactAlarmsAllowed(): Boolean = Reminders.exactAllowed(a)

    @JavascriptInterface
    fun openExactAlarmSettings() {
        a.runOnUiThread { a.openExactAlarmSettings() }
    }

    @JavascriptInterface
    fun testNotification() = Reminders.showTest(a)

    @JavascriptInterface
    fun exportBackup() {
        a.runOnUiThread { a.startExport() }
    }

    @JavascriptInterface
    fun importBackup() {
        a.runOnUiThread { a.startImport() }
    }

    @JavascriptInterface
    fun appVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun startRecording(): String = a.startRec()

    @JavascriptInterface
    fun stopRecording(): String = a.stopRec()

    @JavascriptInterface
    fun cancelRecording() = a.discardRec()

    @JavascriptInterface
    fun playAudio(id: String) {
        if (Store.SAFE_AUDIO.matches("$id.m4a")) a.playAudio(id)
    }

    @JavascriptInterface
    fun stopAudio() {
        a.runOnUiThread { a.stopPlayback() }
    }

    @JavascriptInterface
    fun deleteAudio(id: String) {
        val name = "$id.m4a"
        if (Store.SAFE_AUDIO.matches(name)) File(Store.audioDir(a), name).delete()
    }
}
