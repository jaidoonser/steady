package com.jaiden.steady

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pageReady = false
    private var pendingPhotoReq: String? = null
    private var cameraFile: File? = null
    private var pendingOpen: Array<String>? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var player: MediaPlayer? = null

    companion object {
        private const val REQ_CAM = 11
        private const val REQ_EXPORT = 12
        private const val REQ_IMPORT = 13
        private const val REQ_NOTIF = 14
        private const val REQ_MIC = 15
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F2F6F4"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(this), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                deliverOpen()
            }

            // The app never navigates anywhere else.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url
                if (u != null && (u.scheme == "tel" || u.scheme == "sms")) {
                    try {
                        if (u.scheme == "tel") {
                            startActivity(Intent(Intent.ACTION_DIAL, u))
                        } else {
                            // sms:NUMBER?body=WORD -> open messages with the word typed in
                            val num = u.schemeSpecificPart.substringBefore("?")
                            val body = Uri.parse("x://y?" + u.schemeSpecificPart.substringAfter("?", "")).getQueryParameter("body")
                            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
                            if (body != null) i.putExtra("sms_body", body)
                            startActivity(i)
                        }
                    } catch (e: Exception) {
                        toast("Couldn't open the phone app")
                    }
                } else if (u != null && u.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                        toast("Couldn't open the browser")
                    }
                }
                return true
            }
        }
        setContentView(web)

        if (savedInstanceState != null) {
            pendingPhotoReq = savedInstanceState.getString("photoReq")
            savedInstanceState.getString("camFile")?.let { cameraFile = File(it) }
        }
        handleIntent(intent)
        web.loadUrl("file:///android_asset/index.html")
        Reminders.rescheduleAll(applicationContext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photoReq", pendingPhotoReq)
        outState.putString("camFile", cameraFile?.absolutePath)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        deliverOpen()
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) {
            val id = stopRec()
            js("window.onRecStopped && window.onRecStopped(${q(id)})")
        }
        stopPlayback()
    }

    // ---------- voice recording ----------
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    @Synchronized
    fun startRec(): String {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
            return "perm"
        }
        runOnUiThread { stopPlayback() }
        discardRec()
        return try {
            val f = File(Store.audioDir(this), "a" + System.currentTimeMillis() + ".m4a")
            val r = newRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            "ok"
        } catch (e: Exception) {
            discardRec()
            "error"
        }
    }

    /** Returns the new recording's id, or "" if it was too short / failed. */
    @Synchronized
    fun stopRec(): String {
        val r = recorder ?: return ""
        val f = recFile
        recorder = null
        recFile = null
        return try {
            r.stop()
            r.release()
            f?.nameWithoutExtension ?: ""
        } catch (e: Exception) {
            try {
                r.release()
            } catch (e2: Exception) {
            }
            f?.delete()
            ""
        }
    }

    @Synchronized
    fun discardRec() {
        val r = recorder ?: return
        try {
            r.stop()
        } catch (e: Exception) {
        }
        try {
            r.release()
        } catch (e: Exception) {
        }
        recFile?.delete()
        recorder = null
        recFile = null
    }

    fun playAudio(id: String) {
        runOnUiThread {
            stopPlayback()
            val f = File(Store.audioDir(this), "$id.m4a")
            if (!f.exists()) {
                toast("That recording is missing")
            } else {
                try {
                    val p = MediaPlayer()
                    p.setDataSource(f.absolutePath)
                    p.setOnCompletionListener {
                        stopPlayback()
                        js("window.onAudioEnded && window.onAudioEnded()")
                    }
                    p.prepare()
                    p.start()
                    player = p
                } catch (e: Exception) {
                    stopPlayback()
                    toast("Couldn't play that recording")
                }
            }
        }
    }

    fun stopPlayback() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (e: Exception) {
        }
        p.release()
    }

    override fun onResume() {
        super.onResume()
        if (pageReady) js("window.reloadData && window.reloadData()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    private fun handleIntent(i: Intent?) {
        val t = i?.getStringExtra("taskId") ?: return
        val d = i.getStringExtra("date") ?: return
        val s = i.getStringExtra("slot") ?: return
        pendingOpen = arrayOf(t, d, s)
        i.removeExtra("taskId")
    }

    private fun deliverOpen() {
        val p = pendingOpen ?: return
        if (!pageReady) return
        pendingOpen = null
        js("window.openFromNotification(${q(p[0])},${q(p[1])},${q(p[2])})")
    }

    private fun q(s: String) = JSONObject.quote(s)

    fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- notifications permission ----------
    fun askNotifications() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!prefs.getBoolean("askedNotif", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            try {
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            js("window.onMicPermission && window.onMicPermission($granted)")
        }
        if (requestCode == REQ_NOTIF) {
            Reminders.rescheduleAll(applicationContext)
            js("window.reloadData && window.reloadData()")
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    // ---------- camera ----------
    fun startCamera(req: String) {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        pendingPhotoReq = req
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try {
            startActivityForResult(i, REQ_CAM)
        } catch (e: ActivityNotFoundException) {
            toast("No camera app found")
            js("window.onPhotoTaken(${q(req)},'')")
        }
    }

    private fun processPhoto(src: File, req: String) {
        Thread {
            var id = ""
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.absolutePath, bounds)
                val longSide = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longSide / (sample * 2) >= 1280) sample *= 2
                val raw = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IllegalStateException("could not read photo")
                val rot = when (ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                val scale = minOf(1f, 1280f / maxOf(raw.width, raw.height))
                val m = Matrix()
                m.postScale(scale, scale)
                m.postRotate(rot)
                val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                val bmp = turned.copy(Bitmap.Config.ARGB_8888, true)

                // Date/time stamp in the corner, so the photo is real proof.
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.WHITE
                paint.textSize = bmp.width * 0.045f
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.setShadowLayer(6f, 0f, 0f, Color.BLACK)
                val stamp = SimpleDateFormat("EEE d MMM yyyy  h:mm a", Locale.getDefault()).format(Date())
                canvas.drawText(stamp, bmp.width * 0.04f, bmp.height - bmp.width * 0.05f, paint)

                id = "p" + System.currentTimeMillis()
                FileOutputStream(File(Store.photoDir(this), "$id.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            } catch (e: Exception) {
                id = ""
                toast("Couldn't save that photo, try again")
            } finally {
                src.delete()
            }
            js("window.onPhotoTaken(${q(req)},${q(id)})")
        }.start()
    }

    // ---------- backup ----------
    fun startExport() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "steady-backup-$day.zip")
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: ActivityNotFoundException) {
            toast("Can't open the file picker on this phone")
        }
    }

    fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) {
            toast("Can't open the file picker on this phone")
        }
    }

    private fun doExport(uri: Uri) {
        Thread {
            var ok = false
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("data.json"))
                        zip.write(Store.load(this).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        Store.photoDir(this).listFiles()?.forEach { f ->
                            if (Store.SAFE_PHOTO.matches(f.name)) {
                                zip.putNextEntry(ZipEntry("photos/" + f.name))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        Store.audioDir(this).listFiles()?.forEach { f ->
                            if (Store.SAFE_AUDIO.matches(f.name)) {
                                zip.putNextEntry(ZipEntry("audio/" + f.name))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            js("window.onBackupDone('export',$ok,${q(if (ok) "" else "Backup failed, try saving somewhere else")})")
        }.start()
    }

    private fun doImport(uri: Uri) {
        Thread {
            var ok = false
            var msg = "That file isn't a Steady backup"
            try {
                val tmp = File(cacheDir, "restore")
                tmp.deleteRecursively()
                tmp.mkdirs()
                val tmpAudio = File(cacheDir, "restore_audio")
                tmpAudio.deleteRecursively()
                tmpAudio.mkdirs()
                var json: String? = null
                contentResolver.openInputStream(uri)?.use { ins ->
                    ZipInputStream(ins).use { zin ->
                        var e = zin.nextEntry
                        while (e != null) {
                            val n = e.name
                            if (n == "data.json") {
                                json = zin.readBytes().toString(Charsets.UTF_8)
                            } else if (n.startsWith("photos/") && Store.SAFE_PHOTO.matches(n.removePrefix("photos/"))) {
                                FileOutputStream(File(tmp, n.removePrefix("photos/"))).use { zin.copyTo(it) }
                            } else if (n.startsWith("audio/") && Store.SAFE_AUDIO.matches(n.removePrefix("audio/"))) {
                                FileOutputStream(File(tmpAudio, n.removePrefix("audio/"))).use { zin.copyTo(it) }
                            }
                            e = zin.nextEntry
                        }
                    }
                }
                val j = json
                if (j != null) {
                    JSONObject(j) // make sure it's valid before touching anything
                    val pd = Store.photoDir(this)
                    pd.listFiles()?.forEach { it.delete() }
                    tmp.listFiles()?.forEach { it.copyTo(File(pd, it.name), true) }
                    val ad = Store.audioDir(this)
                    ad.listFiles()?.forEach { it.delete() }
                    tmpAudio.listFiles()?.forEach { it.copyTo(File(ad, it.name), true) }
                    Store.save(this, j)
                    ok = true
                }
                tmp.deleteRecursively()
                tmpAudio.deleteRecursively()
            } catch (e: Exception) {
                msg = "Couldn't read that backup file"
            }
            if (ok) {
                Reminders.rescheduleAll(applicationContext)
                js("window.reloadData()")
                toast("Backup restored ✓")
            } else {
                js("window.onBackupDone('import',false,${q(msg)})")
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAM -> {
                val req = pendingPhotoReq ?: return
                val f = cameraFile
                pendingPhotoReq = null
                cameraFile = null
                if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                    processPhoto(f, req)
                } else {
                    f?.delete()
                    js("window.onPhotoTaken(${q(req)},'')")
                }
            }
            REQ_EXPORT -> if (resultCode == RESULT_OK) data?.data?.let { doExport(it) }
            REQ_IMPORT -> if (resultCode == RESULT_OK) data?.data?.let { doImport(it) }
        }
    }
}
