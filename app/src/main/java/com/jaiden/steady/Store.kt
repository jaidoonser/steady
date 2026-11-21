package com.jaiden.steady

import android.content.Context
import java.io.File

/** All the data lives in one JSON file plus a folder of proof photos, inside the app's private storage. */
object Store {
    val SAFE_PHOTO = Regex("[A-Za-z0-9_\\-]+\\.jpg")
    val SAFE_AUDIO = Regex("[A-Za-z0-9_\\-]+\\.m4a")
    val SAFE_DOC = Regex("d[0-9]+_[0-9]+\\.[a-z0-9]{1,8}")

    fun dataFile(c: Context) = File(c.filesDir, "data.json")

    fun photoDir(c: Context): File = File(c.filesDir, "photos").apply { mkdirs() }

    fun audioDir(c: Context): File = File(c.filesDir, "audio").apply { mkdirs() }

    fun docDir(c: Context): File = File(c.filesDir, "docs").apply { mkdirs() }

    @Synchronized
    fun load(c: Context): String {
        val f = dataFile(c)
        return if (f.exists()) f.readText() else ""
    }

    @Synchronized
    fun save(c: Context, json: String) {
        val tmp = File(c.filesDir, "data.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(dataFile(c))) {
            dataFile(c).writeText(json)
            tmp.delete()
        }
    }
}
