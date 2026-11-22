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
import android.widget.LinearLayout
import android.widget.Toast
import android.view.View
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var pendingPickReq: String? = null
    private var pendingDocReq: String? = null
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
        private const val REQ_STEPS = 16
        private const val REQ_PICK = 17
        private const val REQ_DOCS = 18
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
        // Edge-to-edge (required when targeting Android 15+): the page sits between two
        // strips sized to the status bar and the nav bar / keyboard, so the layout is unchanged.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val topStrip = View(this).apply { setBackgroundColor(Color.parseColor("#DCEBE6")) }
        val bottomStrip = View(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F6F4"))
            addView(topStrip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomStrip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, 0)
            topStrip.layoutParams = (topStrip.layoutParams as LinearLayout.LayoutParams).apply { height = bars.top }
            bottomStrip.layoutParams = (bottomStrip.layoutParams as LinearLayout.LayoutParams).apply { height = maxOf(bars.bottom, ime.bottom) }
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        // Back gesture: Android 16 apps no longer get onBackPressed, so listen the new way.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }

        if (savedInstanceState != null) {
            pendingPhotoReq = savedInstanceState.getString("photoReq")
            pendingPickReq = savedInstanceState.getString("pickReq")
            pendingDocReq = savedInstanceState.getString("docReq")
            savedInstanceState.getString("camFile")?.let { cameraFile = File(it) }
        }
        handleIntent(intent)
        web.loadUrl("file:///android_asset/index.html")
        Reminders.rescheduleAll(applicationContext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photoReq", pendingPhotoReq)
        outState.putString("pickReq", pendingPickReq)
        outState.putString("docReq", pendingDocReq)
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
        Thread { Widgets.updateAll(applicationContext) }.start()
        if (recorder != null) {
            val id = stopRec()
            js("window.onRecStopped && window.onRecStopped(${q(id)})")
        }
        stopPlayback()
        Steps.stopLive(this)
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

    private var exactWas: Boolean? = null

    override fun onResume() {
        super.onResume()
        // If "Alarms & reminders" was just allowed, move reminders back onto exact times.
        val ex = Reminders.exactAllowed(this)
        if (exactWas != null && exactWas != ex) Reminders.rescheduleAll(applicationContext)
        exactWas = ex
        if (pageReady) js("window.reloadData && window.reloadData()")
        Steps.sample(this) { js("window.onSteps && window.onSteps()") }
        Steps.startLive(this) { js("window.onSteps && window.onSteps()") }
    }

    // ---------- step counter ----------
    fun enableSteps(on: Boolean) {
        if (!on) {
            Steps.stopLive(this)
            Steps.setEnabled(this, false)
            js("window.reloadData && window.reloadData()")
            return
        }
        if (!Steps.available(this)) {
            toast("Sorry, this phone doesn't have a step counter")
            return
        }
        if (!Steps.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_STEPS)
            return
        }
        Steps.setEnabled(this, true)
        Steps.startLive(this) { js("window.onSteps && window.onSteps()") }
        Steps.sample(this) { js("window.onStepsEnabled && window.onStepsEnabled()") }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        web.evaluateJavascript("window.handleBack ? window.handleBack() : false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    private var pendingTab: String? = null
    private var pendingDay: String? = null

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("openTab")?.let { tab ->
            if (tab in setOf("today", "cal", "rewards", "mind", "plan")) { pendingTab = tab; pendingDay = i.getStringExtra("openDay") }
            i.removeExtra("openTab")
            i.removeExtra("openDay")
        }
        val t = i?.getStringExtra("taskId") ?: return
        val d = i.getStringExtra("date") ?: return
        val s = i.getStringExtra("slot") ?: return
        pendingOpen = arrayOf(t, d, s)
        i.removeExtra("taskId")
    }

    private fun deliverOpen() {
        if (pageReady) pendingTab?.let { tab ->
            pendingTab = null
            val day = pendingDay ?: ""
            pendingDay = null
            js("window.openTabFromWidget && window.openTabFromWidget(${q(tab)},${q(day)})")
        }
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
        if (requestCode == REQ_STEPS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                enableSteps(true)
            } else {
                toast("To count steps, allow \"Physical activity\" for Steady in your phone's Settings")
                js("window.onStepsEnabled && window.onStepsEnabled()")
            }
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

    // ---------- pick images (screenshots / info for a task) ----------
    fun startPickImages(req: String) {
        pendingPickReq = req
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PICK)
            } catch (e2: ActivityNotFoundException) {
                pendingPickReq = null
                toast("No gallery app found")
                js("window.onImagesPicked(${q(req)},'[]')")
            }
        }
    }

    private fun processPicked(uris: List<Uri>, req: String) {
        Thread {
            val ids = org.json.JSONArray()
            var failed = 0
            uris.forEachIndexed { n, uri ->
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
                    if (longSide <= 0) throw IllegalStateException("not an image")
                    val maxSide = 2400
                    var sample = 1
                    while (longSide / (sample * 2) >= maxSide) sample *= 2
                    val raw = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: throw IllegalStateException("could not read image")
                    val rot = try {
                        contentResolver.openInputStream(uri)?.use {
                            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                        } ?: 0f
                    } catch (e: Exception) { 0f }
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
                    val mx = Matrix()
                    mx.postScale(scale, scale)
                    mx.postRotate(rot)
                    val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, mx, true)
                    // flatten transparency onto white so screenshots with alpha don't go black
                    val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                    val id = "f" + System.currentTimeMillis() + "_" + n
                    FileOutputStream(File(Store.photoDir(this), "$id.jpg")).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    ids.put(id)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) toast(if (failed == 1) "Couldn't add one of those images" else "Couldn't add $failed of those images")
            js("window.onImagesPicked(${q(req)},${q(ids.toString())})")
        }.start()
    }

    // ---------- documents (PDFs etc.) kept on a task or project ----------
    fun startPickDocs(req: String) {
        pendingDocReq = req
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_DOCS)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_DOCS)
            } catch (e2: ActivityNotFoundException) {
                pendingDocReq = null
                toast("No file picker found on this phone")
                js("window.onDocsPicked(${q(req)},'[]')")
            }
        }
    }

    private fun processDocs(uris: List<Uri>, req: String) {
        Thread {
            val out = org.json.JSONArray()
            var tooBig = 0
            var failed = 0
            val stamp = System.currentTimeMillis()
            uris.forEachIndexed { n, uri ->
                try {
                    var name = "File"
                    var size = -1L
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                    if (size > 30L * 1024 * 1024) { tooBig++; return@forEachIndexed }
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    var ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
                    if (ext.isEmpty()) ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                    val file = "d${stamp}_$n.$ext"
                    val dest = File(Store.docDir(this), file)
                    var copied = 0L
                    contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(dest).use { o -> copied = ins.copyTo(o) }
                    } ?: throw IllegalStateException("can't read")
                    if (copied > 30L * 1024 * 1024) { dest.delete(); tooBig++; return@forEachIndexed }
                    out.put(JSONObject().put("file", file).put("name", name).put("size", copied).put("mime", mime))
                } catch (e: Exception) {
                    failed++
                }
            }
            if (tooBig > 0) toast(if (tooBig == 1) "One file was over 30 MB, so it wasn't added" else "$tooBig files were over 30 MB, so they weren't added")
            if (failed > 0) toast(if (failed == 1) "Couldn't add one of those files" else "Couldn't add $failed of those files")
            js("window.onDocsPicked(${q(req)},${q(out.toString())})")
        }.start()
    }

    private fun docForSharing(file: String, name: String): File? {
        if (!Store.SAFE_DOC.matches(file)) return null
        val src = File(Store.docDir(this), file)
        if (!src.exists()) return null
        val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }
        var clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80)
        if (clean.isEmpty()) clean = file
        val dest = File(dir, clean)
        src.copyTo(dest, true)
        return dest
    }

    fun openDoc(file: String, mime: String, name: String, share: Boolean) {
        Thread {
            val f = try { docForSharing(file, name) } catch (e: Exception) { null }
            runOnUiThread {
                if (f == null) { toast("That file's missing"); return@runOnUiThread }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                val type = if (mime.isBlank()) "application/octet-stream" else mime
                val i = if (share) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(Intent.createChooser(i, if (share) "Send it" else "Open with"))
                } catch (e: ActivityNotFoundException) {
                    toast("No app on this phone can open that type of file. Try Send it instead")
                }
            }
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
                        Store.docDir(this).listFiles()?.forEach { f ->
                            if (Store.SAFE_DOC.matches(f.name)) {
                                zip.putNextEntry(ZipEntry("docs/" + f.name))
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
                val tmpDocs = File(cacheDir, "restore_docs")
                tmpDocs.deleteRecursively()
                tmpDocs.mkdirs()
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
                            } else if (n.startsWith("docs/") && Store.SAFE_DOC.matches(n.removePrefix("docs/"))) {
                                FileOutputStream(File(tmpDocs, n.removePrefix("docs/"))).use { zin.copyTo(it) }
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
                    val dd = Store.docDir(this)
                    dd.listFiles()?.forEach { it.delete() }
                    tmpDocs.listFiles()?.forEach { it.copyTo(File(dd, it.name), true) }
                    Store.save(this, j)
                    ok = true
                }
                tmp.deleteRecursively()
                tmpAudio.deleteRecursively()
                tmpDocs.deleteRecursively()
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
            REQ_PICK -> {
                val req = pendingPickReq ?: return
                pendingPickReq = null
                val uris = mutableListOf<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val cd = data.clipData
                    if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
                    else data.data?.let { uris.add(it) }
                }
                if (uris.isEmpty()) js("window.onImagesPicked(${q(req)},'[]')") else processPicked(uris.take(10), req)
            }
            REQ_DOCS -> {
                val req = pendingDocReq ?: return
                pendingDocReq = null
                val uris = mutableListOf<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val cd = data.clipData
                    if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
                    else data.data?.let { uris.add(it) }
                }
                if (uris.isEmpty()) js("window.onDocsPicked(${q(req)},'[]')") else processDocs(uris.take(10), req)
            }
            REQ_EXPORT -> if (resultCode == RESULT_OK) data?.data?.let { doExport(it) }
            REQ_IMPORT -> if (resultCode == RESULT_OK) data?.data?.let { doImport(it) }
        }
    }
}
